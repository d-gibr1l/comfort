"""Spotify link support (track/album/playlist). Spotify's own streams are DRM-protected — this
module never touches them at all. Instead it scrapes Spotify's own *public* embed pages for real
metadata (title, artist(s), album, cover art) with zero API credentials, then searches YouTube for
the matching track and delegates the actual download to yt_dlp_wrapper.py's own already-built
download() — inheriting its aria2c progress polling, jpg-sibling thumbnail preference,
EmbedThumbnail fallback, and "best"-codec audio fix for free instead of reimplementing any of it.

The real `spotdl` PyPI package was investigated and deliberately not bundled: its own
pyproject.toml pulls in 21 dependencies, including a full unneeded FastAPI/uvicorn/websockets/
jinja2 web-server stack (spotDL's own built-in web UI) and pykakasi (Japanese romanization, likely
with no prebuilt Android wheel) — a large APK-size and cross-compilation risk this app's
deliberately minimal embedded Python (see PythonRuntime.kt) isn't worth taking on. spotDL itself
only ever accepts Spotify links as input and searches YouTube exclusively as the actual audio
source (confirmed via its own docs) — this module does exactly that, at a fraction of the
dependency cost.

Confirmed live (via curl) that these two unauthenticated Spotify endpoints carry everything
needed:
  - https://open.spotify.com/oembed?url=<track-url> -> {"title", "thumbnail_url"} (cover art).
  - https://open.spotify.com/embed/{track,album,playlist}/<id> -> an embedded
    "__NEXT_DATA__" JSON script tag at props.pageProps.state.data.entity, giving a track's own
    "artists"/"duration", or an album/playlist's "name" plus its full "trackList" (each entry:
    "title", "subtitle" [artist names], "uri" [spotify:track:<id>]).
Album name specifically is NOT exposed on a single track's own embed page (only artist + title +
duration) — a track link's own "album" therefore stays whatever the matched YouTube result itself
reports (same fallback yt_dlp_wrapper.py's own [album] line already tolerates), while an
album/playlist link's tracks all correctly get the real containing album name for free.
"""

import json
import os
import re
import ssl
import urllib.parse
import urllib.request

import yt_dlp
import yt_dlp_wrapper

_SPOTIFY_URL_RE = re.compile(r"open\.spotify\.com/(track|album|playlist)/([a-zA-Z0-9]+)")

_CACERT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cacert.pem")


def _ssl_context():
    # Same reasoning as yt_dlp_wrapper.py's own aria2c-size-probe and thumbnail-jpg-fetch fixes
    # this session: this embedded Python's default SSL context has no CA trust store wired in at
    # all, so a bare urlopen() call fails every time with "unable to get local issuer certificate"
    # unless explicitly pointed at the same bundled cacert.pem every other network fix here uses.
    if os.path.exists(_CACERT_PATH):
        try:
            return ssl.create_default_context(cafile=_CACERT_PATH)
        except Exception:
            return None
    return None


def _fetch(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=timeout, context=_ssl_context()) as resp:
        return resp.read()


def _resolve_redirect(url):
    """spotify.link/bare spotify.com share links 302-redirect to the real open.spotify.com URL —
    resolved here before parsing since those are the URLs actually shared from the Spotify app."""
    if "open.spotify.com" in url:
        return url
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}, method="HEAD")
        with urllib.request.urlopen(req, timeout=10, context=_ssl_context()) as resp:
            return resp.geturl()
    except Exception:
        return url


def _fetch_entity(entity_type, entity_id):
    """Scrapes the "__NEXT_DATA__" JSON out of Spotify's own public embed page — see this
    module's own top comment for the exact path/shape confirmed live."""
    html = _fetch(f"https://open.spotify.com/embed/{entity_type}/{entity_id}").decode("utf-8", "replace")
    match = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    if not match:
        raise ValueError("Spotify embed page structure not recognized")
    data = json.loads(match.group(1))
    return data["props"]["pageProps"]["state"]["data"]["entity"]


def _get_track_metadata(track_id):
    """Returns (title, artist, duration_seconds, thumbnail_url) for one Spotify track, from the
    two public endpoints described in this module's own top comment. Any field this can't
    determine comes back None — callers tolerate that the same way the rest of this app's audio
    pipeline tolerates missing metadata, rather than failing the whole track over it."""
    track_url = f"https://open.spotify.com/track/{track_id}"
    title = artist = thumbnail = None
    duration = None
    try:
        oembed = json.loads(_fetch(f"https://open.spotify.com/oembed?url={track_url}"))
        title = oembed.get("title")
        thumbnail = oembed.get("thumbnail_url")
    except Exception:
        pass
    try:
        entity = _fetch_entity("track", track_id)
        title = title or entity.get("name")
        artists = entity.get("artists") or []
        artist = ", ".join(a.get("name") for a in artists if a.get("name")) or None
        duration_ms = entity.get("duration")
        if duration_ms:
            duration = duration_ms / 1000
    except Exception:
        pass
    return title, artist, duration, thumbnail


def list_info(url, cookies_path=None, extra_args=None, js_runtime_path=None, tls_client_path=None):
    """Same JSON contract as yt_dlp_wrapper.py's own list_info() — a single track is
    {"title", "thumbnail", "uploader" (artist), "filesize", "duration", "url",
    "requested_formats"}; an album/playlist is {"entries": [...]} in that same per-item shape —
    so the existing share-sheet/preview-sheet UI, built for that exact shape, needs no changes to
    render a Spotify listing. Per-track thumbnails are deliberately left null in an album/playlist
    listing (an oEmbed call per track just for a preview doesn't scale to a large playlist); each
    track's own cover art is still fetched for real during the actual download, where it matters."""
    try:
        url = _resolve_redirect(url)
        match = _SPOTIFY_URL_RE.search(url)
        if not match:
            return json.dumps({"error": "Not a recognized Spotify link"})
        entity_type, entity_id = match.group(1), match.group(2)

        if entity_type == "track":
            title, artist, duration, thumbnail = _get_track_metadata(entity_id)
            return json.dumps({
                "title": title, "thumbnail": thumbnail, "uploader": artist,
                "filesize": None, "duration": duration, "url": url, "requested_formats": None,
            })

        entity = _fetch_entity(entity_type, entity_id)
        entries = []
        for track in entity.get("trackList") or []:
            track_id = (track.get("uri") or "").rsplit(":", 1)[-1]
            if not track_id:
                continue
            duration_ms = track.get("duration")
            entries.append({
                "title": track.get("title"),
                "thumbnail": None,
                "uploader": track.get("subtitle"),
                "filesize": None,
                "duration": (duration_ms / 1000) if duration_ms else None,
                "url": f"https://open.spotify.com/track/{track_id}",
                "requested_formats": None,
            })
        return json.dumps({"entries": entries})
    except Exception as e:
        return json.dumps({"error": str(e)})


def _resolve_youtube_match(artist, title, js_runtime_path):
    """Top hit of a YouTube Music "songs" search for "<artist> <title>" — yt-dlp's own
    YoutubeMusicSearchURLIE (extractor/youtube/_search.py), invoked via the real
    music.youtube.com/search URL it matches (this extractor has no "ytmsearchN:" pseudo-URL
    shorthand the way plain YoutubeSearchIE has "ytsearchN:" — confirmed by reading its source,
    it only defines _VALID_URL, not _SEARCH_KEY). The "sp=" param is YouTube Music's own encoded
    filter for the "songs" section specifically (see the extractor's own _SECTIONS map) — this
    resolves to the actual studio-audio "Artist - Topic" upload nearly every Spotify-catalog
    track has on YouTube Music, rather than a plain YouTube search's mix of official/lyric-video/
    fan uploads (often longer, re-encoded, or with intro/outro content not on the studio track).
    extract_flat + playlist_items="1" keeps this to resolving just the top hit's URL, instead of
    fully extracting every result on the page. Returns a real, directly downloadable webpage_url,
    or None if nothing resolved."""
    ydl_opts = {
        "quiet": True, "no_warnings": True, "skip_download": True,
        "extract_flat": "in_playlist", "playlist_items": "1",
    }
    if js_runtime_path:
        ydl_opts["js_runtimes"] = {"quickjs": {"path": js_runtime_path}}
    query_string = urllib.parse.urlencode({
        "q": f"{artist} {title}",
        "sp": "EgWKAQIIAWoKEAoQAxAEEAkQBQ==",
    })
    url = f"https://music.youtube.com/search?{query_string}"
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception:
        return None
    if not info:
        return None
    entries = info.get("entries")
    entry = entries[0] if entries else info
    if not entry:
        return None
    return entry.get("webpage_url") or entry.get("original_url") or entry.get("url")


def _embed_cover_art(filepath, cover_data):
    """Format-specific — mutagen's "easy" tag interface (used for text tags below) has no uniform
    cover API, so this mirrors embedthumbnail.py's own mutagen usage for the formats this app's
    own audio pipeline actually produces (m4a is by far the common case — see yt_dlp_wrapper.py's
    own preferredcodec="best" doc comment). Anything else is skipped gracefully, same "cosmetic
    gap, not worth failing the download over" tolerance already used throughout this app's audio
    handling."""
    ext = os.path.splitext(filepath)[1].lower()
    try:
        if ext in (".m4a", ".mp4"):
            from mutagen.mp4 import MP4, MP4Cover
            audio = MP4(filepath)
            audio["covr"] = [MP4Cover(cover_data, imageformat=MP4Cover.FORMAT_JPEG)]
            audio.save()
        elif ext == ".mp3":
            from mutagen.id3 import ID3, APIC
            audio = ID3(filepath)
            audio.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Cover", data=cover_data))
            audio.save()
    except Exception:
        pass


def _retag_with_spotify_metadata(filepath, title, artist, album, thumbnail_url):
    """Overrides whatever the matched YouTube video's own metadata/thumbnail ended up embedded
    with — Spotify's real tags are more authoritative than a YouTube upload's own, often much
    messier, title/uploader fields."""
    try:
        import mutagen
        audio = mutagen.File(filepath, easy=True)
        if audio is not None:
            if title:
                audio["title"] = title
            if artist:
                audio["artist"] = artist
            if album:
                audio["album"] = album
            audio.save()
    except Exception:
        pass

    if not thumbnail_url:
        return
    try:
        cover_data = _fetch(thumbnail_url)
    except Exception:
        return
    _embed_cover_art(filepath, cover_data)


def download(url, download_dir, cookies_path=None, callback=None, filename_format=None,
             archive_path=None, js_runtime_path=None, ffmpeg_path=None, ffmpeg_lib_dir=None,
             aria2_path=None, aria2_lib_dir=None, restrict_filenames=True, trim_filenames=True,
             verbose=False, tls_client_path=None):
    """One call per Spotify link (track, or every track in an album/playlist in turn). Each
    track's own final-file callback line comes straight from the inner yt_dlp_wrapper.download()
    call unchanged, so DownloadWorker.kt's existing bare-filepath/[progress]/[size] handling needs
    no Spotify-specific protocol at all — multi-item counting works exactly the way a multi-item
    gallery-dl download already does."""
    try:
        url = _resolve_redirect(url)
        match = _SPOTIFY_URL_RE.search(url)
        if not match:
            if callback:
                callback(f"[error] Not a recognized Spotify link: {url}")
            return "Error: not a recognized Spotify link"
        entity_type, entity_id = match.group(1), match.group(2)

        if entity_type == "track":
            track_ids = [entity_id]
            album_name = None
        else:
            entity = _fetch_entity(entity_type, entity_id)
            album_name = entity.get("name") if entity_type == "album" else None
            track_ids = []
            for track in entity.get("trackList") or []:
                track_id = (track.get("uri") or "").rsplit(":", 1)[-1]
                if track_id:
                    track_ids.append(track_id)

        if not track_ids:
            if callback:
                callback("[error] No tracks found at this Spotify link")
            return "Error: no tracks found"

        any_success = False
        for track_id in track_ids:
            title, artist, _duration, thumbnail_url = _get_track_metadata(track_id)
            if not title or not artist:
                if callback:
                    callback("[error] Couldn't resolve metadata for a track — skipping")
                continue

            # Spotify's own metadata is authoritative — sent BEFORE delegating to
            # yt_dlp_wrapper.download() below, so its own *IfAbsent DAO writes (driven by
            # whatever the matched YouTube video's own info_dict reports) never overwrite this
            # once it lands in the DB (see DownloadDao's own doc comment on why *IfAbsent exists).
            if callback:
                callback(f"[artist] {artist[:200]}")
                if album_name:
                    callback(f"[album] {album_name[:200]}")

            matched_url = _resolve_youtube_match(artist, title, js_runtime_path)
            if not matched_url:
                if callback:
                    callback(f'[error] No YouTube match found for "{artist} - {title}"')
                continue

            captured_path = [None]

            # Retagging has to happen HERE — before forwarding the line on to _cb — not after
            # yt_dlp_wrapper.download() returns like a first version of this did. _cb is
            # DownloadWorker.kt's own actualCallback: the moment it receives this exact bare
            # absolute-path line, it moves the file into MediaStore and deletes the staging
            # copy (see its own doc comment on the file-path branch) — synchronously, since the
            # Python->Kotlin callback bridge blocks until that suspend function returns. Calling
            # _cb(line) first, then retagging captured_path[0] afterward, was retagging a file
            # that either no longer existed or (worse) had already been read into MediaStore
            # with its untagged bytes — reproduced live: mutagen's write silently no-op'd or
            # landed too late, and MediaStore's own scanned metadata for the saved file showed
            # no real artist/album at all. Tagging the file on disk before it ever gets handed
            # to Kotlin is the only ordering that can actually work.
            def _capture_and_forward(line, _cb=callback, _out=captured_path):
                if line and not line.startswith("[") and os.path.isabs(line) and os.path.exists(line):
                    _out[0] = line
                    _retag_with_spotify_metadata(line, title, artist, album_name, thumbnail_url)
                if _cb:
                    _cb(line)

            status = yt_dlp_wrapper.download(
                url=matched_url, download_dir=download_dir, cookies_path=cookies_path,
                callback=_capture_and_forward, filename_format=filename_format,
                archive_path=archive_path, js_runtime_path=js_runtime_path,
                ffmpeg_path=ffmpeg_path, ffmpeg_lib_dir=ffmpeg_lib_dir,
                audio_only=True, restrict_filenames=restrict_filenames,
                trim_filenames=trim_filenames, verbose=verbose,
                aria2_path=aria2_path, aria2_lib_dir=aria2_lib_dir,
                tls_client_path=tls_client_path,
            )
            if status != "Done":
                continue
            any_success = True

        return "Done" if any_success else "Error: no tracks downloaded"
    except Exception as e:
        if callback:
            callback(f"[error] {e}")
        return f"Error: {e}"


# CLI entry point for PythonRuntime.kt, same subprocess/argv-convention as yt_dlp_wrapper.py's own
# __main__ block: argv is all strings, "" is this module's own "None" sentinel.
if __name__ == "__main__":
    import sys as _sys

    def _s(v):
        return None if v == "" else v

    def _b(v):
        return v == "1"

    def _emit(line):
        print(line, flush=True)

    if len(_sys.argv) < 2 or _sys.argv[1] not in ("download", "list"):
        print("Usage: spotify_wrapper.py download <13 positional args> | list <4 positional args>", file=_sys.stderr)
        _sys.exit(2)

    if _sys.argv[1] == "list":
        a = _sys.argv[2:]
        print(list_info(
            url=a[0], cookies_path=_s(a[1]), extra_args=_s(a[2]), js_runtime_path=_s(a[3]),
            tls_client_path=(_s(a[4]) if len(a) > 4 else None),
        ), flush=True)
        _sys.exit(0)

    a = _sys.argv[2:]
    status = download(
        url=a[0], download_dir=a[1], cookies_path=_s(a[2]), callback=_emit,
        filename_format=_s(a[3]), archive_path=_s(a[4]), js_runtime_path=_s(a[5]),
        ffmpeg_path=_s(a[6]), ffmpeg_lib_dir=_s(a[7]), aria2_path=_s(a[8]),
        aria2_lib_dir=_s(a[9]), restrict_filenames=_b(a[10]) if len(a) > 10 else True,
        trim_filenames=_b(a[11]) if len(a) > 11 else True,
        verbose=_b(a[12]) if len(a) > 12 else False,
        tls_client_path=_s(a[13]) if len(a) > 13 else None,
    )
    print(f"[__status__] {status}", flush=True)
