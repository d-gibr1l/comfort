import io
import shlex
import sys
from contextlib import redirect_stdout, redirect_stderr
import gallery_dl
import gallery_dl.job

# gallery-dl's own internal yt-dlp delegation (downloader/ytdl.py, used for "ytdl:"-prefixed
# URLs on sites like Instagram) names each pre-merge DASH stream "...fdash-<id>v.<ext>" (video)
# or "...fdash-<id>a.<ext>" (audio) — and unlike our own yt_dlp_wrapper.py, that internal instance
# is never configured with ffmpeg_location, so the merge silently fails, stranding both fragments.
# The --filter/exclude_video option below (extension-based) doesn't reliably keep gallery-dl from
# reaching this path at all in practice, so the one point that reliably catches it regardless is
# here: never treat a raw fragment as a real saved file. Passing it through would either crash
# MediaStoreHelper on the raw MIME type or, worse, race gallery-dl's own attempted merge for the
# file out from under it (see DownloadWorker's actualCallback, which moves/deletes whatever path
# it's given as soon as it sees it). DownloadWorker's savedCount==0 fallback then correctly hands
# the same URL to yt_dlp_wrapper.py instead, which merges it properly.
class CallbackWriter:
    def __init__(self, callback, should_cancel=None):
        self.callback = callback
        self.should_cancel = should_cancel
        self.buffer = ""

    def reconfigure(self, *args, **kwargs):
        # gallery-dl (pinned to git HEAD, not a fixed release — see build.gradle.kts) started
        # calling sys.stdout.reconfigure(...) at some point after this was last tested; real
        # TextIOWrapper stdout supports that call (adjusting encoding/buffering), this stand-in
        # never did. No-op: this writer already only ever does newline-buffered text, so there's
        # nothing to actually reconfigure — but the call has to not *raise*, or gallery-dl's own
        # startup crashes with "'CallbackWriter' object has no attribute 'reconfigure'" before a
        # single line of real output ever happens (reproduced live, both under Chaquopy and here
        # under the subprocess runtime — this isn't a Chaquopy-specific quirk, any custom stand-in
        # substituting for real stdout during a redirect_stdout() hits the same gap).
        pass

    def _emit(self, line):
        if ".fdash-" in line:
            return
        self.callback(line)

    def write(self, text):
        # gallery-dl finishes writing a file (and records it in --download-archive) *before* it
        # prints the line announcing that file — the line our callback listens for to move it out
        # of staging and count it. Checking should_cancel() before processing `text` here meant a
        # pause landing in that narrow gap would raise before we ever saw the announcement for a
        # file gallery-dl already considers done — orphaned in the private cache forever, since a
        # later resume just sees it in the archive and silently skips it. Flushing whatever text
        # just arrived *first*, and only checking should_cancel() after, guarantees an
        # already-completed file's announcement always reaches the callback before we act on a
        # pending cancellation.
        self.buffer += text
        if '\n' in self.buffer:
            lines = self.buffer.split('\n')
            for line in lines[:-1]:
                if line.strip():
                    self._emit(line)
            self.buffer = lines[-1]
        # KeyboardInterrupt (not a plain Exception) is used deliberately — gallery-dl, like most
        # well-behaved CLI tools, lets it propagate for a graceful Ctrl-C-style stop instead of
        # swallowing it in per-item error handling the way a generic exception would be.
        if self.should_cancel is not None and self.should_cancel():
            raise KeyboardInterrupt()

    def flush(self):
        if self.buffer.strip():
            self._emit(self.buffer)
            self.buffer = ""

def download(url, download_dir, cookies_path=None, callback=None, filename_format=None, extra_args=None, archive_path=None, limit_rate=None, item_filter=None, should_cancel=None, exclude_video=False, retries=None, max_filesize=None, write_info_files=False, proxy_url=None):
    writer = CallbackWriter(callback, should_cancel) if callback else sys.stdout

    original_argv = sys.argv
    args = ["gallery-dl", "--directory", download_dir]
    if cookies_path:
        args.extend(["--cookies", cookies_path])
    if filename_format:
        args.extend(["--filename", filename_format])
    if limit_rate:
        args.extend(["--limit-rate", limit_rate])
    if proxy_url:
        args.extend(["--proxy", proxy_url])
    # Only one --filter is honored by gallery-dl (the last one wins, they don't combine), so an
    # item_filter from the share-picker and the video-exclusion filter have to be merged into one
    # expression rather than passed as two separate flags.
    video_filter = "extension not in ('mp4','webm','mov','mkv','m4v','avi','flv','wmv')" if exclude_video else None
    combined_filter = None
    if item_filter and video_filter:
        combined_filter = f"({item_filter}) and {video_filter}"
    elif item_filter:
        combined_filter = item_filter
    elif video_filter:
        combined_filter = video_filter
    if combined_filter:
        args.extend(["--filter", combined_filter])
    if archive_path:
        # Tracks already-downloaded item IDs so a retried/resumed download only fetches what's
        # still missing, instead of re-downloading the whole gallery from scratch.
        args.extend(["--download-archive", archive_path])
    if retries:
        # Two separate gallery-dl config paths cover the two places a request can fail:
        # "extractor.retries" for the metadata/listing fetch itself, "downloader.retries" for
        # each individual file's download — setting only one leaves the other at gallery-dl's
        # own default (4) regardless of what the user configured.
        args.extend(["-o", f"extractor.retries={retries}", "-o", f"downloader.retries={retries}"])
    if max_filesize:
        args.extend(["-o", f"downloader.filesize-max={max_filesize}"])
    if write_info_files:
        # gallery-dl's closest analog to yt-dlp's --write-info-json: one JSON sidecar per
        # downloaded file, containing the same metadata used for --filename/-filter expressions.
        args.append("--write-metadata")
    if extra_args:
        try:
            args.extend(shlex.split(extra_args))
        except ValueError:
            pass
    args.append(url)

    sys.argv = args
    status = "Done"

    with redirect_stdout(writer), redirect_stderr(writer):
        try:
            gallery_dl.main()
        except SystemExit as e:
            if e.code != 0:
                status = f"Error: exited with code {e.code}"
        except KeyboardInterrupt:
            status = "Cancelled"
        except Exception as e:
            status = f"Error: {e}"
            print(f"Exception: {e}")
        finally:
            if callback and not (should_cancel is not None and should_cancel()):
                writer.flush()
            sys.argv = original_argv

    return status

# Same gap as CallbackWriter.reconfigure() above, hitting a different object here: DataJob
# (patched in below to write straight into out_buffer instead of real stdout) calls
# file.reconfigure(...) on whatever it's given, and plain io.StringIO has no such method —
# reproduced live as "'_io.StringIO' object has no attribute 'reconfigure'", silently caught by
# list_items()'s own except-and-report-empty handling below, so every listing quietly came back
# empty instead of raising loudly.
class _ReconfigurableStringIO(io.StringIO):
    def reconfigure(self, *args, **kwargs):
        pass

def list_items(url, cookies_path=None, extra_args=None):
    """Enumerates items in a gallery without downloading anything, for the share-sheet item
    picker. Returns gallery-dl's raw --dump-json output as text (a JSON array of
    [message_type, url, keywords] entries); parsed on the Kotlin side. --dump-json already
    implies simulate mode on its own, so no separate --simulate flag is needed."""
    original_argv = sys.argv
    args = ["gallery-dl", "--dump-json"]
    if cookies_path:
        args.extend(["--cookies", cookies_path])
    if extra_args:
        try:
            args.extend(shlex.split(extra_args))
        except ValueError:
            pass
    args.append(url)

    sys.argv = args
    out_buffer = _ReconfigurableStringIO()
    err_buffer = _ReconfigurableStringIO()

    # DataJob.__init__(self, url, parent=None, file=sys.stdout, ...) captures the *original*
    # sys.stdout as a default argument at import time, before we ever get a chance to redirect
    # it — so redirect_stdout() alone never intercepts --dump-json's output. Patch the captured
    # default directly so it points at our buffer instead, for the duration of this call only.
    original_defaults = gallery_dl.job.DataJob.__init__.__defaults__
    try:
        code = gallery_dl.job.DataJob.__init__.__code__
        defaults_start = code.co_argcount - len(original_defaults)
        file_index = list(code.co_varnames).index("file") - defaults_start
        patched = list(original_defaults)
        patched[file_index] = out_buffer
        gallery_dl.job.DataJob.__init__.__defaults__ = tuple(patched)
    except Exception:
        file_index = None

    with redirect_stdout(out_buffer), redirect_stderr(err_buffer):
        try:
            gallery_dl.main()
        except SystemExit:
            pass
        except Exception as e:
            err_buffer.write(f"Exception: {e}")
        finally:
            sys.argv = original_argv
            if file_index is not None:
                gallery_dl.job.DataJob.__init__.__defaults__ = original_defaults

    out = out_buffer.getvalue()
    warnings = err_buffer.getvalue().strip()
    if out.strip():
        # gallery-dl can silently drop an individual item mid-listing (logging a warning, not
        # raising) — e.g. Instagram's extractor skips a carousel child it couldn't fetch media
        # info for, most often the video in an otherwise-all-photos post, while everything else
        # still succeeds. Previously that warning was simply discarded here (stdout was non-empty,
        # so the old "nothing at all" fallback below never triggered) — the caller had zero trace
        # that anything was missing. Appended after a marker line Kotlin splits on before JSON-
        # parsing, so a dropped item at least leaves a visible clue in logcat instead of vanishing
        # with none.
        if warnings:
            return out + "\n---GALLERY_DL_WARNINGS---\n" + warnings
        return out
    # Nothing on stdout — surface whatever gallery-dl said on stderr (auth required,
    # unsupported URL, etc.) so it's visible in logs instead of silently returning nothing.
    return "ERR:" + warnings


# CLI entry point for PythonRuntime.kt (subprocess model, replacing Chaquopy's direct callAttr()).
# should_cancel is deliberately not wired here — with each download now its own OS process,
# cancellation is just Kotlin killing the process, no cooperative polling needed. argv is all
# strings, so an empty string is this module's own "None" sentinel; DownloadWorker.kt passes "" for
# any positional arg it would otherwise pass Kotlin null for.
if __name__ == "__main__":
    import sys as _sys

    # download() redirects sys.stdout to its own CallbackWriter for the duration of the call (see
    # above) — capturing the *real* stdout here, before that happens, and printing straight to it
    # (bypassing whatever sys.stdout currently is) is what stops _emit -> print -> sys.stdout ->
    # CallbackWriter.write -> _emit from recursing into itself forever.
    _real_stdout = _sys.stdout

    def _s(v):
        return None if v == "" else v

    def _emit(line):
        print(line, file=_real_stdout, flush=True)

    if len(_sys.argv) < 2:
        print("Usage: gallery_dl_wrapper.py <download|list_items> ...", file=_sys.stderr)
        _sys.exit(2)

    _cmd, _rest = _sys.argv[1], _sys.argv[2:]
    if _cmd == "download":
        _status = download(
            url=_rest[0], download_dir=_rest[1], cookies_path=_s(_rest[2]),
            callback=_emit, filename_format=_s(_rest[3]), extra_args=_s(_rest[4]),
            archive_path=_s(_rest[5]), limit_rate=_s(_rest[6]), item_filter=_s(_rest[7]),
            should_cancel=None, exclude_video=(_rest[8] == "1"),
            retries=_s(_rest[9]) if len(_rest) > 9 else None,
            max_filesize=_s(_rest[10]) if len(_rest) > 10 else None,
            write_info_files=(_rest[11] == "1") if len(_rest) > 11 else False,
            proxy_url=_s(_rest[12]) if len(_rest) > 12 else None,
        )
        print(f"[__status__] {_status}", file=_real_stdout, flush=True)
    elif _cmd == "list_items":
        _result = list_items(url=_rest[0], cookies_path=_s(_rest[1]), extra_args=_s(_rest[2]))
        print(_result, file=_real_stdout, flush=True)
    else:
        print(f"Unknown command: {_cmd}", file=_sys.stderr)
        _sys.exit(2)
