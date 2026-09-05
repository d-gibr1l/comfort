package com.comfort.app.data

import android.content.Context
import android.net.Uri

enum class VideoQuality(val label: String) {
    BEST("Best available"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    AUDIO_ONLY("Audio only");

    /** null means "no cap" (BEST) or "meaningless" (AUDIO_ONLY, which overrides the format
     * entirely). Passed to yt_dlp_wrapper.py as a `format_sort: ["res:N"]` entry rather than a
     * manual "[height<=?N]" filter on the format string — a first attempt used the latter and
     * broke on portrait video (Instagram reels etc.): yt-dlp's `height` field is the raw pixel
     * height, which for a portrait video is the *long* side (e.g. ~1920 for a "1080p" reel, since
     * 1080 there means 1080px wide), so a height<=1080 filter wrongly excluded the very format the
     * cap was supposed to allow and silently fell back to a lower quality. `res:N` sorts by
     * min(height, width) instead — the conventional quality number regardless of orientation —
     * which is yt-dlp's own documented idiom for "best available at or under N, gracefully
     * degrading if nothing fits" (see their README's "Sorting Formats" section). */
    fun resolutionCap(): Int? = when (this) {
        BEST, AUDIO_ONLY -> null
        P1080 -> 1080
        P720 -> 720
        P480 -> 480
    }
}

enum class OutputFormat(val label: String, val extension: String) {
    MKV("MKV", "mkv"),
    MP4("MP4", "mp4"),
}

object GalleryDlPreferences {
    const val PREFS_NAME = "GalleryDlPrefs"
    const val KEY_COOKIES = "cookies"
    const val KEY_FILENAME_FORMAT = "filename_format"
    const val KEY_EXTRA_ARGS = "extra_args"
    const val KEY_CONCURRENT_DOWNLOADS = "concurrent_downloads"
    const val KEY_WIFI_ONLY = "wifi_only"
    const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    const val KEY_SCHEDULE_START_MIN = "schedule_start_min"
    const val KEY_SCHEDULE_END_MIN = "schedule_end_min"
    const val KEY_SPEED_LIMIT = "speed_limit"
    const val KEY_MAX_FILESIZE_ENABLED = "max_filesize_enabled"
    const val KEY_MAX_FILESIZE = "max_filesize"
    const val KEY_PROXY_URL = "proxy_url"
    const val KEY_DOWNLOAD_LOCATION_URI = "download_location_uri"
    const val KEY_GLOBAL_PAUSE = "global_pause"
    const val KEY_INSTANT_SHARE = "instant_share"
    const val KEY_LIBRARY_GRID_VIEW = "library_grid_view"
    const val KEY_VIDEO_QUALITY = "video_quality"
    const val KEY_DOWNLOAD_SUBTITLES = "download_subtitles"
    const val KEY_SUBTITLE_LANGUAGES = "subtitle_languages"
    const val KEY_EMBED_THUMBNAIL = "embed_thumbnail"
    const val KEY_EMBED_METADATA = "embed_metadata"
    const val KEY_WRITE_INFO_FILES = "write_info_files"
    const val KEY_NO_PLAYLIST = "no_playlist"
    const val KEY_ENGINE_UPDATE_LAST_CHECK_MS = "engine_update_last_check_ms"
    const val KEY_ENGINE_UPDATE_AVAILABLE = "engine_update_available"
    const val KEY_OUTPUT_FORMAT = "output_format"
    const val KEY_NETWORK_RETRIES = "network_retries"
    const val KEY_AUTO_UPDATE_ENGINES = "auto_update_engines"
    // yt-dlp's own built-in default (used whenever this preference hasn't been touched) — chosen
    // to match rather than invent a different "app default", so leaving the setting alone behaves
    // exactly like it always did before this preference existed.
    const val DEFAULT_NETWORK_RETRIES = 10
    const val MAX_NETWORK_RETRIES = 50
    // How often MainScreen's auto-check (see its own LaunchedEffect) is allowed to actually hit
    // PyPI on app launch — not on literally every launch, so relaunching the app repeatedly in a
    // short span doesn't spam it. 6h is frequent enough to catch a same-day extractor fix without
    // being effectively "every launch" for typical usage.
    const val ENGINE_UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    // The naive "{uploader} - {title} - {id}" pattern collapses to the literal string
    // "None - None - None" on sources that don't expose that metadata, which makes every item
    // in the gallery resolve to the same filename — only the first survives, the rest are
    // silently skipped as duplicates. {filename} is a field gallery-dl always populates (it's
    // what the default naming scheme is built from), so anchoring on it guarantees uniqueness
    // even when the human-readable fields are missing.
    private const val LEGACY_DEFAULT_FILENAME_FORMAT = "{uploader} - {title} - {id}.{extension}"
    // {id}-anchored uniqueness worked but read as noise ("uploader - title - id.ext" for every
    // single item); {filename} is gallery-dl's own always-populated per-item field, so anchoring
    // on that instead — bracketed, matching yt-dlp's own "[id]" convention in yt_dlp_wrapper.py —
    // keeps the same guaranteed-unique-even-when-metadata's-missing property while reading as
    // "poster - caption" first. DownloadWorker derives the entity's own display title by
    // stripping that trailing bracket, so the two need to keep matching.
    private const val LEGACY_DEFAULT_FILENAME_FORMAT_2 = "{uploader|category} - {title|id} - {filename}.{extension}"
    const val DEFAULT_FILENAME_FORMAT = "{uploader|category} - {title|category} [{filename}].{extension}"
    const val DEFAULT_CONCURRENT_DOWNLOADS = 2
    const val MAX_CONCURRENT_DOWNLOADS = 5

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFilenameFormat(context: Context): String {
        val stored = prefs(context).getString(KEY_FILENAME_FORMAT, null)?.takeIf { it.isNotBlank() }
        if (stored == null || stored == LEGACY_DEFAULT_FILENAME_FORMAT || stored == LEGACY_DEFAULT_FILENAME_FORMAT_2) return DEFAULT_FILENAME_FORMAT
        return stored
    }

    fun getExtraArgs(context: Context): String {
        return prefs(context).getString(KEY_EXTRA_ARGS, "") ?: ""
    }

    fun getCookies(context: Context): String {
        return prefs(context).getString(KEY_COOKIES, "") ?: ""
    }

    fun getConcurrentDownloads(context: Context): Int {
        return prefs(context).getInt(KEY_CONCURRENT_DOWNLOADS, DEFAULT_CONCURRENT_DOWNLOADS)
            .coerceIn(1, MAX_CONCURRENT_DOWNLOADS)
    }

    fun setConcurrentDownloads(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_CONCURRENT_DOWNLOADS, count.coerceIn(1, MAX_CONCURRENT_DOWNLOADS)).apply()
    }

    fun isWifiOnly(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_WIFI_ONLY, false)
    }

    fun setWifiOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    fun isScheduleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false)
    }

    fun setScheduleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply()
    }

    /** Minutes since midnight (0-1439). */
    fun getScheduleStartMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_SCHEDULE_START_MIN, 0)
    }

    fun getScheduleEndMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_SCHEDULE_END_MIN, 23 * 60 + 59)
    }

    fun setScheduleWindow(context: Context, startMinutes: Int, endMinutes: Int) {
        prefs(context).edit()
            .putInt(KEY_SCHEDULE_START_MIN, startMinutes)
            .putInt(KEY_SCHEDULE_END_MIN, endMinutes)
            .apply()
    }

    /** gallery-dl --limit-rate syntax, e.g. "500k" or "2M". Blank means unlimited. */
    fun getSpeedLimit(context: Context): String {
        return prefs(context).getString(KEY_SPEED_LIMIT, "") ?: ""
    }

    fun setSpeedLimit(context: Context, limit: String) {
        // Both engines' parsers are strict about the unit suffix being a single letter (k/M/G) —
        // gallery-dl's own --limit-rate parser raises outright on "500kb"/"2MB", and yt_dlp_
        // wrapper.py's own _parse_rate() regex (^\s*([\d.]+)\s*([kKmMgG]?)\s*$) just as strictly
        // fails to match, silently dropping the limit instead. The UI hint says "500k"/"2M", but
        // typing the trailing "B" out of habit (a very natural thing to type for a byte unit) used
        // to fatally break gallery-dl's own downloads and silently no-op yt-dlp's. Stripping one
        // trailing b/B here (not deeper validation — this is the one specific, reported failure
        // shape) shields both engines from it before it's ever persisted or handed to either.
        val sanitized = limit.trim().replace(Regex("(?i)b$"), "")
        prefs(context).edit().putString(KEY_SPEED_LIMIT, sanitized).apply()
    }

    fun isMaxFilesizeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MAX_FILESIZE_ENABLED, false)
    }

    fun setMaxFilesizeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAX_FILESIZE_ENABLED, enabled).apply()
    }

    /** gallery-dl/yt-dlp size syntax, e.g. "500k" or "2M" or "1G". Blank means unlimited. Only
     * applied to downloads when [isMaxFilesizeEnabled] is also true. */
    fun getMaxFilesize(context: Context): String {
        return prefs(context).getString(KEY_MAX_FILESIZE, "") ?: ""
    }

    fun setMaxFilesize(context: Context, value: String) {
        // Same trailing b/B footgun as setSpeedLimit() above — see its comment.
        val sanitized = value.trim().replace(Regex("(?i)b$"), "")
        prefs(context).edit().putString(KEY_MAX_FILESIZE, sanitized).apply()
    }

    /** The value to actually hand to a download's engine call — null when the limit is off or
     * unset, so call sites don't need to re-check [isMaxFilesizeEnabled] themselves. */
    fun getEffectiveMaxFilesize(context: Context): String? {
        if (!isMaxFilesizeEnabled(context)) return null
        return getMaxFilesize(context).takeIf { it.isNotBlank() }
    }

    /** http(s):// or socks5:// proxy URL, e.g. "socks5://user:pass@127.0.0.1:1080". Blank means
     * no proxy — both engines' own default (env vars aside, which this app doesn't otherwise
     * touch). */
    fun getProxyUrl(context: Context): String {
        return prefs(context).getString(KEY_PROXY_URL, "") ?: ""
    }

    fun setProxyUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_PROXY_URL, url.trim()).apply()
    }

    /** A user-chosen SAF folder to save downloads into, or null to use the default
     * Pictures/gallery-dl location (the only option that's guaranteed to show up in Gallery apps). */
    fun getDownloadLocationUri(context: Context): Uri? {
        val stored = prefs(context).getString(KEY_DOWNLOAD_LOCATION_URI, null) ?: return null
        return runCatching { Uri.parse(stored) }.getOrNull()
    }

    fun setDownloadLocationUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_DOWNLOAD_LOCATION_URI, uri?.toString()).apply()
    }

    /** Global "pause all" toggle: while true, new downloads are queued but not dispatched to
     * WorkManager until the user resumes. */
    fun isGloballyPaused(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_GLOBAL_PAUSE, false)
    }

    fun setGloballyPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLOBAL_PAUSE, paused).apply()
    }

    /** When enabled, a shared link downloads immediately in the background instead of opening
     * the item picker. */
    fun isInstantShareEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_INSTANT_SHARE, false)
    }

    fun setInstantShareEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INSTANT_SHARE, enabled).apply()
    }

    fun isLibraryGridView(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIBRARY_GRID_VIEW, false)
    }

    fun setLibraryGridView(context: Context, gridView: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIBRARY_GRID_VIEW, gridView).apply()
    }

    fun getVideoQuality(context: Context): VideoQuality {
        val stored = prefs(context).getString(KEY_VIDEO_QUALITY, VideoQuality.BEST.name)
        return runCatching { VideoQuality.valueOf(stored ?: VideoQuality.BEST.name) }.getOrDefault(VideoQuality.BEST)
    }

    fun setVideoQuality(context: Context, quality: VideoQuality) {
        prefs(context).edit().putString(KEY_VIDEO_QUALITY, quality.name).apply()
    }

    fun isDownloadSubtitles(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DOWNLOAD_SUBTITLES, false)
    }

    fun setDownloadSubtitles(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DOWNLOAD_SUBTITLES, enabled).apply()
    }

    fun getSubtitleLanguages(context: Context): String {
        return prefs(context).getString(KEY_SUBTITLE_LANGUAGES, "en") ?: "en"
    }

    fun setSubtitleLanguages(context: Context, languages: String) {
        prefs(context).edit().putString(KEY_SUBTITLE_LANGUAGES, languages.trim()).apply()
    }

    fun isEmbedThumbnail(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_EMBED_THUMBNAIL, false)
    }

    fun setEmbedThumbnail(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EMBED_THUMBNAIL, enabled).apply()
    }

    fun isEmbedMetadata(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_EMBED_METADATA, false)
    }

    fun setEmbedMetadata(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EMBED_METADATA, enabled).apply()
    }

    /** Saves a per-item JSON metadata sidecar alongside each download — gallery-dl's
     * --write-metadata, yt-dlp's --write-info-json plus --write-description. */
    fun isWriteInfoFiles(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_WRITE_INFO_FILES, false)
    }

    fun setWriteInfoFiles(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WRITE_INFO_FILES, enabled).apply()
    }

    /** Whether a link that's technically part of a playlist/channel downloads just that one
     * video (true) or the whole playlist (false, the default). Off by default because actually
     * setting this on a yt-dlp download — not just the preference existing — has a real cost:
     * verified live that it turns a ~6s extraction into 60-250+s in the currently-bundled yt-dlp
     * version (see yt_dlp_wrapper.py's own comment on this), so it's opt-in rather than paid by
     * every download by default. */
    fun isNoPlaylist(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NO_PLAYLIST, false)
    }

    fun setNoPlaylist(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NO_PLAYLIST, enabled).apply()
    }

    // Drives the small red badge on the Settings tab / About row — set by MainScreen's rate-limited
    // auto-check (see ENGINE_UPDATE_CHECK_INTERVAL_MS) so the badge survives without re-hitting
    // PyPI on every recomposition, and cleared once the user actually opens the Engines section
    // (which always does its own fresh check regardless of this cached flag).
    fun getEngineUpdateLastCheckMs(context: Context): Long = prefs(context).getLong(KEY_ENGINE_UPDATE_LAST_CHECK_MS, 0L)

    fun setEngineUpdateLastCheckMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_ENGINE_UPDATE_LAST_CHECK_MS, ms).apply()
    }

    fun isEngineUpdateAvailable(context: Context): Boolean = prefs(context).getBoolean(KEY_ENGINE_UPDATE_AVAILABLE, false)

    fun setEngineUpdateAvailable(context: Context, available: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENGINE_UPDATE_AVAILABLE, available).apply()
    }

    /** Only affects a merged video (audio+video muxed via ffmpeg) — a single already-muxed format
     * downloads straight through regardless of this setting, and gallery-dl-sourced items keep
     * whatever extension the site actually served. MKV stays the default: see
     * yt_dlp_wrapper.py's own merge_output_format comment for why MP4 needed real work (a
     * format_sort codec bias) to avoid producing unplayable output on some sources. */
    fun getOutputFormat(context: Context): OutputFormat {
        val stored = prefs(context).getString(KEY_OUTPUT_FORMAT, OutputFormat.MKV.name)
        return runCatching { OutputFormat.valueOf(stored ?: OutputFormat.MKV.name) }.getOrDefault(OutputFormat.MKV)
    }

    fun setOutputFormat(context: Context, format: OutputFormat) {
        prefs(context).edit().putString(KEY_OUTPUT_FORMAT, format.name).apply()
    }

    /** How many times a failed request (extraction, or an individual file/fragment fetch) gets
     * retried before the download actually fails. Shared by both engines — see
     * yt_dlp_wrapper.py's retries/fragment_retries and gallery_dl_wrapper.py's
     * extractor.retries/downloader.retries. */
    fun getNetworkRetries(context: Context): Int {
        return prefs(context).getInt(KEY_NETWORK_RETRIES, DEFAULT_NETWORK_RETRIES).coerceIn(1, MAX_NETWORK_RETRIES)
    }

    fun setNetworkRetries(context: Context, retries: Int) {
        prefs(context).edit().putInt(KEY_NETWORK_RETRIES, retries.coerceIn(1, MAX_NETWORK_RETRIES)).apply()
    }

    /** When enabled (the default), MainScreen's own rate-limited engine check installs any update
     * it finds automatically instead of only flagging it for the user to apply by hand. */
    fun isAutoUpdateEnginesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_UPDATE_ENGINES, true)
    }

    fun setAutoUpdateEnginesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENGINES, enabled).apply()
    }
}
