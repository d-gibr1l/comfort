package com.example.gallerydl.data

import android.content.Context
import android.net.Uri

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
    const val KEY_DOWNLOAD_LOCATION_URI = "download_location_uri"
    const val KEY_GLOBAL_PAUSE = "global_pause"
    const val KEY_INSTANT_SHARE = "instant_share"
    // The naive "{uploader} - {title} - {id}" pattern collapses to the literal string
    // "None - None - None" on sources that don't expose that metadata, which makes every item
    // in the gallery resolve to the same filename — only the first survives, the rest are
    // silently skipped as duplicates. {filename} is a field gallery-dl always populates (it's
    // what the default naming scheme is built from), so anchoring on it guarantees uniqueness
    // even when the human-readable fields are missing.
    private const val LEGACY_DEFAULT_FILENAME_FORMAT = "{uploader} - {title} - {id}.{extension}"
    const val DEFAULT_FILENAME_FORMAT = "{uploader|category} - {title|id} - {filename}.{extension}"
    const val DEFAULT_CONCURRENT_DOWNLOADS = 2
    const val MAX_CONCURRENT_DOWNLOADS = 5

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFilenameFormat(context: Context): String {
        val stored = prefs(context).getString(KEY_FILENAME_FORMAT, null)?.takeIf { it.isNotBlank() }
        if (stored == null || stored == LEGACY_DEFAULT_FILENAME_FORMAT) return DEFAULT_FILENAME_FORMAT
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
        prefs(context).edit().putString(KEY_SPEED_LIMIT, limit.trim()).apply()
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
}
