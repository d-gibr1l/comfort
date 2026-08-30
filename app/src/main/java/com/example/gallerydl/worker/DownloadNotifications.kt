package com.example.gallerydl.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.gallerydl.R
import com.example.gallerydl.ui.main.formatFileSize

object DownloadNotifications {
    const val CHANNEL_ID = "downloads"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows download progress and completion"
        }
        manager.createNotificationChannel(channel)
    }

    fun notificationId(downloadId: String): Int = downloadId.hashCode()

    /** A deliberately *different* id from [notificationId] for the terminal (finished/failed)
     * notification — sharing the ongoing notification's id doesn't work: that id is also what
     * setForeground(ForegroundInfo(...)) in DownloadWorker registers as *the* foreground-service
     * notification, and the instant doWork() returns (right after notifyFinished()/notifyFailed()
     * posts its update), WorkManager tears the foreground service down — which removes whatever
     * notification currently sits at that id, silently wiping out the finished/failed notification
     * that was just posted a moment earlier. Reproduced live: notifyFailed()'s own notify() call
     * showed up correctly in logcat every time, but nothing ever appeared in the shade. A distinct
     * id sidesteps the whole race — it's never tied to the foreground service, so nothing tears it
     * down when the worker finishes. xor rather than +1 so it can't collide via integer overflow at
     * the Int.MAX_VALUE/MIN_VALUE edges the way a plain increment could. */
    private fun terminalNotificationId(downloadId: String): Int = notificationId(downloadId) xor 0x5A5A5A5A

    private fun actionPendingIntent(context: Context, action: String, downloadId: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
        }
        // Distinct request codes per (action, id) so Pause/Cancel PendingIntents for different
        // downloads don't collide and overwrite each other's extras.
        val requestCode = (action + downloadId).hashCode()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** [progressPercent] null means indeterminate (still extracting, or genuinely nothing known
     * about size/item-count yet) — otherwise a real 0-100 value renders an actual filling bar
     * instead of the perpetual spinner this used to hardcode regardless of how much was actually
     * known, which was the whole reason download notifications never visibly showed progress.
     * [speedMbs]/[currentBytes]/[expectedBytes] are only meaningfully non-zero for a byte-tracked
     * single-file download (see DownloadWorker's own comment on why item-count wins over bytes for
     * a multi-item gallery) — a gallery download just falls back to the item-count text below,
     * same as before this was added. */
    fun progressNotification(
        context: Context,
        title: String,
        downloadId: String,
        downloadedItems: Int,
        progressPercent: Int? = null,
        speedMbs: Float = 0f,
        currentBytes: Long = 0L,
        expectedBytes: Long = 0L,
    ): Notification {
        ensureChannel(context)
        val sizeText = if (expectedBytes > 0) "${formatFileSize(currentBytes)} of ${formatFileSize(expectedBytes)}" else null
        val speedText = if (speedMbs > 0.01f) "%.1f MB/s".format(speedMbs) else null
        val byteDetail = listOfNotNull(sizeText, speedText).joinToString(" · ")
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_logo)
            .setContentTitle(title)
            .setContentText(
                when {
                    // Real byte-level detail (size + speed) already implies progress more
                    // usefully than a bare percent would, so it takes priority when available.
                    byteDetail.isNotBlank() -> byteDetail
                    progressPercent != null -> "$progressPercent% · ${if (downloadedItems > 0) "$downloadedItems downloaded" else "Downloading…"}"
                    downloadedItems > 0 -> "$downloadedItems downloaded"
                    else -> "Starting…"
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent ?: 0, progressPercent == null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0, "Pause",
                actionPendingIntent(context, DownloadActionReceiver.ACTION_PAUSE, downloadId),
            )
            .addAction(
                0, "Cancel",
                actionPendingIntent(context, DownloadActionReceiver.ACTION_CANCEL, downloadId),
            )
            .build()
    }

    fun updateProgress(
        context: Context,
        downloadId: String,
        title: String,
        downloadedItems: Int,
        progressPercent: Int? = null,
        speedMbs: Float = 0f,
        currentBytes: Long = 0L,
        expectedBytes: Long = 0L,
    ) {
        notifySafe(
            context, downloadId,
            progressNotification(context, title, downloadId, downloadedItems, progressPercent, speedMbs, currentBytes, expectedBytes),
        )
    }

    fun notifyFinished(context: Context, downloadId: String, title: String, downloadedItems: Int, thumbnailUri: String?) {
        val text = if (downloadedItems > 0) "$downloadedItems picture${if (downloadedItems == 1) "" else "s"} saved" else "Nothing new to download"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // A distinct checkmark icon, not the plain app icon the ongoing/progress notification
            // still uses — so "this one finished" is visible at a glance in the shade/status bar
            // without having to read the text.
            .setSmallIcon(R.drawable.ic_notif_success)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (thumbnailUri != null) {
            val uri = Uri.parse(thumbnailUri)
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val requestBase = downloadId.hashCode()
            builder
                .setContentIntent(
                    PendingIntent.getActivity(context, requestBase, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
                .addAction(
                    0, "Open",
                    PendingIntent.getActivity(context, requestBase + 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
                .addAction(
                    0, "Share",
                    PendingIntent.getActivity(
                        context, requestBase + 2,
                        Intent.createChooser(shareIntent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
        }
        notifySafe(context, downloadId, builder.build(), terminalNotificationId(downloadId))
    }

    fun notifyFailed(context: Context, downloadId: String, title: String, errorMessage: String? = null) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Same idea as notifyFinished's checkmark, mirrored — a distinct error glyph instead
            // of the plain app icon, so a failure reads as clearly wrong at a glance.
            .setSmallIcon(R.drawable.ic_notif_error)
            .setContentTitle(title)
            .setContentText(errorMessage?.takeIf { it.isNotBlank() } ?: "Download failed")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifySafe(context, downloadId, notification, terminalNotificationId(downloadId))
    }

    fun cancel(context: Context, downloadId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(downloadId))
        NotificationManagerCompat.from(context).cancel(terminalNotificationId(downloadId))
    }

    /** [id] defaults to the ongoing/progress id ([notificationId]) so [updateProgress]'s existing
     * call site doesn't need to change — [notifyFinished]/[notifyFailed] explicitly pass
     * [terminalNotificationId] instead, per the doc comment on that function. */
    private fun notifySafe(context: Context, downloadId: String, notification: Notification, id: Int = notificationId(downloadId)) {
        ensureChannel(context)
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
