import importlib.util
import json
import os
import re
import time
import yt_dlp
from yt_dlp.networking.impersonate import ImpersonateTarget
from yt_dlp.postprocessor.common import PostProcessor
from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor
from yt_dlp.utils import PostProcessingError
from yt_dlp.postprocessor.metadataparser import MetadataParserPP

# curl_cffi is the only impersonate backend this bundled yt-dlp ships with (see
# PythonRuntime.kt's own comment on where it comes from) — and it's only bundled for the
# arm64-v8a Python runtime, not armeabi-v7a or x86_64 (no upstream build available for those
# ABIs). Setting ydl_opts["impersonate"] with zero backends available doesn't just skip
# impersonation quietly — YoutubeDL.__init__ hard-raises 'Impersonate target "" is not
# available' before a single request is attempted, killing the whole download outright
# (reproduced live on a 32-bit-only device). Checking importability up front lets a Reddit
# share link at least fall through to a normal, non-impersonated request on those ABIs —
# same as any other yt-dlp error, instead of a guaranteed hard crash.
_IMPERSONATE_AVAILABLE = importlib.util.find_spec("curl_cffi") is not None

class _Cancelled(Exception):
    pass

# yt-dlp names each pre-merge fragment "<title>.f<format_id>.<ext>" when video and audio were
# fetched as separate formats to be merged (bestvideo+bestaudio) — e.g. "foo.f395.mp4" and
# "foo.f251.webm" for a video-only and an audio-only stream about to be combined into one file.
# A single already-muxed format downloads straight to the plain output name instead. format_id
# isn't always purely numeric — Instagram's DASH representations use ids like "dash-1234567v" —
# so this matches on shape (starts right after the dot with an alphanumeric, no embedded dots)
# rather than requiring digits only.
_FRAGMENT_SUFFIX_RE = re.compile(r"\.f[A-Za-z0-9][A-Za-z0-9_-]*\.[^./\\]+$")

# Reddit's own WAF blocks a plain request for a reddit.com/r/<sub>/s/<code> mobile share link's
# redirect (reproduced live: "[generic] ...: Unable to download webpage: HTTP Error 403: Blocked")
# — yt-dlp's Reddit extractor only recognizes the /comments/... URL that redirect resolves to, not
# the /s/ shape itself, so a raw share link falls through to the generic extractor to resolve it,
# which is what actually hits the block. A prior fix resolved the redirect in Kotlin first with a
# naked HttpURLConnection and a spoofed User-Agent header — but a spoofed *header* with the wrong
# TLS fingerprint behind it is exactly what a WAF like this is designed to catch, so that "fix" was
# tripping the same block it was meant to avoid.
#
# Real TLS-fingerprint impersonation (not just a spoofed header) is what actually gets past it —
# curl_cffi (yt-dlp's --impersonate backend) does this but is only bundled for arm64-v8a (its cffi
# dependency needs a Python-version-locked native module nobody has built for armeabi-v7a/x86_64),
# so a first attempt scoping this fix to curl_cffi left it unresolved on every other device
# (crashing outright until _IMPERSONATE_AVAILABLE above was added as a stopgap). A second attempt
# fixed only the one redirect request with a narrow, standalone tls-client (github.com/bogdanfinn/
# tls-client) ctypes call — a plain Go binary with zero Python version coupling, so the exact same
# build works on all three ABIs (see TlsClientRuntime.kt) — but a controlled back-to-back A/B test
# (same posts, both builds, minutes apart) showed Reddit's own JSON metadata endpoint *also*
# intermittently rejects a plain, unimpersonated follow-up request ("Your IP address is unable to
# access the Reddit API") even once the redirect itself resolves fine — curl_cffi's old fix
# happened to dodge this because setting `impersonate` on YoutubeDL applies to the *entire*
# session, not just the one request that needed it.
#
# TlsClientRH below is the fix that actually matches that: a real yt_dlp RequestHandler (mirroring
# curl_cffi's own CurlCFFIRH), registered so any reddit.com request — the share-link redirect, the
# JSON metadata fetch, anything else reddit.com itself serves — rides on tls-client's real
# TLS-fingerprint impersonation, on every ABI. It deliberately never claims requests to other hosts
# (_validate rejects them outright, see below) — Reddit's actual video/audio segments are served
# from a completely different host (v.redd.it) that has never needed impersonation, and TlsClientRH
# never touches that path at all. That host-scoping is exactly what keeps this from being the
# *general* impersonate backend already considered and rejected (see TlsClientRuntime.kt's own
# comment): every request this handler will ever actually send is a single small page/JSON fetch,
# never a multi-hundred-MB video body, so tls-client's call-blocks-until-the-whole-body-is-done
# model (fine for a few KB, a real problem for a video) never comes into play here.
_REDDIT_HOST_RE = re.compile(r"^(www\.)?reddit\.com$", re.IGNORECASE)

# Set once per process, from the CLI arg, before any YoutubeDL(...) is constructed — read directly
# by TlsClientRH below rather than threaded through YoutubeDL's own handler-construction kwargs
# (build_request_director only forwards params it already knows about), which is fine since this
# whole script is a single download/list call running as its own fresh OS process (see
# PythonRuntime.kt), the same lifetime a module-level global here needs to have.
_TLS_CLIENT_PATH = None

def _tls_client_request(url, method="GET", headers=None, body=None, timeout_seconds=20):
    """One HTTP request via tls-client's request() export, working around two Android-specific
    problems confirmed live on-device before landing this:
    - Go's own DNS resolver doesn't work reliably on Android at all (reproduced live: even a plain
      https://example.com request from this exact bundled binary times out with "dial tcp: lookup
      example.com: i/o timeout"). Python's own socket.gethostbyname(), going through bionic's real
      resolver, works fine — every other network call in this app already depends on that same
      resolver — so this resolves the hostname itself and connects to that literal IP instead,
      with requestHostOverride/serverNameOverwrite keeping the real hostname in the Host header and
      the TLS SNI/certificate check, so the server and cert validation still see the real host
      exactly as normal — only the actual TCP connection target changes.
    - Never asks tls-client to follow redirects itself (followRedirects=False always) — a redirect
      target is a literal hostname again (not our resolved IP), so an internally-followed hop would
      hit the exact same broken DNS resolution this function exists to route around. Callers that
      care about redirects (TlsClientRH below) read the Location header and loop themselves,
      re-resolving each hop's own host the same way.

    Returns (status, headers_dict, body_text). Raises on any load/network/JSON failure — every
    caller here treats that as an ordinary transport failure, not a special case."""
    import ctypes
    import socket
    from urllib.parse import urlsplit, urlunsplit

    parsed = urlsplit(url)
    host = parsed.hostname
    ip = socket.gethostbyname(host)
    netloc = ip if parsed.port is None else f"{ip}:{parsed.port}"
    ip_url = urlunsplit((parsed.scheme, netloc, parsed.path, parsed.query, parsed.fragment))

    lib = ctypes.CDLL(_TLS_CLIENT_PATH)
    lib.request.argtypes = [ctypes.c_char_p]
    lib.request.restype = ctypes.c_char_p
    lib.freeMemory.argtypes = [ctypes.c_char_p]
    payload = {
        "tlsClientIdentifier": "chrome_146",
        "followRedirects": False,
        "timeoutSeconds": timeout_seconds,
        "requestUrl": ip_url,
        "requestHostOverride": host,
        "serverNameOverwrite": host,
        "requestMethod": method,
        "headers": {**(headers or {}), "host": host},
    }
    if body is not None:
        payload["requestBody"] = body
    raw = lib.request(json.dumps(payload).encode("utf-8"))
    response = json.loads(ctypes.string_at(raw).decode("utf-8"))
    response_id = response.get("id")
    try:
        status = response.get("status") or 0
        if status == 0:
            raise OSError(response.get("body") or "tls-client request failed")
        return status, (response.get("headers") or {}), (response.get("body") or "")
    finally:
        # Every response allocates memory on the Go side that's only ever freed by this call —
        # skipping it on an early return/exception would leak it for the life of this process.
        if response_id:
            lib.freeMemory(response_id.encode("utf-8"))

def _header_value(headers, name):
    """First value for `name` in a tls-client response's headers dict, case-insensitively — Go's
    http.Header round-trips through JSON as {"Canonical-Case-Name": ["value", ...]}."""
    for key, value in headers.items():
        if key.lower() == name.lower():
            return value[0] if isinstance(value, list) and value else value
    return None

from yt_dlp.networking.common import Request, Response, register_preference, register_rh
from yt_dlp.networking.exceptions import HTTPError, RequestError, TransportError, UnsupportedRequest
from yt_dlp.networking.impersonate import ImpersonateRequestHandler

@register_rh
class TlsClientRH(ImpersonateRequestHandler):
    """See this module's own comment above _REDDIT_HOST_RE for the full story — scoped to
    reddit.com requests only, via _validate below, never a general impersonate backend."""
    RH_NAME = "tls_client"
    _SUPPORTED_URL_SCHEMES = ("http", "https")
    _SUPPORTED_PROXY_SCHEMES = None
    _SUPPORTED_FEATURES = ()
    _SUPPORTED_IMPERSONATE_TARGET_MAP = {ImpersonateTarget("chrome"): "chrome_146"}

    def _check_extensions(self, extensions):
        super()._check_extensions(extensions)
        extensions.pop("impersonate", None)
        extensions.pop("cookiejar", None)
        extensions.pop("timeout", None)

    def _validate(self, request):
        super()._validate(request)
        if not _TLS_CLIENT_PATH:
            raise UnsupportedRequest("tls-client library not available on this ABI")
        from urllib.parse import urlsplit
        host = (urlsplit(request.url).hostname or "").lower()
        if not _REDDIT_HOST_RE.match(host):
            raise UnsupportedRequest("tls_client is scoped to reddit.com requests only")

    def _send(self, request):
        import io
        import urllib.request
        from email.message import Message
        from urllib.parse import urljoin

        # Reddit's own extractor does a two-step dance for this exact host: a "session setup"
        # request first (to get a `loid` session cookie), then the real JSON metadata fetch that
        # only trusts the response if that cookie is attached (see reddit.py's own _real_extract —
        # confirmed live: without this, the second request looks sessionless to Reddit and gets
        # rejected with "Your IP address is unable to access the Reddit API", a misleading message
        # for what's actually a missing-cookie problem, not a real IP block). curl_cffi's own
        # CurlCFFIRH gets this for free from its persistent Session object; this handler has no
        # persistent session at all (each call is a stateless one-shot ctypes request), so cookies
        # have to be carried explicitly through yt-dlp's own cookiejar instead — added to every
        # outgoing request here, and every Set-Cookie response fed back into the same jar, exactly
        # like the plain urllib handler's HTTPCookieProcessor does, so the *next* request in this
        # same download (the actual JSON fetch) sees whatever the session-setup request left behind.
        cookiejar = self._get_cookiejar(request)

        url = request.url
        method = request.method
        body = request.data
        if isinstance(body, bytes):
            body = body.decode("utf-8", "replace")
        elif body is not None and not isinstance(body, str):
            body = None  # a file-like/iterable payload — none of this handler's own callers send one
        headers = dict(self._get_impersonate_headers(request))
        timeout = int(self._calculate_timeout(request)) or 20

        for _ in range(6):
            cookie_req = urllib.request.Request(url)
            if cookiejar is not None and "cookie" not in {k.lower() for k in headers}:
                cookiejar.add_cookie_header(cookie_req)
                cookie_header = cookie_req.get_header("Cookie")
                if cookie_header:
                    headers = {**headers, "Cookie": cookie_header}
            try:
                status, resp_headers, resp_body = _tls_client_request(
                    url, method=method, headers=headers, body=body, timeout_seconds=timeout)
            except Exception as e:
                raise TransportError(cause=e) from e
            if cookiejar is not None:
                set_cookie_msg = Message()
                for key, value in resp_headers.items():
                    if key.lower() == "set-cookie":
                        for one in (value if isinstance(value, list) else [value]):
                            set_cookie_msg.add_header("Set-Cookie", one)
                fake_response = type("_TlsClientCookieResponse", (), {"info": lambda self: set_cookie_msg})()
                cookiejar.extract_cookies(fake_response, cookie_req)
            if status in (301, 302, 303, 307, 308):
                location = _header_value(resp_headers, "location")
                if not location:
                    break
                url = urljoin(url, location)
                if status == 303:
                    method, body = "GET", None
                continue
            flat_headers = {k: (v[0] if isinstance(v, list) and v else v) for k, v in resp_headers.items()}
            response = Response(fp=io.BytesIO(resp_body.encode("utf-8")), url=url, headers=flat_headers, status=status)
            if not 200 <= status < 300:
                raise HTTPError(response)
            return response
        raise TransportError(cause=Exception("too many redirects"))

@register_preference(TlsClientRH)
def _tls_client_preference(rh, request):
    # Same shape as yt_dlp.networking.impersonate's own impersonate_preference — outranks the
    # default non-impersonating handlers for every request once `impersonate` is configured, not
    # just ones that explicitly ask for it. _validate above is what actually limits this to
    # reddit.com; every other host falls through to the next handler in preference order.
    # One point above impersonate_preference's own 1000 so this deterministically wins the tie
    # against curl_cffi for a reddit.com request on arm64-v8a (the one ABI where both are
    # registered and eligible) — this handler is the one actually verified end-to-end against
    # Reddit's current WAF/API behavior, so every ABI takes the same, consistently-tested path
    # for reddit.com rather than arm64 silently diverging onto curl_cffi's.
    if request.extensions.get("impersonate") or rh.impersonate:
        return 1001
    return 0

def _fix_impersonate_availability_check(ydl):
    """Extractors decide whether to *request* impersonation at all by first asking yt-dlp
    "is impersonation available in general" (YoutubeDL._impersonate_target_available, called with
    no host/URL in scope — see instagram.py's own _can_impersonate property, which every one of
    its impersonated requests is gated behind, and _request_webpage's own _parse_impersonate_targets
    call, which every "impersonate=True" extractor kwarg funnels through, Reddit's included). That
    check just asks every registered ImpersonateRequestHandler "can you EVER serve a chrome
    target", with no awareness that TlsClientRH's answer to that is only true for reddit.com
    (enforced separately, in _validate, which this check never calls). Reproduced live on a
    non-arm64 device (no curl_cffi, so before TlsClientRH existed, nothing answered "yes" and
    Instagram correctly skipped impersonation): once TlsClientRH is registered, the generic check
    now answers "yes" everywhere TlsClientRH's .so is bundled (every ABI), so Instagram believes
    impersonation is available, requests it for its instagram.com calls, TlsClientRH._validate
    then rejects those on the real dispatch (wrong host), no other handler exists to pick up the
    slack on non-arm64, and the request fails with yt-dlp's own "Impersonate target ... is not
    available" error.

    Excluding TlsClientRH from the availability check itself (the first thing tried here) is
    wrong, not just narrow: Reddit's own impersonate=True call goes through this exact same
    check, and on the very ABIs TlsClientRH exists to support (armeabi-v7a/x86_64, no curl_cffi),
    excluding it would make Reddit's own request believe impersonation isn't available either —
    reintroducing the WAF block TlsClientRH was built to fix, on the ABIs that need it most.

    Fixed one layer down instead: let the availability check keep answering honestly (TlsClientRH
    really can impersonate chrome, just not for arbitrary hosts), and catch the resulting failure
    only when it actually happens — at dispatch, when TlsClientRH._validate rejects the concrete
    non-reddit URL and no other handler picks it up. That surfaces as YoutubeDL.urlopen's own
    "Impersonate target ... is not available" RequestError; caught here and silently retried once
    with the impersonate extension stripped, so a non-reddit request degrades to a plain
    unimpersonated one — exactly the graceful behavior every extractor already falls back to when
    _parse_impersonate_targets finds nothing available up front (see extractor/common.py's
    _request_webpage) — rather than hard-failing the whole download."""
    _orig_urlopen = ydl.urlopen

    def _urlopen_with_impersonate_fallback(req):
        try:
            return _orig_urlopen(req)
        except RequestError as e:
            if "requires browser impersonation" not in str(e):
                raise
            request = Request(req) if isinstance(req, str) else req
            if not request.extensions.get("impersonate"):
                raise
            fallback_request = request.copy()
            fallback_request.extensions.pop("impersonate", None)
            return _orig_urlopen(fallback_request)

    ydl.urlopen = _urlopen_with_impersonate_fallback

_EXTERNAL_DOWNLOADER_PROGRESS_PATCHED = False

def _patch_external_downloader_progress():
    """aria2c (see the aria2_path branch above) is invoked through yt-dlp's own ExternalFD, which
    shells the whole transfer out to one blocking subprocess call and only calls _hook_progress
    once, after that subprocess has already exited (see downloader/external.py's real_download:
    a single status='finished' dict built from the completed file's own size on disk — no
    status='downloading' event is ever fired while the subprocess is still running). This app's
    own progress_hook (above) only knows how to turn "downloading" events into the [size]/
    [progress] lines DownloadWorker.kt parses for the live progress bar/byte count — so with
    aria2c on, those lines never appear at all, even though the file is visibly growing on disk
    the whole time. (DownloadWorker.kt's speed figure keeps working regardless, since it's
    computed separately, once, from total elapsed time when the finished file callback lands —
    which is what made this look like only speed survived rather than everything breaking.)

    Patched by wrapping ExternalFD.real_download with a background thread that polls the same
    tmpfilename real_download itself already writes to (aria2c has `--file-allocation=none`, so
    its output file grows incrementally in place rather than being preallocated), feeding each
    growth into self._hook_progress as a synthetic "downloading" event — same shape yt-dlp's own
    native downloader produces, so progress_hook above (and everything downstream of it) can't
    tell the difference. Applied once per process (this script is invoked fresh per download, but
    the guard costs nothing and avoids double-wrapping if that ever changes)."""
    global _EXTERNAL_DOWNLOADER_PROGRESS_PATCHED
    if _EXTERNAL_DOWNLOADER_PROGRESS_PATCHED:
        return
    _EXTERNAL_DOWNLOADER_PROGRESS_PATCHED = True

    import ssl
    import threading
    import urllib.request
    from yt_dlp.downloader.external import ExternalFD

    # Same cacert.pem already bundled for aria2c's own --ca-certificate (see the aria2_path
    # branch above) — this embedded Python build has no system CA trust store wired into its
    # default SSL context (confirmed live: a plain urllib.request.urlopen() call here failed
    # every single time with "unable to get local issuer certificate", the exact same class of
    # error aria2c's own GnuTLS stack hit before it got this same file), so a bare urlopen() call
    # needs an explicit context pointed at it just like aria2c needed an explicit flag.
    _probe_ssl_context = None
    _cacert_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cacert.pem")
    if os.path.exists(_cacert_path):
        try:
            _probe_ssl_context = ssl.create_default_context(cafile=_cacert_path)
        except Exception:
            _probe_ssl_context = None

    def _probe_total_bytes(info_dict):
        # info_dict["filesize"/"filesize_approx"] is the extractor's own upfront estimate — often
        # simply absent for a DASH manifest that doesn't advertise size (reproduced live: an
        # Instagram reel's video format had neither). The native (non-aria2c) downloader never
        # needed this fallback because it makes the real HTTP request itself and reads Content-
        # Length straight off that live response; aria2c makes that same request but, being an
        # opaque external subprocess yt-dlp only waits on, never reports what it saw back. A quick
        # HEAD (falling back to a 1-byte ranged GET for a CDN that rejects HEAD) recovers the same
        # number directly, best-effort — total size just stays unknown (matching pre-fix behavior,
        # not a regression) if even that fails.
        url = info_dict.get("url")
        if not url:
            return None
        headers = dict(info_dict.get("http_headers") or {})
        for method, extra_headers in (("HEAD", {}), ("GET", {"Range": "bytes=0-0"})):
            try:
                req = urllib.request.Request(url, method=method, headers={**headers, **extra_headers})
                with urllib.request.urlopen(req, timeout=10, context=_probe_ssl_context) as resp:
                    if method == "GET":
                        content_range = resp.headers.get("Content-Range")
                        if content_range and "/" in content_range:
                            total = content_range.rsplit("/", 1)[-1]
                            return int(total) if total.isdigit() else None
                    length = resp.headers.get("Content-Length")
                    if length and length.isdigit():
                        return int(length)
            except Exception:
                continue
        return None

    _orig_real_download = ExternalFD.real_download

    def _real_download_with_polling(self, filename, info_dict):
        tmpfilename = self.temp_name(filename)
        total_bytes = (info_dict.get("filesize") or info_dict.get("filesize_approx")
                       or _probe_total_bytes(info_dict))
        stop_event = threading.Event()

        def _poll():
            # -x16/-s16 (see the aria2c command line built above) is a genuinely segmented
            # download: up to 16 connections each write to their own byte offset in the same
            # output file concurrently, which needs the file seekable out to its full logical
            # length from early on — so os.path.getsize() (the highest offset anything has ever
            # written to, i.e. a sparse file's logical length) jumps toward the full size the
            # moment the *last* segment (writing near the end of the file) starts, however little
            # data any segment has actually transferred yet. Reproduced live: progress showing
            # ~100% within a second of a download starting. os.stat().st_blocks counts actual
            # disk blocks allocated — sparse holes that were seeked-over but never written don't
            # consume blocks — so multiplying by the POSIX-standard 512-byte block size recovers
            # real bytes-on-disk regardless of which segment wrote them or in what order.
            last_size = 0
            while not stop_event.wait(0.5):
                try:
                    size = os.stat(tmpfilename).st_blocks * 512
                except OSError:
                    continue
                if total_bytes:
                    size = min(size, total_bytes)
                if size > last_size:
                    last_size = size
                    self._hook_progress({
                        "status": "downloading",
                        "downloaded_bytes": size,
                        "total_bytes": total_bytes,
                        "filename": filename,
                        "elapsed": 0,
                    }, info_dict)

        poll_thread = threading.Thread(target=_poll, daemon=True)
        poll_thread.start()
        try:
            return _orig_real_download(self, filename, info_dict)
        finally:
            stop_event.set()
            poll_thread.join(timeout=2)

    ExternalFD.real_download = _real_download_with_polling

_EMBED_THUMBNAIL_PATCHED = False

def _patch_embed_thumbnail_fallback():
    """EmbedThumbnailPP only accepts jpg/png (or any format at all, for mkv/mka) straight from
    the extractor; anything else — YouTube's own thumbnails are commonly .webp — gets run through
    FFmpegThumbnailsConvertorPP.convert_thumbnail(..., 'png') first (see its own call site in
    embedthumbnail.py's run()). That conversion needs both a real PNG encoder and yt-dlp's
    "image2" output muxer, and this bundled ffmpeg has neither: --enable-muxer above never
    includes image2/png (only 'mp4,ipod,matroska,webm,adts,wav,mp3' — see the aria2_path branch's
    own sibling comment on this same build's --disable-encoders for the parallel audio-side
    story), so the conversion always fails. Reproduced live: enabling "Embed thumbnail" for an
    audio-only YouTube Music download failed the *entire* download with "Postprocessing: Error
    splitting the argument list: Option not found" over a cosmetic, non-essential step — the
    actual audio had already downloaded and extracted successfully by that point.

    Fixed two ways, layered:

    1. For YouTube specifically, avoid ever needing the conversion at all. Its own extractor
       (extractor/youtube/_video.py) generates a plain .jpg sibling for *every* .webp thumbnail
       candidate at the same name/resolution — `for name in thumbnail_names for ext in ('webp',
       'jpg')` — and ranks the webp one only one preference point higher, which is why it's
       normally the one picked and downloaded. Swapping "/vi_webp/"->"/vi/" and ".webp"->".jpg"
       in the chosen thumbnail's URL isn't a guessed pattern — it's the exact same URL yt-dlp's
       own extractor would have generated had it ranked jpg first. Fetching that instead lets
       mutagen (embedthumbnail.py's own primary, ffmpeg-free path for m4a/mp4) embed a real
       thumbnail directly, no conversion needed.

    2. Everywhere else (a site whose thumbnail is some other non-jpg/png format, or the jpg
       fetch above fails for any reason), fall back to catching the failure and degrading to "no
       thumbnail embedded" instead of failing the whole download — the same "a real capability
       gap in this stripped ffmpeg build shouldn't sink an otherwise-successful transfer"
       philosophy as the audio-codec and impersonate-fallback fixes elsewhere in this file. A
       genuinely unrelated embedding failure (corrupt thumbnail, disk full, ...) still surfaces
       via the debug callback line, just no longer fatally."""
    global _EMBED_THUMBNAIL_PATCHED
    if _EMBED_THUMBNAIL_PATCHED:
        return
    _EMBED_THUMBNAIL_PATCHED = True

    import ssl
    import urllib.request
    from yt_dlp.postprocessor.embedthumbnail import EmbedThumbnailPP

    # Same reasoning as the aria2c size-probe's own SSL context (see _patch_external_downloader_
    # progress): this embedded Python's default SSL context has no CA trust store wired in.
    _cacert_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cacert.pem")
    _ssl_context = None
    if os.path.exists(_cacert_path):
        try:
            _ssl_context = ssl.create_default_context(cafile=_cacert_path)
        except Exception:
            _ssl_context = None

    def _prefer_jpg_thumbnail(info):
        thumbnails = info.get("thumbnails") or []
        idx = next((-i for i, t in enumerate(thumbnails[::-1], 1) if t.get("filepath")), None)
        if idx is None:
            return
        entry = thumbnails[idx]
        filepath = entry.get("filepath") or ""
        if os.path.splitext(filepath)[1].lower() in (".jpg", ".jpeg", ".png"):
            return
        url = entry.get("url") or ""
        jpg_url = url.replace("/vi_webp/", "/vi/").replace(".webp", ".jpg")
        if jpg_url == url:
            return
        jpg_path = os.path.splitext(filepath)[0] + ".jpg"
        try:
            req = urllib.request.Request(jpg_url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=15, context=_ssl_context) as resp:
                data = resp.read()
            if not data:
                return
            with open(jpg_path, "wb") as f:
                f.write(data)
        except Exception:
            return
        entry["filepath"] = jpg_path
        entry["url"] = jpg_url

    _orig_run = EmbedThumbnailPP.run

    def _run_with_fallback(self, info):
        _prefer_jpg_thumbnail(info)
        try:
            return _orig_run(self, info)
        except Exception as e:
            self.to_screen(f"Not embedding thumbnail; {e}")
            return [], info

    EmbedThumbnailPP.run = _run_with_fallback

_FFMPEG_AUDIO_COPY_PATCHED = False

def _patch_ffmpeg_audio_copy_for_stripped_build():
    """Two separate incompatibilities between FFmpegExtractAudioPP's "best"-codec/copy-only path
    (see that fix's own doc comment just above) and this app's own stripped ffmpeg build
    (--disable-encoders/--disable-decoders, and a narrow --enable-muxer list of just
    'mp4,ipod,matroska,webm,adts,wav,mp3' — confirmed directly from this build's own `-version`
    configure string), both reproduced live extracting audio from a source whose native codec is
    opus (a common YouTube bestaudio format, not just aac/m4a):

    1. yt_dlp's own ACODECS table (postprocessor/ffmpeg.py) maps a copy-only opus/vorbis
       extraction to a bare ".opus"/".ogg" extension — but that build's --enable-muxer list has
       no ogg/opus muxer at all, so writing to that extension can never work here, copy-only or
       not. WebM *is* in the muxer list and is itself a fully valid, standard container for
       Opus/Vorbis audio, so remapping just the extension keeps this a pure remux (still
       "-acodec copy", no real encode) into a container this build can actually write.

    2. FFmpegPostProcessor.real_run_ffmpeg (same file) separately, unconditionally appends
       "-movflags", "+faststart" to every output file's own args (see its own make_args() nested
       helper) — a flag only the MP4 muxer understands. A full desktop ffmpeg build just warns
       and ignores an option a muxer doesn't recognize; this stripped build turns that into a
       hard "Invalid argument" failure instead — so even after the extension fix above, a webm
       output still needs this flag stripped to actually succeed.

    Fixed with two small, narrowly-targeted patches: remapping ACODECS's own opus/vorbis
    extensions to "webm" (keeping their real encoder/opts entries untouched — irrelevant anyway
    once the "best"/copy-only branch overrides acodec to "copy" regardless), and wrapping
    Popen.run (yt_dlp.utils.Popen, the one point every ffmpeg invocation funnels through) to
    strip a literal "-movflags"/"+faststart" pair whenever the command's own output path isn't an
    mp4-family extension the flag actually applies to. Neither touches a real mp4/m4a output,
    where both the original extension and the flag are correct and unaffected."""
    global _FFMPEG_AUDIO_COPY_PATCHED
    if _FFMPEG_AUDIO_COPY_PATCHED:
        return
    _FFMPEG_AUDIO_COPY_PATCHED = True

    from yt_dlp.postprocessor.ffmpeg import ACODECS
    from yt_dlp.utils import Popen

    for _codec in ("opus", "vorbis"):
        _ext, _encoder, _opts = ACODECS[_codec]
        ACODECS[_codec] = ("webm", _encoder, _opts)

    _orig_run = Popen.run.__func__

    def _run_without_bad_movflags(cls, *args, **kwargs):
        cmd = args[0] if args else None
        if isinstance(cmd, list) and "-movflags" in cmd:
            idx = cmd.index("-movflags")
            if idx + 1 < len(cmd) and cmd[idx + 1] == "+faststart":
                out_path = str(cmd[-1]) if cmd else ""
                if not out_path.lower().endswith((".mp4", ".m4a", ".m4v", ".mov")):
                    cmd = cmd[:idx] + cmd[idx + 2:]
                    args = (cmd, *args[1:])
        return _orig_run(cls, *args, **kwargs)

    Popen.run = classmethod(_run_without_bad_movflags)

def _parse_size(size_str):
    """Converts gallery-dl-style size strings ("500k", "2M", "1G") into a plain byte-count
    integer. Shared by the Speed limit field (bytes-per-second, for yt-dlp's "ratelimit" opt) and
    the Max file size field (bytes, for its "max_filesize" opt) — both engines' own config/CLI
    layers accept these suffixed strings directly, but yt-dlp's Python API (unlike its CLI) wants
    the value pre-parsed into an int before it ever reaches YoutubeDL's constructor dict."""
    if not size_str:
        return None
    match = re.match(r"^\s*([\d.]+)\s*([kKmMgG]?)\s*$", size_str)
    if not match:
        return None
    value = float(match.group(1))
    unit = match.group(2).lower()
    multiplier = {"": 1, "k": 1024, "m": 1024 * 1024, "g": 1024 * 1024 * 1024}[unit]
    return int(value * multiplier)

def _parse_timestamp(value):
    """Converts a "H:MM:SS"/"MM:SS"/"SS" clip-trim timestamp (from the Home screen's Start/End
    fields) into seconds. Returns None for a blank/unparseable value."""
    value = (value or "").strip()
    if not value:
        return None
    try:
        # A leading/trailing empty segment (e.g. ":10" or "10:") means an omitted hour/minute,
        # not a literal 0 duration to add — treated as 0 rather than fed to float(""), which
        # would otherwise raise and reject an otherwise-valid, very typeable timestamp.
        parts = [float(p) if p else 0.0 for p in value.split(":")]
    except ValueError:
        return None
    seconds = 0.0
    for part in parts:
        seconds = seconds * 60 + part
    return seconds

def _parse_clip_range(clip_range):
    """Converts "start-end" range string(s) — either side of a range optionally blank, meaning
    "from the beginning"/"to the end" — into a list of (start, end) second tuples, or None if none
    of them parse to anything. Several comma-separated ranges ("00:10-00:20,01:00-01:30") are
    accepted, matching the preview sheet's multi-segment trim — see _LocalTrimPP, which cuts and
    concatenates every segment into the one final output file.

    Deliberately NOT a yt-dlp download_ranges callable (what this fed directly into ydl_opts
    before) — that requires the FFmpegFD downloader, which shells ffmpeg out to the source's
    remote HTTPS URL directly so it can seek-and-trim over the network. This bundled ffmpeg build
    has no network protocol support at all (its own `ffmpeg -version` configure string:
    `--disable-network --enable-protocol='file,pipe'`), so that path always failed outright with a
    bare "ffmpeg exited with code 8" — reproduced live against a real Instagram Reel — regardless
    of force_keyframes_at_cuts or any other yt-dlp-side option. _LocalTrimPP instead trims the
    already-fully-downloaded local file after the fact, which only needs the file protocol this
    build does have."""
    if not clip_range:
        return None
    ranges = []
    for part in clip_range.split(","):
        if "-" not in part:
            continue
        start_str, _, end_str = part.partition("-")
        start = _parse_timestamp(start_str)
        end = _parse_timestamp(end_str)
        if start is None and end is None:
            continue
        ranges.append((start or 0, end if end is not None else float("inf")))
    return ranges or None

def _parse_extractor_args(raw):
    """Converts yt-dlp CLI-syntax --extractor-args strings ("youtube:player_client=android,web")
    into the nested dict yt-dlp's own extractor_args opt expects
    ({"youtube": {"player_client": ["android", "web"]}}) — mirrors the real CLI's own parser
    (options.py's _extractor_arg_parser) closely enough to accept the same syntax users would
    find documented for yt-dlp itself. Multiple "IE_KEY:ARGS" blocks can be whitespace-separated,
    standing in for --extractor-args being repeatable on the real CLI for different extractors."""
    if not raw:
        return None
    result = {}
    for block in raw.split():
        if ":" not in block:
            continue
        ie_key, _, args_str = block.partition(":")
        ie_key = ie_key.strip().lower()
        if not ie_key:
            continue
        args = {}
        for arg in args_str.split(";"):
            if "=" not in arg:
                continue
            key, _, vals = arg.partition("=")
            key = key.strip().lower().replace("-", "_")
            values = [v.replace("\\,", ",").strip() for v in re.split(r"(?<!\\),", vals)]
            args[key] = values
        if args:
            result[ie_key] = args
    return result or None

class _LocalTrimPP(FFmpegPostProcessor):
    """Trims the just-produced file to `ranges` (a list of (start, end) second tuples — see
    _parse_clip_range) via a plain local, file-to-file ffmpeg subprocess, replacing it in place at
    the same path once done. Registered as the *last* postprocessor (see download()'s own
    ydl.add_post_processor call) so it runs after merging and any thumbnail/metadata/subtitle
    embedding already have — trimming last keeps this one operation, on whatever the real final
    file already is, rather than needing its own opinion about which of those other steps to run
    before/after.

    Stream-copy only ("-c copy", `stream_copy_opts()`) — this bundled ffmpeg build has no encoders
    at all (see _parse_clip_range's own doc comment), so a cut lands on the nearest existing
    keyframe rather than the exact requested timestamp. Multiple ranges are each cut to their own
    temp file, then concatenated via ffmpeg's own concat demuxer — also stream-copy-safe, since
    every segment shares the same source codecs."""

    def __init__(self, downloader, ranges):
        super().__init__(downloader)
        self._ranges = ranges

    @classmethod
    def pp_key(cls):
        # PostProcessor.__init__ sets self.PP_NAME = self.pp_key() *after* any class-level
        # PP_NAME is defined, silently overwriting it — and the base implementation derives the
        # key from cls.__name__[:-2] (built for the "FfmpegXxxPP" naming convention), which for
        # this class's actual name ("_LocalTrimPP") yields "_LocalTrim" (leading underscore
        # intact), not "LocalTrim". postprocessor_hook (below) reports d["postprocessor"] from
        # this same pp_key(), and custom_pp_keys' "LocalTrim" entry (see download()) never
        # matched that leading-underscore variant — is_final was always False, so the finished
        # file's path never reached `callback`, DownloadWorker never saw a real output file, and
        # the download was reported as errored ("No downloadable content found") even though the
        # trim had actually succeeded on disk. Reproduced live against the Instagram Reel trim
        # case that originally motivated this whole postprocessor. Overriding pp_key() explicitly
        # sidesteps the naming convention entirely instead of relying on this class's Python name.
        return "LocalTrim"

    def run(self, info):
        filepath = info.get("filepath")
        if not filepath or not self._ranges or not os.path.exists(filepath):
            return [], info

        base, ext = os.path.splitext(filepath)
        ext = ext.lstrip(".")
        segment_paths = []
        try:
            for i, (start, end) in enumerate(self._ranges):
                seg_path = f"{base}.trimseg{i}.{ext}"
                seek_opts = ["-ss", str(start)]
                if end != float("inf"):
                    seek_opts += ["-to", str(end)]
                # -ss/-to given here (as part of the *input*'s own opts, ahead of "-i" — see
                # real_run_ffmpeg) rather than after -i: an input-side seek is a fast, plain
                # demuxer-level skip: exactly what a stream-copy-only build can still do. An
                # output-side seek needs to decode every discarded frame first, which this build
                # can't do at all.
                self.real_run_ffmpeg(
                    [(filepath, seek_opts)],
                    [(seg_path, list(self.stream_copy_opts(ext=ext)))],
                )
                segment_paths.append(seg_path)

            if len(segment_paths) == 1:
                trimmed_path = segment_paths[0]
            else:
                concat_list_path = f"{base}.trimconcat.txt"
                with open(concat_list_path, "w", encoding="utf-8") as f:
                    for seg_path in segment_paths:
                        f.write("file '{}'\n".format(seg_path.replace("'", "'\\''")))
                trimmed_path = f"{base}.trimmed.{ext}"
                self.real_run_ffmpeg(
                    [(concat_list_path, ["-f", "concat", "-safe", "0"])],
                    [(trimmed_path, list(self.stream_copy_opts(ext=ext)))],
                )
                os.remove(concat_list_path)
                for seg_path in segment_paths:
                    os.remove(seg_path)

            # A start past the real end of the file (the Kotlin side clamps against the known
            # duration now, but an older/cached UI, a manually-typed range, or a duration that
            # simply wasn't known yet when the range was set could still send one here) makes an
            # input-side "-ss" land at EOF immediately — ffmpeg's stream-copy-only build then
            # writes a valid but empty container and still exits 0, not an error. Without this
            # check, os.replace() below would silently swap the already-fully-downloaded original
            # out for that empty file, with the run reported as a plain success. Raising here
            # instead routes it through the same except block below, which discards the segments
            # and lets the real error surface — leaving `filepath` (os.replace never runs) as the
            # untrimmed, still-complete original.
            if os.path.getsize(trimmed_path) == 0:
                raise PostProcessingError(
                    "Trim produced an empty file — the requested range is likely past the end of "
                    "the actual video"
                )

            # Same path the file already had — nothing downstream (this module's own
            # postprocessor_hook, DownloadWorker's file-move logic on the Kotlin side) needs to
            # know a trim happened at all.
            os.replace(trimmed_path, filepath)
        except Exception:
            # The untrimmed original at `filepath` is still intact at this point (os.replace above
            # never ran) — best-effort cleanup of whatever temp segments did get created, then
            # let the real error propagate through yt-dlp's own postprocessor error handling (the
            # same path "ERROR: ffmpeg exited with code 8" itself was already surfacing through).
            for seg_path in segment_paths:
                if os.path.exists(seg_path):
                    os.remove(seg_path)
            raise

        return [], info

class _FinalFilePP(PostProcessor):
    """Reports the download's real final file to `callback` once every other requested
    postprocessor (whichever combination of ExtractAudio/EmbedThumbnail/Metadata/EmbedSubtitle/
    LocalTrim custom_pp_keys ends up representing) has actually finished — added last, after
    _LocalTrimPP itself, so it's genuinely the last thing to run.

    This exists because postprocessor_hook (yt-dlp's own progress_hooks-style mechanism for
    postprocessors) can't be trusted for this: PostProcessorMetaClass.run_wrapper (postprocessor/
    common.py) snapshots info_dict *before* calling the postprocessor's own run(), then fires the
    'finished' hook event with that same pre-run snapshot — so for any postprocessor that renames
    its own output (ExtractAudio: source.mp4 -> source.m4a; the others all modify their file in
    place, which is why this went unnoticed until audio-only downloads existed), the 'finished'
    event always reports the *previous*, about-to-be-deleted filename. Reproduced live: a YouTube
    Music audio-only download reported the original .mp4 — which yt-dlp's own cleanup deletes
    immediately after producing the real .m4a — leaving DownloadWorker nothing to find on disk
    ("No downloadable content found at this link", despite the .m4a existing the whole time).

    A real PostProcessor's own run() has no such snapshot problem: by the time THIS one's run()
    is called, every prior postprocessor in the chain has already returned its own updated info
    dict (see YoutubeDL.run_pp/run_all_pps, which thread the return value from each postprocessor
    into the next) — so info['filepath'] here is always the true, currently-real final path."""

    def __init__(self, downloader, callback, override_title=None, override_artist=None, source_url=None):
        super().__init__(downloader)
        self._callback = callback
        self._override_title = override_title
        self._override_artist = override_artist
        self._source_url = source_url

    def run(self, info):
        filepath = info.get("filepath")
        if filepath:
            # Applied here — after every other postprocessor (ExtractAudio/EmbedThumbnail/
            # Metadata/...) has already finished, same reasoning as this whole class's own doc
            # comment above, and the identical ordering fix spotify_wrapper.py's own retag needed
            # this session: DownloadWorker's actualCallback moves/deletes the staging file the
            # moment it receives this callback's own path line, synchronously — retagging has to
            # happen before that line fires, not after.
            if self._override_title or self._override_artist:
                _apply_title_artist_override(filepath, self._override_title, self._override_artist)
            # music.youtube.com only (not plain youtube.com/youtu.be) — a real song is a
            # near-guarantee there, unlike a random YouTube video, where an iTunes title+artist
            # search would just as often return a wrong or nonexistent match. See
            # _apply_cover_art_override's own doc comment for why this is worth doing at all:
            # YouTube's own thumbnail is always a 16:9 video frame, never a square cover.
            if self._source_url and "music.youtube.com" in self._source_url:
                title = self._override_title or info.get("track") or info.get("title")
                artist = self._override_artist or info.get("artist") or info.get("uploader") or info.get("channel")
                if title and artist:
                    cover_url = _itunes_cover_art_url(title, artist)
                    if cover_url:
                        _apply_cover_art_override(filepath, cover_url)
            self._callback(os.path.abspath(filepath))
        return [], info


def _apply_title_artist_override(filepath, title, artist):
    """The download preview sheet's own editable title/artist (SongPreviewCard) — overrides
    whatever the source itself reported, the same way spotify_wrapper.py's own
    _retag_with_spotify_metadata overrides a matched YouTube video's tags with Spotify's real
    ones. Text tags only (no cover art — EmbedThumbnail, earlier in the postprocessor chain,
    already handled that); silently no-ops on a format mutagen's "easy" interface can't open,
    same tolerance every other optional metadata step in this app's audio pipeline already has."""
    try:
        import mutagen
        audio = mutagen.File(filepath, easy=True)
        if audio is not None:
            if title:
                audio["title"] = title
            if artist:
                audio["artist"] = artist
            audio.save()
    except Exception:
        pass


def _itunes_cover_art_url(title, artist):
    """Best-effort square album-art URL for a title+artist pair with no known track id — used
    for plain YouTube Music downloads (see _FinalFilePP's own call site below), which only ever
    carry YouTube's own non-square 16:9 video-frame thumbnail. iTunes' public search API needs
    no signup/API key at all — tried instead of Spotify's own search, which needs either a
    registered developer app's Client Credentials token or an anonymous session token scraped
    from open.spotify.com/search (confirmed live that the latter no longer works: Spotify has
    evidently moved token issuance to a client-side JS call a plain HTTP fetch can't observe).
    "artworkUrl100"'s "100x100" segment is a well-documented, swappable size hint — requesting
    600x600 back gets a real high-res image instead of the tiny default thumbnail. Returns None
    on no match or any failure, never raises — a missing cover-art upgrade should never fail an
    otherwise-successful download."""
    try:
        import urllib.parse
        import urllib.request
        import ssl
        cacert_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cacert.pem")
        ssl_context = ssl.create_default_context(cafile=cacert_path) if os.path.exists(cacert_path) else None
        query = urllib.parse.quote(f"{artist} {title}")
        req = urllib.request.Request(
            f"https://itunes.apple.com/search?term={query}&media=music&entity=song&limit=1",
            headers={"User-Agent": "Mozilla/5.0"},
        )
        with urllib.request.urlopen(req, timeout=10, context=ssl_context) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
        results = data.get("results") or []
        if not results:
            return None
        artwork = results[0].get("artworkUrl100")
        return artwork.replace("100x100", "600x600") if artwork else None
    except Exception:
        return None


def _apply_cover_art_override(filepath, image_url):
    """Square cover art from _itunes_cover_art_url's own lookup (see _FinalFilePP's own call
    site above), overriding whatever EmbedThumbnailPP already embedded from YouTube's own 16:9
    video-frame thumbnail — this app's stripped ffmpeg build has no
    image encoder/muxer at all (see _patch_embed_thumbnail_fallback's own doc comment on that
    same limitation) and the bundled Python has no image library, so cropping that thumbnail
    locally isn't possible; fetching an already-square image instead sidesteps the problem
    entirely. mutagen-only, mp4/m4a only (the container every audio-only YouTube Music download
    actually uses) — mirrors yt-dlp's own EmbedThumbnailPP mutagen branch for that container.
    Silently no-ops on any failure (bad URL, wrong container, ...): a missing cover-art upgrade
    should never turn an otherwise-successful download into a failure."""
    try:
        import ssl
        import urllib.request
        cacert_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cacert.pem")
        ssl_context = None
        if os.path.exists(cacert_path):
            ssl_context = ssl.create_default_context(cafile=cacert_path)
        req = urllib.request.Request(image_url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=10, context=ssl_context) as resp:
            image_data = resp.read()
        if not image_data:
            return
        from mutagen.mp4 import MP4, MP4Cover
        audio = MP4(filepath)
        audio.tags["covr"] = [MP4Cover(data=image_data, imageformat=MP4Cover.FORMAT_JPEG)]
        audio.save()
    except Exception:
        pass


class _Logger:
    """Routes yt-dlp's own log messages through the same per-line callback DownloadWorker
    already uses for gallery-dl, instead of yt-dlp's default of printing to stdout — this module
    never touches sys.stdout/sys.argv at all, unlike gallery_dl_wrapper.py, since YoutubeDL takes
    its configuration as a constructor dict and isn't a CLI-only entry point."""
    def __init__(self, callback):
        self.callback = callback

    def debug(self, msg):
        # Only ever reaches here when the caller opted into ydl_opts["verbose"] — write_debug()
        # itself is gated behind that flag before it calls logger.debug() at all (see YoutubeDL's
        # own write_debug()), so this doesn't add any output/overhead to a normal (non-verbose) run.
        if self.callback:
            self.callback(f"[debug] {msg}")

    def warning(self, msg):
        if self.callback:
            self.callback(f"[warning] {msg}")

    def error(self, msg):
        if self.callback:
            self.callback(f"[error] {msg}")

def probe(url):
    # "1" if yt-dlp has a real (non-generic) extractor for url, "0" otherwise (incl. on any
    # error). GenericIE.suitable() always returns True (it's yt-dlp's own catch-all fallback
    # used only when nothing else matches) so it's explicitly excluded here — including it
    # would make every URL "match" and defeat the whole point of this probe. suitable()
    # wraps _match_valid_url(), pure regex, no network I/O.
    try:
        import yt_dlp.extractor
        for ie in yt_dlp.extractor.gen_extractor_classes():
            if ie.__name__ == "GenericIE":
                continue
            try:
                if ie.suitable(url):
                    return "1"
            except Exception:
                continue
        return "0"
    except Exception:
        return "0"

def download(url, download_dir, cookies_path=None, callback=None, filename_format=None,
             extra_args=None, archive_path=None, limit_rate=None, format_selector=None,
             should_cancel=None, js_runtime_path=None, ffmpeg_path=None,
             audio_only=False, download_subtitles=False, subtitle_langs=None,
             embed_thumbnail=False, embed_metadata=False, no_playlist=True,
             resolution_cap=None, output_format=None, retries=None, playlist_items=None, max_filesize=None,
             write_info_files=False, clip_range=None, proxy_url=None, live_from_start=False,
             extractor_args=None, save_thumbnail=False, force_ipv4=False, concurrent_fragments=None,
             no_check_certificates=False, sleep_interval_seconds=None, custom_headers=None,
             format_sort_extra=None, verbose=False, embed_chapters=False, save_subtitle_files=False,
             restrict_filenames=True, trim_filenames=True, fragment_retries=None,
             socket_timeout_seconds=None, buffer_size_kb=None, youtube_client_rotation=False,
             impersonate=False, aria2_path=None, aria2_lib_dir=None, ffmpeg_lib_dir=None,
             tls_client_path=None, override_title=None, override_artist=None):
    """Downloads a video via yt-dlp's embeddable YoutubeDL API — deliberately not yt_dlp.main(),
    which (like gallery-dl's CLI entry point) reads sys.argv, a process-global that two
    concurrent calls would race on. YoutubeDL instead takes all configuration as a constructor
    dict and reports progress through callbacks, so it's self-contained per call. Every finished
    file's absolute path is sent to `callback`, matching how DownloadWorker's actualCallback
    already expects one path per line from gallery_dl_wrapper.download()."""
    # See TlsClientRH's own comment (near _REDDIT_HOST_RE, above) for why this needs to be a real
    # registered RequestHandler rather than a one-off pre-fetch, and why a module global rather
    # than threading this through YoutubeDL's own handler-construction kwargs.
    global _TLS_CLIENT_PATH
    _TLS_CLIENT_PATH = tls_client_path

    # DownloadWorker throttles nothing on its own end, so a raw per-chunk progress_hook (which
    # fires dozens of times a second) would otherwise flood the DB with writes — this closure
    # state caps real updates to twice a second. QueueScreen's own progress bar eases toward each
    # new value over 450ms (see its animateFloatAsState) rather than snapping to it; at the
    # original once-a-second cadence the ease finished with ~550ms left before the next update,
    # visible as a brief pause-then-jump. Twice a second keeps a new target arriving before the
    # current ease finishes, so the animation reads as continuous motion instead.
    last_progress_emit = [0.0]
    last_emitted_bytes = [0]
    reported_size = [False]
    reported_title = [False]
    reported_thumbnail = [False]

    # Every one of these postprocessors further transforms the file yt-dlp just finished
    # downloading (ExtractAudio changes its extension and deletes the original; the others embed
    # into it in place) — same idea as the merge-fragment filtering below: reporting the
    # pre-postprocessing file to `callback` would let DownloadWorker move or delete it out from
    # under ffmpeg while these are still running. When any are requested, the raw "finished" event
    # from progress_hook is suppressed entirely (not just fragment-shaped filenames) and the
    # *last* one's own completion is reported instead — this list (in the same order they're
    # appended to ydl_opts below) is what tells postprocessor_hook which one that is. Only
    # meaningful with ffmpeg bundled, since every one of these postprocessors requires it.
    # Parsed early (not down where it's actually used, alongside the other ydl_opts) so its
    # presence can feed custom_pp_keys below, same as every other postprocessor-triggering flag.
    clip_ranges = _parse_clip_range(clip_range)

    custom_pp_keys = []
    if ffmpeg_path:
        if audio_only:
            custom_pp_keys.append("ExtractAudio")
        # Metadata *before* EmbedThumbnail — FFmpegMetadataPP's own ffmpeg command includes "-vn"
        # (no video), and mutagen exposes an m4a's embedded cover art back to ffmpeg as an
        # attached-pic video stream when re-reading the file; running Metadata after Thumbnail
        # silently stripped the cover art that step had just embedded (reproduced live: an audio
        # download with both "Embed Metadata" and a thumbnail landed with real tags but no art at
        # all). Embedding art last means nothing downstream ever remuxes the file again to lose it.
        if embed_metadata or embed_chapters or audio_only:
            custom_pp_keys.append("Metadata")
        # Cover art is worth embedding into an audio file regardless of the general "Embed
        # thumbnail" setting (which is really about *video* files) — an audio-only download with
        # no cover art at all looks broken in most music players, whereas video thumbnails are
        # much more optional (the video frame itself is the "thumbnail"). audio_only's own
        # ExtractAudio entry is appended just above, so this stays in the same relative order —
        # EmbedThumbnail needs to run *after* ExtractAudio so info['ext'] is already 'm4a' by the
        # time it runs, which is what lets it use mutagen's ffmpeg-free embed path at all.
        if embed_thumbnail or audio_only:
            custom_pp_keys.append("EmbedThumbnail")
            _patch_embed_thumbnail_fallback()
        if download_subtitles and not audio_only:
            custom_pp_keys.append("EmbedSubtitle")
        if clip_ranges:
            # _LocalTrimPP (registered further down, once the YoutubeDL instance exists to
            # construct it with) always runs last regardless of what else is requested — see its
            # own doc comment — so it's always the last key appended here too, matching whichever
            # combination of the above actually applies this run.
            custom_pp_keys.append("LocalTrim")

    def progress_hook(d):
        if should_cancel is not None and should_cancel():
            raise _Cancelled()
        status = d.get("status")
        if status == "downloading" and callback:
            info = d.get("info_dict") or {}
            if not reported_title[0]:
                # Extraction has already happened by the time any "downloading" event fires, so
                # the real poster/caption are available immediately — sent once, this early,
                # rather than waiting for a file to actually finish (DownloadWorker's own
                # derivation from the saved filename), so the queue card shows what's actually
                # downloading well before the first byte lands, not just a "Downloading from X"
                # placeholder for the whole transfer.
                poster = info.get("uploader") or info.get("channel") or info.get("creator")
                caption = info.get("title") or info.get("description")
                if poster or caption:
                    reported_title[0] = True
                    title = f"{poster} - {caption}" if poster and caption else (poster or caption)
                    callback(f"[title] {title[:200]}")
                # Same idea, same timing — the extractor already picked a thumbnail URL by now.
                # Sent from this same one-shot block (guarded by reported_title, not its own flag)
                # since a title-less video is rare enough not to bother re-checking every tick.
                if not reported_thumbnail[0]:
                    thumbnail = info.get("thumbnail")
                    if thumbnail:
                        reported_thumbnail[0] = True
                        callback(f"[thumbnail] {thumbnail}")
                # Real track metadata, distinct from the uploader/caption-based [title] line
                # above — "artist" is only ever populated by extractors that genuinely carry
                # music metadata (YouTube Music releases); a plain YouTube video's info_dict
                # simply has no "artist" key, so this falls back to the same poster (uploader/
                # channel/creator) [title] already uses rather than ever sending "Unknown" —
                # DownloadWorker's own AUDIO_EXTENSIONS check is what actually decides whether
                # this download counts as audio at all, not whether this metadata happens to
                # exist.
                artist = info.get("artist") or poster
                album = info.get("album")
                track = info.get("track")
                if artist:
                    callback(f"[artist] {artist[:200]}")
                if album:
                    callback(f"[album] {album[:200]}")
                if track:
                    callback(f"[track] {track[:200]}")
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total and not reported_size[0]:
                # Sent once, as soon as it's known — before any bytes have actually moved — so the
                # UI can show a real size and a byte-accurate progress fraction immediately instead
                # of only once the download finishes.
                reported_size[0] = True
                callback(f"[size] {total}")
            now = time.monotonic()
            if now - last_progress_emit[0] >= 0.5:
                downloaded = d.get("downloaded_bytes") or 0
                # Not d.get("speed") — yt-dlp computes that as a cumulative average over the whole
                # transfer so far (total bytes ÷ total elapsed time since this file started), not a
                # recent/instantaneous rate, so it drifts more and more sluggishly the longer a
                # download runs instead of reflecting what's actually happening right now. The
                # bytes moved since the *last* emit, divided by the actual time since then, is a
                # real short-window rate — on the first sample (nothing to diff against yet) it
                # falls back to yt-dlp's own value just this once. Also falls back whenever
                # downloaded_bytes has gone *backwards* since the last sample: a merge (bestvideo
                # +bestaudio) fetches two separate sub-files in the same download() call, each with
                # its own downloaded_bytes counting from 0 — without this check, the video track
                # finishing and the audio track starting produces a large bogus negative delta.
                elapsed = now - last_progress_emit[0]
                if last_progress_emit[0] > 0 and elapsed > 0 and downloaded >= last_emitted_bytes[0]:
                    speed = (downloaded - last_emitted_bytes[0]) / elapsed
                else:
                    speed = d.get("speed") or 0
                last_progress_emit[0] = now
                last_emitted_bytes[0] = downloaded
                callback(f"[progress] downloaded={downloaded} speed={speed}")
            return
        if status != "finished" or not callback:
            return
        if ffmpeg_path:
            # Whenever ffmpeg is available, *some* postprocessor always runs after the raw
            # download finishes — at minimum MetadataParser (always registered below), possibly
            # also a Merger (bestvideo+bestaudio), ExtractAudio, EmbedThumbnail, Metadata,
            # EmbedSubtitle and/or LocalTrim depending on what was requested. _FinalFilePP (always
            # added last — see its own doc comment for exactly why postprocessor_hook can't do
            # this reliably) reports the real final file once that whole chain has actually
            # finished; reporting the bare downloaded file here too would either be a harmless
            # duplicate (nothing renamed it) or, worse, a stale path a later postprocessor is
            # about to delete (exactly what ExtractAudio does to produce its own differently-named
            # output) — so this is always left to _FinalFilePP instead whenever ffmpeg exists.
            return
        filename = d.get("filename") or (d.get("info_dict") or {}).get("filepath")
        if not filename:
            return
        if _FRAGMENT_SUFFIX_RE.search(filename):
            # This is a pre-merge fragment, not the real final file — but this branch only runs
            # at all when ffmpeg_path is falsy, meaning no Merger can exist to consume it either;
            # kept as a defensive no-op rather than reporting a fragment DownloadWorker can't use.
            return
        callback(os.path.abspath(filename))

    # "bestvideo+bestaudio" (yt-dlp's own default) needs ffmpeg to mux the separately-fetched
    # streams together — without the bundled ffmpeg binary (see FfmpegRuntime.kt) that would abort
    # every download with "ffmpeg is not installed", so it's only used when ffmpeg_path is set.
    # "best/18" instead picks the best already-muxed single format, capping quality below the true
    # best-available tier on sites (like YouTube) that split their highest resolutions into
    # separate streams, but needs no native dependency at all.
    default_format = "bestvideo+bestaudio/best" if ffmpeg_path else "best/18"
    # audio_only overrides any quality-cap format_selector the caller passed — a video-shaped
    # selector here would just waste bandwidth downloading video that gets discarded.
    # "bestaudio[ext=m4a]" first, "bestaudio" as fallback: nearly every YouTube video's own
    # bestaudio format is Opus-in-WebM these days (itag 251) — a real, valid audio format, but
    # one this app's own stripped ffmpeg build can't remux without the ACODECS/webm-extension
    # patch just above (see its own doc comment), and mutagen can't tag/embed cover art into a
    # WebM container at all afterward regardless. m4a (itag 140, AAC), when YouTube also offers
    # it for a given video, sidesteps both problems entirely — a real .m4a file, correctly filed
    # under Music by MediaStore, with working mutagen tagging/cover art — at the cost of usually
    # being YouTube's lower-bitrate alternative to its own opus stream when both exist. Falls
    # back to plain "bestaudio" (Opus include) when a video has no m4a-native audio format at
    # all, same as before this preference existed.
    chosen_format = "bestaudio[ext=m4a]/bestaudio/best" if audio_only else (format_selector or default_format)
    # Poster name, then caption — "%(a,b|default)s" tries each field left to right and falls back
    # to the literal default only once every field in the list is empty; DownloadWorker derives
    # the entity's own display title from this same "name - caption [id]" shape (see its own
    # comment on that), so the two need to keep matching.
    # ".150B" caps the title field at 150 bytes so an overlong caption doesn't produce an
    # unreasonably long filename — trim_filenames (Settings > Folders) toggles this cap off,
    # matching YTDLnis's own "Trim filenames" setting. Only affects this *default* template; a
    # caller-supplied filename_format (a saved template, or a per-download override) is used
    # exactly as given either way.
    title_field = "%(title,description|Unknown).150B" if trim_filenames else "%(title,description|Unknown)s"
    outtmpl = filename_format or f"%(uploader,channel,creator|Unknown)s - {title_field} [%(id)s].%(ext)s"
    ydl_opts = {
        "outtmpl": os.path.join(download_dir, outtmpl),
        "format": chosen_format,
        "progress_hooks": [progress_hook],
        "logger": _Logger(callback),
        "noprogress": True,
        "quiet": True,
        "no_color": True,
        # Was hardcoded True unconditionally — now a real Settings > Folders choice (see
        # GalleryDlPreferences.isRestrictFilenames's own doc comment), defaulting to the same
        # always-on behavior this had before so an existing install's downloads look identical
        # unless the user actually changes it.
        "restrictfilenames": restrict_filenames,
        # The CLI sets this by default (unless --abort-on-error is passed); the raw YoutubeDL API
        # does not — without it, one bad item in a multi-item URL (e.g. an Instagram carousel's
        # non-video photo entries, which legitimately have "No video formats found") raises
        # immediately out of ydl.download() and aborts the whole call, silently skipping every
        # item after it — including any real videos later in the same carousel. Reproduced live:
        # a 3-item Instagram post (1 photo + 2 real videos) downloaded zero videos without this,
        # because the photo item (processed first) raised before yt-dlp ever reached the videos.
        # "only_download" still lets a genuine extraction-level failure (bad URL, private/deleted
        # post, etc.) raise normally — it only tolerates individual items failing mid-playlist.
        "ignoreerrors": "only_download",
    }
    if verbose:
        # Every internal debug line yt-dlp itself would print with --verbose on the real CLI, not
        # just the handful this app's own _Logger.debug() (see its own comment) already forwards
        # regardless of this flag — troubleshooting-only, see GalleryDlPreferences.isVerboseLogging.
        ydl_opts["verbose"] = True
    if force_ipv4:
        # yt-dlp's own -4/--force-ipv4 CLI flag is implemented as exactly this under the hood —
        # binding outgoing connections to 0.0.0.0 forces IPv4 address resolution.
        ydl_opts["source_address"] = "0.0.0.0"
    if concurrent_fragments:
        ydl_opts["concurrent_fragment_downloads"] = int(concurrent_fragments)
    if no_check_certificates:
        ydl_opts["nocheckcertificate"] = True
    if sleep_interval_seconds:
        # A real random range, like yt-dlp's own --min-sleep-interval/--max-sleep-interval pair is
        # meant to be used — a fixed per-request delay is exactly the kind of clockwork pattern
        # that's easy for a site's own rate-limit/bot-detection to fingerprint. The floor is
        # hardcoded at 2s (not user-configurable) since anything lower isn't meaningfully
        # different from no delay at all; the app's own setting is just the ceiling.
        ydl_opts["sleep_interval"] = 2
        # max(2, ...) since yt-dlp asserts min <= max — guards a stale/out-of-range stored value
        # from a version before this setting became a 2-20 slider (see GalleryDlPreferences).
        ydl_opts["max_sleep_interval"] = max(2, int(sleep_interval_seconds))
    if socket_timeout_seconds:
        # How long a single read/connect can stall before yt-dlp gives up on it (then retries,
        # per the "retries" handling above) — yt-dlp's own default is 20s, generous enough that
        # most users never touch this; only useful on a slow/flaky connection where 20s of
        # silence isn't actually a dead request yet.
        ydl_opts["socket_timeout"] = float(socket_timeout_seconds)
    if buffer_size_kb:
        # yt-dlp's own --buffer-size, in bytes — this app's own setting is entered in KB for a
        # more human-scaled number.
        ydl_opts["buffersize"] = int(buffer_size_kb) * 1024
    if aria2_path:
        # Real multi-connection segmented downloading of a single file (yt-dlp's own downloader
        # fetches one file with one connection) — matches --downloader in the real CLI. aria2c
        # isn't statically linked (see Aria2Runtime.kt's own doc comment for the full dependency
        # story), so its own dynamic linker needs LD_LIBRARY_PATH pointed at the unzipped bundle of
        # its actual shared-library deps; os.environ here is inherited by the subprocess yt-dlp's
        # own Aria2cFD spawns aria2c as, same as any other child process inheriting its parent's
        # environment unless explicitly overridden.
        ydl_opts["external_downloader"] = aria2_path
        if aria2_lib_dir:
            existing = os.environ.get("LD_LIBRARY_PATH")
            os.environ["LD_LIBRARY_PATH"] = f"{aria2_lib_dir}:{existing}" if existing else aria2_lib_dir
        # aria2c's GnuTLS-linked TLS stack has no CA trust store of its own here (unlike a request
        # made through Python's own ssl module, which transparently uses Android's system trust
        # store) — reproduced live as every HTTPS URL failing with "SSL/TLS handshake failure: not
        # signed by known authorities", aria2c's own generic error for "I have literally no CA
        # certificates to check against". cacert.pem sits next to this script itself (copied there
        # by PythonRuntime.ensureProvisioned alongside the wrapper scripts).
        cacert_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cacert.pem")
        if os.path.exists(cacert_path):
            ydl_opts["external_downloader_args"] = {"aria2c": [f"--ca-certificate={cacert_path}"]}
        _patch_external_downloader_progress()
    if custom_headers:
        # "Header-Name: value" lines, one per header — merged into (not replacing) yt-dlp's own
        # default request headers, the same override-specific-headers behavior --add-header has on
        # the real CLI.
        headers = {}
        for line in custom_headers.splitlines():
            if ":" not in line:
                continue
            name, _, value = line.partition(":")
            name, value = name.strip(), value.strip()
            if name and value:
                headers[name] = value
        if headers:
            ydl_opts["http_headers"] = headers
    from urllib.parse import urlsplit as _urlsplit
    if (impersonate and _IMPERSONATE_AVAILABLE) or (tls_client_path and _REDDIT_HOST_RE.match((_urlsplit(url).hostname or ""))):
        # An empty ImpersonateTarget() (rather than a specific browser/version string) asks yt-dlp
        # for its own default target — the first one whose backend is actually available in this
        # bundled environment. YoutubeDL.__init__'s own availability check
        # (self.params.get('impersonate') passed straight to _impersonate_target_available) skips
        # the True/''-to-ImpersonateTarget() normalization that _parse_impersonate_targets does
        # elsewhere in this yt-dlp version — passing the bare `True` the CLI's --impersonate
        # accepts hits an `assert isinstance(target, ImpersonateTarget)` in is_supported_target()
        # instead (reproduced live: AssertionError with an empty message, right out of
        # YoutubeDL(ydl_opts) construction). Constructing the real object ourselves sidesteps that.
        #
        # A reddit.com URL always sets this (regardless of the `impersonate` param) — TlsClientRH
        # is scoped to reddit.com specifically (see its own comment) and outranks the default
        # handler for every request once this is set, so it's the one that actually ends up
        # carrying both the redirect resolution and the JSON metadata fetch; `impersonate` itself
        # (Settings > Advanced) stays the opt-in "try this everywhere else" toggle for other sites
        # hitting bot detection, off by default so a site that already works fine doesn't pay
        # curl_cffi's overhead or risk a TLS profile going stale for no reason.
        ydl_opts["impersonate"] = ImpersonateTarget()
    if retries:
        # "retries" alone only covers whole-request failures (extraction, a plain single-file
        # fetch); a merge download's separate video/audio fragments each get their own retry
        # budget via "fragment_retries", uncovered by the first one on its own — default it to
        # the same count unless the caller supplies its own value below.
        ydl_opts["retries"] = int(retries)
        ydl_opts["fragment_retries"] = int(retries)
    if fragment_retries is not None:
        # Explicit override, kept separate from "retries" above so a user can give flaky
        # fragmented downloads (HLS/DASH) a different retry budget than whole-request retries.
        ydl_opts["fragment_retries"] = int(fragment_retries)
    # Built once, combining every reason to prefer one format over another, rather than each
    # concern setting "format_sort" independently and silently clobbering whichever ran last.
    format_sort_terms = []
    if resolution_cap and not audio_only:
        # "res" sorts by min(height, width) rather than raw height — the conventional quality
        # number regardless of portrait/landscape orientation — so this correctly biases
        # "bestvideo"/"best" in chosen_format toward the closest resolution at-or-under the cap
        # instead of the raw-height filter this replaced, which broke on portrait video (see
        # GalleryDlPreferences.VideoQuality.resolutionCap()'s comment for the full story).
        format_sort_terms.append(f"res:{resolution_cap}")
    if output_format == "mp4" and not audio_only:
        # Muxing VP9 video into an MP4 container needs a "vpcC" codec-configuration box this
        # build's ffmpeg doesn't reliably write (see merge_output_format below) — biasing toward
        # h264 up front means an MP4-output download actually picks a source that mixes cleanly
        # instead of picking VP9 (yt-dlp's usual preference) and then failing to mux it. Still
        # falls back to whatever's actually available (av1/vp9/...) when a source has no h264
        # variant at all — this only reorders the preference, it doesn't exclude anything.
        #
        # Audio needs the same treatment, reproduced live after the video-only fix above: yt-dlp's
        # own default best-audio for a YouTube source is Opus in a WebM container (itag 251), and
        # ffmpeg muxes that into an .mp4 without ever raising an error — but plenty of real
        # players (this device's own included) can't actually *play* Opus-in-MP4, so the file
        # "downloaded successfully" while being unplayable. AAC is MP4's own native, universally-
        # supported audio codec, so bias toward it the same way — falls back to whatever's actually
        # available when a source has no AAC variant.
        format_sort_terms.append("vcodec:h264")
        format_sort_terms.append("acodec:aac")
    if format_sort_extra:
        # Raw --format-sort syntax (e.g. "codec:vp9,fps") from Settings > Advanced — appended
        # last so it can still reorder/override the quality-cap and MP4-compatibility terms above
        # rather than being silently outranked by them.
        format_sort_terms.extend(term.strip() for term in format_sort_extra.split(",") if term.strip())
    if format_sort_terms:
        ydl_opts["format_sort"] = format_sort_terms
    if playlist_items:
        # The share-sheet picker's own explicit "download exactly these items" selection —
        # matches gallery-dl's --filter counterpart (see gallery_dl_wrapper.py) but as yt-dlp's own
        # native "1,3,4"/"1-3" playlist_items syntax, mapped from the same 1-indexed item numbers
        # the picker showed (see GalleryDlListing.listViaYtDlp's entryToGalleryItem — the same
        # top-to-bottom order yt-dlp itself enumerates the playlist in). Deliberately overrides
        # no_playlist below: a user who explicitly picked specific items from a multi-item listing
        # wants those items downloaded, not yt-dlp's single-video "noplaylist" shortcut silently
        # discarding everything but item 1.
        ydl_opts["playlist_items"] = playlist_items
    elif no_playlist:
        # Only set when actually requested, not unconditionally — verified live (same URL, single
        # variable changed) that passing this at all turns a ~6s extraction into 60-250+s in this
        # yt-dlp version, seemingly by forcing a much more expensive resolution path that compounds
        # with this build's now-failing JS-challenge solver (yt-dlp recently moved to a "remote
        # components" challenge-solver model our bundled quickjs config predates — see the
        # "[jsc] ... was skipped" warning some downloads log now). Defaulting the "Single video
        # only" preference to off (see GalleryDlPreferences) keeps that cost opt-in rather than
        # paid by every download.
        ydl_opts["noplaylist"] = True
    if ffmpeg_path:
        ydl_opts["ffmpeg_location"] = ffmpeg_path
        if ffmpeg_lib_dir:
            # armeabi-v7a only (FfmpegRuntime.kt's own doc comment) — that build isn't statically
            # linked the way the other two ABIs' ffmpeg is, so its own dynamic linker needs
            # LD_LIBRARY_PATH pointed at its unzipped dependency bundle before it runs, same
            # mechanism as aria2_lib_dir above (os.environ is inherited by whatever subprocess
            # yt-dlp itself spawns ffmpeg as — FFmpegFD/FFmpegPostProcessor, later in this same
            # process). Appended, not assigned outright — aria2_lib_dir may have already set this
            # same variable above if aria2 is also enabled, and both entries need to survive.
            existing = os.environ.get("LD_LIBRARY_PATH")
            os.environ["LD_LIBRARY_PATH"] = f"{ffmpeg_lib_dir}:{existing}" if existing else ffmpeg_lib_dir
        # FFmpegFD.available() (checked specifically for a partial/trimmed download — see
        # clip_range below) instantiates FFmpegPostProcessor() with no downloader at all, so it
        # never sees ydl_opts["ffmpeg_location"] and reports ffmpeg missing even though it's
        # right there — a known yt-dlp gap ("Fixme: This may be wrong when --ffmpeg-location is
        # used" in its own source). Its real CLI works around exactly this by also setting this
        # module-level ContextVar directly; reproduced live (clip trim aborted with "ffmpeg is
        # not installed" despite ffmpeg_location being set) and fixed the same way.
        FFmpegPostProcessor._ffmpeg_location.set(ffmpeg_path)
        # Letting yt-dlp pick MP4 for a merge (its own default when the video track allows it)
        # used to produce unplayable output on real devices — some sites (Instagram among them)
        # serve VP9 video, and muxing VP9 into an MP4 container needs a "vpcC" codec-configuration
        # box that this build's ffmpeg doesn't reliably write, silently producing a file every
        # player rejects with "Empty VP Codec Configuration box". MKV has no such requirement for
        # any codec combination, which is why it stayed the hardcoded default — now that
        # output_format is a real user choice (Settings > Downloads), MP4 additionally gets the
        # vcodec:h264/acodec:aac format_sort bias above so it picks a source that mixes cleanly
        # when one's actually available; MKV needs no such steering.
        #
        # That bias can only ever reorder *existing* candidates, not conjure a compatible one —
        # reproduced live against a real Instagram Reel offering VP9 video with no H264 variant at
        # all (common for Reels specifically): the bias had nothing to pick, yt-dlp still forced a
        # stream-copy remux into .mp4 (this bundled ffmpeg has every encoder disabled — see
        # FfmpegRuntime's own build config — so *transcoding* VP9 into real H264 to rescue this
        # isn't an option at all), and produced the exact "Empty VP Codec Configuration box"
        # failure this whole feature exists to avoid — just now reachable via an explicit user
        # choice instead of yt-dlp's own old default. "mp4/mkv" (a preference *list*, not a single
        # value) is yt-dlp's own documented mechanism for exactly this: get_compatible_ext() (see
        # YoutubeDL.py) walks the list in order and returns the first extension the actual codecs
        # can losslessly satisfy, with "mkv" always accepted as a universal container regardless of
        # codec — so this still produces a real .mp4 whenever the source genuinely supports one,
        # and only quietly drops to .mkv for the specific sources that can't, rather than a file
        # that "downloaded successfully" while being unplayable either way.
        ydl_opts["merge_output_format"] = f"{output_format}/mkv" if output_format == "mp4" else (output_format or "mkv")
        # Built in the same order as custom_pp_keys above — get_postprocessor() resolves each
        # "key" to a "<key>PP" class (e.g. "FFmpegExtractAudio" -> FFmpegExtractAudioPP), while
        # the runtime hook event names strip the "Ffmpeg" prefix (-> "ExtractAudio"), which is
        # why custom_pp_keys uses the stripped form even though these dicts use the full name.
        postprocessors = []
        # Same trick YTDLnis's own generated command uses (--parse-metadata "%(uploader,channel,
        # creator|)l:^(?P<uploader>.*?)(?:(?= - Topic)|$)"): YouTube's auto-generated "Topic"
        # channels (for albums/singles with no official upload) report an uploader literally named
        # "<Artist> - Topic". Stripping that suffix here, unconditionally and pre_process (so it
        # runs before the filename template above is evaluated), cleans up both the embedded
        # metadata and the "%(uploader,channel,creator|Unknown)s" piece of the default filename —
        # same field/fallback chain the outtmpl above already uses, for consistency.
        postprocessors.append({
            "key": "MetadataParser",
            "when": "pre_process",
            "actions": [(
                MetadataParserPP.Actions.INTERPRET,
                "%(uploader,channel,creator|Unknown)s",
                r"(?P<uploader>.*?)(?: - Topic)?$",
            )],
        })
        if audio_only:
            # "mp3" (yt-dlp's own CLI default) forces FFmpegExtractAudioPP into a real transcode
            # for any source not already literally mp3 — this bundled ffmpeg build is compiled
            # with --disable-encoders (see _LocalTrimPP's own doc comment: stream-copy only, same
            # reason), so that transcode always failed with "Encoder not found", on every source,
            # regardless of quality/codec. "best" (yt-dlp's own --audio-format best) tells
            # FFmpegExtractAudioPP to keep the source's native audio codec and only remux the
            # container via "-acodec copy" (see its run() in postprocessor/ffmpeg.py) — exactly
            # what this ffmpeg build can still do. Output keeps whatever extension the source
            # audio codec maps to (Reddit/Instagram's aac/opus -> .m4a/.opus, not always .mp3),
            # which is the expected, documented behavior of --audio-format best itself.
            postprocessors.append({"key": "FFmpegExtractAudio", "preferredcodec": "best"})
            _patch_ffmpeg_audio_copy_for_stripped_build()
        if embed_metadata or embed_chapters or audio_only:
            # A single FFmpegMetadata entry handles both — add_chapters is that postprocessor's
            # own independent kwarg (this is literally how the real CLI's --embed-chapters is
            # implemented), so embed_chapters doesn't need a metadata embed to come along with it.
            # "or audio_only": same reasoning as "embed_thumbnail or audio_only" below — an
            # audio download always gets its real artist/album/title written into the file itself,
            # regardless of the global "Embed Metadata" setting, the same way Spotify downloads
            # already always do via spotify_wrapper.py's own unconditional mutagen retag.
            # Reproduced live without this: DownloadEntity.artist/album were captured correctly
            # (the separate [artist]/[album] callback lines, read straight off this same
            # info_dict, always fire), but the saved file's own tags stayed empty/"<unknown>"
            # whenever the user hadn't separately turned this setting on — a real, confusing
            # inconsistency between what the app's own Library showed and what the file itself
            # actually carried.
            #
            # Appended *before* EmbedThumbnail (custom_pp_keys above has the matching order, and
            # its own doc comment there has the full story) — FFmpegMetadataPP's own ffmpeg
            # command includes "-vn", which silently strips an m4a's embedded cover art if a
            # thumbnail was already embedded by the time this runs.
            postprocessors.append({"key": "FFmpegMetadata", "add_chapters": embed_chapters})
        if embed_thumbnail or audio_only:
            ydl_opts["writethumbnail"] = True
            postprocessors.append({"key": "EmbedThumbnail"})
        if (download_subtitles or save_subtitle_files) and not audio_only:
            # writesubtitles alone (no EmbedSubtitle postprocessor) leaves the fetched track as
            # its own sidecar .srt/.vtt file next to the video — DownloadWorker's own staging-dir
            # sidecar sweep (the same mechanism that already saves .json/.description/thumbnail
            # sidecars) picks these up and moves them into the gallery, same as any other
            # non-primary output file. Embedding (download_subtitles) and the sidecar file
            # (save_subtitle_files) are independent choices; either, both, or neither can be on.
            ydl_opts["writesubtitles"] = True
            ydl_opts["subtitleslangs"] = [lang.strip() for lang in (subtitle_langs or "en").split(",") if lang.strip()]
            if download_subtitles:
                postprocessors.append({"key": "FFmpegEmbedSubtitle"})
        if postprocessors:
            ydl_opts["postprocessors"] = postprocessors
    if js_runtime_path:
        # Sites like YouTube now require solving a JavaScript challenge to get real format URLs
        # at all — without this, extraction returns zero usable formats regardless of the format
        # selector above (yt-dlp's own "pure-Python fallback" is deprecated and severely limited).
        # This replaces the default {"deno": {}} entirely rather than adding to it, since deno
        # itself isn't bundled and would just be tried first, fail, and fall through anyway.
        # The Python API takes a dict of {runtime: {config}} — NOT the "runtime:path" string list
        # the --js-runtimes CLI flag parses into; passing a list here raises "Invalid js_runtimes
        # format" from YoutubeDL.__init__'s own validation.
        ydl_opts["js_runtimes"] = {"quickjs": {"path": js_runtime_path}}
    if cookies_path:
        ydl_opts["cookiefile"] = cookies_path
    if archive_path:
        # Same idea as gallery-dl's --download-archive — DownloadWorker's own DownloadedFileRecord
        # table is still the real source of truth for dedup (see its comment for why), this is
        # just yt-dlp's own bookkeeping so a resume doesn't re-fetch already-finished files either.
        ydl_opts["download_archive"] = archive_path
    rate = _parse_size(limit_rate)
    if rate:
        ydl_opts["ratelimit"] = rate
    if proxy_url:
        ydl_opts["proxy"] = proxy_url
    if live_from_start:
        # A no-op for anything that isn't currently live (info_dict.get('is_live') gates every
        # actual use of this internally), so safe to set unconditionally from a global preference.
        ydl_opts["live_from_start"] = True
    # _parse_extractor_args returns None (not {}) for an empty/unset field — the common case, since
    # this is normally left blank — which setdefault() below would crash on.
    parsed_extractor_args = _parse_extractor_args(extractor_args) or {}
    if youtube_client_rotation:
        # Rotates through multiple internal YouTube API clients instead of just "web" — if one
        # client's endpoint is throttling or returning degraded formats, yt-dlp falls back to the
        # next. A default only: merged in first so an explicit youtube:player_client=... already
        # present in extractor_args (the free-text Advanced setting) still wins outright, same
        # "app default, explicit override wins" shape custom_headers already uses above.
        youtube_args = parsed_extractor_args.setdefault("youtube", {})
        youtube_args.setdefault("player_client", ["android", "web", "ios"])
    if parsed_extractor_args:
        ydl_opts["extractor_args"] = parsed_extractor_args
    if save_thumbnail:
        # Writes the thumbnail out as its own file next to the media, unlike embed_thumbnail
        # above which muxes it into the file itself — the preview sheet offers these as two
        # separate choices because they produce genuinely different results.
        ydl_opts["writethumbnail"] = True
    filesize_bytes = _parse_size(max_filesize)
    if filesize_bytes:
        ydl_opts["max_filesize"] = filesize_bytes
    if write_info_files:
        ydl_opts["writedescription"] = True
        ydl_opts["writeinfojson"] = True
    # clip_ranges was already parsed earlier (feeds custom_pp_keys above) — _LocalTrimPP itself is
    # registered below, once the YoutubeDL instance exists to construct it with.

    if extra_args:
        # yt-dlp has no CLI-args-string constructor, so only a small, safe subset of raw options
        # is supported this way: "key=value" pairs matching real yt_dlp option names, one per line
        # or space-separated. Anything unrecognized is ignored rather than raising.
        for token in extra_args.split():
            if "=" in token:
                key, _, value = token.partition("=")
                ydl_opts[key] = value

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            _fix_impersonate_availability_check(ydl)
            if clip_ranges and ffmpeg_path:
                # Added directly rather than through ydl_opts["postprocessors"] (a list of plain
                # {"key": ...} dicts yt-dlp itself resolves to stock Ffmpeg*PP classes) since
                # _LocalTrimPP isn't one of those — this is yt-dlp's own supported way to add a
                # custom postprocessor instance. "post_process" is the same stage every stock
                # postprocessor above runs at, and instances added here run *after* ones already
                # registered via ydl_opts at that same stage — see _LocalTrimPP's own doc comment
                # for why running last is what we want here anyway.
                ydl.add_post_processor(_LocalTrimPP(ydl, clip_ranges), when="post_process")
            if ffmpeg_path and callback:
                # See _FinalFilePP's own doc comment for why this (not postprocessor_hooks) is
                # what reports the real final file whenever any postprocessing could happen —
                # added last, after _LocalTrimPP above, so it always runs genuinely last.
                ydl.add_post_processor(
                    _FinalFilePP(ydl, callback, override_title, override_artist, source_url=url), when="post_process",
                )
            ydl.download([url])
        return "Done"
    except _Cancelled:
        return "Cancelled"
    except Exception as e:
        if callback:
            callback(f"[error] {e}")
        return f"Error: {e}"

def list_info(url, cookies_path=None, extra_args=None, js_runtime_path=None, tls_client_path=None):
    """Extracts metadata only (no download) via yt-dlp's own extractor — used for the share-sheet
    item picker's preview, specifically to get a *real*, directly fetchable thumbnail image URL
    for video items. gallery-dl's own listing gives every video item an internal "ytdl:"-prefixed
    pseudo-URL for its delegated yt-dlp download path (see gallery_dl_wrapper.py's CallbackWriter
    comment) rather than a plain image — Coil can't render a preview frame from that, so it shows
    up blank. yt-dlp's extractor, run here in metadata-only mode, already resolves each item's real
    poster-frame URL as part of normal extraction (see download()'s own `[thumbnail]` callback
    line above), regardless of whether anything actually downloads.

    Returns JSON on stdout: a single video is {"title":..., "thumbnail":...}; a multi-item source
    (a carousel/playlist url given to yt-dlp directly) is {"entries": [{"title", "thumbnail"}, ...]}
    in the same top-to-bottom order gallery-dl's own listing enumerates the same post in — the
    Kotlin side correlates the two listings by that shared ordering, not by id (the two engines
    don't share an item-numbering scheme). {"error": "..."} on failure — callers fall back to
    treating this the same as "nothing usable came back" rather than crashing the whole listing
    over a preview-only enrichment step."""
    # See download()'s own use of this — same module global TlsClientRH reads from.
    global _TLS_CLIENT_PATH
    _TLS_CLIENT_PATH = tls_client_path
    ydl_opts = {
        "quiet": True,
        # "quiet" alone only suppresses yt-dlp's normal progress/info output — WARNING/ERROR lines
        # (e.g. a missing JS-challenge-solver component) still print straight to stdout by default.
        # This module has no logger wired in here (unlike download()'s own _Logger, which routes
        # through the per-line callback instead), so those warnings were landing directly in the
        # same stdout stream this function's single JSON line is printed to — corrupting it into
        # "WARNING: ...\n{...real json...}", which then fails to parse as JSON on the Kotlin side.
        # Reproduced live: every YouTube/Instagram listing failed this way until this was added.
        "no_warnings": True,
        "no_color": True,
        "skip_download": True,
        # Same reasoning as download()'s own use of this: one bad item (a carousel's non-video
        # photo entries, which yt-dlp can't extract at all) shouldn't abort metadata extraction
        # for the whole post — those entries just come back as None in "entries" below instead.
        "ignoreerrors": "only_download",
        # Only flattens a *playlist's own child entries* (a directly-requested single video is
        # unaffected either way) — without this, listing a long playlist fully resolves every
        # single entry (a separate webpage/watch-page request per video) before returning
        # anything, which is what made a long YouTube Music playlist's preview sheet take a very
        # long time to appear with no feedback in the meantime, reported live as looking like the
        # request had failed. Most playlist extractors (YouTube/YouTube Music included) already
        # carry title/uploader/duration on the playlist page itself, so flat entries still have
        # those for _pick() below — only per-entry thumbnail/requested_formats reliably disappear,
        # which the multi-track checklist UI never rendered anyway (see GalleryDlListing's
        # TrackPreview — no per-track thumbnail field at all).
        "extract_flat": "in_playlist",
        # Deliberately NOT setting "noplaylist" at all (not even False) — reproduced live that an
        # *explicit* noplaylist=False on a single Instagram Reel URL sends its extractor down a
        # different internal path that comes back "Failed to parse JSON (... Expecting value in
        # '': line 1 column 1 ...)" every time, while leaving it unset (yt-dlp's own default,
        # matching what download() above already does successfully for the exact same URLs)
        # extracts the same Reel cleanly. Playlists/multi-item sources still expand into "entries"
        # normally either way — this only affects *which* code path a single item takes.
    }
    if cookies_path:
        ydl_opts["cookiefile"] = cookies_path
    if js_runtime_path:
        # Same reasoning as download()'s own use of this: sites like Instagram/YouTube now
        # require solving a JavaScript challenge to extract *any* real metadata, not just format
        # URLs — without this, extract_info() below fails outright for them instead of just
        # returning fewer fields.
        ydl_opts["js_runtimes"] = {"quickjs": {"path": js_runtime_path}}
    from urllib.parse import urlsplit as _urlsplit
    if tls_client_path and _REDDIT_HOST_RE.match((_urlsplit(url).hostname or "")):
        # Same reasoning as download()'s own use of this — the share-sheet picker's preview hits
        # the exact same reddit.com redirect/metadata calls a real download would.
        ydl_opts["impersonate"] = ImpersonateTarget()
    if extra_args:
        for token in extra_args.split():
            if "=" in token:
                key, _, value = token.partition("=")
                ydl_opts[key] = value

    def _pick(entry):
        if not entry:
            return None
        # uploader is for the download preview sheet's card subtitle. Several extractors only
        # populate one of these (Instagram gives "uploader", YouTube "channel", some give only
        # "uploader_id"), so fall through them rather than showing nothing when the first is absent.
        uploader = entry.get("uploader") or entry.get("channel") or entry.get("uploader_id")
        # filesize is only populated once a specific format is picked; filesize_approx is what a
        # plain extraction usually carries, so fall through to it rather than showing nothing.
        filesize = entry.get("filesize") or entry.get("filesize_approx")
        # For the Trim screen's own ExoPlayer preview (Kotlin side reads "url"/"requested_formats"/
        # "duration" — see DownloadPreviewSheet.kt's TrimVideoScreen and GalleryDlListing's
        # fetchPreviewInfo). extract_info() already resolves yt-dlp's normal default format
        # selection even with download=False (same mechanism --dump-json relies on), so these are
        # already sitting on `entry` here — just never used to reach this point before. A single
        # progressive format lands on "url" directly; an adaptive one (YouTube's usual case, no
        # single file has both video+audio) needs both halves merged client-side, hence
        # requested_formats. Trimmed to bare {"url": ...} dicts rather than forwarded whole:
        # yt-dlp's real requested_formats entries carry dozens of fields (format tables, HTTP
        # headers, ...) that both bloat this JSON line for no reason and were never meant to leave
        # the process.
        requested_formats = entry.get("requested_formats")
        # artist/album: for the download preview sheet's own song-styled card/subtitle — same
        # fields download()'s own [artist]/[album] callback lines already read off this same
        # info-dict (see its own doc comment there), just also surfaced during listing so the
        # sheet can show them before a download ever starts.
        return {
            "title": entry.get("title"),
            "thumbnail": entry.get("thumbnail"),
            "uploader": uploader,
            "artist": entry.get("artist") or entry.get("creator"),
            "album": entry.get("album"),
            "filesize": filesize,
            "duration": entry.get("duration"),
            "url": entry.get("url"),
            "requested_formats": (
                [{"url": f.get("url")} for f in requested_formats if f.get("url")]
                if requested_formats else None
            ),
        }

    # Real-time status line (not part of the returned JSON) — this function's own caller
    # (GalleryDlListing.kt's runYtDlpListInfo) reads PythonRuntime.run()'s per-line callback as
    # it's printed, well before this call returns, and surfaces it in place of a static "Loading…"
    # so a long-playlist listing doesn't look stuck with no feedback.
    print("[status] Fetching info…", flush=True)
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            _fix_impersonate_availability_check(ydl)
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        return json.dumps({"error": str(e)})

    if info is None:
        return json.dumps({"error": "no info extracted"})
    entries = info.get("entries")
    if entries is not None:
        print(f"[status] Found {len(entries)} tracks…", flush=True)
        return json.dumps({"entries": [_pick(e) for e in entries]})
    return json.dumps(_pick(info) or {"error": "no info extracted"})


# CLI entry point for PythonRuntime.kt (subprocess model, replacing Chaquopy's direct callAttr()).
# should_cancel is deliberately not wired here — with each download now its own OS process,
# cancellation is just Kotlin killing the process, no cooperative polling needed. argv is all
# strings, so an empty string is this module's own "None" sentinel; DownloadWorker.kt passes "" for
# any positional arg it would otherwise pass Kotlin null for.
if __name__ == "__main__":
    import sys as _sys

    def _s(v):
        return None if v == "" else v

    def _b(v):
        return v == "1"

    def _emit(line):
        print(line, flush=True)

    if len(_sys.argv) < 2 or _sys.argv[1] not in ("download", "list", "probe"):
        print("Usage: yt_dlp_wrapper.py download <21 positional args> | list <4 positional args> | probe <url>", file=_sys.stderr)
        _sys.exit(2)

    if _sys.argv[1] == "probe":
        print(probe(_sys.argv[2]), flush=True)
        _sys.exit(0)

    if _sys.argv[1] == "list":
        a = _sys.argv[2:]
        print(list_info(
            url=a[0], cookies_path=_s(a[1]), extra_args=_s(a[2]), js_runtime_path=_s(a[3]),
            tls_client_path=(_s(a[4]) if len(a) > 4 else None),
        ), flush=True)
        _sys.exit(0)

    a = _sys.argv[2:]
    status = download(
        url=a[0], download_dir=a[1], cookies_path=_s(a[2]),
        callback=_emit, filename_format=_s(a[3]), extra_args=_s(a[4]),
        archive_path=_s(a[5]), limit_rate=_s(a[6]), format_selector=_s(a[7]),
        should_cancel=None, js_runtime_path=_s(a[8]), ffmpeg_path=_s(a[9]),
        audio_only=_b(a[10]), download_subtitles=_b(a[11]), subtitle_langs=_s(a[12]),
        embed_thumbnail=_b(a[13]), embed_metadata=_b(a[14]), no_playlist=_b(a[15]),
        resolution_cap=(int(a[16]) if a[16] else None),
        output_format=_s(a[17]) if len(a) > 17 else None,
        retries=_s(a[18]) if len(a) > 18 else None,
        playlist_items=_s(a[19]) if len(a) > 19 else None,
        max_filesize=_s(a[20]) if len(a) > 20 else None,
        write_info_files=_b(a[21]) if len(a) > 21 else False,
        clip_range=_s(a[22]) if len(a) > 22 else None,
        proxy_url=_s(a[23]) if len(a) > 23 else None,
        live_from_start=_b(a[24]) if len(a) > 24 else False,
        extractor_args=_s(a[25]) if len(a) > 25 else None,
        save_thumbnail=_b(a[26]) if len(a) > 26 else False,
        force_ipv4=_b(a[27]) if len(a) > 27 else False,
        concurrent_fragments=(int(a[28]) if len(a) > 28 and a[28] else None),
        no_check_certificates=_b(a[29]) if len(a) > 29 else False,
        sleep_interval_seconds=(int(a[30]) if len(a) > 30 and a[30] else None),
        # No escaping needed — PythonRuntime.run() execs via ProcessBuilder with a real argv list,
        # not a shell, so a literal newline inside this one positional argument passes through
        # exactly as typed (same reasoning already applies to extractor_args above).
        custom_headers=_s(a[31]) if len(a) > 31 else None,
        format_sort_extra=_s(a[32]) if len(a) > 32 else None,
        verbose=_b(a[33]) if len(a) > 33 else False,
        embed_chapters=_b(a[34]) if len(a) > 34 else False,
        save_subtitle_files=_b(a[35]) if len(a) > 35 else False,
        restrict_filenames=_b(a[36]) if len(a) > 36 else True,
        trim_filenames=_b(a[37]) if len(a) > 37 else True,
        fragment_retries=(int(a[38]) if len(a) > 38 and a[38] else None),
        socket_timeout_seconds=(_s(a[39]) if len(a) > 39 else None),
        buffer_size_kb=(int(a[40]) if len(a) > 40 and a[40] else None),
        youtube_client_rotation=_b(a[41]) if len(a) > 41 else False,
        impersonate=_b(a[42]) if len(a) > 42 else False,
        aria2_path=(_s(a[43]) if len(a) > 43 else None),
        aria2_lib_dir=(_s(a[44]) if len(a) > 44 else None),
        ffmpeg_lib_dir=(_s(a[45]) if len(a) > 45 else None),
        tls_client_path=(_s(a[46]) if len(a) > 46 else None),
        override_title=(_s(a[47]) if len(a) > 47 else None),
        override_artist=(_s(a[48]) if len(a) > 48 else None),
    )
    print(f"[__status__] {status}", flush=True)
