import io
import shlex
import sys
from contextlib import redirect_stdout, redirect_stderr
import gallery_dl
import gallery_dl.job

class CallbackWriter:
    def __init__(self, callback):
        self.callback = callback
        self.buffer = ""

    def write(self, text):
        self.buffer += text
        if '\n' in self.buffer:
            lines = self.buffer.split('\n')
            for line in lines[:-1]:
                if line.strip():
                    self.callback(line)
            self.buffer = lines[-1]

    def flush(self):
        if self.buffer.strip():
            self.callback(self.buffer)
            self.buffer = ""

def download(url, download_dir, cookies_path=None, callback=None, filename_format=None, extra_args=None, archive_path=None, limit_rate=None, item_filter=None):
    writer = CallbackWriter(callback) if callback else sys.stdout

    original_argv = sys.argv
    args = ["gallery-dl", "--directory", download_dir]
    if cookies_path:
        args.extend(["--cookies", cookies_path])
    if filename_format:
        args.extend(["--filename", filename_format])
    if limit_rate:
        args.extend(["--limit-rate", limit_rate])
    if item_filter:
        # Restricts the download to specific items picked in the share sheet, e.g. "num in {1,3,5}".
        args.extend(["--filter", item_filter])
    if archive_path:
        # Tracks already-downloaded item IDs so a retried/resumed download only fetches what's
        # still missing, instead of re-downloading the whole gallery from scratch.
        args.extend(["--download-archive", archive_path])
    if extra_args:
        try:
            args.extend(shlex.split(extra_args))
        except ValueError:
            pass
    args.append(url)
    
    sys.argv = args
    
    with redirect_stdout(writer), redirect_stderr(writer):
        try:
            gallery_dl.main()
        except SystemExit as e:
            if e.code != 0:
                print(f"Error, exited with code {e.code}")
        except Exception as e:
            print(f"Exception: {e}")
        finally:
            if callback:
                writer.flush()
            sys.argv = original_argv
    
    return "Done"

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
    out_buffer = io.StringIO()
    err_buffer = io.StringIO()

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
    if out.strip():
        return out
    # Nothing on stdout — surface whatever gallery-dl said on stderr (auth required,
    # unsupported URL, etc.) so it's visible in logs instead of silently returning nothing.
    return "ERR:" + err_buffer.getvalue()
