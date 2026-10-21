"""Instagram posts/reels via Instaloader — the default engine for single Instagram posts (see
VideoSiteRouter.resolveEngine / the "Use Instaloader for Instagram" setting).

Why a third engine for one site: tested side by side without cookies, gallery-dl (bundled and
latest) hit Instagram's login wall on every post ("HTTP redirect to login page"), while
Instaloader fetched public posts anonymously — every carousel image, reels, and captions. With
cookies the user's Instagram session is loaded too, so private/restricted posts work the same way.

Speaks the exact same stdout line protocol DownloadWorker already parses for the other wrappers,
so nothing downstream needs to know which engine produced a file:
  [total] N          items this run will fetch (after the picker's item filter)
  [title] ...        "owner - caption" display title, as soon as metadata is known
  [thumbnail] URL    remote preview image for the queue card
  [error] ERROR: ... a human-readable failure reason (only the "ERROR:" form counts as real)
  /abs/path          one line per finished file inside the staging dir
Anything that fails here (0 files) makes DownloadWorker fall back to the classic gallery-dl/
yt-dlp path, so this never makes an Instagram download worse than before it existed.

`list` prints one line of JSON shaped like gallery-dl's --dump-json ([3, url, {num, filename,
extension, description}] per item, [-1, {error, message}] on failure) so GalleryDlListing reuses
its existing parser, and item "num"s match gallery-dl's own 1-based carousel numbering — the
picker's "num in {...}" filter means the same items to either engine.
"""

import json
import os
import random
import re
import sys
import time

import instaloader
from instaloader import Instaloader, Post, RateController
import net_resilience
import custom_names
from comfort_events import event
net_resilience.install()  # stalled connects retry on a fresh connection; see its docstring

_SHORTCODE_RE = re.compile(
    r"instagram\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", re.IGNORECASE,
)
_ITEM_FILTER_RE = re.compile(r"\{([\d,\s]+)\}")
# What download_pic saves: the extension comes from the server's Content-Type ("jpeg" -> "jpg").
_MEDIA_EXTENSIONS = (".mp4", ".jpg", ".png", ".webp", ".heic", ".gif")
_RESTRICT_RE = re.compile(r"[^0-9A-Za-z_.\[\]-]")
_BAD_FILENAME_CHARS = re.compile(r'[\\/:*?"<>|\r\n\t]+')
# Rate-limit waits longer than this abort instead of sleeping: Instaloader's own default is to wait
# out a 429 for as long as Instagram asks (often many minutes), which would leave a download
# sitting at "Downloading..." with nothing happening. Failing fast hands it to the classic
# gallery-dl/yt-dlp fallback instead.
_MAX_RATE_LIMIT_WAIT_SECONDS = 20
# How long a preview's saved post (list_items' info_cache_path) is trusted by download(), same
# window as yt_dlp_wrapper's. A saved media link that no longer works just triggers a fresh fetch.
_INFO_CACHE_MAX_AGE_SECONDS = 20 * 60


class _RateLimited(Exception):
    pass


class _TooLarge(Exception):
    pass


# Settings > Downloads for this one job (set by download()): the ones that apply to every engine.
_SETTINGS = {"limit_rate": None, "retries": None, "max_filesize": None, "restrict": False}


def _parse_size(value):
    """"500k", "2M", "1g" or plain bytes (Settings' speed limit and max file size, the form yt-dlp
    and gallery-dl take) -> bytes, 1024-based like theirs. None when unset or unreadable."""
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*([kmg]?)\s*", value or "", re.IGNORECASE)
    if not m:
        return None
    return int(float(m.group(1)) * 1024 ** "_kmg".index((m.group(2) or "_").lower()))


def _write_raw(context):
    """Instaloader's InstaloaderContext.write_raw, plus the speed limit (bytes/s, averaged over the
    file like yt-dlp's --limit-rate) and max file size (the file is skipped, as with the others:
    _TooLarge, from its Content-Length or once it gets past the limit)."""
    import requests

    def write_raw(resp, filename):
        rate, max_bytes = _SETTINGS["limit_rate"], _SETTINGS["max_filesize"]
        temp = filename + ".temp"
        if not isinstance(resp, requests.Response):
            with open(temp, "wb") as f:
                f.write(resp)
            os.replace(temp, filename)
            return
        length = resp.headers.get("Content-Length")
        if max_bytes and length and length.isdigit() and int(length) > max_bytes:
            resp.close()
            raise _TooLarge()
        started, written = time.monotonic(), 0
        try:
            with open(temp, "wb") as f:
                while True:
                    chunk = resp.raw.read(64 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
                    written += len(chunk)
                    if max_bytes and written > max_bytes:
                        raise _TooLarge()
                    if rate:
                        ahead = written / rate - (time.monotonic() - started)
                        if ahead > 0:
                            time.sleep(ahead)
        except BaseException:
            try:
                os.remove(temp)
            except OSError:
                pass
            raise
        os.replace(temp, filename)

    return write_raw


def _apply_network_settings(retries, no_check_certificates, force_ipv4):
    """For this whole process (one download job): certificate checks off, IPv4 only."""
    _SETTINGS["retries"] = retries
    if force_ipv4:
        net_resilience.force_ipv4()
    if no_check_certificates:
        import requests
        import urllib3
        urllib3.disable_warnings()
        original = requests.Session.request

        def request(self, *args, **kwargs):
            kwargs["verify"] = False
            return original(self, *args, **kwargs)

        requests.Session.request = request


class _ProxyUnsupported(Exception):
    pass


def _check_proxy(proxy_url):
    """Instaloader talks through requests, which needs PySocks for a socks:// proxy — not in this
    runtime. Without it every request would fail one by one (and be retried); stopping here says so
    at once, and the download goes on to the other engines (yt-dlp has its own SOCKS support)."""
    if not proxy_url or not proxy_url.lower().startswith("socks"):
        return
    try:
        import socks  # noqa: F401
    except ImportError:
        raise _ProxyUnsupported() from None


class _FailFastRateController(RateController):
    def sleep(self, secs):
        if secs > _MAX_RATE_LIMIT_WAIT_SECONDS:
            raise _RateLimited()
        time.sleep(secs)


def _s(v):
    return None if v in (None, "") else v


def shortcode_of(url):
    m = _SHORTCODE_RE.search(url or "")
    return m.group(1) if m else None


def _read_instagram_cookies(cookies_path):
    """Netscape cookies.txt -> {name: value} for instagram.com only. "#HttpOnly_" lines are real
    cookies (the prefix marks the flag), not comments. The same name can appear under more than one
    Instagram domain (seen live: csrftoken/ds_user_id/rur twice) — the one expiring last is the
    current one, so it wins regardless of file order."""
    cookies = {}
    expiries = {}
    if not cookies_path or not os.path.isfile(cookies_path):
        return cookies
    with open(cookies_path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if line.startswith("#HttpOnly_"):
                line = line[len("#HttpOnly_"):]
            elif not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 7:
                continue
            domain = parts[0].lstrip(".").lower()
            if domain == "instagram.com" or domain.endswith(".instagram.com"):
                try:
                    expiry = int(parts[4])
                except ValueError:
                    expiry = 0
                # 0 is a session cookie (no expiry) — treat it as the freshest.
                expiry = expiry or 2**62
                if expiry >= expiries.get(parts[5], -1):
                    expiries[parts[5]] = expiry
                    cookies[parts[5]] = parts[6]
    return cookies


def _new_loader(session_cookies, proxy_url, timeout_seconds):
    loader = Instaloader(
        quiet=True,
        # Instaloader's own pause before every instagram.com request (random, ~1.7s on average, up
        # to 15s) is off: a post is one lookup, and the slow part of a rate limit is handled by
        # _FailFastRateController. Settings > Downloads' sleep interval applies instead, between
        # files (see download()), the way it does for yt-dlp.
        sleep=False,
        download_pictures=False, download_videos=False, download_video_thumbnails=False,
        save_metadata=False, compress_json=False,
        # 20s of silence (yt-dlp's default too) already means a dead connection; 60s here showed
        # up as a download frozen for 2 minutes (a stalled read, then a retry that stalled too).
        # One more attempt instead, each on a fresh connection.
        # Settings > Downloads' retries (attempts = retries + 1), else 3 attempts.
        max_connection_attempts=int(_SETTINGS["retries"]) + 1 if _SETTINGS["retries"] else 3,
        request_timeout=float(timeout_seconds) if timeout_seconds else 20.0,
        rate_controller=lambda ctx: _FailFastRateController(ctx),
    )
    if session_cookies and session_cookies.get("sessionid"):
        cookies = dict(session_cookies)
        # load_session() reads csrftoken unconditionally for its X-CSRFToken header.
        cookies.setdefault("csrftoken", "")
        # Any non-empty username marks the context as logged in; the real username isn't needed
        # to fetch a post, and test_login() would cost an extra request against the rate limit.
        loader.load_session(cookies.get("ds_user_id") or "user", cookies)
    loader.context.write_raw = _write_raw(loader.context)
    if proxy_url:
        # Only the main session: Instaloader makes a fresh session for each query and each file,
        # and those get the proxy from the environment PythonRuntime sets for every job.
        loader.context._session.proxies = {"http": proxy_url, "https": proxy_url}
    return loader


def _friendly_error(exc):
    if isinstance(exc, _ProxyUnsupported):
        return "Instaloader can't use a SOCKS proxy — use an http:// proxy, or turn off Instaloader in Settings"
    if isinstance(exc, _RateLimited):
        return "Instagram is rate-limiting requests right now — try again in a few minutes"
    if isinstance(exc, instaloader.exceptions.LoginRequiredException):
        return "Instagram requires a logged-in session for this post — add Instagram cookies in Settings > Cookies & Login"
    if isinstance(exc, instaloader.exceptions.BadResponseException):
        return "Instagram didn't return this post — it may be private, age-restricted, or need a logged-in session"
    if isinstance(exc, instaloader.exceptions.ConnectionException):
        return f"Couldn't reach Instagram: {exc}"
    return f"{type(exc).__name__}: {exc}"


def _fresh_cache(path):
    return bool(path) and os.path.isfile(path) and time.time() - os.path.getmtime(path) < _INFO_CACHE_MAX_AGE_SECONDS


def _save_post(post, path):
    """Best-effort: a failure only means the download fetches the post again, same as before."""
    if not path:
        return
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        instaloader.save_structure_to_file(post, tmp)
        os.replace(tmp, path)
    except Exception:  # noqa: BLE001
        pass


def _fetch_post(url, cookies_path, proxy_url, timeout_seconds, info_cache_path=None):
    """(loader, post, from_cache). Uses the preview's saved post when it's recent (see
    _INFO_CACHE_MAX_AGE_SECONDS) — skipping the metadata request entirely — otherwise tries the
    saved Instagram session first when there is one, then anonymously: an expired session fails
    outright where a public post would have worked without it."""
    shortcode = shortcode_of(url)
    if not shortcode:
        raise ValueError("Not an Instagram post or reel link")
    _check_proxy(proxy_url)
    session = _read_instagram_cookies(cookies_path)
    if _fresh_cache(info_cache_path):
        try:
            loader = _new_loader(session if session.get("sessionid") else None, proxy_url, timeout_seconds)
            post = instaloader.load_structure_from_file(loader.context, info_cache_path)
            if isinstance(post, Post) and post.shortcode == shortcode:
                return loader, post, True
        except Exception:  # noqa: BLE001 — unreadable cache: just fetch normally
            pass
    attempts = [session, None] if session.get("sessionid") else [None]
    last_exc = None
    for cookies in attempts:
        loader = _new_loader(cookies, proxy_url, timeout_seconds)
        try:
            return loader, Post.from_shortcode(loader.context, shortcode), False
        except _RateLimited:
            raise
        except Exception as exc:  # noqa: BLE001 — every failure is reported, not just Instaloader's
            last_exc = exc
    raise last_exc


def _media_items(post):
    """[(num, is_video, media_url, preview_url)] in carousel order, 1-based like gallery-dl."""
    if post.typename == "GraphSidecar":
        return [
            (i, node.is_video, node.video_url if node.is_video else node.display_url, node.display_url)
            for i, node in enumerate(post.get_sidecar_nodes(), start=1)
        ]
    return [(1, post.is_video, post.video_url if post.is_video else post.url, post.url)]


def _clean(text, limit):
    text = _BAD_FILENAME_CHARS.sub(" ", text or "")
    text = re.sub(r"\s+", " ", text).strip().rstrip(".")
    if len(text) > limit:
        text = text[:limit].rstrip()
    return text


def _caption_line(post):
    caption = post.caption or ""
    first = next((ln for ln in caption.splitlines() if ln.strip()), "")
    return first


def _display_title(post):
    owner = post.owner_username or "instagram"
    caption = _clean(_caption_line(post), 60)
    return f"{owner} - {caption}" if caption else owner


def _parse_item_filter(item_filter):
    m = _ITEM_FILTER_RE.search(item_filter or "")
    if not m:
        return None
    return {int(n) for n in m.group(1).replace(" ", "").split(",") if n}


def _read_archive(path):
    if not path or not os.path.isfile(path):
        return set()
    with open(path, encoding="utf-8") as f:
        return {ln.strip() for ln in f if ln.strip()}


def _append_archive(path, key):
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(key + "\n")


def download(url, download_dir, cookies_path=None, item_filter=None, archive_path=None,
             write_info_files=False, proxy_url=None, socket_timeout_seconds=None, emit=print,
             info_cache_path=None, filename_format=None, sleep_interval_seconds=None,
             limit_rate=None, retries=None, max_filesize=None, restrict_filenames=False,
             force_ipv4=False, no_check_certificates=False):
    _SETTINGS.update(limit_rate=_parse_size(limit_rate), max_filesize=_parse_size(max_filesize),
                     restrict=restrict_filenames)
    _apply_network_settings(retries, no_check_certificates, force_ipv4)
    try:
        loader, post, from_cache = _fetch_post(url, cookies_path, proxy_url, socket_timeout_seconds, info_cache_path)
        items = _media_items(post)
        return _download_post(loader, post, from_cache, items, url, download_dir, cookies_path, item_filter,
                              archive_path, write_info_files, proxy_url, socket_timeout_seconds, emit,
                              filename_format, sleep_interval_seconds)
    except Exception as exc:  # noqa: BLE001 — reported as an error line, never a bare traceback
        emit(event("error", message=f"ERROR: {_friendly_error(exc)}"))
        return "error"


def _restricted(name):
    """Settings > Folders' "Restrict filenames": the characters yt-dlp's restricted names keep
    (letters, digits, "_", ".", "-" and the "[id]" brackets), anything else "_" — the same rule
    gallery_dl_wrapper gives gallery-dl, so a file is named alike whichever engine saved it.
    (yt-dlp's own sanitize_filename, used before, also dropped the brackets.)"""
    if not _SETTINGS["restrict"]:
        return name
    return _RESTRICT_RE.sub("_", name).strip("_") or name


def _download_post(loader, post, from_cache, items, url, download_dir, cookies_path, item_filter, archive_path,
                   write_info_files, proxy_url, socket_timeout_seconds, emit, filename_format, sleep_interval_seconds):
    wanted = _parse_item_filter(item_filter)
    if wanted is not None:
        items = [it for it in items if it[0] in wanted]
    done = _read_archive(archive_path)
    shortcode = post.shortcode
    multi = post.typename == "GraphSidecar"

    emit(event("total", count=len(items)))
    # The whole download's size up front, like gallery-dl and yt-dlp report theirs — only when
    # every file to fetch answered, so the queue card never shows a total that's too small.
    pending = [media for num, _v, media, _p in items if (f"{shortcode}_{num}" if multi else shortcode) not in done]
    sizes = net_resilience.content_lengths(pending)
    if pending and all(m in sizes for m in pending):
        emit(event("size", bytes=sum(sizes[m] for m in pending)))
    title = _display_title(post)
    emit(event("title", title=title))
    if post.url:
        emit(event("thumbnail", url=post.url))

    os.makedirs(download_dir, exist_ok=True)
    # Same "poster - caption [unique-id].ext" shape as the other engines' default filename format,
    # so DownloadWorker's derivePosterCaptionTitle recovers the same display title from it.
    safe_title = _clean(title, 90) or shortcode
    used_names = set()

    def item_name(num, is_video, key):
        """Settings > Folders' filename format (gallery-dl's syntax, see custom_names) when the
        user set their own, else "poster - caption [id]". Kept unique within the post."""
        name = None
        if filename_format:
            caption = _caption_line(post)
            name = custom_names.render(filename_format, {
                "category": "instagram", "subcategory": "post",
                "poster_name": post.owner_username or "", "username": post.owner_username or "",
                "author": post.owner_username or "",
                "title": _clean(caption, 150), "content": _clean(caption, 150),
                "description": post.caption or "",
                "id": shortcode, "shortcode": shortcode, "post_shortcode": shortcode,
                "filename": key, "short_id": key, "num": num, "count": len(items),
                "date": post.date_utc.strftime("%Y-%m-%d %H:%M:%S") if post.date_utc else "",
                "extension": "mp4" if is_video else "jpg",
                "likes": post.likes, "type": "video" if is_video else "image",
            })
            if name and name in used_names:
                name = f"{name} [{key}]"
        name = _restricted(name or f"{safe_title} [{key}]")
        used_names.add(name)
        return name

    saved = 0
    fetched_one = False
    fresh_urls = {}  # filled only if saved links turn out to be expired, see below
    for num, is_video, media_url, _preview in items:
        key = f"{shortcode}_{num}" if multi else shortcode
        if key in done:
            continue
        if not media_url:
            emit(event("error", message=f"ERROR: Instagram didn't provide a download link for item {num} of this post"))
            continue
        max_bytes = _SETTINGS["max_filesize"]
        if max_bytes and sizes.get(media_url, 0) > max_bytes:
            continue  # skipped, not failed: the same as yt-dlp's and gallery-dl's max file size
        base = os.path.join(download_dir, item_name(num, is_video, key))
        # Settings > Downloads' sleep interval: a random 2-to-N second pause before each file but
        # the first, like yt-dlp's --min/--max-sleep-interval.
        if sleep_interval_seconds and fetched_one:
            time.sleep(random.uniform(2, max(2, int(sleep_interval_seconds))))
        fetched_one = True
        try:
            for leftover in (base + ext + temp for ext in _MEDIA_EXTENSIONS for temp in ("", ".temp")):
                if os.path.isfile(leftover):
                    os.remove(leftover)
            media_url = fresh_urls.get(num, media_url)
            try:
                loader.download_pic(base, media_url, post.date_utc)
            except _TooLarge:
                raise
            except Exception:  # noqa: BLE001
                if not from_cache:
                    raise
                # The preview's saved media links didn't work (expired): fetch the post fresh
                # once and use its links for this item and every one after it (fresh_urls is
                # looked up per item above — the loop itself keeps iterating the original list).
                loader, post, from_cache = _fetch_post(url, cookies_path, proxy_url, socket_timeout_seconds)
                fresh_urls.update({n: m for n, _v, m, _p in _media_items(post)})
                media_url = fresh_urls.get(num, media_url)
                loader.download_pic(base, media_url, post.date_utc)
        except _TooLarge:
            continue
        except Exception as exc:  # noqa: BLE001
            emit(event("error", message=f"ERROR: {_friendly_error(exc)}"))
            continue
        prefix = os.path.basename(base) + "."
        produced = sorted(n for n in os.listdir(download_dir)
                          if n.startswith(prefix) and os.path.splitext(n)[1].lower() in _MEDIA_EXTENSIONS)
        if not produced:
            continue
        _append_archive(archive_path, key)
        emit(event("file", path=os.path.abspath(os.path.join(download_dir, produced[0]))))
        saved += 1

    if write_info_files and saved:
        info_base = os.path.join(download_dir, _restricted(f"{safe_title} [{shortcode}]"))
        try:
            with open(info_base + ".description", "w", encoding="utf-8") as f:
                f.write(post.caption or "")
            with open(info_base + ".json", "w", encoding="utf-8") as f:
                json.dump(post._node, f, ensure_ascii=False, default=str)
        except Exception:  # noqa: BLE001 — sidecars are best-effort, the media already landed
            pass
    # Everything already in the archive (a resumed download that had finished) is still success.
    return "ok" if saved or not any((f"{shortcode}_{n}" if multi else shortcode) not in done for n, *_ in items) else "error"


def list_items(url, cookies_path=None, proxy_url=None, socket_timeout_seconds=None, info_cache_path=None):
    try:
        _loader, post, _cached = _fetch_post(url, cookies_path, proxy_url, socket_timeout_seconds)
        items = _media_items(post)
        # After _media_items, so the full metadata it may have fetched is saved along with it.
        _save_post(post, info_cache_path)
        return _list_json(post, items)
    except Exception as exc:  # noqa: BLE001
        message = _friendly_error(exc)
        return json.dumps([[-1, {"error": type(exc).__name__, "message": message}]])


def _list_json(post, items):
    caption = post.caption or ""
    # Asked from Instagram's media servers in parallel (a few seconds at most): Instaloader itself
    # doesn't list sizes, and the preview shows how big the download will be.
    sizes = net_resilience.content_lengths([media for _n, _v, media, _p in items])
    out = []
    for num, is_video, media_url, preview_url in items:
        # The preview image doubles as the picker thumbnail for a video item too — the extension
        # alone is what marks it as a video (VideoSiteRouter.isVideoFilename).
        out.append([3, preview_url, {
            "num": num,
            "filename": f"{post.shortcode}_{num}",
            "extension": "mp4" if is_video else "jpg",
            "description": caption,
            "username": post.owner_username,
            **({"filesize": sizes[media_url]} if media_url in sizes else {}),
        }])
    return json.dumps(out, ensure_ascii=False)


if __name__ == "__main__":
    # Captions and usernames routinely carry emoji; DownloadWorker reads this pipe as UTF-8, so
    # never let the interpreter's locale guess otherwise (and crash mid-download on one).
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

    def _emit(line):
        print(line, flush=True)

    if len(sys.argv) < 2:
        print("Usage: instaloader_wrapper.py <download|list> ...", file=sys.stderr)
        sys.exit(2)

    _cmd, _rest = sys.argv[1], sys.argv[2:]
    _rest += [""] * (17 - len(_rest))
    if _cmd == "download":
        _status = download(
            url=_rest[0], download_dir=_rest[1], cookies_path=_s(_rest[2]),
            item_filter=_s(_rest[3]), archive_path=_s(_rest[4]),
            write_info_files=(_rest[5] == "1"), proxy_url=_s(_rest[6]),
            socket_timeout_seconds=_s(_rest[7]), emit=_emit,
            info_cache_path=_s(_rest[8]),
            filename_format=_s(_rest[9]),
            sleep_interval_seconds=_s(_rest[10]),
            limit_rate=_s(_rest[11]), retries=_s(_rest[12]), max_filesize=_s(_rest[13]),
            restrict_filenames=(_rest[14] == "1"), force_ipv4=(_rest[15] == "1"),
            no_check_certificates=(_rest[16] == "1"),
        )
        print(event("exit", status=_status), flush=True)
    elif _cmd == "list":
        print(list_items(url=_rest[0], cookies_path=_s(_rest[1]), proxy_url=_s(_rest[2]),
                         socket_timeout_seconds=_s(_rest[3]), info_cache_path=_s(_rest[4])), flush=True)
    else:
        print(f"Unknown command: {_cmd}", file=sys.stderr)
        sys.exit(2)
