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

    fun progressNotification(context: Context, title: String, downloadId: String, downloadedItems: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (downloadedItems > 0) "$downloadedItems downloaded" else "Starting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
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

    fun updateProgress(context: Context, downloadId: String, title: String, downloadedItems: Int) {
        notifySafe(context, downloadId, progressNotification(context, title, downloadId, downloadedItems))
    }

    fun notifyFinished(context: Context, downloadId: String, title: String, downloadedItems: Int, thumbnailUri: String?) {
        val text = if (downloadedItems > 0) "$downloadedItems picture${if (downloadedItems == 1) "" else "s"} saved" else "Nothing new to download"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        notifySafe(context, downloadId, builder.build())
    }

    fun notifyFailed(context: Context, downloadId: String, title: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Download failed")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifySafe(context, downloadId, notification)
    }

    fun cancel(context: Context, downloadId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(downloadId))
    }

    private fun notifySafe(context: Context, downloadId: String, notification: Notification) {
        ensureChannel(context)
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            NotificationManagerCompat.from(context).notify(notificationId(downloadId), notification)
        }
    }
}
