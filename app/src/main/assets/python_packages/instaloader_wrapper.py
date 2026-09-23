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
import re
import sys
import time

import instaloader
from instaloader import Instaloader, Post, RateController

_SHORTCODE_RE = re.compile(
    r"instagram\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", re.IGNORECASE,
)
_ITEM_FILTER_RE = re.compile(r"\{([\d,\s]+)\}")
_BAD_FILENAME_CHARS = re.compile(r'[\\/:*?"<>|\r\n\t]+')
# Rate-limit waits longer than this abort instead of sleeping: Instaloader's own default is to wait
# out a 429 for as long as Instagram asks (often many minutes), which would leave a download
# sitting at "Downloading..." with nothing happening. Failing fast hands it to the classic
# gallery-dl/yt-dlp fallback instead.
_MAX_RATE_LIMIT_WAIT_SECONDS = 20


class _RateLimited(Exception):
    pass


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
        download_pictures=False, download_videos=False, download_video_thumbnails=False,
        save_metadata=False, compress_json=False,
        max_connection_attempts=2,
        request_timeout=float(timeout_seconds) if timeout_seconds else 60.0,
        rate_controller=lambda ctx: _FailFastRateController(ctx),
    )
    if session_cookies and session_cookies.get("sessionid"):
        cookies = dict(session_cookies)
        # load_session() reads csrftoken unconditionally for its X-CSRFToken header.
        cookies.setdefault("csrftoken", "")
        # Any non-empty username marks the context as logged in; the real username isn't needed
        # to fetch a post, and test_login() would cost an extra request against the rate limit.
        loader.load_session(cookies.get("ds_user_id") or "user", cookies)
    if proxy_url:
        loader.context._session.proxies = {"http": proxy_url, "https": proxy_url}
    return loader


def _friendly_error(exc):
    if isinstance(exc, _RateLimited):
        return "Instagram is rate-limiting requests right now — try again in a few minutes"
    if isinstance(exc, instaloader.exceptions.LoginRequiredException):
        return "Instagram requires a logged-in session for this post — add Instagram cookies in Settings > Cookies & Login"
    if isinstance(exc, instaloader.exceptions.BadResponseException):
        return "Instagram didn't return this post — it may be private, age-restricted, or need a logged-in session"
    if isinstance(exc, instaloader.exceptions.ConnectionException):
        return f"Couldn't reach Instagram: {exc}"
    return f"{type(exc).__name__}: {exc}"


def _fetch_post(url, cookies_path, proxy_url, timeout_seconds):
    """(loader, post). Tries the saved Instagram session first when there is one, then anonymously
    — an expired session fails outright where a public post would have worked without it."""
    shortcode = shortcode_of(url)
    if not shortcode:
        raise ValueError("Not an Instagram post or reel link")
    session = _read_instagram_cookies(cookies_path)
    attempts = [session, None] if session.get("sessionid") else [None]
    last_exc = None
    for cookies in attempts:
        loader = _new_loader(cookies, proxy_url, timeout_seconds)
        try:
            return loader, Post.from_shortcode(loader.context, shortcode)
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
             write_info_files=False, proxy_url=None, socket_timeout_seconds=None, emit=print):
    try:
        loader, post = _fetch_post(url, cookies_path, proxy_url, socket_timeout_seconds)
        items = _media_items(post)
    except Exception as exc:  # noqa: BLE001
        emit(f"[error] ERROR: {_friendly_error(exc)}")
        return "error"

    wanted = _parse_item_filter(item_filter)
    if wanted is not None:
        items = [it for it in items if it[0] in wanted]
    done = _read_archive(archive_path)
    shortcode = post.shortcode
    multi = post.typename == "GraphSidecar"

    emit(f"[total] {len(items)}")
    title = _display_title(post)
    emit(f"[title] {title}")
    if post.url:
        emit(f"[thumbnail] {post.url}")

    os.makedirs(download_dir, exist_ok=True)
    # Same "poster - caption [unique-id].ext" shape as the other engines' default filename format,
    # so DownloadWorker's derivePosterCaptionTitle recovers the same display title from it.
    safe_title = _clean(title, 90) or shortcode
    saved = 0
    for num, is_video, media_url, _preview in items:
        key = f"{shortcode}_{num}" if multi else shortcode
        if key in done:
            continue
        if not media_url:
            emit(f"[error] ERROR: Instagram didn't provide a download link for item {num} of this post")
            continue
        base = os.path.join(download_dir, f"{safe_title} [{key}]")
        try:
            for leftover in (base + ext for ext in (".mp4", ".jpg", ".png", ".webp", ".heic")):
                if os.path.isfile(leftover):
                    os.remove(leftover)
            loader.download_pic(base, media_url, post.date_utc)
        except Exception as exc:  # noqa: BLE001
            emit(f"[error] ERROR: {_friendly_error(exc)}")
            continue
        prefix = os.path.basename(base) + "."
        produced = [n for n in os.listdir(download_dir) if n.startswith(prefix)]
        if not produced:
            continue
        _append_archive(archive_path, key)
        emit(os.path.abspath(os.path.join(download_dir, produced[0])))
        saved += 1

    if write_info_files and saved:
        info_base = os.path.join(download_dir, f"{safe_title} [{shortcode}]")
        try:
            with open(info_base + ".description", "w", encoding="utf-8") as f:
                f.write(post.caption or "")
            with open(info_base + ".json", "w", encoding="utf-8") as f:
                json.dump(post._node, f, ensure_ascii=False, default=str)
        except Exception:  # noqa: BLE001 — sidecars are best-effort, the media already landed
            pass
    # Everything already in the archive (a resumed download that had finished) is still success.
    return "ok" if saved or not any((f"{shortcode}_{n}" if multi else shortcode) not in done for n, *_ in items) else "error"


def list_items(url, cookies_path=None, proxy_url=None, socket_timeout_seconds=None):
    try:
        _loader, post = _fetch_post(url, cookies_path, proxy_url, socket_timeout_seconds)
        items = _media_items(post)
    except Exception as exc:  # noqa: BLE001
        message = _friendly_error(exc)
        return json.dumps([[-1, {"error": type(exc).__name__, "message": message}]])
    caption = post.caption or ""
    out = []
    for num, is_video, _media_url, preview_url in items:
        # The preview image doubles as the picker thumbnail for a video item too — the extension
        # alone is what marks it as a video (VideoSiteRouter.isVideoFilename).
        out.append([3, preview_url, {
            "num": num,
            "filename": f"{post.shortcode}_{num}",
            "extension": "mp4" if is_video else "jpg",
            "description": caption,
            "username": post.owner_username,
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
    _rest += [""] * (8 - len(_rest))
    if _cmd == "download":
        _status = download(
            url=_rest[0], download_dir=_rest[1], cookies_path=_s(_rest[2]),
            item_filter=_s(_rest[3]), archive_path=_s(_rest[4]),
            write_info_files=(_rest[5] == "1"), proxy_url=_s(_rest[6]),
            socket_timeout_seconds=_s(_rest[7]), emit=_emit,
        )
        print(f"[__status__] {_status}", flush=True)
    elif _cmd == "list":
        print(list_items(url=_rest[0], cookies_path=_s(_rest[1]), proxy_url=_s(_rest[2]),
                         socket_timeout_seconds=_s(_rest[3])), flush=True)
    else:
        print(f"Unknown command: {_cmd}", file=sys.stderr)
        sys.exit(2)
