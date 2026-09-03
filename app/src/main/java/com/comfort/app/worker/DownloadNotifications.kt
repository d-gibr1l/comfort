package com.comfort.app.worker

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
import android.os.Handler
import android.os.Looper
import com.comfort.app.R
import com.comfort.app.ui.main.formatFileSize
import java.util.concurrent.atomic.AtomicInteger

object DownloadNotifications {
    const val CHANNEL_ID = "downloads"

    // How many DownloadWorkers currently have the shared foreground notification "checked out" —
    // see FOREGROUND_SERVICE_NOTIFICATION_ID's doc comment. Only cancel that notification once
    // this drops back to zero, so one download finishing doesn't wipe it out from under sibling
    // downloads still running concurrently in other WorkManager queues.
    private val activeForegroundCount = AtomicInteger(0)

    /** Call once, right after setForeground(ForegroundInfo(FOREGROUND_SERVICE_NOTIFICATION_ID, ...))
     * succeeds. Must be paired with exactly one [markForegroundStopped] call (in a finally block)
     * regardless of how the worker finishes — success, failure, or cancellation. */
    fun markForegroundStarted() {
        activeForegroundCount.incrementAndGet()
    }

    /** Call once doWork() is about to return, unconditionally (finally block) — decrements the
     * count and, once no worker is using the shared foreground notification any more, explicitly
     * cancels it. Needed because WorkManager's own teardown of it isn't reliable on every device:
     * reproduced live on this one, the notification was left permanently stuck (flags
     * ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE, not even swipeable) minutes after every worker
     * using it had already returned and dumpsys activity services confirmed no service was even
     * running any more — this cancel() call is what actually clears it, since by this point no
     * foreground service is bound to it any more (that binding, while it lasts, is what makes the
     * platform refuse a plain NotificationManager.cancel() on this same id — verified live too:
     * calling cancel() on it earlier, before every worker had returned, silently did nothing). */
    fun markForegroundStopped(context: Context) {
        if (activeForegroundCount.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
            val appContext = context.applicationContext
            // A cancel() fired synchronously here, right as doWork() is returning, sometimes lost
            // the race against WorkManager's own (also-unreliable) foreground teardown re-touching
            // this same id right after — reproduced live: the notification came right back even
            // though activeForegroundCount had already reached zero and this ran. A short delay
            // lets that settle first.
            Handler(Looper.getMainLooper()).postDelayed({
                // Only actually cancel if still nobody's using it — a new download could have
                // started during the delay.
                if (activeForegroundCount.get() == 0) {
                    NotificationManagerCompat.from(appContext).cancel(FOREGROUND_SERVICE_NOTIFICATION_ID)
                }
            }, 1500)
        }
    }

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

    /** The *only* id ever passed to setForeground(ForegroundInfo(...)) in DownloadWorker — a
     * single id shared by every concurrently running download, never a per-download one.
     *
     * Earlier versions of this file gave each download's own ongoing/progress notification the
     * FOREGROUND_SERVICE role directly (id = notificationId(downloadId)), and separately tried a
     * distinct id just for the terminal (finished/failed) notification to dodge WorkManager
     * wiping it out when the foreground service tore down. That papered over one symptom but not
     * the real problem: reproduced live that the *ongoing* notification itself then got stuck
     * forever — flags ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE still set, not even swipeable,
     * long after dumpsys confirmed the underlying service was fully destroyed. Android refuses to
     * let a plain NotificationManager.cancel() remove a notification that was ever posted via
     * startForeground()/ForegroundInfo — only the owning service calling stopForeground() can —
     * and WorkManager's own teardown of that specific id just wasn't reliably happening on this
     * device/OS build (also reproduced live, including with a deliberate post-return delay before
     * cancelling — still stuck).
     *
     * The fix is to never let a per-download notification carry the FOREGROUND_SERVICE flag at
     * all. This fixed, generic id is the one and only notification WorkManager ever manages via
     * ForegroundInfo — sharing one id across every concurrently running worker is a pattern
     * WorkManager explicitly supports (it keeps the shared notification up as long as *any*
     * worker using that id is still foreground, and only tears it down once the last one
     * finishes), so its teardown machinery gets exercised the way it's actually designed for,
     * rather than once per unique per-download id. Every notification the user actually reads
     * (progress/finished/failed) is posted separately via a plain notify() to
     * [notificationId], completely untouched by setForeground — always freely updatable and
     * cancellable, no service lifecycle involved. */
    const val FOREGROUND_SERVICE_NOTIFICATION_ID = 0x646C664B // "dlfK" — arbitrary but stable

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

    /** The generic, non-cancellable notification posted only via setForeground(ForegroundInfo(...))
     * at [FOREGROUND_SERVICE_NOTIFICATION_ID] — deliberately content-free about *which* download
     * is running (that's what the real per-download notification from [progressNotification],
     * posted separately, is for). Shared across every concurrently running worker, so its exact
     * text is necessarily generic ("Downloading…") rather than naming any one of them. */
    fun foregroundServiceNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_logo)
            .setContentTitle("Downloading…")
            .setContentText("Comfort is downloading in the background")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
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
        // "picture(s)" used to be hardcoded here regardless of what actually got saved — a
        // downloaded video or audio file still read "1 picture saved," which looked like a bug.
        val text = if (downloadedItems > 0) "$downloadedItems item${if (downloadedItems == 1) "" else "s"} saved" else "Nothing new to download"
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
            // Used to hardcode "image/*" here regardless of what actually got saved — harmless for
            // a gallery-dl picture, but a yt-dlp video (a single-item download, so this is always
            // the real saved MediaStore URI by the time a download finishes — see
            // DownloadWorker's own setThumbnail/setThumbnailIfAbsent split) forced Gallery apps to
            // open it as a static image (no playback controls) and made share targets like
            // WhatsApp reject it outright as a fake image. contentResolver.getType() reads the
            // real MIME type back from MediaStore for the content:// URI this normally is; falls
            // back to the old "image/*" only for a multi-item gallery download's remote preview
            // URL (not a content:// URI ContentResolver can resolve at all), which is still
            // usually a picture anyway.
            val mimeType = context.contentResolver.getType(uri) ?: "image/*"
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
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
                        Intent.createChooser(shareIntent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
        }
        // Same id as the ongoing progress notification — since that id is never the
        // FOREGROUND_SERVICE one anymore (see FOREGROUND_SERVICE_NOTIFICATION_ID's doc comment),
        // this plain notify() call just replaces it in place, cleanly and immediately, no
        // separate cancel needed.
        notifySafe(context, downloadId, builder.build())
    }

    /** Replaces the ongoing progress notification with a static "Paused" one carrying a Resume
     * action, instead of pauseDownload() just cancelling it outright — reproduced live: tapping
     * Pause directly from the notification (its own Pause action button, not the in-app one) made
     * the notification vanish entirely, leaving no way to resume without opening the app and
     * finding the item in Queue by hand. Not ongoing/auto-cancel, same id as the progress
     * notification it replaces, so it just sits in the shade until Resume is tapped or the user
     * dismisses it — dismissing it doesn't itself resume or otherwise change the download. */
    fun notifyPaused(context: Context, downloadId: String, title: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_logo)
            .setContentTitle(title)
            .setContentText("Paused")
            .setOngoing(false)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0, "Resume",
                actionPendingIntent(context, DownloadActionReceiver.ACTION_RESUME, downloadId),
            )
            .build()
        notifySafe(context, downloadId, notification)
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
