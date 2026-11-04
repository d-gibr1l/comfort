# Comprehensive Code Review: Instaloader Pipeline & Subsystem Integration

**Date:** 2026-10-20  
**Scope:** Review and vulnerability audit of the Instaloader integration, metadata retrieval, rate limit handling, session lifecycle, and download execution pipeline in the Comfort (`gallerydl`) Android application.

**Target Files Audited:**
- `app/src/main/assets/python_packages/instaloader_wrapper.py`
- `app/src/main/assets/python_packages/net_resilience.py`
- `app/src/main/assets/python_packages/custom_names.py`
- `app/src/main/java/com/comfort/app/worker/DownloadWorker.kt`
- `app/src/main/java/com/comfort/app/util/GalleryDlListing.kt`
- `app/src/main/java/com/comfort/app/worker/EnginePlan.kt`
- `app/src/main/java/com/comfort/app/data/CookieStore.kt`
- `app/src/main/java/com/comfort/app/data/VideoSiteRouter.kt`

---

## 1. Executive Summary

Instaloader serves as the primary engine for Instagram posts, reels, and carousels, selected because it can fetch public posts anonymously without hitting Instagram's immediate login redirect wall.

However, an exhaustive audit revealed **12 critical issues and design vulnerabilities**, including:
1. **Critical Privacy Leakage:** 100% of GraphQL API queries and binary CDN media downloads bypass user-configured proxies.
2. **Silent Carousel Data Loss:** Partial carousel downloads return `"ok"` to `DownloadWorker`, which marks incomplete jobs as `FINISHED` and permanently drops missing items.
3. **Artificial 15s Latency:** Instaloader defaults to `sleep=True`, adding randomized delays up to 15s before every API call.
4. **Session Cookie Rejection:** Strict case-sensitive `#HttpOnly_` checks discard cookies exported with lowercase `#httponly_`.
5. **Broken Routing for Clips & Audio:** Single posts requesting clip trimming or audio extraction get misrouted to `gallery-dl` instead of `yt-dlp`.
6. **Filesystem Crashes:** Unsanitized illegal FAT/Android characters (`:`, `?`, `"`, etc.) cause `[Errno 22] Invalid argument` crashes during file creation.

---

## 2. Execution Lifecycle: Metadata Retrieval vs Immediate Download

### Does Instaloader retrieve metadata first or start downloading immediately?
**It must retrieve metadata before any binary download can begin.**

```
[ Instagram URL: /p/<shortcode>/ ]
                │
                ▼
1. Extract Shortcode (regex)
                │
                ▼
2. Cache Check (info_cache_path) ──(Hit < 20m)──► Reuse Cached Post JSON
                │ (Miss)
                ▼
3. GraphQL API Call: Post.from_shortcode()  [Metadata Network Query]
   - Fetches JSON: owner, caption, carousel sidecar nodes, CDN media URLs
                │
                ▼
4. Pre-Download Size Probes: net_resilience.content_lengths()
   - Parallel HTTP HEAD requests to CDN URLs to compute total byte size
                │
                ▼
5. Stdout Metadata Events: [total], [size], [title], [thumbnail]
                │
                ▼
6. Binary Media Download: loader.download_pic()
   - Iterates through items and saves actual image/video streams to disk
```

Because the **metadata request is always the first network action**, any Instagram rate limiting (HTTP `429 Too Many Requests`) or login-wall challenge will strike at Step 3 before any media bytes are downloaded.

---

## 3. Detailed Audit Findings

### Finding 1: Complete Proxy Bypass (Real IP Leakage)
- **Severity:** Critical (Privacy & Security)
- **Files:** `instaloader_wrapper.py:125-127`, `net_resilience.py:247-258`
- **Root Causes:**
  1. **`copy_session()` in Instaloader:** Instaloader’s internal `copy_session()` helper (used for all GraphQL queries like `doc_id_graphql_query`, `graphql_query`, and iPhone API calls) creates a fresh `requests.Session()` and **does not copy `session.proxies`**. Setting `loader.context._session.proxies` only affects the root session.
  2. **`get_anonymous_session()` in Instaloader:** Media downloads (`loader.download_pic()`) instantiate an unproxied `requests.Session()`.
  3. **`net_resilience.content_lengths()`:** Pre-download size probes use `urllib.request.urlopen()` without passing a `ProxyHandler` or `proxy_url`.
- **Impact:** 100% of GraphQL metadata queries, pre-download file size probes, and binary media downloads bypass the user-configured proxy, exposing the user's real IP address to Instagram and Meta CDNs.

---

### Finding 2: Partial Carousel Failure Trap (Silent Data Loss)
- **Severity:** High (Data Loss & UX)
- **Files:** `instaloader_wrapper.py:325-346`, `DownloadWorker.kt:1030-1062`
- **Problem:**
  ```python
  return "ok" if saved or not any((f"{shortcode}_{n}" if multi else shortcode) not in done for n, *_ in items) else "error"
  ```
  - When a 10-item carousel is downloaded and item 1 succeeds but items 2–10 fail, `saved == 1`. The wrapper returns `"ok"`.
  - In `DownloadWorker.kt`, `savedCount.get() == 1 != 0`. The worker considers the download a complete success and marks it `DownloadStatus.FINISHED`.
  - Furthermore, `_append_archive()` appends to disk but never updates the in-memory `done` set during the loop.
- **Impact:** 9 out of 10 media items are silently lost. The user receives a "Download finished" notification and is never offered a retry option.

---

### Finding 3: Artificial 15s Latency from Default `sleep=True`
- **Severity:** Medium-High (Performance)
- **File:** `instaloader_wrapper.py:106-117`
- **Problem:**
  `Instaloader(...)` is instantiated without explicitly passing `sleep=False`. Instaloader defaults to `sleep: bool = True`. In `InstaloaderContext.do_sleep()`, it executes `time.sleep(min(random.expovariate(0.6), 15.0))` before every GraphQL / API call.
- **Impact:** Adds random delays of up to 15 seconds to metadata retrieval before the download even begins.

---

### Finding 4: Case-Sensitive `#HttpOnly_` Parsing Discards Session Cookies
- **Severity:** Medium-High (Reliability)
- **Files:** `instaloader_wrapper.py:85-88`, `CookieStore.kt:168-170`
- **Problem:**
  Netscape cookie format exporters (browser extensions, curl, Python `http.cookiejar`) frequently output lowercase `#httponly_`. The parser uses `line.startswith("#HttpOnly_")`.
- **Impact:** Lowercase lines fail the check and get treated as comments (`elif line.startswith("#"): continue`). Because Instagram's `sessionid` is strictly HttpOnly, valid user sessions are silently discarded, forcing the app into anonymous mode and failing on private/age-restricted posts.

---

### Finding 5: Broken Routing for Trimmed Clips and Audio-Only Extraction
- **Severity:** Medium-High (Functional Defect)
- **File:** `DownloadWorker.kt:337-339`
- **Problem:**
  ```kotlin
  val wantsYtDlpOnlyProcessing = !entity?.clipRange.isNullOrBlank() ||
      (entity?.videoQuality ?: GalleryDlPreferences.getVideoQuality(applicationContext).name) == VideoQuality.AUDIO_ONLY.name
  val engine = VideoSiteRouter.resolveEngine(applicationContext, url).let {
      if (it == DownloadEngine.INSTALOADER && wantsYtDlpOnlyProcessing) VideoSiteRouter.classify(url) else it
  }
  ```
  - When `wantsYtDlpOnlyProcessing` is true for an Instagram post URL (`/p/<shortcode>`), it calls `VideoSiteRouter.classify(url)`.
  - In `VideoSiteRouter.kt`, `/p/` classifies as `DownloadEngine.GALLERY_DL` (only `/reel/` classifies as `YT_DLP`).
  - `gallery-dl` runs with videos excluded and cannot trim clips or extract audio.
- **Impact:** Trimming requests or audio conversions fail, or download untrimmed videos.

---

### Finding 6: Premature Abort on Authenticated Rate Limits
- **Severity:** Medium (Reliability)
- **File:** `instaloader_wrapper.py:182-183`
- **Problem:**
  In `_fetch_post()`, `except _RateLimited: raise` is placed inside the loop over `attempts = [session, None]`.
- **Impact:** Instagram frequently throttles authenticated accounts with 429s while anonymous requests still succeed. Raising `_RateLimited` immediately kills the download without ever attempting anonymous retrieval.

---

### Finding 7: Incomplete `.temp` File Cleanup & Media Misattribution
- **Severity:** Medium (File Corruption)
- **File:** `instaloader_wrapper.py:328-333`
- **Problem:**
  `produced = [n for n in os.listdir(download_dir) if n.startswith(prefix)]`
  Instaloader writes to `<filename>.temp` before renaming. The leftover cleanup at line 309 does not clean `.temp` files.
- **Impact:** If an earlier download was interrupted, `os.listdir()` can pick `produced[0]` as `<base>.mp4.temp` or sidecar files (`.json`, `.description`), emitting an incomplete or wrong file to `DownloadWorker`.

---

### Finding 8: Illegal Android Filename Characters in `custom_names.py`
- **Severity:** Medium (Crash on Save)
- **File:** `custom_names.py:30`
- **Problem:**
  `custom_names.render()` only sanitizes forward/backslashes (`[\\/]+`). Android storage (FAT/FUSE) strictly rejects `: * ? " < > |`.
- **Impact:** Captions or titles with colons, quotes, or question marks trigger `OSError: [Errno 22] Invalid argument` when creating files, aborting the download.

---

### Finding 9: Missing Null-Check on `post.date_utc`
- **Severity:** Low-Medium (Crash)
- **File:** `instaloader_wrapper.py:314`
- **Problem:**
  If Instagram metadata omits the timestamp, `post.date_utc` is `None`. `loader.download_pic()` passes it to `os.utime()`, causing `AttributeError: 'NoneType' object has no attribute 'timestamp'`.

---

### Finding 10: Missing Video Supplement Pass for Mixed Carousels
- **Severity:** Medium (Data Loss)
- **File:** `EnginePlan.kt:180-188`
- **Problem:**
  Unlike `GalleryDlFirst` (which has `supplementVideo = true` to run `yt-dlp` for missing video streams), Instaloader has no video supplement fallback.
- **Impact:** On Instagram carousels where web GraphQL omits `video_url` for video carousel nodes, Instaloader skips the video items and logs `"didn't provide a download link"`, leaving the video files permanently missing.

---

### Finding 11: Unhandled Top-Level Exceptions & Error Masking
- **Severity:** Low-Medium (Telemetry & Error Clarity)
- **File:** `instaloader_wrapper.py:249-273, 358-374`
- **Problem:**
  In `download()`, lines 249–273 (`_parse_item_filter`, `_read_archive`, `net_resilience.content_lengths`, `_display_title`, `os.makedirs`) are outside `try...except`. In `list_items()`, lines 358–374 are outside `try...except`.
- **Impact:** Unhandled Python exceptions dump raw stack traces to stderr. `EngineEventParser` does not recognize them, and `DownloadWorker` reports generic `"No downloadable content found at this link"`, hiding the real cause.

---

### Finding 12: Memory vs Disk Cache Disconnect on Cookie Updates
- **Severity:** Low (Stale Cache)
- **Files:** `GalleryDlListing.kt:247-248`, `instaloader_wrapper.py:50`
- **Problem:**
  `GalleryDlListing` maintains `listingCache` (20-minute memory TTL) without observing `CookieStore.version`.
- **Impact:** If a user adds Instagram cookies after opening a link, `listingCache` continues returning the anonymous preview for 20 minutes unless the app process restarts.

---

## 4. Concrete Remediation Implementations

### Patch 1: Universal Proxy Configuration (`instaloader_wrapper.py`)
```python
def _configure_proxy(proxy_url):
    if not proxy_url:
        return
    # 1. Environment variables configure all requests.Session() and urllib.request calls
    os.environ["http_proxy"] = proxy_url
    os.environ["https_proxy"] = proxy_url
    os.environ["HTTP_PROXY"] = proxy_url
    os.environ["HTTPS_PROXY"] = proxy_url

    # 2. Patch Instaloader session factories for defense-in-depth
    proxies = {"http": proxy_url, "https": proxy_url}
    
    orig_copy_session = instaloader.instaloadercontext.copy_session
    def _proxied_copy_session(session, request_timeout=None):
        new_sess = orig_copy_session(session, request_timeout)
        new_sess.proxies = proxies
        return new_sess
    instaloader.instaloadercontext.copy_session = _proxied_copy_session

def _new_loader(session_cookies, proxy_url, timeout_seconds):
    _configure_proxy(proxy_url)
    loader = Instaloader(
        quiet=True,
        sleep=False,  # Patch 3: Eliminate artificial delay
        download_pictures=False, download_videos=False, download_video_thumbnails=False,
        save_metadata=False, compress_json=False,
        max_connection_attempts=3,
        request_timeout=float(timeout_seconds) if timeout_seconds else 20.0,
        rate_controller=lambda ctx: _FailFastRateController(ctx),
    )
    if proxy_url:
        loader.context._session.proxies = {"http": proxy_url, "https": proxy_url}
        orig_anon = loader.context.get_anonymous_session
        def _proxied_anon():
            s = orig_anon()
            s.proxies = {"http": proxy_url, "https": proxy_url}
            return s
        loader.context.get_anonymous_session = _proxied_anon

    if session_cookies and session_cookies.get("sessionid"):
        cookies = dict(session_cookies)
        cookies.setdefault("csrftoken", "")
        loader.load_session(cookies.get("ds_user_id") or "user", cookies)
    return loader
```

---

### Patch 2: Carousel Completion & `.temp` File Exclusion (`instaloader_wrapper.py`)
```python
MEDIA_EXTENSIONS = {".mp4", ".jpg", ".jpeg", ".png", ".webp", ".heic"}

# Inside download() loop:
for num, is_video, media_url, _preview in items:
    key = f"{shortcode}_{num}" if multi else shortcode
    if key in done:
        continue
    if not media_url:
        emit(event("error", message=f"ERROR: Instagram didn't provide a download link for item {num} of this post"))
        continue
    base = os.path.join(download_dir, item_name(num, is_video, key))
    try:
        # Clean leftovers including .temp
        for ext in (".mp4", ".jpg", ".png", ".webp", ".heic", ".mp4.temp", ".jpg.temp"):
            leftover = base + ext
            if os.path.isfile(leftover):
                os.remove(leftover)

        # Download with safe mtime fallback (Patch 9)
        mtime = post.date_utc or datetime.now(timezone.utc)
        loader.download_pic(base, media_url, mtime)
    except Exception as exc:
        emit(event("error", message=f"ERROR: {_friendly_error(exc)}"))
        continue

    prefix = os.path.basename(base) + "."
    produced = sorted([
        n for n in os.listdir(download_dir)
        if n.startswith(prefix) and not n.endswith(".temp") and os.path.splitext(n)[1].lower() in MEDIA_EXTENSIONS
    ])
    if not produced:
        continue
    _append_archive(archive_path, key)
    done.add(key)  # Maintain in-memory archive set
    emit(event("file", path=os.path.abspath(os.path.join(download_dir, produced[0]))))
    saved += 1

# Return ok only when all wanted items are completed
all_completed = all((f"{shortcode}_{n}" if multi else shortcode) in done for n, *_ in items)
return "ok" if all_completed else "error"
```

---

### Patch 3: Case-Insensitive `#HttpOnly_` Parsing (`instaloader_wrapper.py` & `CookieStore.kt`)
```python
line = raw.rstrip("\r\n")
if line.lower().startswith("#httponly_"):
    line = line[10:]
elif not line or line.startswith("#"):
    continue

parts = line.split("\t")
if len(parts) >= 7 and (parts[0].lstrip(".").lower() == "instagram.com" or parts[0].lstrip(".").lower().endswith(".instagram.com")):
    try:
        expiry = int(parts[4])
    except ValueError:
        expiry = 0
    now = int(time.time())
    if expiry != 0 and expiry < now:
        continue  # Skip expired cookie
    expiry = expiry or 2**62
    if expiry >= expiries.get(parts[5], -1):
        expiries[parts[5]] = expiry
        cookies[parts[5]] = parts[6]
```

---

### Patch 4: Routing Fix for Clips & Audio-Only (`DownloadWorker.kt`)
```kotlin
// Line 337-339 in DownloadWorker.kt:
val wantsYtDlpOnlyProcessing = !entity?.clipRange.isNullOrBlank() ||
    (entity?.videoQuality ?: GalleryDlPreferences.getVideoQuality(applicationContext).name) == VideoQuality.AUDIO_ONLY.name
val engine = VideoSiteRouter.resolveEngine(applicationContext, url).let {
    if (it == DownloadEngine.INSTALOADER && wantsYtDlpOnlyProcessing) DownloadEngine.YT_DLP else it
}
```

---

### Patch 5: Illegal Character Sanitization (`custom_names.py`)
```python
# Line 30 in custom_names.py:
# Replace illegal FAT / Android VFS characters (: * ? " < > | \ /)
name = re.sub(r'[\\/:*?"<>|\r\n\t]+', '_', name).strip().rstrip(".")
```
