package com.comfort.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings > Downloads' settings that do more than save a value — each also re-plans downloads
 * that already exist:
 * - concurrency re-spreads the queued backlog over the new number of lanes;
 * - speed limit, proxy and max file size restart what's running, since an engine reads them only
 *   when it starts;
 * - the schedule window re-plans queued downloads and re-arms its alarm;
 * - leftover cleanup re-plans its periodic job.
 *
 * The page's plain on/off preferences still read and write GalleryDlPreferences directly. Work
 * runs in viewModelScope, so leaving the page mid-change (or within the proxy's typing debounce)
 * no longer drops the reschedule/restart, as the page's own coroutine scope used to. */
class DownloadsSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val prefs get() = context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private var queueRescheduleJob: Job? = null
    private var restartRunningJob: Job? = null

    fun setConcurrentDownloadsEnabled(enabled: Boolean) {
        GalleryDlPreferences.setConcurrentDownloadsEnabled(context, enabled)
        rescheduleQueue(delayMs = 250L)
    }

    /** Updates the Wi-Fi only constraint and immediately reschedules queued downloads so WorkManager
     * applies the updated network constraints (UNMETERED vs CONNECTED) to existing queue items. */
    fun setWifiOnly(enabled: Boolean) {
        GalleryDlPreferences.setWifiOnly(context, enabled)
        rescheduleQueue(delayMs = 250L)
    }

    /** Without the reschedule, everything already queued stays chained in the lane(s) it was first
     * assigned to and keeps running at the old concurrency — the new count only applied to
     * downloads added after the change. */
    fun setConcurrentDownloads(count: Int) {
        prefs.edit().putInt(GalleryDlPreferences.KEY_CONCURRENT_DOWNLOADS, count).apply()
        rescheduleQueue(delayMs = 250L)
    }

    fun setSpeedLimitEnabled(enabled: Boolean) {
        GalleryDlPreferences.setSpeedLimitEnabled(context, enabled)
        restartRunning(delayMs = 250L)
    }

    fun setSpeedLimit(limit: String) {
        GalleryDlPreferences.setSpeedLimit(context, limit)
        restartRunning(delayMs = 300L)
    }

    fun setProxyEnabled(enabled: Boolean) {
        GalleryDlPreferences.setProxyEnabled(context, enabled)
        val currentUrl = GalleryDlPreferences.getProxyUrl(context)
        if (!enabled || isValidProxyUrl(currentUrl)) {
            restartRunning(delayMs = 250L)
        }
    }

    /** Saved on every keystroke; running downloads restart once typing settles for 800 ms, not on
     * every character, and only if the proxy is enabled and the URL is valid. */
    fun setProxyUrl(url: String) {
        GalleryDlPreferences.setProxyUrl(context, url)
        if (!GalleryDlPreferences.isProxyEnabled(context)) {
            // Proxy is not active; editing the URL doesn't affect currently running downloads.
            return
        }
        if (!isValidProxyUrl(url)) {
            // Incomplete or invalid URL while typing — do not abort active downloads.
            return
        }
        restartRunning(delayMs = 800L)
    }

    fun setMaxFilesizeEnabled(enabled: Boolean) {
        GalleryDlPreferences.setMaxFilesizeEnabled(context, enabled)
        restartRunning(delayMs = 250L)
    }

    fun setMaxFilesize(size: String) {
        GalleryDlPreferences.setMaxFilesize(context, size)
        restartRunning(delayMs = 300L)
    }

    /** A download queued under the old window would otherwise sit until its stale delay elapses —
     * see DownloadDispatcher.rescheduleQueuedDownloads. */
    fun setScheduleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(GalleryDlPreferences.KEY_SCHEDULE_ENABLED, enabled).apply()
        scheduleChanged()
    }

    fun setScheduleStart(minutes: Int) {
        prefs.edit().putInt(GalleryDlPreferences.KEY_SCHEDULE_START_MIN, minutes).apply()
        scheduleChanged()
    }

    fun setScheduleEnd(minutes: Int) {
        prefs.edit().putInt(GalleryDlPreferences.KEY_SCHEDULE_END_MIN, minutes).apply()
        scheduleChanged()
    }

    fun setAlarmSchedulingEnabled(enabled: Boolean) {
        GalleryDlPreferences.setAlarmSchedulingEnabled(context, enabled)
        DownloadDispatcher.scheduleWindowAlarm(context)
    }

    fun setDeleteLeftoverOnFailure(enabled: Boolean) {
        GalleryDlPreferences.setDeleteLeftoverOnFailure(context, enabled)
        DownloadDispatcher.rescheduleStagingCleanup(context)
    }

    fun setCleanupLeftoverInterval(interval: String) {
        GalleryDlPreferences.setCleanupLeftoverInterval(context, interval)
        DownloadDispatcher.rescheduleStagingCleanup(context)
    }

    private fun scheduleChanged() {
        rescheduleQueue(delayMs = 250L)
        DownloadDispatcher.scheduleWindowAlarm(context)
    }

    private fun rescheduleQueue(delayMs: Long = 250L) {
        queueRescheduleJob?.cancel()
        queueRescheduleJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            DownloadDispatcher.rescheduleQueuedDownloads(context)
        }
    }

    private fun restartRunning(delayMs: Long = 300L) {
        restartRunningJob?.cancel()
        restartRunningJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            DownloadDispatcher.restartRunningDownloads(context)
        }
    }

    companion object {
        val PROXY_URL_REGEX = Regex("^(https?|socks[45][ah]?)://.+", RegexOption.IGNORE_CASE)

        fun isValidProxyUrl(url: String): Boolean {
            val trimmed = url.trim()
            return trimmed.isEmpty() || PROXY_URL_REGEX.matches(trimmed)
        }
    }
}
