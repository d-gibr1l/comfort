package com.comfort.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.comfort.app.data.DownloadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires when the Schedule window's exact-alarm backstop (DownloadDispatcher.scheduleWindowAlarm,
 * "Use alarm for scheduling" in Settings > Downloads > Schedule) wakes the device near the
 * window's real open time. Just re-submits everything still waiting — the same recovery
 * ClockChangeReceiver already uses for a clock/timezone change — since scheduleDelayMillis() now
 * computes ~0 once the window has actually opened, dispatching for real instead of trusting
 * WorkManager's own countdown (which Doze may have silently deferred past this exact moment). */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DownloadDispatcher.rescheduleQueuedDownloads(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
