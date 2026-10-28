package com.comfort.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.comfort.app.data.DownloadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** After a restart (or an update of the app), what the app otherwise only does when it's opened
 * (MainActivity): the Schedule window's alarm backstop is re-armed — Android clears every alarm on
 * a restart, so a phone restarted overnight lost it until the app was next opened — and the
 * queue's repairs run, so a download whose job didn't come back waits for its window again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { DownloadDispatcher.repairOrphanedRunning(appContext) }
                runCatching { DownloadDispatcher.repairOrphanedQueue(appContext) }
                DownloadDispatcher.scheduleWindowAlarm(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
