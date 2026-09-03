import json
import os
import re
import time
import yt_dlp

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

def _parse_rate(limit_rate):
    """Converts gallery-dl-style rate strings ("500k", "2M") into yt-dlp's expected
    bytes-per-second integer, so both engines can share the same Settings > Speed limit field."""
    if not limit_rate:
        return None
    match = re.match(r"^\s*([\d.]+)\s*([kKmMgG]?)\s*$", limit_rate)
    if not match:
        return None
    value = float(match.group(1))
    unit = match.group(2).lower()
    multiplier = {"": 1, "k": 1024, "m": 1024 * 1024, "g": 1024 * 1024 * 1024}[unit]
    return int(value * multiplier)

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

def download(url, download_dir, cookies_path=None, callback=None, filename_format=None,
             extra_args=None, archive_path=None, limit_rate=None, format_selector=None,
             should_cancel=None, js_runtime_path=None, ffmpeg_path=None,
             audio_only=False, download_subtitles=False, subtitle_langs=None,
             embed_thumbnail=False, embed_metadata=False, no_playlist=True,
             resolution_cap=None, output_format=None, retries=None, playlist_items=None):
    """Downloads a video via yt-dlp's embeddable YoutubeDL API — deliberately not yt_dlp.main(),
    which (like gallery-dl's CLI entry point) reads sys.argv, a process-global that two
    concurrent calls would race on. YoutubeDL instead takes all configuration as a constructor
    dict and reports progress through callbacks, so it's self-contained per call. Every finished
    file's absolute path is sent to `callback`, matching how DownloadWorker's actualCallback
    already expects one path per line from gallery_dl_wrapper.download()."""

    # DownloadWorker throttles nothing on its own end, so a raw per-chunk progress_hook (which
    # fires dozens of times a second) would otherwise flood the DB with writes — this closure
    # state caps real updates to roughly once a second, matching what's actually useful on screen.
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
    custom_pp_keys = []
    if ffmpeg_path:
        if audio_only:
            custom_pp_keys.append("ExtractAudio")
        if embed_thumbnail:
            custom_pp_keys.append("EmbedThumbnail")
        if embed_metadata:
            custom_pp_keys.append("Metadata")
        if download_subtitles and not audio_only:
            custom_pp_keys.append("EmbedSubtitle")

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
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total and not reported_size[0]:
                # Sent once, as soon as it's known — before any bytes have actually moved — so the
                # UI can show a real size and a byte-accurate progress fraction immediately instead
                # of only once the download finishes.
                reported_size[0] = True
                callback(f"[size] {total}")
            now = time.monotonic()
            if now - last_progress_emit[0] >= 1.0:
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
        if custom_pp_keys:
            # A further postprocessor (tracked above) will produce the real final file — reported
            # from postprocessor_hook below once it completes, not here.
            return
        filename = d.get("filename") or (d.get("info_dict") or {}).get("filepath")
        if not filename:
            return
        if _FRAGMENT_SUFFIX_RE.search(filename):
            # This is a pre-merge fragment, not the real final file — ffmpeg still needs to read
            # it to produce the merged output (reported separately below, by postprocessor_hook).
            # DownloadWorker's callback moves/deletes whatever path it's given as soon as it sees
            # it, asynchronously and concurrently with this synchronous download() call; reporting
            # a fragment here would race that move against ffmpeg's own read of the same file.
            return
        callback(os.path.abspath(filename))

    def postprocessor_hook(d):
        if d.get("status") != "finished" or not callback:
            return
        pp_name = d.get("postprocessor")
        is_final = pp_name == custom_pp_keys[-1] if custom_pp_keys else pp_name == "Merger"
        if not is_final:
            return
        info = d.get("info_dict") or {}
        filename = info.get("filepath") or info.get("_filename")
        if filename:
            callback(os.path.abspath(filename))

    # "bestvideo+bestaudio" (yt-dlp's own default) needs ffmpeg to mux the separately-fetched
    # streams together — without the bundled ffmpeg binary (see FfmpegRuntime.kt) that would abort
    # every download with "ffmpeg is not installed", so it's only used when ffmpeg_path is set.
    # "best/18" instead picks the best already-muxed single format, capping quality below the true
    # best-available tier on sites (like YouTube) that split their highest resolutions into
    # separate streams, but needs no native dependency at all.
    default_format = "bestvideo+bestaudio/best" if ffmpeg_path else "best/18"
    # audio_only overrides any quality-cap format_selector the caller passed — "bestaudio/best"
    # is what ExtractAudio (added to postprocessors below) actually converts to mp3, so a
    # video-shaped selector here would just waste bandwidth downloading video that gets discarded.
    chosen_format = "bestaudio/best" if audio_only else (format_selector or default_format)
    # Poster name, then caption — "%(a,b|default)s" tries each field left to right and falls back
    # to the literal default only once every field in the list is empty; DownloadWorker derives
    # the entity's own display title from this same "name - caption [id]" shape (see its own
    # comment on that), so the two need to keep matching.
    outtmpl = filename_format or "%(uploader,channel,creator|Unknown)s - %(title,description|Unknown).150B [%(id)s].%(ext)s"
    ydl_opts = {
        "outtmpl": os.path.join(download_dir, outtmpl),
        "format": chosen_format,
        "progress_hooks": [progress_hook],
        "postprocessor_hooks": [postprocessor_hook],
        "logger": _Logger(callback),
        "noprogress": True,
        "quiet": True,
        "no_color": True,
        "restrictfilenames": True,
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
    if retries:
        # Same count for both — "retries" alone only covers whole-request failures (extraction,
        # a plain single-file fetch); a merge download's separate video/audio fragments each get
        # their own retry budget via "fragment_retries", uncovered by the first one on its own.
        ydl_opts["retries"] = int(retries)
        ydl_opts["fragment_retries"] = int(retries)
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
        if audio_only:
            postprocessors.append({"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "5"})
        if embed_thumbnail:
            ydl_opts["writethumbnail"] = True
            postprocessors.append({"key": "EmbedThumbnail"})
        if embed_metadata:
            postprocessors.append({"key": "FFmpegMetadata"})
        if download_subtitles and not audio_only:
            # Embedding requires actually fetching the subtitle track first, hence writesubtitles
            # alongside FFmpegEmbedSubtitle — a bare writesubtitles-only mode (sidecar .srt files
            # left next to the video) isn't supported here since DownloadWorker's callback pipeline
            # only knows how to move a single reported file per download into the gallery, not
            # track extra sidecar files alongside it.
            ydl_opts["writesubtitles"] = True
            ydl_opts["subtitleslangs"] = [lang.strip() for lang in (subtitle_langs or "en").split(",") if lang.strip()]
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
    rate = _parse_rate(limit_rate)
    if rate:
        ydl_opts["ratelimit"] = rate
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
            ydl.download([url])
        return "Done"
    except _Cancelled:
        return "Cancelled"
    except Exception as e:
        if callback:
            callback(f"[error] {e}")
        return f"Error: {e}"

def list_info(url, cookies_path=None, extra_args=None, js_runtime_path=None):
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
    if extra_args:
        for token in extra_args.split():
            if "=" in token:
                key, _, value = token.partition("=")
                ydl_opts[key] = value

    def _pick(entry):
        if not entry:
            return None
        return {"title": entry.get("title"), "thumbnail": entry.get("thumbnail")}

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        return json.dumps({"error": str(e)})

    if info is None:
        return json.dumps({"error": "no info extracted"})
    entries = info.get("entries")
    if entries is not None:
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

    if len(_sys.argv) < 2 or _sys.argv[1] not in ("download", "list"):
        print("Usage: yt_dlp_wrapper.py download <21 positional args> | list <4 positional args>", file=_sys.stderr)
        _sys.exit(2)

    if _sys.argv[1] == "list":
        a = _sys.argv[2:]
        print(list_info(url=a[0], cookies_path=_s(a[1]), extra_args=_s(a[2]), js_runtime_path=_s(a[3])), flush=True)
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
    )
    print(f"[__status__] {status}", flush=True)
