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

    fun setConcurrentDownloadsEnabled(enabled: Boolean) {
        GalleryDlPreferences.setConcurrentDownloadsEnabled(context, enabled)
        rescheduleQueue()
    }

    /** Without the reschedule, everything already queued stays chained in the lane(s) it was first
     * assigned to and keeps running at the old concurrency — the new count only applied to
     * downloads added after the change. */
    fun setConcurrentDownloads(count: Int) {
        prefs.edit().putInt(GalleryDlPreferences.KEY_CONCURRENT_DOWNLOADS, count).apply()
        rescheduleQueue()
    }

    fun setSpeedLimitEnabled(enabled: Boolean) {
        GalleryDlPreferences.setSpeedLimitEnabled(context, enabled)
        restartRunning()
    }

    fun setSpeedLimit(limit: String) {
        GalleryDlPreferences.setSpeedLimit(context, limit)
        restartRunning()
    }

    fun setProxyEnabled(enabled: Boolean) {
        GalleryDlPreferences.setProxyEnabled(context, enabled)
        restartRunning()
    }

    private var proxyRestart: Job? = null

    /** Saved on every keystroke; running downloads restart once typing settles for 800 ms, not on
     * every character. */
    fun setProxyUrl(url: String) {
        GalleryDlPreferences.setProxyUrl(context, url)
        proxyRestart?.cancel()
        proxyRestart = viewModelScope.launch {
            delay(800)
            DownloadDispatcher.restartRunningDownloads(context)
        }
    }

    fun setMaxFilesizeEnabled(enabled: Boolean) {
        GalleryDlPreferences.setMaxFilesizeEnabled(context, enabled)
        restartRunning()
    }

    fun setMaxFilesize(size: String) {
        GalleryDlPreferences.setMaxFilesize(context, size)
        restartRunning()
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
        rescheduleQueue()
        DownloadDispatcher.scheduleWindowAlarm(context)
    }

    private fun rescheduleQueue() {
        viewModelScope.launch { DownloadDispatcher.rescheduleQueuedDownloads(context) }
    }

    private fun restartRunning() {
        viewModelScope.launch { DownloadDispatcher.restartRunningDownloads(context) }
    }
}
