package com.example.gallerydl.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.gallerydl.data.DownloadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the Pause/Cancel buttons attached directly to a download's progress notification, so
 * acting on a download doesn't require opening the app and finding it in the Queue first. */
class DownloadActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAUSE = "com.example.gallerydl.action.PAUSE_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.gallerydl.action.CANCEL_DOWNLOAD"
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
                    ACTION_CANCEL -> DownloadDispatcher.deleteDownload(appContext, id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
