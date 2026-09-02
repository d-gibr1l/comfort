package com.comfort.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.comfort.app.data.DownloadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the Pause/Cancel buttons attached directly to a download's progress notification, so
 * acting on a download doesn't require opening the app and finding it in the Queue first. */
class DownloadActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAUSE = "com.comfort.app.action.PAUSE_DOWNLOAD"
        const val ACTION_CANCEL = "com.comfort.app.action.CANCEL_DOWNLOAD"
        // The "Resume" action on the static paused notification (see DownloadNotifications.
        // notifyPaused) — the pause/cancel actions above only ever act on something already
        // running, so this is the first action here that has to re-submit a WorkManager job
        // rather than just stop one.
        const val ACTION_RESUME = "com.comfort.app.action.RESUME_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "downloadId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> DownloadDispatcher.pauseDownload(appContext, id)
                    ACTION_CANCEL -> DownloadDispatcher.cancelDownload(appContext, id)
                    ACTION_RESUME -> DownloadDispatcher.resumeDownload(appContext, id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
