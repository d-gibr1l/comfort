package com.comfort.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.comfort.app.worker.DownloadNotifications
import com.comfort.app.worker.DownloadWorker
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

    /** DownloadWorker's own staging area for [id] (`cacheDir/gallery-dl-staging/<id>/`) — where a
     * download's files sit while in progress before each one gets copied out to the real gallery/
     * custom folder (see MediaStoreHelper.saveMediaToGallery(), called per-item as it completes).
     * Deliberately left alone by a plain pause: it's what lets a later Resume continue an
     * in-progress file instead of restarting it, and gallery-dl/yt-dlp's own download-archive
     * tracking depends on it too. Only [cancelDownload]/[deleteDownload] — genuinely final actions,
     * not "pick this back up later" — actually clear it. */
    private fun deleteStagingDir(context: Context, id: String) {
        File(context.cacheDir, "gallery-dl-staging/$id").deleteRecursively()
    }

    suspend fun enqueueDownload(context: Context, url: String, title: String, itemFilter: String? = null, totalItems: Int = 0, videoQuality: VideoQuality? = null): String {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val id = UUID.randomUUID().toString()
        val globallyPaused = GalleryDlPreferences.isGloballyPaused(context)
        dao.insert(
            DownloadEntity(
                id = id,
                url = url,
                title = title,
                thumbnailPath = null,
                // A new download added while globally paused used to always insert as QUEUED
                // regardless — it never actually dispatched to WorkManager (see the check below,
                // unchanged), but the UI told the user otherwise: it sat in the "In Queue" filter
                // tab saying "Waiting to start…" right alongside genuinely-paused items sitting in
                // the "Paused" tab saying "Paused," for what was really the identical frozen state.
                // Reported live as confusing split UI. PAUSED here from the start means it's
                // grouped correctly and resumeAll()'s own `status == PAUSED` filter already picks
                // it straight up — no other change needed for it to resume normally.
                status = if (globallyPaused) DownloadStatus.PAUSED else DownloadStatus.QUEUED,
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
        // While globally paused, new downloads sit undispatched — resumeAll() picks up anything
        // still PAUSED (see above) alongside whatever it un-pauses.
        if (!globallyPaused) {
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
    suspend fun pauseDownload(context: Context, id: String) {
        withDownloadLock(id) {
            val dao = AppDatabase.getDatabase(context).downloadDao()
            val entity = dao.getById(id) ?: return@withDownloadLock
            cancelWorkManagerJob(context, entity.workRequestId)
            dao.updateStatus(id, DownloadStatus.PAUSED)
            dao.resetSpeed(id)
            // A static "Paused" notification with its own Resume action, not cancel() — tapping
            // Pause right from the notification used to make it vanish outright, with no way back
            // to the download short of opening the app and finding it in Queue by hand.
            DownloadNotifications.notifyPaused(context, id, entity.title)
        }
        // Reproduced live: pausing a *running* download silently orphaned everything else queued
        // behind it in the same round-robin lane. Each lane is a real WorkManager dependency chain
        // (enqueueUniqueWork(..., APPEND_OR_REPLACE, ...) — the same shape as .then()), and
        // cancelWorkById() on one link cascade-cancels every dependent chained after it, by
        // WorkManager's own documented design. That cascade only touches WorkManager's internal
        // state, not this app's DB — the DB rows behind the paused head stayed QUEUED/SCHEDULED, so
        // the UI kept showing them as "waiting" even though their WorkRequest was already dead and
        // would never start on its own.
        repairOrphanedQueue(context)
    }

    /** Cancels the in-flight WorkManager job (if any) and marks the entry cancelled. Unlike
     * [pauseDownload], a cancelled entry is never swept back up by resumeAll() — the user has to
     * explicitly resume it from the Cancelled section. */
    suspend fun cancelDownload(context: Context, id: String) {
        withDownloadLock(id) {
            val dao = AppDatabase.getDatabase(context).downloadDao()
            val entity = dao.getById(id) ?: return@withDownloadLock
            cancelWorkManagerJob(context, entity.workRequestId)
            dao.updateStatus(id, DownloadStatus.CANCELLED)
            dao.resetSpeed(id)
            DownloadNotifications.cancel(context, id)
            // cancelWorkManagerJob() just requests the stop — it doesn't wait for the worker's own
            // subprocess to actually die, so this can briefly race an item still writing into its
            // staging dir. deleteRecursively() is best-effort (doesn't throw, just skips whatever's
            // locked at that instant) rather than a hard delete, and the subprocess dies moments
            // later anyway once PythonRuntime's own cancellation-triggered kill lands — so a rare
            // near-miss here just leaves the same harmless private-cache leftover this was already
            // living with, never a real failure.
            deleteStagingDir(context, id)
        }
        // Same WorkManager chain-cascade repair as pauseDownload() above — cancelling a running
        // download's job can just as easily orphan whatever was queued behind it in its lane.
        repairOrphanedQueue(context)
    }

    /** Re-submits only the QUEUED/SCHEDULED entries whose WorkManager job is actually dead —
     * unlike [rescheduleQueuedDownloads] (which unconditionally resubmits *everything* waiting,
     * fine for a genuine one-off setting change), this must be safe to call from [pauseDownload]/
     * [cancelDownload] on every single pause or cancel, including a burst of several in a row.
     * Blindly resubmitting the whole queue every time was reproduced live to be a real regression:
     * with several genuinely-healthy queued/running downloads sharing round-robin lanes, each
     * resubmission churned all of them — cancel, restart from scratch, cancel again a few seconds
     * later — 15+ times in under two minutes, so nothing ever actually finished (this is what
     * surfaced as "concurrency is 2 but only 1 is ever really downloading"). Checking each entry's
     * live WorkInfo first means a healthy job is never touched, only ones WorkManager's chain-cancel
     * cascade actually killed out from under the DB's back. */
    private suspend fun repairOrphanedQueue(context: Context) {
        // A null workRequestId is also how a global pauseAll() deliberately parks a download added
        // while paused (see DownloadsViewModel.resumeAll()'s own matching filter) — not every null
        // means "orphaned by the chain cascade." Leave those alone here; resumeAll() is what's
        // supposed to pick them back up.
        if (GalleryDlPreferences.isGloballyPaused(context)) return
        repairIfJobDead(context, AppDatabase.getDatabase(context).downloadDao().getQueuedOnce())
    }

    /** Startup-only counterpart to [repairOrphanedQueue] — catches a download stuck showing
     * "Downloading..." forever because the process that owned it died (force-stopped, OS-killed
     * while backgrounded, crashed) before its worker ever got to write a terminal status.
     * Reproduced live: WorkManager's own persisted work record for a killed process's job can end
     * up CANCELLED or simply gone by the next cold start, but nothing was re-checking the DB row
     * that pointed at it, so it just sat at RUNNING indefinitely — with no live WorkManager job
     * behind it — until someone noticed and cancelled it by hand.
     *
     * Safe to re-submit rather than just mark ERRORED: gallery-dl's/yt-dlp's own download-archive
     * (keyed by this same download id, see DownloadWorker's galleryArchivePath/ytDlpArchivePath)
     * means a re-run only fetches whatever's still missing — if the original run had actually
     * finished before dying, this resubmission finds nothing new, saves nothing, and immediately
     * reaches DownloadWorker's own `savedCount > 0` check to write FINISHED, exactly as if the
     * original run's own final status write had simply landed a little late.
     *
     * Call once per cold start (MainActivity), not reactively — unlike a QUEUED/SCHEDULED row
     * going stale, a RUNNING row's job dying isn't a predictable side effect of some other action
     * in this file, so there's no "this just happened, check now" trigger to hang it off of. */
    suspend fun repairOrphanedRunning(context: Context) {
        if (GalleryDlPreferences.isGloballyPaused(context)) return
        repairIfJobDead(context, AppDatabase.getDatabase(context).downloadDao().getRunningOnce())
    }

    /** Shared by [repairOrphanedQueue] and [repairOrphanedRunning]: re-submits any of [entities]
     * whose recorded WorkManager job is no longer actually alive, leaving everything else (a job
     * still genuinely ENQUEUED/RUNNING/BLOCKED) untouched. */
    private suspend fun repairIfJobDead(context: Context, entities: List<DownloadEntity>) {
        val workManager = WorkManager.getInstance(context)
        entities.forEach { entity ->
            val uuid = entity.workRequestId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            // No job ever recorded, or WorkManager reports it in a terminal state (CANCELLED from
            // the chain cascade, or FAILED/SUCCEEDED with the DB row never updated to match) — any
            // of those mean this entry's own job is dead and it'll never start on its own. Still
            // ENQUEUED/RUNNING/BLOCKED means it's healthy and must not be touched.
            val info = uuid?.let { runCatching { workManager.getWorkInfoById(it).get() }.getOrNull() }
            if (info == null || info.state.isFinished) {
                enqueueWork(context, entity.id, entity.url)
            }
        }
    }

    /** Cancels any in-flight job, drops the row, and cleans up its download-archive file. */
    suspend fun deleteDownload(context: Context, id: String) = withDownloadLock(id) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id)
        cancelWorkManagerJob(context, entity?.workRequestId)
        DownloadNotifications.cancel(context, id)
        File(context.filesDir, "archives/$id.sqlite3").delete()
        File(context.filesDir, "archives/$id.ytdlp.txt").delete()
        deleteStagingDir(context, id)
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
        // enqueueWork() doesn't check this itself, so without this guard a schedule-window change
        // made while globally paused silently broke the pause: every QUEUED/SCHEDULED row got
        // resubmitted straight to WorkManager regardless, dispatching downloads the user explicitly
        // froze. Normally pauseAll() already converts every QUEUED/SCHEDULED row to PAUSED (so
        // getQueuedOnce() below wouldn't find any while paused), but this stays a real, defensive
        // guard rather than relying on that invariant always holding everywhere.
        if (GalleryDlPreferences.isGloballyPaused(context)) return
        val dao = AppDatabase.getDatabase(context).downloadDao()
        // enqueueWork() cancels whatever job is already associated with each id itself, atomically
        // with resubmitting it — no need to duplicate that cancellation here.
        dao.getQueuedOnce().forEach { entity ->
            enqueueWork(context, entity.id, entity.url)
        }
    }

    /** Plain resume for a single PAUSED download — the "Resume" action on its own static paused
     * notification (see DownloadNotifications.notifyPaused), so tapping it doesn't require opening
     * the app and finding the item in Queue first. Just re-submits the existing job; unlike
     * [startNow] this doesn't jump the queue order or skip the schedule window. */
    suspend fun resumeDownload(context: Context, id: String) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return
        enqueueWork(context, id, entity.url)
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
