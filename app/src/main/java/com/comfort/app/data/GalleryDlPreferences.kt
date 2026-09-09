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
    const val KEY_ALARM_SCHEDULING_ENABLED = "alarm_scheduling_enabled"
    const val KEY_SCHEDULE_START_MIN = "schedule_start_min"
    const val KEY_SCHEDULE_END_MIN = "schedule_end_min"
    const val KEY_SPEED_LIMIT = "speed_limit"
    const val KEY_MAX_FILESIZE_ENABLED = "max_filesize_enabled"
    const val KEY_MAX_FILESIZE = "max_filesize"
    const val KEY_PROXY_URL = "proxy_url"
    const val KEY_EXTRACTOR_ARGS = "extractor_args"
    const val KEY_FILENAME_TEMPLATES = "filename_templates"
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
    const val KEY_LIVE_FROM_START = "live_from_start"
    const val KEY_ENGINE_UPDATE_LAST_CHECK_MS = "engine_update_last_check_ms"
    const val KEY_ENGINE_UPDATE_AVAILABLE = "engine_update_available"
    const val KEY_OUTPUT_FORMAT = "output_format"
    const val KEY_NETWORK_RETRIES = "network_retries"
    const val KEY_AUTO_UPDATE_ENGINES = "auto_update_engines"
    const val KEY_FORCE_IPV4 = "force_ipv4"
    const val KEY_CONCURRENT_FRAGMENTS = "concurrent_fragments"
    const val KEY_NO_CHECK_CERTIFICATES = "no_check_certificates"
    const val KEY_SLEEP_INTERVAL_SECONDS = "sleep_interval_seconds"
    const val MAX_SLEEP_INTERVAL_SECONDS = 20
    const val KEY_CUSTOM_HEADERS = "custom_headers"
    const val KEY_FORMAT_SORT = "format_sort"
    const val KEY_VERBOSE_LOGGING = "verbose_logging"
    const val KEY_DOWNLOAD_DELAY_SECONDS = "download_delay_seconds"
    const val KEY_INCOGNITO_DEFAULT = "incognito_default"
    const val DEFAULT_CONCURRENT_FRAGMENTS = 1
    const val MAX_CONCURRENT_FRAGMENTS = 16
    const val KEY_EMBED_CHAPTERS = "embed_chapters"
    const val KEY_SAVE_SUBTITLE_FILES = "save_subtitle_files"
    const val KEY_DELETE_LEFTOVER_ON_FAILURE = "delete_leftover_on_failure"
    const val KEY_CLEANUP_LEFTOVER_INTERVAL = "cleanup_leftover_interval"
    const val KEY_PREVENT_DUPLICATE_DOWNLOADS = "prevent_duplicate_downloads"
    const val KEY_REMEMBER_DOWNLOAD_TYPE = "remember_download_type"
    const val KEY_AUDIO_LOCATION_URI = "audio_location_uri"
    const val KEY_VIDEO_LOCATION_URI = "video_location_uri"
    const val KEY_RESTRICT_FILENAMES = "restrict_filenames"
    const val KEY_TRIM_FILENAMES = "trim_filenames"
    const val KEY_DOWNLOAD_DELAY_ENABLED = "download_delay_enabled"
    const val KEY_CONCURRENT_DOWNLOADS_ENABLED = "concurrent_downloads_enabled"
    const val KEY_SPEED_LIMIT_ENABLED = "speed_limit_enabled"
    const val KEY_NETWORK_RETRIES_ENABLED = "network_retries_enabled"
    const val KEY_PROXY_ENABLED = "proxy_enabled"
    const val KEY_CONCURRENT_FRAGMENTS_ENABLED = "concurrent_fragments_enabled"
    const val KEY_SLEEP_INTERVAL_ENABLED = "sleep_interval_enabled"
    const val KEY_FRAGMENT_RETRIES = "fragment_retries"
    const val KEY_FRAGMENT_RETRIES_ENABLED = "fragment_retries_enabled"
    const val KEY_SOCKET_TIMEOUT_SECONDS = "socket_timeout_seconds"
    const val KEY_SOCKET_TIMEOUT_ENABLED = "socket_timeout_enabled"
    const val KEY_BUFFER_SIZE_KB = "buffer_size_kb"
    const val KEY_BUFFER_SIZE_ENABLED = "buffer_size_enabled"
    const val KEY_FORMAT_ID_OVERRIDE = "format_id_override"
    const val KEY_YOUTUBE_CLIENT_ROTATION_ENABLED = "youtube_client_rotation_enabled"
    const val KEY_IMPERSONATE_ENABLED = "impersonate_enabled"
    const val KEY_ARIA2_ENABLED = "aria2_enabled"
    // yt-dlp's own built-in default for --fragment-retries, kept separate from
    // DEFAULT_NETWORK_RETRIES below (see getEffectiveFragmentRetries) so a merge download's
    // per-fragment retry budget can be tuned independently of whole-request retries.
    const val DEFAULT_FRAGMENT_RETRIES = 10
    const val MAX_FRAGMENT_RETRIES = 50
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
    private const val DEFAULT_FILENAME_TEMPLATES = """%(title)s.%(ext)s
%(uploader)s - %(title)s [%(id)s].%(ext)s
%(playlist_index)s - %(title)s.%(ext)s
{category} - {filename}.{extension}
{uploader} - {title}.{extension}"""

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

    /** yt-dlp CLI-syntax --extractor-args string(s), e.g. "youtube:player_client=android,web".
     * Multiple IE_KEY:ARGS blocks can be whitespace-separated, mirroring how --extractor-args can
     * be repeated on the real CLI for different extractors. gallery-dl isn't wired to this — its
     * own "Extra arguments" field already accepts arbitrary CLI flags including per-extractor
     * config, so there's no separate mechanism needed there the way there is for yt-dlp's more
     * restrictive extra_args field (only flat "key=value" pairs, not this nested syntax). */
    fun getExtractorArgs(context: Context): String {
        return prefs(context).getString(KEY_EXTRACTOR_ARGS, "") ?: ""
    }

    fun setExtractorArgs(context: Context, args: String) {
        prefs(context).edit().putString(KEY_EXTRACTOR_ARGS, args).apply()
    }

    /** The user's saved filename templates, as offered by the download preview sheet's
     * "Filename Templates" screen. Stored newline-separated rather than as JSON: a template is a
     * single-line format string (see DEFAULT_FILENAME_FORMAT) and can't itself contain a newline,
     * so there's nothing to escape and nothing to parse wrongly. Empty until the user saves one —
     * the sheet shows its own empty state rather than shipping sample templates. */
    fun getFilenameTemplates(context: Context): List<String> {
        val stored = prefs(context).getString(KEY_FILENAME_TEMPLATES, null)
        val raw = if (stored == null) DEFAULT_FILENAME_TEMPLATES else stored
        return raw
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun setFilenameTemplates(context: Context, templates: List<String>) {
        prefs(context).edit().putString(KEY_FILENAME_TEMPLATES, templates.joinToString("\n")).apply()
    }

    /** Returns false when [template] is blank or already saved, so the caller can report that
     * rather than silently appearing to succeed while the list is unchanged. */
    fun addFilenameTemplate(context: Context, template: String): Boolean {
        val trimmed = template.trim().replace("\n", " ")
        if (trimmed.isEmpty()) return false
        val existing = getFilenameTemplates(context)
        if (trimmed in existing) return false
        setFilenameTemplates(context, existing + trimmed)
        return true
    }

    fun removeFilenameTemplate(context: Context, template: String) {
        setFilenameTemplates(context, getFilenameTemplates(context).filterNot { it == template })
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

    /** Whether [getConcurrentDownloads]'s own slider value actually applies — same on/off-plus-
     * value shape as [isMaxFilesizeEnabled]/[isDownloadDelayEnabled]. Defaults to true so an
     * existing install (already running [DEFAULT_CONCURRENT_DOWNLOADS] = 2 at once) sees no
     * behavior change until the user actually touches this toggle; off means exactly one download
     * runs at a time regardless of whatever the slider is set to underneath. */
    fun isConcurrentDownloadsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONCURRENT_DOWNLOADS_ENABLED, true)
    }

    fun setConcurrentDownloadsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONCURRENT_DOWNLOADS_ENABLED, enabled).apply()
    }

    /** The value to actually apply — 1 whenever the toggle is off, so call sites don't need to
     * re-check [isConcurrentDownloadsEnabled] themselves (same shape as [getEffectiveMaxFilesize]/
     * [getEffectiveDownloadDelaySeconds]). */
    fun getEffectiveConcurrentDownloads(context: Context): Int {
        if (!isConcurrentDownloadsEnabled(context)) return 1
        return getConcurrentDownloads(context)
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

    /** Ported from YTDLnis's own "Use alarm for scheduling" — an AlarmManager backstop
     * (DownloadDispatcher.scheduleWindowAlarm) that wakes the device near the Schedule window's
     * real open time, since a long WorkManager setInitialDelay() alone has no absolute wall-clock
     * target and Doze/App Standby can defer it well past the intended moment. On by default —
     * scheduleWindowAlarm() only ever actually arms an alarm when isScheduleEnabled is *also*
     * true, so this has zero effect on anyone not using the Schedule window at all; for anyone who
     * does turn it on, this backstop should just come along automatically rather than needing a
     * second, easy-to-miss toggle to get scheduling that actually fires on time. Still its own
     * separate toggle underneath "Restrict to time window" for turning it back off specifically. */
    fun isAlarmSchedulingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ALARM_SCHEDULING_ENABLED, true)
    }

    fun setAlarmSchedulingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALARM_SCHEDULING_ENABLED, enabled).apply()
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

    /** Whether [getSpeedLimit] actually applies — defaults true so an existing install (where a
     * blank value already meant "unlimited" either way) sees no behavior change; off forces
     * unlimited even if a real limit is still saved underneath, without erasing it. */
    fun isSpeedLimitEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEED_LIMIT_ENABLED, true)
    }

    fun setSpeedLimitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEED_LIMIT_ENABLED, enabled).apply()
    }

    fun getEffectiveSpeedLimit(context: Context): String {
        if (!isSpeedLimitEnabled(context)) return ""
        return getSpeedLimit(context)
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

    /** Whether [getProxyUrl] actually applies — defaults true (an existing saved URL was already
     * being used; a blank one was already a no-op either way). Off forces no proxy without
     * erasing the saved URL, so it can be flipped back on later. */
    fun isProxyEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PROXY_ENABLED, true)
    }

    fun setProxyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply()
    }

    fun getEffectiveProxyUrl(context: Context): String {
        if (!isProxyEnabled(context)) return ""
        return getProxyUrl(context)
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

    /** Whether an in-progress live stream downloads from its actual start instead of from the
     * current moment (yt-dlp's own --live-from-start). A no-op for any URL that isn't currently
     * live, so safe to leave on as a global default unlike most other per-download choices. */
    fun isLiveFromStart(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_FROM_START, false)
    }

    fun setLiveFromStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_FROM_START, enabled).apply()
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

    /** Whether [getNetworkRetries] actually applies — defaults true so an existing install (which
     * always applied [DEFAULT_NETWORK_RETRIES] regardless) sees no behavior change; off means
     * "" — neither engine gets an explicit --retries, falling back to its own built-in default
     * instead of this app's chosen one. */
    fun isNetworkRetriesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NETWORK_RETRIES_ENABLED, true)
    }

    fun setNetworkRetriesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NETWORK_RETRIES_ENABLED, enabled).apply()
    }

    /** String form (empty when disabled) — matches the shape DownloadWorker already hands both
     * engines' own CLI-arg lists as. */
    fun getEffectiveNetworkRetries(context: Context): String {
        if (!isNetworkRetriesEnabled(context)) return ""
        return getNetworkRetries(context).toString()
    }

    /** How many times a failed fragment fetch (one piece of an HLS/DASH merge download) is
     * retried, independent of [getNetworkRetries]'s whole-request budget — yt-dlp only, via
     * yt_dlp_wrapper.py's own fragment_retries kwarg (gallery-dl has no fragment concept). */
    fun getFragmentRetries(context: Context): Int {
        return prefs(context).getInt(KEY_FRAGMENT_RETRIES, DEFAULT_FRAGMENT_RETRIES).coerceIn(1, MAX_FRAGMENT_RETRIES)
    }

    fun setFragmentRetries(context: Context, retries: Int) {
        prefs(context).edit().putInt(KEY_FRAGMENT_RETRIES, retries.coerceIn(1, MAX_FRAGMENT_RETRIES)).apply()
    }

    /** Whether [getFragmentRetries] actually applies — defaults false, since before this setting
     * existed a fragment's retry budget silently rode along with [getNetworkRetries] instead
     * (yt_dlp_wrapper.py's "retries" sets both retries and fragment_retries to the same count);
     * off means yt_dlp_wrapper.py leaves fragment_retries to fall back to that shared value. */
    fun isFragmentRetriesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FRAGMENT_RETRIES_ENABLED, false)
    }

    fun setFragmentRetriesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FRAGMENT_RETRIES_ENABLED, enabled).apply()
    }

    /** String form (empty when disabled) — matches the shape DownloadWorker already hands both
     * engines' own CLI-arg lists as. */
    fun getEffectiveFragmentRetries(context: Context): String {
        if (!isFragmentRetriesEnabled(context)) return ""
        return getFragmentRetries(context).toString()
    }

    /** How long a single stalled read/connect is allowed before the engine gives up on it (and
     * falls back to its own retry handling). Shared by both engines — yt_dlp_wrapper.py's
     * socket_timeout and gallery_dl_wrapper.py's extractor.timeout/downloader.timeout. Off means
     * each engine's own built-in default (yt-dlp: 20s, gallery-dl: 30s). */
    fun getSocketTimeoutSeconds(context: Context): Int {
        return prefs(context).getInt(KEY_SOCKET_TIMEOUT_SECONDS, 20).coerceIn(1, 300)
    }

    fun setSocketTimeoutSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_SOCKET_TIMEOUT_SECONDS, seconds.coerceIn(1, 300)).apply()
    }

    fun isSocketTimeoutEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SOCKET_TIMEOUT_ENABLED, false)
    }

    fun setSocketTimeoutEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOCKET_TIMEOUT_ENABLED, enabled).apply()
    }

    fun getEffectiveSocketTimeoutSeconds(context: Context): String {
        if (!isSocketTimeoutEnabled(context)) return ""
        return getSocketTimeoutSeconds(context).toString()
    }

    /** yt-dlp only (--buffer-size has no gallery-dl equivalent) — the download-stream read chunk
     * size, in KB. yt-dlp's own default is 1024 KB; off leaves it at that. */
    fun getBufferSizeKb(context: Context): Int {
        return prefs(context).getInt(KEY_BUFFER_SIZE_KB, 1024).coerceIn(1, 65536)
    }

    fun setBufferSizeKb(context: Context, kb: Int) {
        prefs(context).edit().putInt(KEY_BUFFER_SIZE_KB, kb.coerceIn(1, 65536)).apply()
    }

    fun isBufferSizeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_BUFFER_SIZE_ENABLED, false)
    }

    fun setBufferSizeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUFFER_SIZE_ENABLED, enabled).apply()
    }

    fun getEffectiveBufferSizeKb(context: Context): String {
        if (!isBufferSizeEnabled(context)) return ""
        return getBufferSizeKb(context).toString()
    }

    /** yt-dlp only — a raw format selector (e.g. "137+140", or any of yt-dlp's own -f expression
     * syntax) that fully replaces the app's own quality-cap-derived selector for every download,
     * for power users who want exact control. Empty (the default) leaves the normal quality
     * picker in charge — this is yt_dlp_wrapper.py's own pre-existing format_selector kwarg,
     * previously never actually wired up to a Settings field. */
    fun getFormatIdOverride(context: Context): String {
        return prefs(context).getString(KEY_FORMAT_ID_OVERRIDE, "") ?: ""
    }

    fun setFormatIdOverride(context: Context, formatId: String) {
        prefs(context).edit().putString(KEY_FORMAT_ID_OVERRIDE, formatId).apply()
    }

    /** yt-dlp only — rotates through multiple internal YouTube API clients (android/web/ios)
     * instead of just "web", so a throttled or degraded endpoint on one client falls back to
     * another rather than failing the whole download. A default only: an explicit
     * "youtube:player_client=..." already present in the free-text Extractor arguments field
     * (Settings > Advanced) still wins. On by default — free resilience with no real downside: if
     * one client's endpoint is throttled, yt-dlp falls back to another instead of failing outright. */
    fun isYoutubeClientRotationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_YOUTUBE_CLIENT_ROTATION_ENABLED, true)
    }

    fun setYoutubeClientRotationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_YOUTUBE_CLIENT_ROTATION_ENABLED, enabled).apply()
    }

    /** yt-dlp only — spoofs a real browser's TLS handshake (via curl_cffi) for every download,
     * not just requests, so the site sees a genuine browser fingerprint instead of a recognizable
     * script signature. yt_dlp_wrapper.py already does this unconditionally for one specific,
     * reproduced case (a Reddit share-link redirect getting WAF-blocked) — this is the general,
     * opt-in "try it everywhere" version for other sites hitting bot detection. Off by default:
     * curl_cffi's impersonation profiles track real browser versions and can go stale, and a site
     * that already works fine shouldn't pay that overhead or risk for nothing. */
    fun isImpersonateEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_IMPERSONATE_ENABLED, false)
    }

    fun setImpersonateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_IMPERSONATE_ENABLED, enabled).apply()
    }

    /** yt-dlp only — real multi-connection segmented downloading of a single file via a bundled
     * aria2c binary (see Aria2Runtime.kt's own doc comment for where it comes from and its actual
     * ~6.8MB footprint), instead of yt-dlp's own one-file-one-connection downloader. Meaningful on
     * a slow/high-latency connection, negligible on a fast one — off by default since it's a real
     * added app-size cost, not something to switch on for someone who never asked for it. */
    fun isAria2Enabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ARIA2_ENABLED, false)
    }

    fun setAria2Enabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ARIA2_ENABLED, enabled).apply()
    }

    /** When enabled (the default), MainScreen's own rate-limited engine check installs any update
     * it finds automatically instead of only flagging it for the user to apply by hand. */
    fun isAutoUpdateEnginesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_UPDATE_ENGINES, true)
    }

    fun setAutoUpdateEnginesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENGINES, enabled).apply()
    }

    // --- Imported from YTDLnis's own settings screens (see gallery-dl.md's "YTDLnis settings
    // import" entry), adapted to this app's engine (yt-dlp only for all of these — same precedent
    // as live_from_start/js_runtimes/impersonation elsewhere in this file: gallery-dl's own CLI-arg
    // shape doesn't map cleanly onto them, and its existing free-form "Extra arguments" field
    // already lets a power user pass the equivalent raw flag by hand). ---

    /** yt-dlp's own -4/--force-ipv4 (binds outgoing connections to 0.0.0.0, forcing IPv4
     * resolution) — useful on networks where IPv6 routing to a given site is broken/blocked but
     * IPv4 works fine. */
    fun isForceIpv4(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FORCE_IPV4, false)
    }

    fun setForceIpv4(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_IPV4, enabled).apply()
    }

    /** How many fragments (pieces of a single format — HLS/DASH segments, not separate
     * video+audio tracks) download in parallel. Separate from [getConcurrentDownloads], which
     * controls how many *whole downloads* run at once — this is yt-dlp's own
     * --concurrent-fragments, one format's own internal parallelism. 1 (sequential) matches
     * yt-dlp's built-in default. */
    fun getConcurrentFragments(context: Context): Int {
        return prefs(context).getInt(KEY_CONCURRENT_FRAGMENTS, DEFAULT_CONCURRENT_FRAGMENTS)
            .coerceIn(1, MAX_CONCURRENT_FRAGMENTS)
    }

    fun setConcurrentFragments(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_CONCURRENT_FRAGMENTS, count.coerceIn(1, MAX_CONCURRENT_FRAGMENTS)).apply()
    }

    /** Whether [getConcurrentFragments] actually applies — defaults true, harmless either way for
     * an install that never touched this (default value 1 is already a no-op downstream — see
     * DownloadWorker's own "only forward when > 1" check). Off forces that same no-op regardless
     * of the stored slider value. */
    fun isConcurrentFragmentsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONCURRENT_FRAGMENTS_ENABLED, true)
    }

    fun setConcurrentFragmentsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONCURRENT_FRAGMENTS_ENABLED, enabled).apply()
    }

    fun getEffectiveConcurrentFragments(context: Context): Int {
        if (!isConcurrentFragmentsEnabled(context)) return 1
        return getConcurrentFragments(context)
    }

    /** yt-dlp's own --no-check-certificate — skips TLS certificate validation. Only useful against
     * a self-signed/misconfigured server; off by default since it's a real security downgrade. */
    fun isNoCheckCertificates(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NO_CHECK_CERTIFICATES, false)
    }

    fun setNoCheckCertificates(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NO_CHECK_CERTIFICATES, enabled).apply()
    }

    /** The *ceiling* of a random 2-to-this-many-second pause before each yt-dlp request (the
     * floor is fixed at 2s in yt_dlp_wrapper.py, not user-configurable) — a real range, like
     * yt-dlp's own --min-sleep-interval/--max-sleep-interval pair is meant to be used, rather
     * than a fixed per-request delay a site's rate-limit/bot-detection could fingerprint as a
     * clockwork pattern. Eases the same pressure a lower concurrent-downloads count does, just
     * per-request instead of per-download-slot. */
    fun getSleepIntervalSeconds(context: Context): Int {
        return prefs(context).getInt(KEY_SLEEP_INTERVAL_SECONDS, MAX_SLEEP_INTERVAL_SECONDS / 2).coerceIn(2, MAX_SLEEP_INTERVAL_SECONDS)
    }

    fun setSleepIntervalSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_SLEEP_INTERVAL_SECONDS, seconds.coerceIn(2, MAX_SLEEP_INTERVAL_SECONDS)).apply()
    }

    /** Whether [getSleepIntervalSeconds] actually applies. Defaults false — unlike before this
     * became a real range, the "off" value (0, no delay) is no longer reachable within the
     * slider's own 2-20 range, so the toggle itself has to carry that no-op instead for a fresh
     * or never-touched install to see the same no-throttling behavior as always. */
    fun isSleepIntervalEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SLEEP_INTERVAL_ENABLED, false)
    }

    fun setSleepIntervalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SLEEP_INTERVAL_ENABLED, enabled).apply()
    }

    fun getEffectiveSleepIntervalSeconds(context: Context): Int {
        if (!isSleepIntervalEnabled(context)) return 0
        return getSleepIntervalSeconds(context)
    }

    /** Raw "Header-Name: value" lines (one per header), merged into yt-dlp's own http_headers dict
     * — its --add-header equivalent. A header here overrides yt-dlp's own default for the same
     * name (including User-Agent/Referer) rather than only adding new ones. */
    fun getCustomHeaders(context: Context): String {
        return prefs(context).getString(KEY_CUSTOM_HEADERS, "") ?: ""
    }

    fun setCustomHeaders(context: Context, headers: String) {
        prefs(context).edit().putString(KEY_CUSTOM_HEADERS, headers).apply()
    }

    /** Raw comma-separated yt-dlp format_sort terms (e.g. "codec:vp9,fps"), the same syntax
     * --format-sort takes on the real CLI — merged after (so it can still be overridden by) the
     * quality-cap/MP4-compatibility terms yt_dlp_wrapper.py already builds internally. Matches the
     * existing "yt-dlp extractor arguments" field's raw-CLI-syntax precedent rather than a
     * per-codec dropdown UI. */
    fun getFormatSort(context: Context): String {
        return prefs(context).getString(KEY_FORMAT_SORT, "") ?: ""
    }

    fun setFormatSort(context: Context, formatSort: String) {
        prefs(context).edit().putString(KEY_FORMAT_SORT, formatSort.trim()).apply()
    }

    /** yt-dlp's own --verbose — every internal debug line (format selection, extractor traces,
     * ...), not just the [title]/[progress]/[error] lines this app's own UI already parses. Off by
     * default; meant for troubleshooting a failure with the log around it, not everyday use. */
    fun isVerboseLogging(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VERBOSE_LOGGING, false)
    }

    fun setVerboseLogging(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERBOSE_LOGGING, enabled).apply()
    }

    /** Whether [getDownloadDelaySeconds] actually applies — same on/off-plus-value shape as
     * [isMaxFilesizeEnabled]/[getMaxFilesize], rather than overloading 0 seconds as "off": leaves
     * the last-typed value in place so re-enabling doesn't lose it. */
    fun isDownloadDelayEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DOWNLOAD_DELAY_ENABLED, false)
    }

    fun setDownloadDelayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DOWNLOAD_DELAY_ENABLED, enabled).apply()
    }

    /** Seconds to wait after one queued download finishes before the next one in the same
     * concurrency lane starts — DownloadDispatcher applies this as each dispatched WorkRequest's
     * own initial delay, which (since a lane is a real WorkManager dependency chain — see
     * DownloadDispatcher's own comment on that) means "wait N seconds after the previous item in
     * this lane" exactly, not merely "wait N seconds after being enqueued." Only takes effect when
     * [isDownloadDelayEnabled] is also true — see [getEffectiveDownloadDelaySeconds]. */
    fun getDownloadDelaySeconds(context: Context): Int {
        return prefs(context).getInt(KEY_DOWNLOAD_DELAY_SECONDS, 0).coerceIn(0, 3600)
    }

    fun setDownloadDelaySeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_DOWNLOAD_DELAY_SECONDS, seconds.coerceIn(0, 3600)).apply()
    }

    /** The value to actually apply — 0 whenever the toggle is off, so call sites don't need to
     * re-check [isDownloadDelayEnabled] themselves (same shape as [getEffectiveMaxFilesize]). */
    fun getEffectiveDownloadDelaySeconds(context: Context): Int {
        if (!isDownloadDelayEnabled(context)) return 0
        return getDownloadDelaySeconds(context)
    }

    /** Default for a new download's own DownloadEntity.incognito flag (still overridable per-
     * download, same relationship as every other "global default, per-download override" setting
     * in this file). An incognito download still saves its real file to the gallery/Downloads
     * folder like normal — only its own row in this app's Library/queue history is removed once it
     * finishes, so nothing about *what got downloaded* is retained here afterward. */
    fun isIncognitoDefault(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_INCOGNITO_DEFAULT, false)
    }

    fun setIncognitoDefault(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCOGNITO_DEFAULT, enabled).apply()
    }

    // --- Second YTDLnis settings-import batch (see gallery-dl.md's "YTDLnis settings import,
    // round 2" entry) — genuine gaps found by reading every one of YTDLnis's own settings XML
    // files directly, not just the subset already covered by the first batch. ---

    /** yt-dlp's own --embed-chapters (FFmpegMetadata's add_chapters kwarg) — muxes the source's
     * chapter markers into the file, independent of [isEmbedMetadata]. */
    fun isEmbedChapters(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_EMBED_CHAPTERS, false)
    }

    fun setEmbedChapters(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EMBED_CHAPTERS, enabled).apply()
    }

    /** Saves the subtitle track as its own sidecar file (.srt/.vtt) alongside the video — a
     * separate choice from [isDownloadSubtitles], which only ever muxes the track into the video
     * itself. Both can be on at once (embed AND keep the sidecar). */
    fun isSaveSubtitleFiles(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SAVE_SUBTITLE_FILES, false)
    }

    fun setSaveSubtitleFiles(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_SUBTITLE_FILES, enabled).apply()
    }

    /** Whether a failed/errored download's staging directory (partially-fetched fragments, etc.)
     * is deleted automatically (the default, matching this app's existing behavior) or left in
     * place for manual inspection/resume. */
    fun isDeleteLeftoverOnFailure(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DELETE_LEFTOVER_ON_FAILURE, true)
    }

    fun setDeleteLeftoverOnFailure(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DELETE_LEFTOVER_ON_FAILURE, enabled).apply()
    }

    /** Periodic sweep (ported from YTDLnis's own "Clean-up leftover downloads (cancelled,
     * errored)") that catches what [isDeleteLeftoverOnFailure] can't: a staging directory left
     * behind because the process was killed outright (OS out-of-memory kill, force-stop, a crash)
     * before that per-download cleanup code ever got to run, or because the setting was off at the
     * time. "" means disabled (the default — matches YTDLnis's own default of unset/disabled). See
     * StagingCleanupWorker. */
    fun getCleanupLeftoverInterval(context: Context): String {
        return prefs(context).getString(KEY_CLEANUP_LEFTOVER_INTERVAL, "") ?: ""
    }

    fun setCleanupLeftoverInterval(context: Context, interval: String) {
        prefs(context).edit().putString(KEY_CLEANUP_LEFTOVER_INTERVAL, interval).apply()
    }

    /** When enabled, a new download whose URL exactly matches an existing non-terminal
     * (QUEUED/RUNNING/PAUSED) or already-FINISHED entry is skipped instead of creating a second,
     * redundant row — see DownloadDispatcher.enqueueDownload's own use of this. */
    fun isPreventDuplicateDownloads(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PREVENT_DUPLICATE_DOWNLOADS, false)
    }

    fun setPreventDuplicateDownloads(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREVENT_DUPLICATE_DOWNLOADS, enabled).apply()
    }

    /** When enabled, changing the quality chip on the download preview sheet also updates the
     * global default (same relationship as every other "global default, per-download override"
     * setting in this file) — the next download starts pre-selected at whatever was last picked,
     * instead of always resetting to the stored global default. */
    fun isRememberDownloadType(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_REMEMBER_DOWNLOAD_TYPE, false)
    }

    fun setRememberDownloadType(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMEMBER_DOWNLOAD_TYPE, enabled).apply()
    }

    /** A user-chosen SAF folder for audio-only downloads specifically — falls back to
     * [getDownloadLocationUri] (the shared default), then the built-in Pictures/gallery-dl
     * location, when unset. Mirrors [getVideoLocationUri] for the video side. */
    fun getAudioLocationUri(context: Context): Uri? {
        val stored = prefs(context).getString(KEY_AUDIO_LOCATION_URI, null) ?: return null
        return runCatching { Uri.parse(stored) }.getOrNull()
    }

    fun setAudioLocationUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_AUDIO_LOCATION_URI, uri?.toString()).apply()
    }

    /** A user-chosen SAF folder for video downloads specifically — see [getAudioLocationUri]. */
    fun getVideoLocationUri(context: Context): Uri? {
        val stored = prefs(context).getString(KEY_VIDEO_LOCATION_URI, null) ?: return null
        return runCatching { Uri.parse(stored) }.getOrNull()
    }

    fun setVideoLocationUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_VIDEO_LOCATION_URI, uri?.toString()).apply()
    }

    /** yt-dlp's own --restrict-filenames (ASCII-only, no spaces/special characters in the
     * output filename) — was previously hardcoded on unconditionally in yt_dlp_wrapper.py; now a
     * real Settings choice. Defaults to true to match that previous always-on behavior exactly,
     * so leaving this alone changes nothing for an existing install. */
    fun isRestrictFilenames(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_RESTRICT_FILENAMES, true)
    }

    fun setRestrictFilenames(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESTRICT_FILENAMES, enabled).apply()
    }

    /** Whether [DEFAULT_FILENAME_FORMAT]'s title field is capped at 150 bytes (the default,
     * keeping filenames from an overlong caption unreasonably long) or left uncapped. Only
     * affects the *default* template — a custom saved template's own length is never touched. */
    fun isTrimFilenames(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TRIM_FILENAMES, true)
    }

    fun setTrimFilenames(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRIM_FILENAMES, enabled).apply()
    }
}
