import hashlib
import io
import json
import shlex
import sys
import time
from contextlib import redirect_stdout, redirect_stderr
import gallery_dl
import gallery_dl.job
import gallery_dl.output
import gallery_dl.option
import gallery_dl.util
import custom_names
import gallery_dl.path
import net_resilience
from comfort_events import event
net_resilience.install()  # stalled connects retry on a fresh connection; see its docstring


# Sites name a file's poster differently — uploader, author (Reddit's "name", X's {"name": ...}),
# user, owner, username (Redgifs' userName), artist — so the default filename can't list them all without picking up
# a record or a link somewhere. "poster_name" is the first of them that's a real name.
_POSTER_KEYS = ("uploader", "author", "user", "owner", "username", "userName", "artist", "creator", "channel")
_POSTER_SUBKEYS = ("name", "username", "handle", "screen_name", "nick")


def _poster_name(kwdict):
    for key in _POSTER_KEYS:
        value = kwdict.get(key)
        if isinstance(value, dict):
            value = next((value[k] for k in _POSTER_SUBKEYS if isinstance(value.get(k), str) and value[k].strip()), None)
        elif isinstance(value, (list, tuple)):
            value = value[0] if value else None
        if isinstance(value, str):
            value = value.strip()
            if value and value != "[deleted]" and "://" not in value:
                return value
    return None


def short_id(value):
    """The id a default filename ends with: as is up to 11 characters (a YouTube id), otherwise
    a 6-character code from its SHA-1 — X post ids are 19 digits and Instagram's file names ~50
    characters. A code, not the id's last characters: those repeat (Instagram's all end "_n").
    OldNames.shortId in the app computes the same code."""
    value = str(value or "")
    if len(value) <= 11:
        return value
    n = int(hashlib.sha1(value.encode("utf-8")).hexdigest()[:12], 16)
    digits = ""
    while n:
        n, r = divmod(n, 36)
        digits = "0123456789abcdefghijklmnopqrstuvwxyz"[r] + digits
    return digits[:6]


_original_update_kwdict = gallery_dl.job.Job.update_kwdict


def _update_kwdict(self, kwdict):
    _original_update_kwdict(self, kwdict)
    # A file reached through a post's link (a Reddit post of a Redgifs video) is named after that
    # post: gallery-dl hands the post's details to the linked site's extractor as "_parent".
    parent = kwdict.get("_parent") if isinstance(kwdict.get("_parent"), dict) else {}
    if "poster_name" not in kwdict:
        name = _poster_name(parent) or _poster_name(kwdict)
        if name:
            kwdict["poster_name"] = name
    if not kwdict.get("title") and isinstance(parent.get("title"), str) and parent["title"].strip():
        kwdict["title"] = parent["title"]
    # Worked out again for every file: an extractor can hand the same dict to each item of a post
    # (Reddit's gallery does, changing only filename), and a kept short_id named all of them after
    # the first, so every later one was skipped as "already downloaded".
    if kwdict.get("filename"):
        kwdict["short_id"] = short_id(kwdict["filename"])
    # The filename format's "{title|content[:150]!W...}": with neither field (a direct image link
    # like i.redd.it has no post around it), !W gets gallery-dl's None and the whole name fails.
    # An empty content just leaves the caption out.
    if not kwdict.get("title") and kwdict.get("content") is None:
        kwdict["content"] = ""


gallery_dl.job.Job.update_kwdict = _update_kwdict

_original_build_filename = gallery_dl.path.PathFormat.build_filename


def _build_filename(self, kwdict):
    # Every file's name, the default format's or a custom one's, at most custom_names'
    # NAME_MAX_BYTES (see there). The format caps the caption at 150 *characters*
    # ("{content[:150]}"), up to ~600 bytes in Japanese or emoji, well past Android's 255.
    return custom_names.fit_name(_original_build_filename(self, kwdict))


gallery_dl.path.PathFormat.build_filename = _build_filename

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

# gallery-dl's own HTTP downloader already reads each file's real Content-Length and calls
# self.out.progress(total, downloaded, speed) as it streams (downloader/http.py) — confirmed live
# this is exactly what shows a real total size/progress on a real terminal. But output.select()
# (output.py) only picks a real reporting class (TerminalOutput/ColorOutput) when sys.stdout.isatty()
# — under this app's own non-tty CallbackWriter redirect it silently picks PipeOutput instead,
# whose progress() is a no-op inherited from NullOutput, so none of this ever reached Kotlin at
# all. Reproduced live: yt-dlp-routed downloads always report [size]/[progress] (yt_dlp_wrapper.py's
# own progress_hooks, a first-class yt-dlp option), gallery-dl-routed ones (Reddit, Twitter, most
# image-board sites) never did, even though gallery-dl had the real byte counts the whole time.
# This reports them in the exact same [size]/[progress] line shape DownloadWorker.kt already
# parses, patched in for download() only (see its own use, below) — list_items()'s --dump-json
# pass never downloads anything, so there's nothing for this to report there.
class _CallbackOutput:
    def __init__(self, emit):
        self._emit = emit
        self._last_total = None
        self._last_emit_time = 0.0

    def start(self, path):
        self._last_total = None  # next progress() call's total belongs to this new file

    def skip(self, path):
        pass

    # gallery-dl's own default output class (PipeOutput, what a plain non-tty CLI run gets)
    # does exactly this on a successful file: `stdout_write(f"{path}\n")`. This class replaces
    # that default (see download()'s own gallery_dl.output.select patch below) so it could also
    # report [size]/[progress] lines, but never carried over the one line DownloadWorker.kt's own
    # callback actually depends on: it recognizes "a file was saved" by matching a bare absolute
    # path that exists inside stagingDir, and increments savedCount from that. Left as `pass`,
    # gallery-dl silently wrote every real file to disk with no visible signal any of them existed
    # — savedCount stayed 0 regardless of how many files a download actually produced. Reproduced
    # live: an Instagram photo carousel where gallery-dl completed with only cosmetic resolution
    # warnings (no error) and genuinely saved all 5 images to disk, yet the app recorded 0
    # downloaded items, triggered its savedCount==0 fallback to yt_dlp_wrapper.py, and surfaced
    # yt-dlp's own "No video formats found!" (correct for a photo-only post, but not the real
    # problem) as the download's final error — masking that gallery-dl had actually already
    # succeeded.
    def success(self, path):
        self._emit(event("file", path=path))

    def progress(self, bytes_total, bytes_downloaded, bytes_per_second):
        if bytes_total is not None and bytes_total != self._last_total:
            self._last_total = bytes_total
            self._emit(event("size", bytes=bytes_total))
        # Same 0.5s throttle yt_dlp_wrapper.py's own progress_hook already uses — gallery-dl's
        # downloader otherwise calls this several times a second, far more often than the UI
        # (or a DB write per line) needs.
        now = time.monotonic()
        if now - self._last_emit_time >= 0.5:
            self._last_emit_time = now
            self._emit(event("progress", downloaded=bytes_downloaded, speed=bytes_per_second or 0))

def probe(url):
    # "1" if gallery-dl has a real extractor for url, "0" otherwise (incl. on any error).
    # Pure regex match against each extractor's own `pattern` attribute, no network I/O.
    # gallery-dl's generic/direct-link extractor is opt-in (needs a "g:" prefix or explicit
    # config), so this doesn't trivially match everything. Used by DownloadWorker only to
    # *skip* an attempt that would otherwise still run and safely fail on its own, so failing
    # closed to "0" on any unexpected error here is always safe.
    try:
        import gallery_dl.extractor
        return "1" if gallery_dl.extractor.find(url) is not None else "0"
    except Exception:
        return "0"

def _has_reddit_login(cookies_path):
    """True if cookies.txt holds a logged-in Reddit session (a reddit_session cookie)."""
    try:
        with open(cookies_path, encoding="utf-8", errors="replace") as f:
            for raw in f:
                parts = raw.rstrip("\r\n").split("\t")
                if len(parts) > 6 and "reddit.com" in parts[0] and parts[5] == "reddit_session" and parts[6]:
                    return True
    except (OSError, TypeError):
        pass
    return False


def _reddit_client_args(cookies_path):
    """The anonymous app-token client-id (see download()'s comment) — unless the user is logged in
    to Reddit. That token mode sends every request to oauth.reddit.com without the user's cookies,
    and Reddit now blocks it by IP ("You've been blocked by network security", 403, seen live
    2026-10-21) while the same post's .json fetched with the user's own reddit_session cookie still
    returns 200. So with a login, leave client-id unset: gallery-dl's plain mode, with the cookies."""
    if cookies_path and _has_reddit_login(cookies_path):
        return []
    return ["-o", "extractor.reddit.client-id=6N9uN0krSDE-ig"]


# The user's extra arguments go onto gallery-dl's command line one option at a time: argparse
# stops at the first one it can't read (the whole download failed as "exited with code 2"), a
# shlex error used to drop the whole set without a word, and some options gallery-dl does take
# save nothing or save it outside the download folder. Every token left out is told to the app
# as a "note" report (code, flag, detail, engine).

# Spellings yt-dlp has too and gallery-dl reads the same way: kept in the set meant for both engines.
# The last row means the same too but is refused anyway (_refusal).
_SAME_IN_BOTH = frozenset((
    "--cookies", "--download-archive", "--force-ipv4", "--force-ipv6", "-4", "-6", "--ignore-config",
    "--limit-rate", "-r", "--netrc", "--no-colors", "--no-mtime", "--no-part", "--password", "-p",
    "--print-traffic", "--proxy", "--quiet", "-q", "--retries", "-R", "--source-address",
    "--user-agent", "--username", "-u", "--verbose", "-v", "--write-pages", "--xff",
    "--windows-filenames", "--write-info-json",
    "--cookies-from-browser", "--dump-json", "-j", "-J", "--exec", "--help", "-h", "--list-extractors",
    "--no-download", "--print", "--print-to-file", "--simulate", "-s", "--version", "-U", "-g",
))
# The other spellings both have (gallery-dl 1.32.9 vs yt-dlp 2026.08), with how many values yt-dlp
# gives each: gallery-dl reads them as something else. "-f best" named every file "best", "-N 4"
# printed "4" instead of downloading, "-S res:720" started gallery-dl's server mode and took
# "res:720" for a link, "-i"/"-c"/"-x" read the link itself as a file to load.
_YTDLP_MEANING = {
    "-C": 0, "-I": 1, "-N": 1, "-O": 1, "-P": 1, "-S": 1, "-a": 1, "-c": 0, "-e": 0, "-f": 1,
    "-i": 0, "-o": 1, "-w": 0, "-x": 0, "--mtime": 0, "--restrict-filenames": 0,
}
# yt_dlp_wrapper's own markers in a download's extra commands (WebBrowser.queueCaught), with their
# value counts: not the user's, and nothing gallery-dl should see.
_APP_MARKERS = {"--comfort-direct-fallback": 0, "--comfort-merge-audio": 1}
# Options that save nothing, print instead of saving, put files outside the download folder,
# write to other paths (the process's cwd is "/" on Android, read-only), load config or code, or
# read a browser's cookies.
_REFUSED_DESTS = frozenset((
    "directory", "base-directory", "list_urls", "dump_json", "jobtype", "list_modules",
    "list_extractors", "update", "input_files", "configs_extra", "configs_json", "configs_yaml",
    "configs_toml", "config_type", "config", "cookies_from_browser", "server", "extractor_sources",
    "cache_file", "cache_status", "cache_show", "cache_clear", "cache_vacuum", "errorfile",
    "logfile", "unsupportedfile", "cookies-update", "write-pages", "download", "help", "version",
))
_REFUSED_FLAGS = frozenset((
    "--zip", "--cbz", "-N", "--print", "--Print", "--print-to-file", "--Print-to-file", "--exec",
    "--exec-after",
))
# The same, as -o config keys (their last part).
_REFUSED_KEYS = frozenset((
    "part-directory", "logfile", "unsupportedfile", "errorfile", "cookies-update", "write-pages",
    "globals", "module-sources",
))
_FILTER_DESTS = frozenset(("file-filter", "post-filter", "child-filter"))
_FILTER_KEYS = frozenset(("image-filter", "file-filter", "post-filter", "child-filter", "chapter-filter"))
_parsers = {}


def _gdl_option(parser, tok):
    """How gallery-dl's argparse reads [tok]: None for a value or link, else a list of
    (action, spelling, inline value) — more than one for "-qv". action is None for an option
    gallery-dl doesn't have, "ambiguous" for an abbreviation of several. Mirrors argparse's own
    _parse_optional (abbreviations, "--opt=value", "-Rvalue")."""
    acts = parser._option_string_actions
    if not tok.startswith("-") or tok == "-":
        return None
    if tok in acts:
        return [(acts[tok], tok, None)]
    name, eq, value = tok.partition("=")
    if eq and name in acts:
        return [(acts[name], name, value)]
    if tok.startswith("--"):
        if tok == "--":
            return [(None, tok, None)]
        hits = [s for s in acts if s.startswith("--") and s.startswith(name)]
        if len(hits) == 1:
            return [(acts[hits[0]], hits[0], value if eq else None)]
        if hits:
            return [("ambiguous", tok, None)]
    elif tok[:2] in acts:
        found, rest = [], tok[1:]
        while rest:
            flag = "-" + rest[0]
            action = acts.get(flag)
            if action is None:
                return [(None, tok, None)]
            if action.nargs != 0:
                found.append((action, flag, rest[1:] or None))
                return found
            found.append((action, flag, None))
            rest = rest[1:]
        return found
    if parser._negative_number_matcher.match(tok) or " " in tok:
        return None
    return [(None, tok, None)]


def _ytdlp_values(tok):
    """How many of the tokens after [tok] are its values for yt-dlp, None if yt-dlp has no such
    option (or can't be loaded: then the token is just unknown)."""
    if "ytdlp" not in _parsers:
        try:
            import yt_dlp.options
            _parsers["ytdlp"] = yt_dlp.options.create_parser()
        except Exception:  # noqa: BLE001
            _parsers["ytdlp"] = None
    parser = _parsers["ytdlp"]
    if parser is None:
        return None
    name, eq, _ = tok.partition("=")
    if name.startswith("--"):
        opt = parser._long_opt.get(name)
        if opt is None:
            hits = [s for s in parser._long_opt if s.startswith(name)]
            opt = parser._long_opt[hits[0]] if len(hits) == 1 else None
        inline = bool(eq)
    else:
        opt, inline = parser._short_opt.get(tok[:2]), len(tok) > 2
    if opt is None:
        return None
    if not opt.takes_value():
        return 0
    return max(opt.nargs - (1 if inline else 0), 0)


def _parse_error(parser, group):
    """argparse's complaint about [group] alone, without its "usage: ... error:" lead, or None.
    No link after it: a value left out would take the link instead ("--range URL")."""
    err = io.StringIO()
    try:
        with redirect_stdout(io.StringIO()), redirect_stderr(err):
            parser.parse_args(group)
    except SystemExit:
        lines = err.getvalue().strip().splitlines()
        return lines[-1].split("error: ", 1)[-1] if lines else "invalid"
    return None


def _filter_error(expr):
    # gallery-dl only warns about a filter that doesn't compile, and then downloads everything.
    try:
        gallery_dl.util.compile_filter(expr)
    except Exception as e:  # noqa: BLE001
        return str(e) or type(e).__name__
    return None


def _has_parent_segment(value):
    if isinstance(value, str):
        return any(seg.strip() == ".." for seg in value.replace("\\", "/").split("/"))
    if isinstance(value, dict):
        return any(_has_parent_segment(v) for v in value.values())
    if isinstance(value, (list, tuple)):
        return any(_has_parent_segment(v) for v in value)
    return False


def _keeps_separators(restrict=None, replace="_"):
    """True if names cleaned with this path-restrict/path-replace could still hold a "/" (or
    Windows' "\\"), so a "../" in a name or format would leave the download folder."""
    import os
    if restrict in (None, "auto", "unix", "windows", "windows+", "ascii", "ascii+"):
        restrict = "auto"
        sample = os.sep
    else:
        sample = "/" + os.sep
    if restrict == "auto":
        restrict = "\\\\|/<>:\"?*" if os.name == "nt" else "/"
    if not isinstance(replace, str) or "/" in replace or os.sep in replace:
        return True
    clean = gallery_dl.path._build_cleanfunc(dict(restrict) if isinstance(restrict, dict) else restrict, replace)
    return any(c in clean(sample) for c in "/" + os.sep)


def _refusal(action, spelling, values):
    """(code, detail) for a gallery-dl option that can't be used here, else None."""
    if spelling in _REFUSED_FLAGS or action.dest in _REFUSED_DESTS:
        return "extra_not_allowed", None
    value = values[0] if values else None
    if action.dest in _FILTER_DESTS and isinstance(value, str):
        error = _filter_error(value)
        return ("extra_invalid", error) if error else None
    if action.dest == "postprocessors" and spelling in ("-P", "--postprocessor") and value in ("zip", "exec"):
        return "extra_not_allowed", value
    if action.dest == "path-restrict" and action.nargs != 0:
        try:
            return ("extra_not_allowed", value) if _keeps_separators(value) else None
        except Exception as e:  # noqa: BLE001
            return "extra_invalid", str(e)
    if action.dest in ("options", "options_pp"):
        if "=" not in (value or ""):
            return "extra_invalid", "expected KEY=VALUE"
        key, parsed = gallery_dl.option._parse_option(value)
        parts = key.split(".")
        last, top = parts[-1], len(parts) == 1
        if action.dest == "options_pp":
            # Post processor options (the metadata writer's): its own folder, relative to each file's.
            if last == "base-directory" or (last == "directory" and _has_parent_segment(parsed)):
                return "extra_not_allowed", value
            return None
        # --directory sets the top-level base-directory and directory, which beat every
        # extractor's own (config.interpolate), so only top-level ones can move files.
        if (last in _REFUSED_KEYS or (top and last == "base-directory")
                or (top and last == "directory" and _has_parent_segment(parsed))
                or (last == "download" and parsed is False)
                or (last == "cookies" and not isinstance(parsed, str))
                or (last == "postprocessors" and ("zip" in str(parsed) or "exec" in str(parsed)))):
            return "extra_not_allowed", value
        if last in _FILTER_KEYS and isinstance(parsed, str):
            error = _filter_error(parsed)
            return ("extra_invalid", error) if error else None
        try:
            if last == "path-restrict" and _keeps_separators(parsed):
                return "extra_not_allowed", value
            if last == "path-replace" and _keeps_separators(replace=parsed):
                return "extra_not_allowed", value
        except Exception as e:  # noqa: BLE001
            return "extra_invalid", str(e)
    return None


def _checked_extras(text, shared, note):
    """The tokens of [text] gallery-dl can use, in order; note(**fields) for each one left out.
    [shared]: the set meant for both engines, where options gallery-dl doesn't have but yt-dlp
    does, and those both have but read differently, were meant for yt-dlp."""
    if not text or not text.strip():
        return []
    try:
        tokens = shlex.split(text)
    except ValueError:
        note(code="extra_quotes")
        return []
    if "gdl" not in _parsers:
        _parsers["gdl"] = gallery_dl.option.build_parser()
    parser = _parsers["gdl"]

    def is_value(t):
        return _gdl_option(parser, t) is None

    def is_link(t):
        return "://" in t or t.lower().startswith(("http:", "https:"))

    kept, i = [], 0
    while i < len(tokens):
        tok = tokens[i]
        i += 1
        if shared and tok in _APP_MARKERS:
            i += _APP_MARKERS[tok]
            continue
        found = _gdl_option(parser, tok)
        if found is None:
            # A word on its own: gallery-dl would take it for one more link to download.
            note(code="extra_url" if is_link(tok) else "extra_unknown", flag=tok)
            continue
        for n, (action, spelling, inline) in enumerate(found):
            last = n == len(found) - 1
            if action == "ambiguous":
                note(code="extra_invalid", flag=tok, detail=_parse_error(parser, [tok]) or "ambiguous option")
                continue
            if action is None or (shared and spelling in _YTDLP_MEANING and spelling not in _SAME_IN_BOTH):
                count = _ytdlp_values(tok) if action is None else (0 if inline is not None else _YTDLP_MEANING[spelling])
                if shared and count is not None:
                    i += min(count, len(tokens) - i)
                    note(code="extra_other_engine", flag=tok, engine="yt-dlp")
                else:
                    # Its value too, if it has one: the words after it that aren't links.
                    while i < len(tokens) and is_value(tokens[i]) and not is_link(tokens[i]):
                        i += 1
                    note(code="extra_unknown", flag=tok)
                break
            flag = spelling if len(found) > 1 else tok
            values = [] if inline is None else [inline]
            if last and inline is None and action.nargs != 0:
                want = {None: 1, "?": 1, "*": len(tokens), "+": len(tokens)}.get(action.nargs, action.nargs)
                while len(values) < want and i < len(tokens) and is_value(tokens[i]):
                    values.append(tokens[i])
                    i += 1
            if inline is None:
                group = [spelling] + values
            else:
                group = [spelling + ("=" if spelling.startswith("--") else "") + inline]
            # Refused before parsing: --help and --version exit from inside the parse.
            refused = _refusal(action, spelling, values) if action.dest not in ("options", "options_pp") or values else None
            error = None if refused else _parse_error(parser, group)
            if error:
                note(code="extra_invalid", flag=flag, detail=error)
                continue
            if refused:
                code, detail = refused
                fields = {"code": code, "flag": flag}
                if detail:
                    fields["detail"] = str(detail)
                note(**fields)
                continue
            kept.extend(group)
    return kept


def _extras(text, shared, note):
    # Whatever goes wrong in here mustn't fail the download: the set is left out, and said so.
    try:
        return _checked_extras(text, shared, note)
    except Exception as e:  # noqa: BLE001
        if text and text.strip():
            note(code="extra_invalid", flag=text, detail=f"{type(e).__name__}: {e}")
        return []


def download(url, download_dir, cookies_path=None, callback=None, filename_format=None, extra_args=None, archive_path=None, limit_rate=None, item_filter=None, should_cancel=None, exclude_video=False, retries=None, max_filesize=None, write_info_files=False, proxy_url=None, socket_timeout_seconds=None, sleep_interval_seconds=None, restrict_filenames=False, force_ipv4=False, no_check_certificates=False, shared_extra_args=None):
    writer = CallbackWriter(callback, should_cancel) if callback else sys.stdout

    original_argv = sys.argv
    # Reddit's own extractor defaults to its plain, unauthenticated "REST" mode (a bare GET to
    # reddit.com/.../.json) whenever no client-id is configured, *regardless* of any "api" option
    # (reddit.py: "if config('api') == 'rest' or not client_id" — the "or not client_id" half means
    # setting api=oauth alone, tried first, does nothing on its own) — confirmed live that REST mode
    # now gets a flat 403 from Reddit for *any* anonymous request, not just NSFW/quarantined ones (a
    # plain r/aww listing 403'd identically). Setting client-id to gallery-dl's own bundled default
    # value (the same constant reddit.py falls back to itself once this escapes the REST branch —
    # not a real secret) is what actually flips it into Reddit's still-sanctioned anonymous
    # "installed_client" app-token flow — confirmed live end-to-end against a real reddit.com share
    # link whose post links out to a redgifs video: OAuth token issued, submission JSON fetched
    # (200, vs REST mode's 403), and the redgifs file downloaded successfully. reddit.py's own
    # request loop already retries with a backoff on that path's occasional 429 rate limit (shared
    # client-id, contended across every anonymous gallery-dl install), unlike REST mode's
    # un-recoverable 403. Also incidentally re-enables reddit.py's own "allow downloading from
    # quarantined subreddits" cookie logic, which is dead code under REST mode.
    args = ["gallery-dl", "--directory", download_dir] + _reddit_client_args(cookies_path)
    if cookies_path:
        args.extend(["--cookies", cookies_path])
    if filename_format and filename_format.startswith("{poster_name|category}"):
        # The default format (its "{poster_name|category}" start). A plain file link (gallery-dl's
        # "directlink": no poster, no title) came out of it as "directlink [Example]
        # [directlink].jpg", titled "directlink" in the Library; it's named after the file and its
        # site instead: "Example [wikimedia.org].jpg". Set as extractor.filename, not --filename:
        # that one sits at the top of gallery-dl's config and beats every per-site setting.
        args.extend(["-o", f"extractor.filename={filename_format}",
                     "-o", "extractor.directlink.filename={filename} [{subcategory}].{extension}"])
    elif filename_format:
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
    # gallery-dl sets each file's mtime to the server's Last-Modified by default (the upload time
    # for e.g. Redgifs), so the saved file would look uploaded rather than downloaded.
    args.extend(["-o", "downloader.mtime=false"])
    if max_filesize:
        args.extend(["-o", f"downloader.filesize-max={max_filesize}"])
    if socket_timeout_seconds:
        # Same idea as yt_dlp_wrapper.py's socket_timeout — gallery-dl's own default is 30s;
        # covers both the metadata/listing request and each individual file fetch, same two
        # config paths "retries" above already needed to cover both places.
        args.extend(["-o", f"extractor.timeout={socket_timeout_seconds}", "-o", f"downloader.timeout={socket_timeout_seconds}"])
    # Settings > Downloads / Folders options the other engines follow too, through gallery-dl's own:
    if sleep_interval_seconds:
        # A random 2-to-N second pause before each file, like yt-dlp's --min/--max-sleep-interval.
        args.extend(["--sleep", f"2-{max(2, int(sleep_interval_seconds))}"])
    if restrict_filenames:
        # yt-dlp's restricted names' characters: letters, digits, "_", ".", "-" and the "[id]"
        # brackets; anything else (spaces included) becomes "_". gallery-dl's own "ascii" also
        # turned "-" and the brackets into "_" ("imgur__6WDRFne___imgur_.jpg").
        args.extend(["-o", r"path-restrict=^0-9A-Za-z_.\[\]-"])
    if force_ipv4:
        args.append("--force-ipv4")
    if no_check_certificates:
        args.append("--no-check-certificate")
    if write_info_files:
        # gallery-dl's closest analog to yt-dlp's --write-info-json: one JSON sidecar per
        # downloaded file, containing the same metadata used for --filename/-filter expressions.
        args.append("--write-metadata")
    # The set meant for both engines first, gallery-dl's own after it: gallery-dl's own wins where
    # they set the same thing, as when the app joined them into one set.
    def note(**fields):
        (callback or print)(event("note", **fields))
    args.extend(_extras(shared_extra_args, True, note))
    args.extend(_extras(extra_args, False, note))
    args.append(url)

    sys.argv = args
    status = "Done"

    original_output_select = gallery_dl.output.select
    if callback:
        gallery_dl.output.select = lambda: _CallbackOutput(writer._emit)

    with redirect_stdout(writer), redirect_stderr(writer):
        try:
            gallery_dl.main()
        except SystemExit as e:
            if e.code != 0:
                status = f"Error: exited with code {e.code}"
                # Unlike the `except Exception` branch just below, this used to have no live
                # signal at all — only the final `[__status__] {status}` line printed after this
                # whole `with` block exits (via _real_stdout directly, bypassing `callback`
                # entirely). DownloadWorker.kt's error-capturing logic never recognized that
                # line's shape (it isn't `[error] `, doesn't match gallery-dl's own
                # `[extractor][error]` pattern, and isn't `Error,`/`Exception:`), so a bare
                # `sys.exit(N)` from inside gallery-dl with nothing else printed first left the
                # user with the generic "no downloadable content" message instead of the real
                # reason. Matching the sibling branch's own already-working "Exception: ..." live
                # print fixes this at the source instead of widening Kotlin's own line matching.
                print(f"Exception: exited with code {e.code}")
        except KeyboardInterrupt:
            status = "Cancelled"
        except Exception as e:
            status = f"Error: {e}"
            print(f"Exception: {e}")
        finally:
            gallery_dl.output.select = original_output_select
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

# Items whose size is asked for — enough for a carousel or a page of a gallery without a long wait
# on a huge one (the Kotlin listing itself stops at a similar count).
_SIZED_ITEMS = 60


def _with_sizes(dump_json, referer=None):
    """--dump-json output with each file's size added as "filesize" (bytes), asked from the media
    server since gallery-dl doesn't list sizes — so the picker/preview can say how big a download
    will be. Anything that goes wrong returns the output unchanged: sizes are a nicety."""
    try:
        entries = json.loads(dump_json)
        files = [e for e in entries if isinstance(e, list) and len(e) >= 3 and e[0] == 3
                 and isinstance(e[1], str) and isinstance(e[2], dict) and not e[2].get("filesize")][:_SIZED_ITEMS]
        if not files:
            return dump_json
        sizes = net_resilience.content_lengths([e[1] for e in files], headers={"Referer": referer} if referer else None)
        if not sizes:
            return dump_json
        for e in files:
            if e[1] in sizes:
                e[2]["filesize"] = sizes[e[1]]
        return json.dumps(entries, ensure_ascii=False, default=str)
    except Exception:  # noqa: BLE001
        return dump_json


def list_items(url, cookies_path=None, extra_args=None, shared_extra_args=None):
    """Enumerates items in a gallery without downloading anything, for the share-sheet item
    picker. Returns gallery-dl's raw --dump-json output as text (a JSON array of
    [message_type, url, keywords] entries); parsed on the Kotlin side. --dump-json already
    implies simulate mode on its own, so no separate --simulate flag is needed."""
    original_argv = sys.argv
    # Same fix as download()'s own identical line — see its comment for the full reasoning.
    args = ["gallery-dl", "--dump-json"] + _reddit_client_args(cookies_path)
    if cookies_path:
        args.extend(["--cookies", cookies_path])
    # Checked as download() does (a stray -g or link broke the listing's JSON); the notes aren't
    # shown while listing, the download reports them.
    args.extend(_extras(shared_extra_args, True, lambda **fields: None))
    args.extend(_extras(extra_args, False, lambda **fields: None))
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

    out = _with_sizes(out_buffer.getvalue(), referer=url)
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
        print("Usage: gallery_dl_wrapper.py <download|list_items|probe> ...", file=_sys.stderr)
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
            socket_timeout_seconds=_s(_rest[13]) if len(_rest) > 13 else None,
            sleep_interval_seconds=_s(_rest[14]) if len(_rest) > 14 else None,
            restrict_filenames=(_rest[15] == "1") if len(_rest) > 15 else False,
            force_ipv4=(_rest[16] == "1") if len(_rest) > 16 else False,
            no_check_certificates=(_rest[17] == "1") if len(_rest) > 17 else False,
            shared_extra_args=_s(_rest[18]) if len(_rest) > 18 else None,
        )
        print(event("exit", status=_status), file=_real_stdout, flush=True)
    elif _cmd == "list_items":
        _result = list_items(url=_rest[0], cookies_path=_s(_rest[1]), extra_args=_s(_rest[2]),
                             shared_extra_args=_s(_rest[3]) if len(_rest) > 3 else None)
        print(_result, file=_real_stdout, flush=True)
    elif _cmd == "probe":
        print(probe(_rest[0]), file=_real_stdout, flush=True)
    else:
        print(f"Unknown command: {_cmd}", file=_sys.stderr)
        _sys.exit(2)
