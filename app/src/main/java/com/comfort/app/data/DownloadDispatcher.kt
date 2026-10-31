package com.comfort.app.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.comfort.app.worker.DownloadNotifications
import com.comfort.app.worker.DownloadWorker
import com.comfort.app.worker.ScheduleAlarmReceiver
import com.comfort.app.worker.StagingCleanupWorker
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [DownloadDispatcher.enqueueDownload]'s outcome — most callers only ever want [id] (both
 * branches carry one, so `.id` works regardless), but the share sheet specifically needs to tell
 * the two apart to show its own duplicate-detected Snackbar only when [Duplicate] comes back. */
sealed class EnqueueResult(val id: String) {
    class Started(id: String) : EnqueueResult(id)
    class Duplicate(id: String) : EnqueueResult(id)
}

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

    /** [withDownloadLock] for many downloads at once — a bulk action holds all of them for its one
     * pass. Taken in sorted id order, so two bulk actions over overlapping downloads can't each
     * hold one the other is waiting for. */
    private suspend fun <T> withDownloadLocks(ids: Collection<String>, block: suspend () -> T): T {
        val mutexes = ids.distinct().sorted().map { id -> locks.computeIfAbsent(id) { Mutex() } }
        var held = 0
        try {
            mutexes.forEach { it.lock(); held++ }
            return block()
        } finally {
            for (i in held - 1 downTo 0) mutexes[i].unlock()
        }
    }

    /** Drops a download's entry from [locks] once it can no longer be enqueued/paused/cancelled
     * again — a row that's been deleted, or that reached a terminal FINISHED/ERRORED state.
     * Every download id otherwise lives in that map forever (it's only ever added to, never
     * removed), permanently retaining a String + Mutex per download the app has ever run. Called
     * outside of [withDownloadLock] itself, so there's a theoretically possible sliver of a race
     * with a call that's just about to computeIfAbsent() the same id (it would get a fresh, already
     * unlocked Mutex slightly early) — harmless here since this only ever runs once an id is truly
     * done, and the fresh Mutex still serializes whatever comes next correctly either way. */
    fun forgetLock(id: String) {
        locks.remove(id)
        stagingLocks.remove(id)
    }

    private val stagingLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** Held while a download's finished files are copied out of its staging dir into the gallery
     * (DownloadWorker's saveHeldFiles), and taken before that dir is deleted: a cancel used to
     * delete it mid-copy, leaving a cut-off file in the gallery. A cancel that comes first just
     * means nothing more is saved. Its own lock, not [withDownloadLock]: a cancel holds that one
     * while it waits for the worker's job to be cancelled. */
    suspend fun <T> withStagingLock(id: String, block: suspend () -> T): T =
        stagingLocks.computeIfAbsent(id) { Mutex() }.withLock { block() }

    /** Cancels one download's job, then has the queue checked for downloads that lost theirs.
     *
     * Downloads used to wait in WorkManager lanes (chains), where cancelling a job that other jobs
     * were chained after cancelled all of those too — then the next download added to that lane
     * found cancelled jobs, and APPEND_OR_REPLACE deleted the whole chain. Their rows stayed
     * QUEUED pointing at nothing, forever: deleting 2 queued downloads in a burst of ~200 left 121
     * stuck (found live, 2026-09-23). Each download has its own job now (see [enqueueWork]), but this
     * still waits for the cancellation to actually land, then
     * asks for [repairOrphanedQueue] — asynchronously, since callers hold [withDownloadLock] and
     * the repair re-enqueues through it, and debounced, so a burst of cancels repairs once. */
    private suspend fun cancelWorkManagerJob(context: Context, workRequestId: String?) {
        if (cancelWorkManagerJobs(context, listOf(workRequestId)) > 0) requestQueueRepair(context)
    }

    /** Cancels the given jobs and waits for the cancellations to land; returns how many there were.
     * Submitted all at once and only then waited on: WorkManager runs its operations one after
     * another in submission order, so a bulk action doesn't wait out each cancel before asking for
     * the next (that wait was most of the ~35 ms per download that made Retry All on 100 downloads
     * take seconds). */
    private suspend fun cancelWorkManagerJobs(context: Context, workRequestIds: List<String?>): Int {
        val workManager = WorkManager.getInstance(context)
        val operations = workRequestIds.mapNotNull { workRequestId ->
            workRequestId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { workManager.cancelWorkById(it) }
        }
        awaitOperations(operations)
        return operations.size
    }

    private suspend fun awaitOperations(operations: List<androidx.work.Operation>) {
        if (operations.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            operations.forEach { runCatching { it.result.get(5, TimeUnit.SECONDS) } }
        }
    }

    private val repairScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var pendingRepair: kotlinx.coroutines.Job? = null

    /** [repairOrphanedQueue] shortly after the last of a burst of calls. Also called by a
     * DownloadWorker that was cancelled or stopped, so a download left QUEUED/SCHEDULED without a
     * live job is started again right away rather than at the next app start. */
    @Synchronized
    fun requestQueueRepair(context: Context) {
        val appContext = context.applicationContext
        pendingRepair?.cancel()
        pendingRepair = repairScope.launch {
            try {
                kotlinx.coroutines.delay(QUEUE_REPAIR_DEBOUNCE_MS)
                repairOrphanedQueue(appContext)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DownloadDispatcher", "Queue repair failed", e)
            }
        }
    }

    private const val QUEUE_REPAIR_DEBOUNCE_MS = 600L


    private val PAUSABLE_STATUSES = listOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.SCHEDULED)
    private val CANCELLABLE_STATUSES = PAUSABLE_STATUSES + DownloadStatus.PAUSED

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

    /** True if [url] (and, for SharePickerScreen's own multi-item picker, this exact [itemFilter]
     * selection — see findActiveOrFinishedByUrl's own doc comment for why that matters, not just
     * url) would currently be treated as a duplicate by [enqueueDownload] — "Prevent duplicate
     * downloads" is on AND a matching active/finished download already exists. Screens that show
     * a real "Download" button before actually enqueuing (DownloadPreviewSheet, ShareActivity's
     * LoadingSheet, SharePickerScreen) call this ahead of time to relabel it "Redownload" instead
     * of enqueuing first and finding out only via EnqueueResult.Duplicate afterward — replaces the
     * old flow where a duplicate was only discovered post-hoc, surfaced through a Snackbar with
     * its own separate "Redownload" action to confirm. Once the button itself already says
     * "Redownload," tapping it is the confirmation; callers pass forceDuplicate = true straight
     * through rather than needing a second confirmation step here. */
    suspend fun isDuplicate(context: Context, url: String, itemFilter: String? = null): Boolean {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return false
        if (!GalleryDlPreferences.isPreventDuplicateDownloads(context)) return false
        return findExisting(context, AppDatabase.getDatabase(context).downloadDao(), trimmedUrl, itemFilter) != null
    }

    /** The active or finished download of [url] — by [UrlKey], so the same link shared again with
     * a different tracking token (X's "?a=…&s=09") is still found, and a Reddit post by where its
     * share links lead ([ShareLinks]). */
    private suspend fun findExisting(context: Context, dao: DownloadDao, url: String, itemFilter: String?): DownloadEntity? {
        dao.findActiveOrFinishedByUrl(url, itemFilter)?.let { return it }
        val key = UrlKey.of(url)
        dao.findActiveOrFinishedLike(UrlKey.likePattern(url), itemFilter).firstOrNull { UrlKey.of(it.url) == key }?.let { return it }
        val postKey = if (ShareLinks.isShareLink(url)) (ShareLinks.postKey(context, url) ?: return null) else key
        if (!postKey.startsWith(ShareLinks.REDDIT_POST)) return null
        if (postKey != key) {
            // A share link: the post's own link, saved earlier.
            dao.findActiveOrFinishedLike(UrlKey.likePattern("https://$postKey"), itemFilter)
                .firstOrNull { UrlKey.of(it.url) == postKey }?.let { return it }
        }
        // Another share of the same post (each looked up once, then kept). Once one lookup fails
        // (offline, Reddit refusing), the rest answer only from what's kept: each would wait out
        // the same timeout.
        var lookUp = true
        return dao.findActiveOrFinishedLike(ShareLinks.LIKE_PATTERN, itemFilter).firstOrNull { saved ->
            if (UrlKey.of(saved.url) == key) return@firstOrNull false
            val savedPost = ShareLinks.postKey(context, saved.url, lookUp)
            if (savedPost == null && ShareLinks.failedRecently(saved.url)) lookUp = false
            savedPost == postKey
        }
    }

    suspend fun enqueueDownload(
        context: Context,
        url: String,
        title: String,
        itemFilter: String? = null,
        totalItems: Int = 0,
        videoQuality: VideoQuality? = null,
        clipRange: String? = null,
        extraCommands: String? = null,
        outputFormat: OutputFormat? = null,
        filenameTemplate: String? = null,
        saveThumbnail: Boolean? = null,
        // The song preview sheet's own editable title/artist (SongPreviewCard) — see
        // DownloadEntity's own doc comment on these two fields for exactly what they override.
        overrideTitle: String? = null,
        overrideArtist: String? = null,
        // The Configure sheet's engine pick — see DownloadEntity.engineOverride.
        engineOverride: DownloadEngine? = null,
        // The Configure sheet's audio format — see DownloadEntity.audioFormat.
        audioFormat: AudioFormat? = null,
        // The preview sheet's audio track — see DownloadEntity.audioLanguage.
        audioLanguage: String? = null,
        // The preview sheet's subtitle languages — see DownloadEntity.subtitleLanguages.
        subtitleLanguages: String? = null,
        // Set true for the explicit "Redownload anyway" action on a duplicate's own Snackbar/
        // Library entry — skips the check below for just this one deliberate retry, same shape as
        // startNow()'s own "the user explicitly asked to skip past the usual rule" precedent.
        // The preview sheet's "Start at…" — see DownloadEntity.scheduledAt.
        scheduledAt: Long? = null,
        forceDuplicate: Boolean = false,
    ): EnqueueResult {
        val trimmedUrl = url.trim()
        val sanitizedTitle = title.trim().ifBlank { trimmedUrl }
        val safeTotalItems = totalItems.coerceAtLeast(0)
        val dao = AppDatabase.getDatabase(context).downloadDao()
        // Imported from YTDLnis's own "Prevent duplicate downloads" setting — off by default, so
        // this only ever short-circuits when the user has actually turned it on. Records the skip
        // in duplicate_attempts (see DuplicateAttempt's own doc comment) rather than creating a
        // redundant second downloads row for the same URL.
        if (!forceDuplicate && GalleryDlPreferences.isPreventDuplicateDownloads(context)) {
            findExisting(context, dao, trimmedUrl, itemFilter)?.let { existing ->
                dao.insertDuplicateAttempt(
                    DuplicateAttempt(
                        id = UUID.randomUUID().toString(),
                        url = trimmedUrl,
                        title = sanitizedTitle,
                        thumbnailPath = existing.thumbnailPath,
                        originalDownloadId = existing.id,
                        dateAdded = System.currentTimeMillis(),
                    )
                )
                return EnqueueResult.Duplicate(existing.id)
            }
        }
        val id = UUID.randomUUID().toString()
        val globallyPaused = GalleryDlPreferences.isGloballyPaused(context)
        dao.insert(
            DownloadEntity(
                id = id,
                url = trimmedUrl,
                title = sanitizedTitle,
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
                totalItems = safeTotalItems,
                speedMbs = 0f,
                etaSeconds = 0L,
                errorMessage = null,
                dateAdded = System.currentTimeMillis(),
                itemFilter = itemFilter,
                videoQuality = videoQuality?.name ?: if (VideoSiteRouter.isSongSource(trimmedUrl)) VideoQuality.AUDIO_ONLY.name else null,
                clipRange = clipRange,
                extraCommands = extraCommands,
                outputFormat = outputFormat?.name,
                filenameTemplate = filenameTemplate,
                saveThumbnail = saveThumbnail,
                overrideTitle = overrideTitle?.trim()?.ifBlank { null },
                overrideArtist = overrideArtist?.trim()?.ifBlank { null },
                engineOverride = engineOverride?.name,
                audioFormat = audioFormat?.name,
                audioLanguage = audioLanguage,
                subtitleLanguages = subtitleLanguages,
                // enqueueWork below waits for it (enqueueWorkMany's own start-time delay).
                scheduledAt = scheduledAt?.takeIf { it > System.currentTimeMillis() },
                // Global default only (see GalleryDlPreferences.isIncognitoDefault's own doc
                // comment) — no per-download override UI yet, same scope YTDLnis itself exposes
                // this as (a plain Settings switch, not a per-download picker).
                incognito = GalleryDlPreferences.isIncognitoDefault(context),
            )
        )
        // While globally paused, new downloads sit undispatched — resumeAll() picks up anything
        // still PAUSED (see above) alongside whatever it un-pauses.
        if (!globallyPaused) {
            enqueueWork(context, id, trimmedUrl)
        }
        return EnqueueResult.Started(id)
    }

    /** (Re-)submits a WorkManager job for an existing download entry. gallery-dl's own download
     * archive (keyed by the download id) means a retry only fetches what's still missing.
     * [forceImmediate] skips the schedule-window delay entirely — see [startNow].
     * [userInitiated] marks an explicit tap on this one download (a card's Continue/Retry, the
     * paused notification's Resume, Start now), which starts it even while "Pause All" is on and
     * ends that global hold — see [enqueueWorkMany]. */
    suspend fun enqueueWork(
        context: Context,
        id: String,
        url: String,
        forceImmediate: Boolean = false,
        userInitiated: Boolean = false,
        // For callers working through a snapshot taken earlier: re-checked against the row's
        // *current* status, so a download that finished, failed, was cancelled/paused or deleted
        // since the snapshot isn't resurrected to QUEUED and re-run. null = no check (a single
        // explicit user action).
        expectedStatus: Set<DownloadStatus>? = null,
    ) {
        enqueueWorkMany(context, listOf(Dispatch(id, url, forceImmediate, expectedStatus)), userInitiated)
    }

    /** One download for [enqueueWorkMany]. [moveToQueueEndAt] sends it to the back of the queue
     * first (a retried failure, see DownloadDao.moveToQueueEnd), as of that time — a bulk retry
     * passes times 1 ms apart so the downloads keep their order. */
    class Dispatch(
        val id: String,
        val url: String,
        val forceImmediate: Boolean = false,
        val expectedStatus: Set<DownloadStatus>? = null,
        val moveToQueueEndAt: Long? = null,
    )

    /** The status a download goes to when dispatched right now — what the Queue shows the moment a
     * Retry/Resume is tapped, before [enqueueWorkMany] has written it (DownloadsViewModel's pending
     * moves). Same three outcomes, in the same order, as [enqueueWorkMany]. */
    fun predictedDispatchStatus(context: Context, forceImmediate: Boolean = false, userInitiated: Boolean = false): DownloadStatus = when {
        !userInitiated && GalleryDlPreferences.isGloballyPaused(context) -> DownloadStatus.PAUSED
        !forceImmediate && scheduleDelayMillis(context) > 0 -> DownloadStatus.SCHEDULED
        else -> DownloadStatus.QUEUED
    }

    /** Submits WorkManager jobs for many downloads in one pass — what every dispatch goes through,
     * a single Retry as much as Retry All, Resume All or a schedule change re-timing the queue.
     *
     * Bulk actions used to call [enqueueWork] once per download: each took its download's lock,
     * cancelled its old job and waited for that, then wrote the row twice. 100 downloads took
     * seconds, and the Queue redrew them changing a few at a time. The selection bar's actions
     * also ran one coroutine per download, all at once, so a bulk retry came out in whatever order
     * they happened to finish. Here the settings and the schedule delay are read once, every row
     * is written in one transaction (one change for the lists to show), and the jobs are submitted
     * in the order given. Returns the ids actually dispatched — the rest no longer had their
     * [Dispatch.expectedStatus] or were deleted. */
    suspend fun enqueueWorkMany(
        context: Context,
        dispatches: List<Dispatch>,
        userInitiated: Boolean = false,
    ): Set<String> {
        if (dispatches.isEmpty()) return emptySet()
        var cancelledJobs = 0
        val accepted = withDownloadLocks(dispatches.map { it.id }) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.downloadDao()
            val current = dispatches.associate { it.id to dao.getById(it.id) }

            // Every dispatch path funnels through here — retryDownload/retryAll/startNow/
            // resumeDownload used to call straight through to WorkManager regardless of this,
            // silently undoing "Pause All" the moment the user retried, started-now, or resumed an
            // already-existing row (reproduced live: only a brand-new download via enqueueDownload's
            // own separate check actually stayed paused). Gating once here, rather than adding the
            // same check to every individual caller, is what makes "nothing downloads while paused"
            // actually hold for every entry point instead of just the one enqueueDownload guards.
            // Except [userInitiated]: tapping Continue on one specific paused card after "Pause All"
            // silently wrote PAUSED straight back and did nothing (reported live). An explicit
            // request to start *this* download ends the global hold instead — the user is resuming
            // activity, so the Queue FAB flips back to "Pause" (it kept saying "Resume", reported
            // live) and new links start normally again. Every other PAUSED row stays PAUSED: clearing
            // the flag doesn't dispatch anything by itself, and "Resume All" still picks them up.
            if (userInitiated && GalleryDlPreferences.isGloballyPaused(context)) {
                GalleryDlPreferences.setGloballyPaused(context, false)
            }
            val globallyPaused = GalleryDlPreferences.isGloballyPaused(context)

            // Cancel whatever job is already associated with each download first, under the same
            // locks as the new submission below — closes the race where a second caller for the
            // same id would otherwise interleave its own cancel+submit and end up running two
            // WorkRequests for the same download concurrently. Only a download that can still have
            // a live job: a failed, paused or cancelled one's job already ended, and the new job's
            // REPLACE (below) covers its own unique name anyway.
            cancelledJobs = cancelWorkManagerJobs(
                context,
                current.values.filterNotNull()
                    .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED }
                    .map { it.workRequestId },
            )

            // No network condition on the job: with one, WorkManager stopped a running download
            // the moment the connection blinked (a Wi-Fi hiccup), and it started over — extracting
            // again, and once downloading a whole song again (seen 2026-10-30). The worker itself
            // waits for a usable network before it starts (Wi-Fi only included), and while it runs
            // a short loss is ridden out; a long one, or mobile data taking over with Wi-Fi only
            // on, sends it back to wait (DownloadWorker, [waitForNetwork]).
            // Imported from YTDLnis's own "Download Delay" setting — a flat pause applied to every
            // dispatch (not just ones the schedule window above is already holding back), same
            // "Start now" bypass as the schedule delay for the same reason: jumping the queue means
            // skipping every reason this item would otherwise wait, not just one of them.
            val downloadDelay = GalleryDlPreferences.getEffectiveDownloadDelaySeconds(context) * 1000L
            val windowDelay = scheduleDelayMillis(context)
            val now = System.currentTimeMillis()

            val requests = mutableListOf<Pair<Dispatch, androidx.work.OneTimeWorkRequest>>()
            val pausedIds = mutableSetOf<String>()
            db.withTransaction {
                for (dispatch in dispatches) {
                    // Re-read inside the transaction: a worker can still write a terminal status
                    // without taking these locks.
                    val row = dao.getById(dispatch.id) ?: continue
                    if (dispatch.expectedStatus != null && row.status !in dispatch.expectedStatus) continue
                    dispatch.moveToQueueEndAt?.let { dao.moveToQueueEnd(dispatch.id, it) }
                    if (globallyPaused) {
                        dao.updateStatus(dispatch.id, DownloadStatus.PAUSED)
                        pausedIds += dispatch.id
                        continue
                    }
                    // The window, a start time of its own, Start now and the Download Delay — see
                    // DispatchTiming.timing (unit-tested).
                    val timing = DispatchTiming.timing(row.status, row.scheduledAt, dispatch.forceImmediate, windowDelay, downloadDelay, now)
                    if (timing.clearStartAt) dao.clearScheduledAt(dispatch.id)
                    val delayMillis = timing.delayMillis
                    val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setInputData(workDataOf("downloadId" to dispatch.id, "url" to dispatch.url))
                        .apply {
                            if (delayMillis > 0) {
                                setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                            } else {
                                // Only when there's no delay — WorkManager throws if setExpedited() and
                                // setInitialDelay() are both set on the same request. Gives the OS a
                                // stronger signal to actually start this promptly (and be more reluctant
                                // to kill the process) during the real gap between WorkManager picking
                                // this up and DownloadWorker's own setForegroundSafely() call actually
                                // promoting it to a genuine foreground service —
                                // RUN_AS_NON_EXPEDITED_WORK_REQUEST means this just quietly falls back
                                // to a normal request if the app's expedited-job quota is already spent,
                                // never fails outright over it.
                                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            }
                        }
                        .build()
                    dao.setWorkRequestId(dispatch.id, workRequest.id.toString())
                    // SCHEDULED vs QUEUED distinguishes "waiting on the schedule window" from
                    // "waiting on a concurrency slot" in the UI — recomputed on every (re-)enqueue
                    // so a schedule change flips this correctly for anything
                    // rescheduleQueuedDownloads() re-submits. Only the schedule window makes it
                    // SCHEDULED — the generic Download Delay is a short wait that still counts as
                    // plain "In Queue", not "Waiting for the scheduled time window".
                    dao.updateStatus(dispatch.id, if (timing.scheduled) DownloadStatus.SCHEDULED else DownloadStatus.QUEUED)
                    requests += dispatch to workRequest
                }
            }
            if (requests.isEmpty()) return@withDownloadLocks pausedIds
            scheduleWindowAlarm(context)
            scheduleStartAtAlarm(context)

            // One job per download, not a slot in a chain. Downloads used to be appended round-robin
            // to one WorkManager chain per concurrency slot ("gallery_dl_queue_N"), which ran each
            // chain one job at a time. A download's lane was fixed when it was queued, so once lanes
            // went uneven (fast failures in one, slow downloads in the other, deletions) one sat idle
            // while the other still had a backlog: "downloading one at a time instead of 2" (found
            // live, 2026-09-23 - one lane empty, the other holding 12). Chains also cascaded:
            // cancelling a waiting job cancelled everything chained after it (see
            // cancelWorkManagerJob). DownloadWorker's DownloadConcurrencyGate already enforces both
            // the concurrency limit and queue order (including "Up next") for every worker that
            // reaches it, so every queued download now simply waits there for the next free slot.
            // REPLACE only ever replaces this same download's own previous job. Submitted while still
            // holding the locks, so the queue repair never sees a row whose job isn't there yet.
            val workManager = WorkManager.getInstance(context)
            awaitOperations(requests.map { (dispatch, workRequest) ->
                val uniqueName = if (dispatch.forceImmediate) "gallery_dl_now_${dispatch.id}" else "gallery_dl_download_${dispatch.id}"
                workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, workRequest)
            })
            requests.map { it.first.id }.toSet()
        }
        // Once for the whole batch: picks up any download that lost its job to the cancels above.
        if (cancelledJobs > 0) requestQueueRepair(context)
        return accepted
    }

    /** Called by a running DownloadWorker if the schedule window closes mid-download.
     *  Cancels the current job and re-submits it so it goes back to SCHEDULED with the proper
     *  delay until the window opens again, instead of staying PAUSED forever. */
    suspend fun suspendForSchedule(context: Context, id: String) {
        // Every other re-enqueuing path in this file bails out while globally paused
        // (rescheduleQueuedDownloads, repairOrphanedQueue/repairOrphanedRunning via
        // repairIfJobDead) — this one didn't, so a "Pause All" tap racing this same worker's own
        // schedule-window check (which only polls once per 60s and lags behind cancellation, see
        // DownloadWorker's actualCallback) could get silently undone for whichever download
        // happened to be mid-transfer: it would come back SCHEDULED and start again on its own
        // once the window reopened, ignoring the pause the user just asked for.
        if (GalleryDlPreferences.isGloballyPaused(context)) return
        // NonCancellable: the caller is the download's own worker, and enqueueWork() starts by
        // cancelling that worker's job — which cancelled this very call before it had submitted the
        // new job. The download was left QUEUED with a dead job, waiting until the next app start
        // (seen live: 5 downloads stuck in "In Queue" with nothing running).
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val dao = AppDatabase.getDatabase(context).downloadDao()
            val entity = dao.getById(id) ?: return@withContext
            dao.resetSpeed(id)
            DownloadNotifications.cancel(context, id)
            enqueueWork(context, id, entity.url)
            repairOrphanedQueue(context)
        }
    }

    /** Called by a running DownloadWorker when the network is gone for too long, or Wi-Fi only is on
     * and mobile data took over: the download goes back to waiting — its job is replaced by a new
     * one, which waits for a usable network before starting and then picks up where this one left
     * off (its staging folder stays). Start now's override is kept. NonCancellable for the same
     * reason as [suspendForSchedule]: replacing the job cancels the caller. */
    suspend fun waitForNetwork(context: Context, id: String) {
        if (GalleryDlPreferences.isGloballyPaused(context)) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val dao = AppDatabase.getDatabase(context).downloadDao()
            val entity = dao.getById(id) ?: return@withContext
            dao.resetSpeed(id)
            DownloadNotifications.cancel(context, id)
            enqueueWork(context, id, entity.url, forceImmediate = entity.forceStart, expectedStatus = setOf(DownloadStatus.RUNNING))
            repairOrphanedQueue(context)
        }
    }

    /** Cancels the in-flight WorkManager job (if any) and marks the entry paused — distinct from
     * [cancelDownload] so a global pauseAll()/resumeAll() cycle only ever touches downloads it
     * itself held back, never ones the user explicitly cancelled. Callable from anywhere — the
     * ViewModel, or a notification action's BroadcastReceiver with no Activity.
     *
     * [notify] false skips replacing the notification with a static "Paused" one — pauseAll()
     * passes this for every item in its own loop, since leaving one behind *per download* would
     * turn a single "Pause All" tap on a large queue into a stack of individual notifications, one
     * per paused item. The single-item paths (the in-app Pause button, the notification's own
     * Pause action) still want it, so this defaults to true rather than pauseAll() needing its own
     * separate cleanup pass. */

    suspend fun pauseDownload(context: Context, id: String, notify: Boolean = true) {
        pauseDownloads(context, listOf(id), notify)
    }

    /** [pauseDownload] for many downloads in one pass — Pause All and the selection bar's Pause.
     * Same reasoning as [enqueueWorkMany]: their jobs are cancelled together and their rows
     * written in one transaction, rather than one download at a time. Returns the ids it paused. */
    suspend fun pauseDownloads(context: Context, ids: List<String>, notify: Boolean = true): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val (cancelled, paused) = withDownloadLocks(ids) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.downloadDao()
            val rows = ids.mapNotNull { dao.getById(it) }
            val cancelled = cancelWorkManagerJobs(context, rows.map { it.workRequestId })
            // Conditional: Pause All works from a snapshot, and a download that finished or failed
            // before its turn must stay FINISHED/ERRORED rather than being flipped to PAUSED.
            val paused = db.withTransaction {
                rows.filter { dao.updateStatusIfIn(it.id, DownloadStatus.PAUSED, PAUSABLE_STATUSES) > 0 }
                    .onEach {
                        dao.resetSpeed(it.id)
                        // "Up next" doesn't outlast a pause: resumed, a download waits for a
                        // closed schedule window again (asked for: Up next, Pause, Resume went
                        // straight on downloading). The paused card's own "Up next" skips it.
                        dao.clearForceStart(it.id)
                    }
            }
            paused.forEach { entity ->
                if (notify) {
                    // A static "Paused" notification with its own Resume action, not cancel() —
                    // tapping Pause right from the notification used to make it vanish outright,
                    // with no way back to the download short of opening the app and finding it in
                    // Queue by hand.
                    DownloadNotifications.notifyPaused(context, entity.id, entity.title)
                } else {
                    DownloadNotifications.cancel(context, entity.id)
                }
            }
            cancelled to paused.map { it.id }.toSet()
        }
        // Once for the whole batch, so a burst of pauses consolidates into a single repair pass
        // instead of thrashing.
        if (cancelled > 0) requestQueueRepair(context)
        return paused
    }

    /** Cancels the in-flight WorkManager job (if any) and marks the entry cancelled. Unlike
     * [pauseDownload], a cancelled entry is never swept back up by resumeAll() — the user has to
     * explicitly resume it from the Cancelled section. */
    suspend fun cancelDownload(context: Context, id: String) {
        if (id.isBlank()) return
        cancelDownloads(context, listOf(id))
    }

    /** [cancelDownload] for many downloads in one pass — the selection bar's Cancel. Returns the
     * ids it cancelled. */
    suspend fun cancelDownloads(context: Context, ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val (cancelled, stoppedIds) = withDownloadLocks(ids) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.downloadDao()
            val rows = ids.mapNotNull { dao.getById(it) }
            val cancelled = cancelWorkManagerJobs(context, rows.map { it.workRequestId })
            // Same reasoning as pauseDownloads: don't overwrite a download that already finished
            // or failed (and don't wipe its staging dir either).
            val stopped = db.withTransaction {
                rows.filter { dao.updateStatusIfIn(it.id, DownloadStatus.CANCELLED, CANCELLABLE_STATUSES) > 0 }
                    .onEach { dao.resetSpeed(it.id) }
            }
            stopped.forEach { entity ->
                DownloadNotifications.cancel(context, entity.id)
                // cancelWorkManagerJobs() just requests the stop — it doesn't wait for the worker's
                // own subprocess to actually die, so this can briefly race an item still writing
                // into its staging dir. deleteRecursively() is best-effort (doesn't throw, just
                // skips whatever's locked at that instant) rather than a hard delete, and the
                // subprocess dies moments later anyway once PythonRuntime's own
                // cancellation-triggered kill lands — so a rare near-miss here just leaves the same
                // harmless private-cache leftover this was already living with, never a real failure.
                withStagingLock(entity.id) { deleteStagingDir(context, entity.id) }
            }
            cancelled to stopped.map { it.id }.toSet()
        }
        if (cancelled > 0) requestQueueRepair(context)
        return stoppedIds
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
     * cascade actually killed out from under the DB's back.
     *
     * Also called once per cold start (MainActivity, alongside [repairOrphanedRunning]) — not
     * just reactively. Reproduced live: several QUEUED rows sat for days with "nothing running"
     * because their recorded workRequestId no longer existed in WorkManager's own database at
     * all (confirmed directly — zero matching WorkSpec rows), and every one of this function's
     * other call sites is a reaction to some *other* action (a pause/cancel/schedule change) that
     * simply never happened in between, so nothing ever re-checked them. */
    suspend fun repairOrphanedQueue(context: Context) {
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
        val dead = entities.filter { entity ->
            val uuid = entity.workRequestId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            // No job ever recorded, or WorkManager reports it in a terminal state (CANCELLED from
            // the chain cascade, or FAILED/SUCCEEDED with the DB row never updated to match) — any
            // of those mean this entry's own job is dead and it'll never start on its own. Still
            // ENQUEUED/RUNNING/BLOCKED means it's healthy and must not be touched.
            val info = uuid?.let { runCatching { workManager.getWorkInfoById(it).get() }.getOrNull() }
            info == null || info.state.isFinished
        }
        // forceStart is startNow()'s own marker for "the user explicitly jumped this past the
        // schedule window" (see its doc comment) — re-submitting through the normal delayed path
        // here would silently drop that override the moment a forced download's process dies
        // mid-transfer and gets picked back up by this repair on the next cold start, sending it
        // back to waiting on a window that's since closed instead of resuming immediately like the
        // user asked.
        enqueueWorkMany(context, dead.map { Dispatch(it.id, it.url, it.forceStart, setOf(it.status)) })
    }

    /** Cancels any in-flight job, drops the row, and cleans up its download-archive file. */
    suspend fun deleteDownload(context: Context, id: String) {
        if (id.isBlank()) return
        withDownloadLock(id) {
            val dao = AppDatabase.getDatabase(context).downloadDao()
            val entity = dao.getById(id)
            cancelWorkManagerJob(context, entity?.workRequestId)
            DownloadNotifications.cancel(context, id)
            File(context.filesDir, "archives/$id.sqlite3").delete()
            File(context.filesDir, "archives/$id.ytdlp.txt").delete()
            File(context.filesDir, "archives/$id.instaloader.txt").delete()
            withStagingLock(id) { deleteStagingDir(context, id) }
            dao.clearDownloadedFileRecords(id)
            dao.clearSavedFiles(id)
            dao.delete(id)
            forgetLock(id)
        }
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
        // enqueueWorkMany() cancels whatever job is already associated with each id itself, atomically
        // with resubmitting it — no need to duplicate that cancellation here.
        // forceStart: a download the user jumped with "Up next" must keep that override (same as
        // repairIfJobDead) instead of going back to waiting on a closed schedule window.
        val waiting = setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED)
        enqueueWorkMany(context, dao.getQueuedOnce().map { Dispatch(it.id, it.url, it.forceStart, waiting) })
    }

    /** Kills and immediately re-submits every currently RUNNING download's WorkManager job —
     * used to apply a setting (the speed limit) that's only ever read fresh when a new Python
     * subprocess is spawned, baked into its own CLI/dict args at that exact moment, with no IPC
     * channel to reach an already-running one. gallery-dl's own --download-archive and yt-dlp's
     * partial .part-file resume mean the freshly spawned replacement picks up exactly where the
     * killed one left off, not from scratch — enqueueWorkMany() itself already does the cancel-then-
     * resubmit in one atomic step (see its own doc comment), so this is just that, once per
     * running item. */
    suspend fun restartRunningDownloads(context: Context) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val running = setOf(DownloadStatus.RUNNING)
        enqueueWorkMany(context, dao.getRunningOnce().map { Dispatch(it.id, it.url, it.forceStart, running) })
    }

    private const val STAGING_CLEANUP_WORK_NAME = "staging-cleanup-sweep"

    /** (Re)applies the "Clean up leftover downloads" interval (Settings > Downloads) to
     * StagingCleanupWorker's periodic schedule — called once at app startup (so a change made in
     * a previous session takes effect again) and immediately whenever the setting itself changes,
     * since a PeriodicWorkRequest's own interval is fixed at the moment it's enqueued and won't
     * otherwise notice a later change. Gated on the same master toggle as on-failure cleanup
     * ([GalleryDlPreferences.isDeleteLeftoverOnFailure]) — the stored interval itself is left
     * untouched while off, so turning the toggle back on restores whichever interval was picked
     * before, the same "off preserves the value for re-enabling" shape every other toggle+value
     * setting on this page already uses (Proxy's URL, Speed limit's number, ...). */
    fun rescheduleStagingCleanup(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val intervalDays = if (!GalleryDlPreferences.isDeleteLeftoverOnFailure(context)) null else when (
            GalleryDlPreferences.getCleanupLeftoverInterval(context)
        ) {
            "daily" -> 1L
            "weekly" -> 7L
            "monthly" -> 30L
            else -> null
        }
        if (intervalDays == null) {
            workManager.cancelUniqueWork(STAGING_CLEANUP_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<StagingCleanupWorker>(intervalDays, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(
            STAGING_CLEANUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Plain resume for a single PAUSED download — the "Resume" action on its own static paused
     * notification (see DownloadNotifications.notifyPaused), so tapping it doesn't require opening
     * the app and finding the item in Queue first. Just re-submits the existing job; unlike
     * [startNow] this doesn't jump the queue order or skip the schedule window. */
    suspend fun resumeDownload(context: Context, id: String) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return
        // forceStart is false after a pause (see pauseDownloads); still read, for a paused row
        // whose mark predates that.
        enqueueWork(context, id, entity.url, forceImmediate = entity.forceStart, userInitiated = true)
    }

    /** The Queue card's "Start now": this download starts right away, past the schedule window's
     * delay if one was holding it. When every download slot is busy, the download that started
     * most recently steps aside for it — sent back to the front of the queue, right behind this
     * one, so it picks up where it left off as soon as a slot frees. (It used to be "Up next",
     * which only moved the download to the front of the line to wait for the next free slot.)
     * Negative seconds-since-epoch as its place: a later Start now always outranks an earlier one
     * (more negative = sorts first in getQueueFlow's `queueOrder ASC`), within Int range for
     * decades. */
    suspend fun startNow(context: Context, id: String) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return
        val order = -(System.currentTimeMillis() / 1000L).toInt()
        dao.setForceStart(id, order)
        enqueueWork(context, id, entity.url, forceImmediate = true, userInitiated = true)
        makeRoomFor(context, id, order)
        repairOrphanedQueue(context)
    }

    /** The Queue's "Start at…": [id] starts at [at] (epoch millis) — or, with null, goes back to
     * waiting for the schedule window like any other download. A set time replaces the window and
     * Start now for this download (see [DownloadEntity.scheduledAt]); a paused one is sent back to
     * wait for it, and a running one is paused first, keeping what it has downloaded. An explicit
     * request for this download, so it ends a Pause All, like Resume. */
    suspend fun scheduleAt(context: Context, id: String, at: Long?) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return
        // A running one stops first, the way Pause stops it: its staging folder stays, so at its
        // time it picks up where it left off. No "Paused" notification: it's waiting, not paused.
        if (entity.status == DownloadStatus.RUNNING) pauseDownloads(context, listOf(id), notify = false)
        dao.setScheduledAt(id, at)
        enqueueWork(
            context, id, entity.url, userInitiated = true,
            expectedStatus = setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED, DownloadStatus.PAUSED),
        )
        repairOrphanedQueue(context)
    }

    /** Frees a slot for [id] when all of them are taken — see [startNow]. Waits until its worker
     * is actually in line at the gate first: freed any earlier, the slot went to whichever worker
     * asked for it next, which could be the very download that was just sent back. */
    private suspend fun makeRoomFor(context: Context, id: String, order: Int) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val giveUpAt = System.currentTimeMillis() + 15_000L
        while (!com.comfort.app.worker.DownloadConcurrencyGate.isWaiting(id)) {
            // Already running (a slot was free), or stopped meanwhile: nothing to make room for.
            if (dao.getById(id)?.status != DownloadStatus.QUEUED || System.currentTimeMillis() > giveUpAt) return
            kotlinx.coroutines.delay(200L)
        }
        val limit = GalleryDlPreferences.getEffectiveConcurrentDownloads(context).coerceAtLeast(1)
        val running = dao.getRunningOnce().filter { it.id != id }
        if (running.size < limit) return
        val bumped = running.maxByOrNull { it.downloadStartTime } ?: return
        withDownloadLock(bumped.id) { dao.setQueueOrder(bumped.id, order + 1) }
        dao.resetSpeed(bumped.id)
        DownloadNotifications.cancel(context, bumped.id)
        // Its job is cancelled and a new one queued (still RUNNING, so only that row): the same
        // way the schedule window closing sends a running download back to wait.
        enqueueWorkMany(context, listOf(Dispatch(bumped.id, bumped.url, bumped.forceStart, setOf(DownloadStatus.RUNNING))))
    }

    /** Whether the configured schedule window (if any) currently allows downloading — the
     * complement of [scheduleDelayMillis] returning 0, pulled out under its own name for
     * DownloadWorker's own mid-download bleed-over check (see its actualCallback), which reads
     * much more clearly as "are we still inside the window" than "is the delay zero." */
    fun isWithinScheduleWindow(context: Context): Boolean = scheduleDelayMillis(context) == 0L

    /** Millis until the configured download window next opens, or 0 if downloads are allowed right now. */
    fun scheduleDelayMillis(context: Context): Long {
        if (!GalleryDlPreferences.isScheduleEnabled(context)) return 0L
        val startMin = GalleryDlPreferences.getScheduleStartMinutes(context).coerceIn(0, 1439)
        val endMin = GalleryDlPreferences.getScheduleEndMinutes(context).coerceIn(0, 1439)
        return DispatchTiming.windowDelayMillis(startMin, endMin, Calendar.getInstance())
    }

    private const val SCHEDULE_ALARM_REQUEST_CODE = 1001
    private const val START_AT_ALARM_REQUEST_CODE = 1002

    /** [scheduleWindowAlarm]'s backstop for the downloads' own start times ("Start at…"): an alarm
     * at the earliest one still ahead, so Doze can't hold it back past its time. Its own alarm
     * (request code), not the window's: Settings re-arms that one without knowing about these. The
     * same receiver re-submits everything waiting, which starts what's due and re-arms this for the
     * next. Under the same "Use alarm for scheduling" switch; not tied to the window being on. */
    suspend fun scheduleStartAtAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context, START_AT_ALARM_REQUEST_CODE,
            Intent(context, ScheduleAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = if (GalleryDlPreferences.isAlarmSchedulingEnabled(context)) {
            AppDatabase.getDatabase(context).downloadDao().earliestScheduledAt(System.currentTimeMillis())
        } else null
        if (at == null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        }.onFailure { e ->
            android.util.Log.w("DownloadDispatcher", "Failed to schedule start-time alarm", e)
        }
    }

    /** (Re)arms — or cancels — the exact-alarm backstop for the Schedule window, ported from
     * YTDLnis's own "Use alarm for scheduling" toggle. WorkManager's setInitialDelay() is a plain
     * countdown with no concept of an absolute wake time; Doze/App Standby can defer a
     * long-delayed job well past its intended fire time, silently pushing a "start at 1am"
     * schedule to whenever the OS next lets the app wake for maintenance. setAndAllowWhileIdle()
     * wakes the device close to the real target time even during Doze, without needing the
     * SCHEDULE_EXACT_ALARM permission a true setExactAndAllowWhileIdle() would require on API
     * 31+ — ScheduleAlarmReceiver just kicks rescheduleQueuedDownloads() when it fires, the same
     * recovery ClockChangeReceiver already uses for a clock/timezone change.
     *
     * Called from [enqueueWork] (whenever something actually lands in SCHEDULED state, so the
     * alarm always matches the freshest computed window-open time) and from Settings whenever the
     * Schedule window or this toggle itself changes — deliberately *not* from the receiver after
     * it fires, since scheduleDelayMillis() is ~0 right when the window opens and there's nothing
     * to re-arm for until the next SCHEDULED item comes along naturally. */
    fun scheduleWindowAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context, SCHEDULE_ALARM_REQUEST_CODE,
            Intent(context, ScheduleAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (!GalleryDlPreferences.isScheduleEnabled(context) || !GalleryDlPreferences.isAlarmSchedulingEnabled(context)) {
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }
        val delayMillis = scheduleDelayMillis(context)
        if (delayMillis <= 0L) {
            // Already inside the window (or nothing configured) — nothing to wake up for.
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pendingIntent)
        }.onFailure { e ->
            android.util.Log.w("DownloadDispatcher", "Failed to schedule window alarm", e)
        }
    }
}
