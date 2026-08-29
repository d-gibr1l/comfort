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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Enqueue logic shared between the in-app ViewModel and the instant-share path (which runs
 * outside any Compose/ViewModel lifecycle, straight from the Activity handling a share intent). */
object DownloadDispatcher {
    // Round-robin slot for spreading downloads across N unique WorkManager queues, which is
    // how concurrency is controlled: each queue runs its jobs sequentially, so N queues == N
    // downloads in flight at once. (True process-level concurrency via WorkManager's own
    // work-multiprocess library was attempted and reverted — see conversation history: it hit a
    // reproducible "Tried to marshall a Parcel that contains objects" crash inside its own
    // internals that couldn't be resolved from app code. This N-queue split now really does mean
    // N genuinely concurrent downloads, since PythonRuntime runs each one as its own real OS
    // subprocess — gallery-dl/yt-dlp calls used to all be serialized through one shared embedded
    // interpreter (PythonEngineLock, since removed) regardless of how many queues this spread
    // them across.)
    private val nextQueueSlot = AtomicInteger(0)

    // Serializes cancel+enqueue for a given download id. Without this, two callers racing for the
    // same id (e.g. rescheduleQueuedDownloads() firing twice from a quick double-toggle, or
    // resumeAll() overlapping a retry) can both read the same stale workRequestId, both cancel it
    // harmlessly, then both submit a brand new WorkRequest — landing in two different round-robin
    // queue slots, so neither replaces the other and gallery-dl ends up running the same download
    // twice concurrently. Confirmed happening in the wild via logcat before this fix.
    private val locks = ConcurrentHashMap<String, Mutex>()
    private suspend fun <T> withDownloadLock(id: String, block: suspend () -> T): T =
        // Kotlin's getOrPut() extension on ConcurrentHashMap is NOT atomic (separate get()+put()),
        // so two concurrent first-time calls for the same id could each create and lock their own
        // separate Mutex instance, running unsynchronized — silently defeating this whole guard.
        // computeIfAbsent() is the JDK's real atomic check-and-set.
        locks.computeIfAbsent(id) { Mutex() }.withLock { block() }

    private fun cancelWorkManagerJob(context: Context, workRequestId: String?) {
        workRequestId?.let { runCatching { UUID.fromString(it) } }?.getOrNull()?.let { uuid ->
            WorkManager.getInstance(context).cancelWorkById(uuid)
        }
    }

    suspend fun enqueueDownload(context: Context, url: String, title: String, itemFilter: String? = null, totalItems: Int = 0, videoQuality: VideoQuality? = null): String {
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
                // Known for free when this came from the share-sheet item picker (it already
                // enumerated the gallery to render itself); left at 0 — "unknown" — otherwise, in
                // which case DownloadWorker enumerates it itself before starting the real download.
                totalItems = totalItems,
                speedMbs = 0f,
                etaSeconds = 0L,
                errorMessage = null,
                dateAdded = System.currentTimeMillis(),
                itemFilter = itemFilter,
                videoQuality = videoQuality?.name,
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
     * archive (keyed by the download id) means a retry only fetches what's still missing.
     * [forceImmediate] skips the schedule-window delay entirely — see [startNow]. */
    suspend fun enqueueWork(context: Context, id: String, url: String, forceImmediate: Boolean = false) = withDownloadLock(id) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        // Cancel whatever job is already associated with this id first, atomically with the new
        // submission below — closes the race where a second caller for the same id would
        // otherwise interleave its own cancel+submit and end up running two WorkRequests for the
        // same download concurrently (see the lock's own doc comment above).
        cancelWorkManagerJob(context, dao.getById(id)?.workRequestId)

        val networkType = if (GalleryDlPreferences.isWifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder().setRequiredNetworkType(networkType).build()
        val delayMillis = if (forceImmediate) 0L else scheduleDelayMillis(context)

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("downloadId" to id, "url" to url))
            .setConstraints(constraints)
            .apply { if (delayMillis > 0) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS) }
            .build()

        dao.setWorkRequestId(id, workRequest.id.toString())
        // SCHEDULED vs QUEUED distinguishes "waiting on the schedule window" from "waiting on a
        // concurrency slot" in the UI — recomputed on every (re-)enqueue so a schedule change
        // flips this correctly for anything rescheduleQueuedDownloads() re-submits.
        dao.updateStatus(id, if (delayMillis > 0) DownloadStatus.SCHEDULED else DownloadStatus.QUEUED)

        val concurrentDownloads = GalleryDlPreferences.getConcurrentDownloads(context)
        val slot = nextQueueSlot.getAndUpdate { (it + 1) % concurrentDownloads }
        WorkManager.getInstance(context).enqueueUniqueWork(
            "gallery_dl_queue_$slot",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest,
        )
    }

    /** Cancels the in-flight WorkManager job (if any) and marks the entry paused — distinct from
     * [cancelDownload] so a global pauseAll()/resumeAll() cycle only ever touches downloads it
     * itself held back, never ones the user explicitly cancelled. Callable from anywhere — the
     * ViewModel, or a notification action's BroadcastReceiver with no Activity. */
    suspend fun pauseDownload(context: Context, id: String) = withDownloadLock(id) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return@withDownloadLock
        cancelWorkManagerJob(context, entity.workRequestId)
        dao.updateStatus(id, DownloadStatus.PAUSED)
        dao.resetSpeed(id)
        DownloadNotifications.cancel(context, id)
    }

    /** Cancels the in-flight WorkManager job (if any) and marks the entry cancelled. Unlike
     * [pauseDownload], a cancelled entry is never swept back up by resumeAll() — the user has to
     * explicitly resume it from the Cancelled section. */
    suspend fun cancelDownload(context: Context, id: String) = withDownloadLock(id) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return@withDownloadLock
        cancelWorkManagerJob(context, entity.workRequestId)
        dao.updateStatus(id, DownloadStatus.CANCELLED)
        dao.resetSpeed(id)
        DownloadNotifications.cancel(context, id)
    }

    /** Cancels any in-flight job, drops the row, and cleans up its download-archive file. */
    suspend fun deleteDownload(context: Context, id: String) = withDownloadLock(id) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id)
        cancelWorkManagerJob(context, entity?.workRequestId)
        DownloadNotifications.cancel(context, id)
        File(context.filesDir, "archives/$id.sqlite3").delete()
        File(context.filesDir, "archives/$id.ytdlp.txt").delete()
        dao.clearDownloadedFileRecords(id)
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
        // enqueueWork() cancels whatever job is already associated with each id itself, atomically
        // with resubmitting it — no need to duplicate that cancellation here.
        dao.getQueuedOnce().forEach { entity ->
            enqueueWork(context, entity.id, entity.url)
        }
    }

    /** Jumps a still-waiting download (QUEUED or SCHEDULED) to the front of the queue and past
     * the schedule window's delay, if one was blocking it — the "Start now" action. Negative
     * seconds-since-epoch so a later "Start now" tap always outranks an earlier one (more negative
     * = sorts first in getQueueFlow's `queueOrder ASC`), safely within Int range for decades. */
    suspend fun startNow(context: Context, id: String) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return
        dao.setQueueOrder(id, -(System.currentTimeMillis() / 1000L).toInt())
        enqueueWork(context, id, entity.url, forceImmediate = true)
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
