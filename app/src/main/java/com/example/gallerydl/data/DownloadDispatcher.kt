package com.example.gallerydl.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.gallerydl.worker.DownloadNotifications
import com.example.gallerydl.worker.DownloadWorker
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Enqueue logic shared between the in-app ViewModel and the instant-share path (which runs
 * outside any Compose/ViewModel lifecycle, straight from the Activity handling a share intent). */
object DownloadDispatcher {
    // Round-robin slot for spreading downloads across N unique WorkManager queues, which is
    // how concurrency is controlled: each queue runs its jobs sequentially, so N queues == N
    // downloads in flight at once.
    private val nextQueueSlot = AtomicInteger(0)

    suspend fun enqueueDownload(context: Context, url: String, title: String, itemFilter: String? = null): String {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val id = UUID.randomUUID().toString()
        dao.insert(
            DownloadEntity(
                id = id,
                url = url,
                title = title,
                thumbnailPath = null,
                status = DownloadStatus.QUEUED,
                progress = 0f,
                downloadedItems = 0,
                totalItems = 0,
                speedMbs = 0f,
                etaSeconds = 0L,
                errorMessage = null,
                dateAdded = System.currentTimeMillis(),
                itemFilter = itemFilter,
            )
        )
        // While globally paused, new downloads sit in the queue undispatched — resumeAll()
        // picks up anything with no workRequestId yet, alongside whatever it un-pauses.
        if (!GalleryDlPreferences.isGloballyPaused(context)) {
            enqueueWork(context, id, url)
        }
        return id
    }

    /** (Re-)submits a WorkManager job for an existing download entry. gallery-dl's own download
     * archive (keyed by the download id) means a retry only fetches what's still missing. */
    suspend fun enqueueWork(context: Context, id: String, url: String) {
        val dao = AppDatabase.getDatabase(context).downloadDao()

        val networkType = if (GalleryDlPreferences.isWifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder().setRequiredNetworkType(networkType).build()
        val delayMillis = scheduleDelayMillis(context)

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("downloadId" to id, "url" to url))
            .setConstraints(constraints)
            .apply { if (delayMillis > 0) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS) }
            .build()

        dao.setWorkRequestId(id, workRequest.id.toString())

        val concurrentDownloads = GalleryDlPreferences.getConcurrentDownloads(context)
        val slot = nextQueueSlot.getAndUpdate { (it + 1) % concurrentDownloads }
        WorkManager.getInstance(context).enqueueUniqueWork(
            "gallery_dl_queue_$slot",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest,
        )
    }

    /** Cancels the in-flight WorkManager job (if any) and marks the entry paused. Callable from
     * anywhere — the ViewModel, or a notification action's BroadcastReceiver with no Activity. */
    suspend fun pauseDownload(context: Context, id: String) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return
        entity.workRequestId?.let { runCatching { UUID.fromString(it) } }?.getOrNull()?.let { uuid ->
            WorkManager.getInstance(context).cancelWorkById(uuid)
        }
        dao.updateStatus(id, DownloadStatus.CANCELLED)
        DownloadNotifications.cancel(context, id)
    }

    /** Cancels any in-flight job, drops the row, and cleans up its download-archive file. */
    suspend fun deleteDownload(context: Context, id: String) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id)
        entity?.workRequestId?.let { runCatching { UUID.fromString(it) } }?.getOrNull()?.let { uuid ->
            WorkManager.getInstance(context).cancelWorkById(uuid)
        }
        DownloadNotifications.cancel(context, id)
        File(context.filesDir, "archives/$id.sqlite3").delete()
        dao.delete(id)
    }

    /** Re-submits every not-yet-started download's WorkManager job so it recomputes its delay
     * against the *current* schedule settings. Call this right after the schedule is toggled or
     * its time window is changed — otherwise a job that was already queued with the old delay
     * baked in via setInitialDelay() just sits there until that original delay elapses, since
     * WorkManager never re-evaluates a fixed delay on its own (only true Constraints, like
     * network type, are continuously re-checked). */
    suspend fun rescheduleQueuedDownloads(context: Context) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        dao.getQueuedOnce().forEach { entity ->
            entity.workRequestId?.let { runCatching { UUID.fromString(it) } }?.getOrNull()?.let { uuid ->
                WorkManager.getInstance(context).cancelWorkById(uuid)
            }
            enqueueWork(context, entity.id, entity.url)
        }
    }

    /** Millis until the configured download window next opens, or 0 if downloads are allowed right now. */
    fun scheduleDelayMillis(context: Context): Long {
        if (!GalleryDlPreferences.isScheduleEnabled(context)) return 0L

        val startMin = GalleryDlPreferences.getScheduleStartMinutes(context)
        val endMin = GalleryDlPreferences.getScheduleEndMinutes(context)
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val withinWindow = if (startMin <= endMin) {
            nowMin in startMin..endMin
        } else {
            // window wraps past midnight, e.g. 22:00-06:00
            nowMin >= startMin || nowMin <= endMin
        }
        if (withinWindow) return 0L

        val minutesUntilStart = if (nowMin < startMin) startMin - nowMin else (24 * 60 - nowMin) + startMin
        return TimeUnit.MINUTES.toMillis(minutesUntilStart.toLong())
    }
}
