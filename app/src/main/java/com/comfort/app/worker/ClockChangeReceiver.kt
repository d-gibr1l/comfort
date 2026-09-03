package com.comfort.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.comfort.app.data.DownloadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The Schedule window's delay is computed once, as a relative `setInitialDelay` millisecond
 * offset from "now" (see DownloadDispatcher.scheduleDelayMillis/enqueueWork) — WorkManager has no
 * concept of an absolute wall-clock target, it just counts down that duration. If the user
 * manually sets the clock forward/back, or crosses a timezone (or DST flips) while a job is
 * sitting on that delay, the countdown keeps ticking against the *old* notion of "now", so the job
 * fires at the wrong real-world local time relative to the configured window.
 *
 * Manifest-registered for ACTION_TIME_CHANGED/ACTION_TIMEZONE_CHANGED specifically because those
 * two remain exempt from Android 8's implicit-broadcast restrictions (unlike most other system
 * broadcasts, which require a runtime-registered receiver to still be delivered here). */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Re-submits every not-yet-started download's WorkManager job so its delay gets
                // recomputed against the schedule window using the clock/timezone that's now
                // current — the same recovery rescheduleQueuedDownloads already does for an actual
                // schedule-setting change.
                DownloadDispatcher.rescheduleQueuedDownloads(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
