package com.comfort.app.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.DownloadedFileRecord
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.Aria2Runtime
import com.comfort.app.util.EngineProbe
import com.comfort.app.util.FfmpegRuntime
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.MediaStoreHelper
import com.comfort.app.util.PythonRuntime
import com.comfort.app.util.QuickJsRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Wraps setForeground(info) with a short settle delay and a safe catch for
 * ForegroundServiceStartNotAllowedException (a subclass of IllegalStateException on API 31+,
 * thrown when the app isn't currently allowed to start a new foreground service — e.g. already
 * backgrounded past the OS's grace period). Modeled directly on YTDLnis's own
 * setForegroundSafely() (work/WorkManagerExtensions.kt) — studied their source while chasing a
 * related foreground-notification issue this session; the delay(500) mirrors theirs verbatim
 * ("avoiding system crash" per their own comment there — a real timing issue with calling
 * Service.startForeground() and then immediately doing more work, separate from the stuck-
 * notification problem the comments below describe). Failing to become foreground this way just
 * means the download proceeds without one, rather than crashing the whole worker over it. */
private suspend fun CoroutineWorker.setForegroundSafely(info: ForegroundInfo) {
    try {
        setForeground(info)
        delay(500)
    } catch (e: IllegalStateException) {
        android.util.Log.e("DownloadWorker", "Not allowed to set foreground state", e)
    }
}

/** Hard cap on how many DownloadWorkers can be doing real download work at once, independent of
 * DownloadDispatcher's own round-robin unique-work-chain scheduling. Reported live: 5 downloads
 * running concurrently while "Concurrent downloads" was set to 2. That round-robin design assumes
 * WorkManager's APPEND_OR_REPLACE unique-work chains guarantee only one active job per chain at a
 * time — a real, normally-reliable WorkManager guarantee, but retries/reschedules re-picking a
 * slot via the same shared counter, a chain getting silently replaced (not appended) once its head
 * reaches a terminal cancelled/failed state, etc. leave real room for more chains to exist, and
 * therefore more simultaneously-active heads, than the configured limit intends. Rather than fully
 * re-audit every one of those WorkManager edge cases, this enforces the cap directly at the one
 * place that actually matters — whether a worker is allowed to start doing its real work — no
 * matter how many chains/slots exist or how they got there.
 *
 * Poll-based (checked once per [POLL_INTERVAL_MS]) rather than a fixed-size Semaphore specifically
 * so it keeps respecting a concurrency-limit change made mid-session without needing to recreate or
 * resize anything — each wait iteration re-reads the current preference value fresh. */
private object DownloadConcurrencyGate {
    // Fallback only now, not the steady-state wait — a waiter normally wakes the instant
    // slotFreed fires below, so this only actually matters for the one case that can't signal
    // itself: the user *raising* the concurrency limit in Settings while every existing slot is
    // still legitimately busy (nothing released, so there's nothing for release() to emit).
    // Reproduced-anti-pattern this replaces: the previous version polled on this same 500ms
    // interval *unconditionally*, waking every waiting worker twice a second for the entire time
    // it sat at the concurrency limit regardless of whether anything had actually changed.
    private const val POLL_FALLBACK_MS = 5_000L
    private val active = AtomicInteger(0)

    // release() emits here; a suspended acquire() wakes on the very next line instead of waiting
    // out whatever's left of a fixed poll interval. DROP_OLDEST + capacity 1 because this is a
    // pure "something changed, go recheck the real state" signal, not a value in itself — a
    // waiter that's about to recheck anyway doesn't need every past release queued up for it.
    private val slotFreed = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Everyone currently waiting for a slot, with their place in line — same ordering the Queue
    // screen shows (queueOrder ASC, then dateAdded ASC). Slots used to go to whichever waiter
    // happened to re-check first, so several downloads moved up with "Up next" (see
    // DownloadDispatcher.startNow) raced each other at random instead of going in the order the
    // queue promises. Now only the front of this line may take a free slot.
    private data class Place(val queueOrder: Int, val dateAdded: Long)
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, Place>()
    private val lineOrder = compareBy<Map.Entry<String, Place>>({ it.value.queueOrder }, { it.value.dateAdded }, { it.key })

    suspend fun acquire(context: Context, downloadId: String, queueOrder: Int, dateAdded: Long) {
        waiting[downloadId] = Place(queueOrder, dateAdded)
        try {
            while (true) {
                val limit = GalleryDlPreferences.getEffectiveConcurrentDownloads(context).coerceAtLeast(1)
                val myTurn = waiting.entries.minWithOrNull(lineOrder)?.key == downloadId

                // Perfectly atomic lock-free compare-and-set loop. Eliminates the previous
                // race condition where multiple waiters could read a stale "under limit" value
                // simultaneously and overshoot the cap.
                while (myTurn) {
                    val current = active.get()
                    if (current >= limit) break
                    if (active.compareAndSet(current, current + 1)) {
                        waiting.remove(downloadId)
                        // The next in line may also fit (concurrency limit above 1).
                        slotFreed.tryEmit(Unit)
                        return
                    }
                }

                withTimeoutOrNull(POLL_FALLBACK_MS) { slotFreed.first() }
            }
        } finally {
            // Cancelled while waiting (paused/cancelled): leave the line so it can't block others.
            waiting.remove(downloadId)
        }
    }

    fun release() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
        slotFreed.tryEmit(Unit)
    }
}

/** Extensions this app's own yt-dlp wrapper can actually produce for an audio-only download (see
 * yt_dlp_wrapper.py's own ACODECS-driven preferredcodec="best" comment) plus the handful of other
 * common audio containers gallery-dl/yt-dlp might hand back unmodified — used to decide whether a
 * saved file's *own* content Uri is worth trying MediaMetadataRetriever's embedded-artwork
 * extraction on at all, rather than wasting a MediaMetadataRetriever pass on every video/image. */
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "alac", "wma")

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Result.success() here too (never expected to actually trigger — WorkManager's own input
        // is always set by DownloadDispatcher) — same reasoning throughout this file: failure()
        // would cascade and kill every other download sharing this WorkManager concurrency slot.
        val downloadId = inputData.getString("downloadId") ?: return Result.success()
        val url = inputData.getString("url") ?: return Result.success()

        val dao = AppDatabase.getDatabase(applicationContext).downloadDao()
        val entity = dao.getById(downloadId)
        val displayTitle = entity?.title?.ifBlank { url } ?: url
        // The song preview sheet's own editable title/artist (SongPreviewCard) — see
        // DownloadEntity.overrideTitle/overrideArtist's own doc comment. Empty string (not null)
        // is this argv's own "not set" sentinel, same convention every other optional string arg
        // passed to the Python wrappers already uses. Computed here (not down by runYtDlp's own
        // other argv-building vals) since the final-file handling block, well above that point in
        // the file, also needs these to update the DB's own displayed title/artist.
        val overrideTitle = entity?.overrideTitle.orEmpty()
        val overrideArtist = entity?.overrideArtist.orEmpty()

        // A resumed/retried download already has real progress sitting in the DB from its last
        // run — starting the notification back at a bare indeterminate spinner (only for the first
        // real progress line or file to arrive to correct it) would visibly regress what the user
        // already saw before it paused. Same item-count-wins-over-bytes priority as
        // computeProgressPercent below (not reused directly — that closure isn't in scope yet here).
        val initialPercent = when {
            entity == null -> null
            entity.totalItems > 1 -> ((entity.downloadedItems.toFloat() / entity.totalItems) * 100).toInt().coerceIn(0, 100)
            entity.expectedBytes > 0 -> (((entity.totalBytes + entity.liveBytes).toFloat() / entity.expectedBytes) * 100).toInt().coerceIn(0, 100)
            else -> null
        }
        // A single, fixed, generic notification id shared by every concurrently running download
        // — never a per-download one — is the only thing setForeground(ForegroundInfo(...)) ever
        // registers now. See FOREGROUND_SERVICE_NOTIFICATION_ID's doc comment for why: a
        // per-download id here used to leave that exact notification permanently stuck (flags
        // ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE, uncancellable) once this worker finished,
        // reproduced live even long after the underlying service was confirmed destroyed.
        //
        // Called *before* DownloadConcurrencyGate.acquire() below, not after — a worker that has to
        // wait its turn at the gate used to sit as an unprotected plain background task for however
        // long that wait takes (potentially indefinite — see the gate's own doc comment), which
        // Android's background execution limits can kill outright before the worker ever gets to
        // become a real foreground service. Promoting first means the wait itself happens under
        // foreground-service protection.
        setForegroundSafely(
            ForegroundInfo(
                DownloadNotifications.FOREGROUND_SERVICE_NOTIFICATION_ID,
                DownloadNotifications.foregroundServiceNotification(applicationContext),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        )
        // Reproduced live: WorkManager's own teardown of the shared foreground notification once
        // this (or every concurrently running) worker finishes isn't reliable on this device/OS
        // build — dumpsys still showed it stuck with ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE
        // minutes after the worker returned SUCCESS *and* dumpsys activity services confirmed no
        // service was even running any more. markForegroundStarted/Stopped keep our own count of
        // how many downloads are actually still using it, and explicitly cancel it once that count
        // hits zero — not tied to (or trusting) WorkManager's own foreground-service bookkeeping.
        DownloadNotifications.markForegroundStarted()
        try {
            // Blocks here (not a hard failure) until a concurrency slot is actually free. Every
            // queued download's worker waits here in line (one job per download, see
            // DownloadDispatcher.enqueueWork) — this is what enforces the limit and the order. release() is in
            // its own try/finally right below, not this outer one — so that if acquire() itself
            // throws/gets cancelled before ever incrementing the counter, release() correctly never
            // runs for a slot this worker never actually took.
            DownloadConcurrencyGate.acquire(
                applicationContext, downloadId,
                queueOrder = entity?.queueOrder ?: 0,
                dateAdded = entity?.dateAdded ?: System.currentTimeMillis(),
            )
            // The real, per-download notification the user actually reads - posted as a plain
            // notify() to its own id, entirely separate from the foreground-service one above, so
            // it's always freely updatable/cancellable regardless of that service's lifecycle.
            // Only once this download has a slot: every queued download's worker now waits at the
            // gate (one job per download, see DownloadDispatcher.enqueueWork), and posting before
            // it gave each waiting one its own "downloading" notification.
            DownloadNotifications.updateProgress(applicationContext, downloadId, displayTitle, entity?.downloadedItems ?: 0, initialPercent)
            try {
            return withContext(Dispatchers.IO) {
                try {
                // Re-checked fresh here, not the `entity` snapshot fetched before this worker ever
                // waited at the concurrency gate above — a Pause/Cancel tapped while this worker
                // was blocked there already wrote PAUSED/CANCELLED and requested this exact job's
                // own cancellation, but per this file's other cancellation-race comments, that's
                // only noticed at this coroutine's own next suspension point, not synchronously.
                // An unconditional RUNNING write here would silently overwrite that already-
                // recorded pause/cancel the instant the gate lets this worker through, before its
                // own cancellation ever catches up — showing "Downloading..." for a download the
                // user just explicitly stopped.
                val freshStatus = dao.getById(downloadId)?.status
                if (freshStatus == DownloadStatus.PAUSED || freshStatus == DownloadStatus.CANCELLED) {
                    DownloadNotifications.cancel(applicationContext, downloadId)
                    return@withContext Result.success()
                }
                dao.updateStatus(downloadId, DownloadStatus.RUNNING)
                val startTime = System.currentTimeMillis()
                dao.setStartTime(downloadId, startTime)

                // Sites gallery-dl can't parse at all skip the gallery-dl attempt entirely;
                // everything else goes through gallery-dl first since that's the engine with real
                // gallery/image support, with yt-dlp used afterward as a fallback or a same-post
                // video supplement (see below) — including TikTok, whose "photo mode" slideshow
                // posts are real image galleries, not video (see VideoSiteRouter's own doc comment).
                // Instagram posts/reels start on Instaloader when that setting is on (see
                // VideoSiteRouter.resolveEngine) — except when this download asked for something
                // only yt-dlp can do: a trimmed clip or an audio-only extraction. Instaloader just
                // fetches the original file, so it would silently ignore both.
                val wantsYtDlpOnlyProcessing = !entity?.clipRange.isNullOrBlank() ||
                    (entity?.videoQuality ?: GalleryDlPreferences.getVideoQuality(applicationContext).name) == VideoQuality.AUDIO_ONLY.name
                val engine = VideoSiteRouter.resolveEngine(applicationContext, url).let {
                    if (it == DownloadEngine.INSTALOADER && wantsYtDlpOnlyProcessing) VideoSiteRouter.classify(url) else it
                }

                // The share-picker flow already knows the total (it enumerated the gallery to
                // render itself) and passes it in up front; everything else — Home screen,
                // instant share — starts with it unknown. Enumerate once here (gallery-dl sites
                // only — yt-dlp-routed sites don't have gallery-dl item metadata to enumerate) so
                // the progress bar can show a real percentage instead of indeterminate, and so we
                // know up front whether the gallery contains a video worth a yt-dlp supplement
                // pass afterward. A count exactly at MAX_ITEMS might just be where the listing got
                // truncated, not the real total, so it's deliberately not trusted in that case.
                // Mirrors the totalItems DB column for the notification's own progress percent (see
                // computeProgressPercent below) — set here upfront and again just below once the
                // gallery-dl listing pass (if any) determines the real count, same two points that
                // already write it to the DB.
                val totalItemsRef = AtomicInteger(entity?.totalItems ?: 0)
                var hasVideoItem = false
                // Listing runs whenever itemFilter is null, NOT only when totalItems isn't known
                // yet — those are two separate concerns that used to share one gate. totalItems
                // already being known (a resumed/retried download whose first attempt already
                // wrote it) is a reason to skip *re-writing* it, but it says nothing about
                // whether hasVideoItem was ever actually determined; hasVideoItem itself is never
                // persisted, so without this every resume of a paused/interrupted download
                // silently defaulted it back to false — on a host outside
                // VideoSiteRouter.alwaysSupplementVideoHosts (Reddit, Twitter/X, ...), that
                // permanently dropped the yt-dlp video-supplement pass for a mixed post's video on
                // every subsequent resume, with no error and no trace it had ever been there.
                if (engine == DownloadEngine.GALLERY_DL && entity?.itemFilter == null) {
                    val listed = GalleryDlListing.listItems(applicationContext, url).items
                    if ((entity?.totalItems ?: 0) <= 0 && listed.isNotEmpty() && listed.size < GalleryDlListing.MAX_ITEMS) {
                        dao.setTotalItems(downloadId, listed.size)
                        totalItemsRef.set(listed.size)
                    }
                    hasVideoItem = listed.any { item -> item.filename?.let(VideoSiteRouter::isVideoFilename) == true }
                }
                // Spotify album/playlist links need the same upfront item count a gallery-dl
                // gallery gets (a track link's own listing is always exactly 1, so this is a
                // no-op for that case) — spotify_wrapper.py's own download() call below reports
                // each track's real final file the same way a multi-item gallery-dl download
                // already does, so this is the only Spotify-specific wiring the multi-item case
                // actually needs; everything downstream (item counting, progress %) is engine-
                // agnostic already.
                if (engine == DownloadEngine.SPOTIFY && (entity?.totalItems ?: 0) <= 0 && entity?.itemFilter == null) {
                    val listed = GalleryDlListing.listItems(applicationContext, url).items
                    if (listed.isNotEmpty() && listed.size < GalleryDlListing.MAX_ITEMS) {
                        dao.setTotalItems(downloadId, listed.size)
                        totalItemsRef.set(listed.size)
                    }
                }
                if (isStopped) {
                    // Result.success(), not failure() — this WorkRequest only shares a WorkManager
                    // "queue" name with unrelated downloads to cap concurrency (see
                    // DownloadDispatcher's round-robin slots), not because they depend on each
                    // other. Result.failure() propagates through enqueueUniqueWork's chain and
                    // auto-fails every OTHER download still queued behind this one in the same
                    // slot — without ever running them — permanently freezing them at "waiting to
                    // start" with no error surfaced (reproduced live). Our own DB status column is
                    // the real source of truth for this download's outcome; WorkManager's Result
                    // only needs to say "done, move on to the next queued item".
                    DownloadNotifications.cancel(applicationContext, downloadId)
                    return@withContext Result.success()
                }

                // Normalized into a private per-download temp copy — NOT rewritten in place on the
                // real cookies.txt, which this used to do. cookies.txt is shared by every
                // concurrently running download (see DownloadDispatcher's round-robin queues), and
                // an in-place read-then-write with no locking around it is a genuine TOCTOU race:
                // reproduced live as the real cookies.txt getting reduced to 0 bytes — permanently,
                // no way to recover the content — after enough concurrent downloads hit this same
                // line close together (one worker's writeText() truncating the file out from under
                // another worker's concurrent readText()). A private copy per worker sidesteps the
                // shared-mutable-file problem entirely instead of trying to lock around it.
                val cookiesPath = applicationContext.filesDir.resolve("cookies.txt")
                // length() > 0, not just exists() — an empty cookies.txt (reproduced live: a
                // corrupted 0-byte file, from the very race this normalization step used to cause)
                // still "exists" but gallery-dl/yt-dlp both hard-reject it as not looking like a
                // real Netscape cookies file, which used to fail every download outright even
                // though a *missing* cookies file downloads just fine anonymously. Treating "empty"
                // the same as "absent" means a bad cookies file degrades to normal anonymous
                // behavior instead of breaking every download regardless of whether that particular
                // site even needs cookies.
                val normalizedCookiesPath = if (cookiesPath.exists() && cookiesPath.length() > 0) {
                    File(applicationContext.cacheDir, "cookies-normalized-$downloadId.txt").apply {
                        // The Cookies & Login screen's per-site toggle — a site can have real,
                        // valid saved cookies that the user still doesn't want sent (e.g. a stale
                        // or unwanted login), without deleting them outright. Filtered out of this
                        // per-download copy only; the real, persistent cookies.txt this reads from
                        // is never touched by a toggle, only by an actual delete.
                        val disabledDomains = GalleryDlPreferences.getDisabledCookieDomains(applicationContext)
                        val normalized = cookiesPath.readText().replace("\r\n", "\n")
                        writeText(GalleryDlPreferences.filterCookiesByDisabledDomains(normalized, disabledDomains))
                    }
                } else null

                // gallery-dl needs a real filesystem path to write to; stage downloads here,
                // then move each finished file into the public gallery via MediaStore so it's
                // actually visible in the Photos/Gallery app instead of stuck in private storage.
                val stagingDir = File(applicationContext.cacheDir, "gallery-dl-staging/$downloadId").apply { mkdirs() }
                val savedCount = AtomicInteger(entity?.downloadedItems ?: 0)
                val bytesSoFar = AtomicLong(0)
                // Local mirror of the [size] line's DB column for the notification's own percent —
                // cheaper than a DB round-trip per progress line. Same item-count-wins-over-bytes
                // priority QueueScreen's own progress bar uses, so the notification and the in-app
                // card never visibly disagree.
                val expectedBytesRef = AtomicLong(0)
                val currentFileBytesRef = AtomicLong(0)
                fun computeProgressPercent(): Int? {
                    val totalItems = totalItemsRef.get()
                    if (totalItems > 1) return ((savedCount.get().toFloat() / totalItems) * 100).toInt().coerceIn(0, 100)
                    val expectedBytes = expectedBytesRef.get()
                    if (expectedBytes > 0) {
                        val soFar = bytesSoFar.get() + currentFileBytesRef.get()
                        return ((soFar.toFloat() / expectedBytes) * 100).toInt().coerceIn(0, 100)
                    }
                    return null
                }
                // The actual reason nothing came down, straight from gallery-dl/yt-dlp's own
                // "[error] ..." lines — shown instead of the generic fallback below when nothing
                // gets saved, so a real cause (blocked, login required, no formats found, ...) is
                // visible instead of every failure looking identical.
                val lastErrorLine = java.util.concurrent.atomic.AtomicReference<String?>(null)

                // The placeholder title set at enqueue time (see DownloadDispatcher) always starts
                // this way — used below to tell "still showing the placeholder" apart from "the
                // user already renamed this" so a real poster/caption title only ever replaces the
                // former, never clobbers a deliberate rename.
                val hasPlaceholderTitle = entity?.title?.startsWith("Downloading") != false

                // A multi-item gallery-dl download pins its thumbnail to whichever item finishes
                // first (setThumbnailIfAbsent below) so it doesn't keep flickering to the latest
                // item as more come in. That ambiguity doesn't exist for a single-item download —
                // every yt-dlp download, or a lone-item gallery-dl one — so its real local file
                // should always be free to replace an earlier remote thumbnail preview (see the
                // [thumbnail] branch below) rather than being blocked by an already-filled slot.
                val singleItemDownload = (entity?.totalItems ?: 1) <= 1

                // The schedule window only ever gated a download from *starting* (see
                // DownloadDispatcher.enqueueWork's own setInitialDelay) — nothing here noticed the
                // window closing on a download already RUNNING, so a long-running one (a big video,
                // a large gallery) could freely bleed straight through into the restricted hours a
                // "night-time only" schedule is meant to keep data usage out of. Re-checked at most
                // once every 60s of wall-clock (not on every single output line, which for a fast
                // multi-item gallery-dl gallery can be many times a second) to keep this cheap;
                // scheduleClosedPauseRequested guards against firing pauseDownload() more than once
                // if several lines arrive in the same window right as it closes — WorkManager's own
                // cancellation of this same job takes a moment to actually propagate back in.
                val lastScheduleCheckMs = AtomicLong(0L)
                val scheduleClosedPauseRequested = AtomicBoolean(false)
                // A negative queueOrder is startNow()'s own marker for "the user explicitly jumped
                // this past the schedule window" (see DownloadDispatcher.startNow/repairIfJobDead).
                // Without this, the periodic check below would pause a Start-Now'd download within
                // its first minute anyway the instant the window happens to already be closed —
                // silently undoing the very override the user just tapped. Snapshotted once from the
                // entity fetched at the top of doWork(), not re-read live, so this exemption covers
                // this entire run exactly like the initial forceImmediate skip already did.
                val startedViaStartNow = (entity?.queueOrder ?: 0) < 0

                // suspend, not a plain lambda — PythonRuntime's onLine has no JNI-reentrancy
                // constraint (unlike Chaquopy's old synchronous callback), so every DB write below
                // is a direct, awaited suspend call instead of a fire-and-launch job collected into
                // a separate list and joined afterward.
                // When data last arrived, and when the engine itself last reported progress (only
                // yt-dlp does) — see the transfer monitor around the engine run below.
                val lastDataAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                val lastProgressLineAt = java.util.concurrent.atomic.AtomicLong(0L)
                // That monitor's measured speed (MB/s), so a file landing doesn't overwrite it with a
                // whole-download average.
                val measuredSpeedMbs = java.util.concurrent.atomic.AtomicReference(0f)
                val actualCallback: suspend (String) -> Unit = actualCallback@{ line ->
                    android.util.Log.d("DownloadEngine", "Python output: $line")

                    if (!scheduleClosedPauseRequested.get() && !startedViaStartNow) {
                        val now = System.currentTimeMillis()
                        val last = lastScheduleCheckMs.get()
                        if (now - last >= 60_000L && lastScheduleCheckMs.compareAndSet(last, now)) {
                            if (GalleryDlPreferences.isScheduleEnabled(applicationContext) &&
                                !DownloadDispatcher.isWithinScheduleWindow(applicationContext) &&
                                scheduleClosedPauseRequested.compareAndSet(false, true)
                            ) {
                                // Same mechanism a manual Pause tap already uses while this download
                                // is running: this cancels the WorkManager job *this worker is
                                // itself running under*, which this coroutine hierarchy notices at
                                // its own next suspension point (PythonRuntime.run()'s own killer
                                // sibling coroutine force-kills the subprocess, unblocking the
                                // blocking readLine() loop) — the outer catch(CancellationException)
                                // below already knows to leave whatever status this just set alone.
                                DownloadDispatcher.suspendForSchedule(applicationContext, downloadId)
                            }
                        }
                    }

                    when {
                        // yt-dlp-only signals (see yt_dlp_wrapper.py's progress_hook) — gallery-dl
                        // never emits these, so gallery-dl-routed downloads just never hit this
                        // branch and keep using the item-count progress path below untouched.
                        line.startsWith("[size] ") -> {
                            // toLongOrNull() alone silently dropped this for any HLS/fragmented
                            // stream (Reddit's native videos, Twitter/X, ...): yt-dlp's own
                            // total_bytes_estimate (used whenever there's no exact Content-Length
                            // to report, only an estimate from fragment count/size) is a float,
                            // e.g. "6897840.0" — reproduced live, that exact line never set a size
                            // for a Twitter video, matching the user's own "size never shows for
                            // Reddit/Twitter" report. toDoubleOrNull() first still accepts a plain
                            // integer string too, so this covers both shapes.
                            val bytes = line.removePrefix("[size] ").trim().toDoubleOrNull()?.toLong()
                            if (bytes != null) {
                                dao.setExpectedBytes(downloadId, bytes)
                                expectedBytesRef.set(bytes)
                            }
                        }
                        // Sent once per sub-file (a video+audio merge's own separate audio track,
                        // or the sole file of an audio_only download) — see
                        // DownloadEntity.downloadingAudioTrack's own doc comment for why the queue
                        // card needs to know this at all.
                        // instaloader_wrapper.py's item count (after the picker's filter), known
                        // before the first file lands — the upfront gallery-dl listing pass that
                        // normally provides this is skipped for Instaloader-routed downloads.
                        line.startsWith("[total] ") -> {
                            val total = line.removePrefix("[total] ").trim().toIntOrNull()
                            if (total != null && total > 0 && totalItemsRef.get() <= 0) {
                                dao.setTotalItems(downloadId, total)
                                totalItemsRef.set(total)
                            }
                        }
                        line.startsWith("[phase] ") -> {
                            dao.setDownloadingAudioTrack(downloadId, line.removePrefix("[phase] ").trim() == "audio")
                        }
                        // Sent alongside [phase] above, same per-sub-file timing — see
                        // DownloadEntity.formatTags' own doc comment.
                        line.startsWith("[format] ") -> {
                            dao.setFormatTags(downloadId, line.removePrefix("[format] ").trim())
                        }
                        line.startsWith("[progress] ") -> {
                            val rest = line.removePrefix("[progress] ")
                            val downloaded = Regex("downloaded=(\\d+)").find(rest)?.groupValues?.get(1)?.toLongOrNull()
                            val speedBps = Regex("speed=([\\d.]+)").find(rest)?.groupValues?.get(1)?.toFloatOrNull()
                            if (downloaded != null) {
                                lastDataAt.set(System.currentTimeMillis())
                                lastProgressLineAt.set(System.currentTimeMillis())
                                val speedMbs = (speedBps ?: 0f) / (1024f * 1024f)
                                dao.updateLiveBytes(downloadId, downloaded, speedMbs)
                                currentFileBytesRef.set(downloaded)
                                // yt_dlp_wrapper.py's own progress_hook already throttles these lines
                                // to roughly once a second, so no extra throttling needed here — this
                                // is what actually makes the notification's progress bar move at all
                                // during a single large download instead of sitting indeterminate for
                                // the whole transfer until the one file finishes (the only other call
                                // to updateProgress, below, only fires once per completed *file*).
                                DownloadNotifications.updateProgress(
                                    applicationContext, downloadId, displayTitle, savedCount.get(), computeProgressPercent(),
                                    speedMbs = speedMbs, currentBytes = bytesSoFar.get() + downloaded, expectedBytes = expectedBytesRef.get(),
                                )
                            }
                        }
                        line.startsWith("[thumbnail] ") -> {
                            // Sent as soon as extraction finishes, same timing as [title] — lets
                            // the queue card show a real preview image for the whole transfer
                            // instead of a generic icon. setThumbnailIfAbsent (not the unconditional
                            // setThumbnail) so it doesn't preempt an already-set thumbnail on a
                            // resumed/retried download; the real local file still wins over this
                            // remote preview once it lands, via the singleItemDownload check below.
                            val thumbUrl = line.removePrefix("[thumbnail] ").trim()
                            if (thumbUrl.isNotBlank()) dao.setThumbnailIfAbsent(downloadId, thumbUrl)
                        }
                        line.startsWith("[title] ") -> {
                            // Sent as soon as extraction finishes — well before the first byte
                            // lands — so the card shows what's actually downloading instead of
                            // sitting on the "Downloading from X" placeholder for the whole
                            // transfer. gallery-dl has no equivalent early hook, so its downloads
                            // keep relying on derivePosterCaptionTitle once a file lands instead.
                            if (hasPlaceholderTitle) {
                                val title = line.removePrefix("[title] ").trim()
                                if (title.isNotBlank()) dao.updateTitle(downloadId, title)
                            }
                        }
                        // yt-dlp's own info_dict metadata (yt_dlp_wrapper.py's progress_hook) or
                        // Spotify's own scraped metadata (spotify_wrapper.py) — see
                        // DownloadEntity.artist/album/track's own doc comments.
                        line.startsWith("[artist] ") -> {
                            val artist = line.removePrefix("[artist] ").trim()
                            if (artist.isNotBlank()) dao.setArtistIfAbsent(downloadId, artist)
                        }
                        line.startsWith("[album] ") -> {
                            val album = line.removePrefix("[album] ").trim()
                            if (album.isNotBlank()) dao.setAlbumIfAbsent(downloadId, album)
                        }
                        line.startsWith("[track] ") -> {
                            val track = line.removePrefix("[track] ").trim()
                            if (track.isNotBlank()) dao.setTrackIfAbsent(downloadId, track)
                        }
                        // yt-dlp's own "[error] ERROR: ..." lines, gallery-dl's own
                        // "[extractor_name][error] ..." lines (different shape — its logger name
                        // comes first, confirmed live: "[instagram][error] HTTP redirect to login
                        // page" was silently missed here before, letting a *less* useful fallback
                        // error from yt-dlp's own supplement pass overwrite it instead of ever being
                        // shown), and gallery_dl_wrapper.py's print() fallback for a SystemExit/
                        // Exception it couldn't otherwise report ("Error, exited with code N" /
                        // "Exception: ...") — all reach here as plain stdout/stderr lines via the
                        // same callback.
                        line.startsWith("[error] ") || GALLERY_DL_ERROR_LINE.containsMatchIn(line) ||
                            line.startsWith("Error,") || line.startsWith("Exception:") -> {
                            // First error wins, not last, *unless* that first one turns out to be
                            // unusable garbage. The general rule (when gallery-dl and a yt-dlp
                            // fallback/supplement pass both fail, gallery-dl's message is normally
                            // the actual root cause and yt-dlp's is just a downstream symptom of the
                            // same block) doesn't hold for gallery-dl's own "AbortExtraction(raw
                            // HTML/CSS blob)" failure mode (see GalleryDlListing.sanitizeErrorMessage's
                            // doc comment) — reproduced live against Reddit: gallery-dl's own error
                            // was that unusable blob, while yt-dlp's fallback attempt gave a real,
                            // actionable one ("Account authentication is required") that a strict
                            // first-wins policy was silently discarding in favor of the useless one.
                            // sanitizeErrorMessage() changing the text is exactly the signal that the
                            // captured error is that class of garbage, not a real diagnostic message
                            // worth protecting from being overwritten.
                            val candidate = line.substringAfter("[error] ").trim()
                            // yt-dlp's own logger reprints *every* line of a multi-line Python
                            // traceback with this same "[error] " prefix — not just the real
                            // "ERROR: ..." announcement, but every "  File \"...\", line N, in ..."
                            // and bare code-fragment continuation line too. A genuinely long-but-
                            // real message (reproduced live: yt-dlp's own "Instagram sent an empty
                            // media response... may need cookies" line) is long enough to trip
                            // sanitizeErrorMessage's own length-based "looks like garbage"
                            // heuristic above — which used to let the *traceback's own noise*, a
                            // few lines later, win the overwrite race purely for arriving after it,
                            // discarding the one actually informative line in favor of a bare
                            // "ie_result = self._real_extract(url)" fragment. yt-dlp always starts
                            // a real error announcement with "ERROR:"; a continuation line never
                            // does — this is the correct signal to gate on here, not length.
                            val isTracebackNoise = line.startsWith("[error] ") && !candidate.startsWith("ERROR:")
                            if (!isTracebackNoise) {
                                lastErrorLine.getAndUpdate { current ->
                                    if (current == null || GalleryDlListing.sanitizeErrorMessage(current) != current) candidate else current
                                }
                            }
                        }
                        // gallery-dl's own "no results" outcome — not an [error] line at all (just
                        // its logger's [info] level), so it silently fell through to the file-path
                        // branch below and never got captured as a reason. Reproduced live: a tweet
                        // gallery-dl's guest-token API simply can't see (no [error], just an empty
                        // result) fell all the way through to savedCount==0's yt-dlp fallback, whose
                        // own unrelated failure ("No video could be found in this tweet" for a post
                        // that was actually a picture carousel) was the only thing left to show —
                        // actively misleading about what really went wrong. Capturing gallery-dl's
                        // real, empty-handed outcome here first means the "first wins" rule above
                        // correctly keeps this over yt-dlp's less relevant fallback error, the same
                        // way a genuine gallery-dl [error] line already would.
                        GALLERY_DL_NO_RESULTS_LINE.containsMatchIn(line) -> {
                            lastErrorLine.getAndUpdate { current ->
                                if (current == null || GalleryDlListing.sanitizeErrorMessage(current) != current) {
                                    "No content found at this link — it may need cookies for a logged-in session, or be unavailable"
                                } else current
                            }
                        }
                        // yt-dlp's own non-fatal warnings — never file paths, nothing to act on,
                        // just kept out of the file-path branch below.
                        line.startsWith("[warning] ") -> Unit
                        // Only ever emitted when a caller explicitly opts a run into
                        // ydl_opts["verbose"] (see yt_dlp_wrapper.py's _Logger.debug()) — not
                        // something a normal download run produces, kept out of the file-path
                        // branch below the same as [warning].
                        line.startsWith("[debug] ") -> Unit
                        // The wrapper script's own final status line (see its __main__ block) —
                        // never consumed (status is derived from lastErrorLine/savedCount instead,
                        // same as when this was Chaquopy's callAttr() return value), just kept out
                        // of the file-path branch below.
                        line.startsWith("[__status__] ") -> Unit
                        else -> {
                            try {
                                val candidate = File(line.trim())
                                if (candidate.isAbsolute && candidate.isFile &&
                                    candidate.canonicalPath.startsWith(stagingDir.canonicalPath)
                                ) {
                                    // gallery-dl's own --download-archive isn't reliably updated
                                    // before a pause/cancel interrupt can land, so a file we've
                                    // already moved and counted can still get re-announced as "fresh"
                                    // on a later resume. This is the actual source of truth for
                                    // whether we've handled this exact file for this download before
                                    // — a duplicate announcement is dropped here instead of being
                                    // recounted and saved as a second copy.
                                    val isNewFile = dao.recordDownloadedFile(DownloadedFileRecord(downloadId, candidate.name)) != -1L
                                    if (!isNewFile) {
                                        candidate.delete()
                                        return@actualCallback
                                    }
                                    val fileSize = candidate.length()
                                    // Extension-derived, except for Spotify: its own ffmpeg
                                    // compatibility fix (yt_dlp_wrapper.py's ACODECS remap,
                                    // needed because this app's stripped ffmpeg build has no
                                    // ogg/opus muxer) makes an opus/vorbis extraction land as
                                    // a bare .webm file — a container AUDIO_EXTENSIONS can't
                                    // list on its own without misclassifying real webm video
                                    // downloads as audio. Every Spotify download is audio by
                                    // definition (see VideoSiteRouter/runSpotify), so that
                                    // engine check covers the gap without broadening the
                                    // general-purpose extension set. Computed before
                                    // saveMediaToGallery (not after, like the rest of this
                                    // block) so it can override that "webm" extension's own
                                    // default video/webm MIME guess — without this, a Spotify
                                    // track saved as .webm lands in Movies/Comfort as a
                                    // "video", not Music/Comfort as audio.
                                    val isAudioFile = candidate.extension.lowercase() in AUDIO_EXTENSIONS ||
                                        engine == DownloadEngine.SPOTIFY
                                    val savedUri = MediaStoreHelper.saveMediaToGallery(
                                        applicationContext, candidate, forceAudioMime = isAudioFile,
                                    )
                                    if (savedUri != null) {
                                        lastDataAt.set(System.currentTimeMillis())
                                        candidate.delete()
                                        val count = savedCount.incrementAndGet()
                                        val totalBytes = bytesSoFar.addAndGet(fileSize)
                                        // This file's bytes now live in bytesSoFar (via addAndGet
                                        // above) instead of being "in flight" — without resetting
                                        // this, the next file's own progress would double-count
                                        // everything the previous file already contributed.
                                        currentFileBytesRef.set(0)
                                        val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.5f)
                                        val speedMbs = measuredSpeedMbs.get().takeIf { it > 0f }
                                            ?: ((totalBytes / (1024f * 1024f)) / elapsedSeconds)
                                        dao.updateLiveProgress(downloadId, count, speedMbs)
                                        // savedUri (the audio file's own content Uri) has no frame
                                        // Coil can decode as an image, unlike video — pull the
                                        // embedded cover art out to its own file instead when
                                        // there is one (see extractAudioArtworkUri's own doc
                                        // comment). Only when that actually produces a separate
                                        // image does thumbnailPath stop being "the same Uri as the
                                        // real file" — so only then does mediaUri need to carry the
                                        // real file's own Uri separately (see its own doc comment
                                        // on DownloadEntity) for the Library screen's tap-to-open
                                        // to still open/play the real file instead of the cover art.
                                        // isAudioFile computed above, before saveMediaToGallery.
                                        // Independent of the [artist]/[album]/[track] lines —
                                        // this is extension-derived and always correct for a
                                        // given file, so it's set unconditionally (not IfAbsent)
                                        // every time a file for this download lands, regardless
                                        // of whether any metadata line ever fired.
                                        if (isAudioFile) dao.setIsAudio(downloadId, true)
                                        val artworkUri = if (isAudioFile) {
                                            MediaStoreHelper.extractAudioArtworkUri(applicationContext, savedUri, downloadId)
                                        } else {
                                            null
                                        }
                                        val thumbnailUri = artworkUri ?: savedUri
                                        if (singleItemDownload) {
                                            dao.setThumbnail(downloadId, thumbnailUri.toString())
                                            if (artworkUri != null) dao.setMediaUri(downloadId, savedUri.toString())
                                        } else {
                                            dao.setThumbnailIfAbsent(downloadId, thumbnailUri.toString())
                                            if (artworkUri != null) dao.setMediaUriIfAbsent(downloadId, savedUri.toString())
                                        }
                                        dao.addBytes(downloadId, fileSize)
                                        if (hasPlaceholderTitle) {
                                            derivePosterCaptionTitle(candidate.name)?.let { dao.updateTitle(downloadId, it) }
                                        }
                                        // The song preview sheet's own editable title/artist
                                        // (SongPreviewCard) — applied last, unconditionally, so a
                                        // user's explicit edit always wins in the Library's own
                                        // display regardless of whatever the source itself (or the
                                        // filename-derived title just above) already set. Matches
                                        // what actually got embedded into the file's own tags —
                                        // see yt_dlp_wrapper.py's/spotify_wrapper.py's own
                                        // override_title/override_artist handling.
                                        if (!overrideTitle.isNullOrBlank()) dao.updateTitle(downloadId, overrideTitle)
                                        if (!overrideArtist.isNullOrBlank()) dao.setArtist(downloadId, overrideArtist)
                                        DownloadNotifications.updateProgress(
                                            applicationContext, downloadId, displayTitle, count, computeProgressPercent(),
                                            speedMbs = speedMbs, currentBytes = totalBytes, expectedBytes = expectedBytesRef.get(),
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("DownloadEngine", "Failed to process output line: $line", e)
                            }
                        }
                    }
                }

                val cookiesArg = normalizedCookiesPath?.absolutePath ?: ""
                // Each of these prefers what the download preview sheet recorded for this one
                // download over the global Settings default — see DownloadEntity's own comment on
                // why they're nullable rather than defaulted. A retry/resume therefore re-applies
                // exactly what the user picked in the sheet, not a since-changed global.
                val filenameFormat = entity?.filenameTemplate?.takeIf { it.isNotBlank() }
                    ?: GalleryDlPreferences.getFilenameFormat(applicationContext)
                // The sheet's extra commands are appended to (not a replacement for) the global
                // Advanced > Extra arguments field, so a per-download tweak doesn't silently drop
                // whatever the user configured globally for every download.
                // Advanced > Extra arguments: each engine gets the "both" set plus its own (see
                // GalleryDlPreferences.getExtraArgsFor) — the two take different flags.
                fun extraArgsFor(engine: DownloadEngine) = listOfNotNull(
                    GalleryDlPreferences.getExtraArgsFor(applicationContext, engine).takeIf { it.isNotBlank() },
                    entity?.extraCommands?.takeIf { it.isNotBlank() },
                ).joinToString(" ")
                val extraArgs = extraArgsFor(DownloadEngine.GALLERY_DL)
                val ytDlpExtraArgs = extraArgsFor(DownloadEngine.YT_DLP)
                // Tracks already-fetched item IDs across retries, so pausing/retrying a download
                // resumes where it left off instead of starting the whole gallery over. Separate
                // files per engine — gallery-dl's archive is a sqlite db, yt-dlp's is a plain text
                // list of extractor ids, and the two formats aren't compatible with each other.
                val galleryArchivePath = File(applicationContext.filesDir, "archives/$downloadId.sqlite3")
                    .apply { parentFile?.mkdirs() }
                    .absolutePath
                val ytDlpArchivePath = File(applicationContext.filesDir, "archives/$downloadId.ytdlp.txt")
                    .apply { parentFile?.mkdirs() }
                    .absolutePath
                // Plain list of "<shortcode>_<n>" keys instaloader_wrapper.py already fetched.
                val instaloaderArchivePath = File(applicationContext.filesDir, "archives/$downloadId.instaloader.txt")
                    .apply { parentFile?.mkdirs() }
                    .absolutePath
                val limitRate = GalleryDlPreferences.getEffectiveSpeedLimit(applicationContext)
                val networkRetries = GalleryDlPreferences.getEffectiveNetworkRetries(applicationContext)
                val maxFilesize = GalleryDlPreferences.getEffectiveMaxFilesize(applicationContext).orEmpty()
                val writeInfoFiles = GalleryDlPreferences.isWriteInfoFiles(applicationContext)
                val proxyUrl = GalleryDlPreferences.getEffectiveProxyUrl(applicationContext)
                val extractorArgs = GalleryDlPreferences.getExtractorArgs(applicationContext)
                val socketTimeoutSeconds = GalleryDlPreferences.getEffectiveSocketTimeoutSeconds(applicationContext)

                // Each download is its own OS subprocess now (see PythonRuntime), not a reentrant
                // call into one shared interpreter — the race PythonEngineLock existed to prevent
                // (gallery-dl mutating process-global sys.argv/stdout across concurrent calls)
                // doesn't apply here, so downloads now run genuinely concurrently up to the
                // "Concurrent downloads" setting instead of being serialized behind one lock.
                suspend fun runGalleryDl(excludeVideo: Boolean): Int =
                    PythonRuntime.run(
                        applicationContext, "gallery_dl_wrapper.py",
                        listOf(
                            "download", url, stagingDir.absolutePath, cookiesArg,
                            filenameFormat, extraArgs, galleryArchivePath, limitRate,
                            entity?.itemFilter.orEmpty(), if (excludeVideo) "1" else "0",
                            networkRetries, maxFilesize, if (writeInfoFiles) "1" else "0", proxyUrl,
                            socketTimeoutSeconds,
                        ),
                        actualCallback,
                    )

                // Bundled as jniLibs/<abi>/libqjs.so and libffmpeg.so respectively — see
                // QuickJsRuntime's and FfmpegRuntime's doc comments for why sites like YouTube
                // need the former just to extract real download URLs, and the latter to merge
                // the separate video/audio streams those URLs point to into one playable file.
                val jsRuntimePath = QuickJsRuntime.getExecutablePath(applicationContext).orEmpty()
                val ffmpegPath = FfmpegRuntime.getExecutablePath(applicationContext).orEmpty()
                // Empty on arm64-v8a/x86_64 (that ABI's ffmpeg is fully static, nothing to
                // resolve) — non-empty only on armeabi-v7a, where it points yt_dlp_wrapper.py at
                // ffmpeg's own unpacked shared-library dependencies (LD_LIBRARY_PATH), the same
                // mechanism aria2LibDir below already uses for aria2c.
                val ffmpegLibDir = FfmpegRuntime.ensureProvisioned(applicationContext)?.absolutePath.orEmpty()

                // A per-download override the share-sheet picker set (only offered when its
                // listing found a video item) takes priority over the global Settings default —
                // falls back to it when null, same as before this override existed.
                val videoQuality = entity?.videoQuality?.let { stored -> runCatching { VideoQuality.valueOf(stored) }.getOrNull() }
                    ?: GalleryDlPreferences.getVideoQuality(applicationContext)
                val audioOnly = videoQuality == VideoQuality.AUDIO_ONLY
                val clipRange = entity?.clipRange.orEmpty()
                val downloadSubtitles = GalleryDlPreferences.isDownloadSubtitles(applicationContext)
                val subtitleLangs = GalleryDlPreferences.getSubtitleLanguages(applicationContext)
                val embedThumbnail = GalleryDlPreferences.isEmbedThumbnail(applicationContext)
                // The sheet's "Save thumbnail" chip writes the thumbnail out as its own file
                // (yt-dlp's writethumbnail) rather than embedding it in the media — a separate
                // choice from the global "Embed thumbnail" setting above, which muxes it in.
                val saveThumbnail = entity?.saveThumbnail == true
                val embedMetadata = GalleryDlPreferences.isEmbedMetadata(applicationContext)
                val noPlaylist = GalleryDlPreferences.isNoPlaylist(applicationContext)
                val liveFromStart = GalleryDlPreferences.isLiveFromStart(applicationContext)
                val outputFormat = entity?.outputFormat?.let { stored -> runCatching { OutputFormat.valueOf(stored) }.getOrNull() }
                    ?: GalleryDlPreferences.getOutputFormat(applicationContext)
                // The share-sheet picker's own gallery-dl-syntax --filter ("num in {1,3,4}", see
                // SharePickerScreen) translated into the bare "1,3,4" digit-list syntax both
                // yt-dlp's own native playlist_items AND spotify_wrapper.py's own playlist_items
                // param (added for the song preview sheet's per-track checkboxes) expect — the
                // item numbers themselves are already the right 1-indexed positions in every case
                // (see GalleryDlListing.listViaYtDlp's entryToGalleryItem and
                // GalleryDlListing.PreviewInfo.tracks' own doc comment for the Spotify case), only
                // the surrounding syntax differs between engines' own filter mechanisms — so this
                // one extraction is reused verbatim for both the yt-dlp and Spotify engines below.
                // Previously dropped entirely for a yt-dlp-routed download: the picker let the
                // user uncheck specific playlist videos, but yt-dlp itself never heard about that
                // selection and downloaded based only on the global "Download Playlists" setting
                // instead.
                //
                // Blank whenever engine == GALLERY_DL, though: runYtDlp() below is reused verbatim
                // as that engine's own video-supplement pass (see the GALLERY_DL branch further
                // down), and for a multi-item Instagram/TikTok post the checklist's "num"s now come
                // from gallery-dl's own listing (GalleryDlListing.fetchGalleryDlPreviewInfo) so
                // runGalleryDl's own --filter (below) understands them correctly — but yt-dlp lists
                // that exact same post completely independently (its own extractor, its own
                // numbering, usually far fewer entries since it only ever sees the video items), so
                // the same digits handed to its playlist_items would filter against the wrong
                // listing entirely. Left unfiltered here, the supplement pass just fetches every
                // real video it finds regardless of the checklist selection — an occasional extra
                // file, never a silently wrong one, which is the safer failure mode of the two.
                // INSTALOADER too: its fallback is this same gallery-dl + yt-dlp-supplement path,
                // and its checklist "num"s are gallery-dl's carousel numbering, not yt-dlp's.
                val ytDlpPlaylistItems = if (engine == DownloadEngine.GALLERY_DL || engine == DownloadEngine.INSTALOADER) {
                    ""
                } else {
                    ITEM_FILTER_NUMS_RE.find(entity?.itemFilter.orEmpty())?.groupValues?.get(1).orEmpty()
                }
                // Imported from YTDLnis's own settings screens — see GalleryDlPreferences' own
                // doc comments on each of these for why they're yt-dlp-only.
                val forceIpv4 = GalleryDlPreferences.isForceIpv4(applicationContext)
                val concurrentFragments = GalleryDlPreferences.getEffectiveConcurrentFragments(applicationContext)
                val noCheckCertificates = GalleryDlPreferences.isNoCheckCertificates(applicationContext)
                val sleepIntervalSeconds = GalleryDlPreferences.getEffectiveSleepIntervalSeconds(applicationContext)
                val customHeaders = GalleryDlPreferences.getCustomHeaders(applicationContext)
                val formatSort = GalleryDlPreferences.getFormatSort(applicationContext)
                val verboseLogging = GalleryDlPreferences.isVerboseLogging(applicationContext)
                // Second YTDLnis settings-import batch — see GalleryDlPreferences' own doc
                // comments on each of these.
                val embedChapters = GalleryDlPreferences.isEmbedChapters(applicationContext)
                val saveSubtitleFiles = GalleryDlPreferences.isSaveSubtitleFiles(applicationContext)
                val restrictFilenames = GalleryDlPreferences.isRestrictFilenames(applicationContext)
                val trimFilenames = GalleryDlPreferences.isTrimFilenames(applicationContext)
                val fragmentRetries = GalleryDlPreferences.getEffectiveFragmentRetries(applicationContext)
                val bufferSizeKb = GalleryDlPreferences.getEffectiveBufferSizeKb(applicationContext)
                val formatIdOverride = GalleryDlPreferences.getFormatIdOverride(applicationContext)
                val youtubeClientRotation = GalleryDlPreferences.isYoutubeClientRotationEnabled(applicationContext)
                val impersonate = GalleryDlPreferences.isImpersonateEnabled(applicationContext)
                val aria2Enabled = GalleryDlPreferences.isAria2Enabled(applicationContext)
                val aria2Path = if (aria2Enabled) Aria2Runtime.getExecutablePath(applicationContext).orEmpty() else ""
                val aria2LibDir = if (aria2Enabled) {
                    Aria2Runtime.ensureProvisioned(applicationContext)?.absolutePath.orEmpty()
                } else {
                    ""
                }
                suspend fun runYtDlp(): Int =
                    // gallery-dl's filename-format template syntax means nothing to yt-dlp, so
                    // it isn't passed; extra arguments are yt-dlp's own set (ytDlpExtraArgs, real
                    // yt-dlp flags). Cookies and the speed limit are shared.
                    PythonRuntime.run(
                        applicationContext, "yt_dlp_wrapper.py",
                        listOf(
                            "download", url, stagingDir.absolutePath, cookiesArg,
                            "", ytDlpExtraArgs, ytDlpArchivePath, limitRate, formatIdOverride,
                            jsRuntimePath, ffmpegPath,
                            if (audioOnly) "1" else "0", if (downloadSubtitles) "1" else "0", subtitleLangs,
                            if (embedThumbnail) "1" else "0", if (embedMetadata) "1" else "0", if (noPlaylist) "1" else "0",
                            videoQuality.resolutionCap()?.toString().orEmpty(),
                            outputFormat.extension, networkRetries, ytDlpPlaylistItems, maxFilesize,
                            if (writeInfoFiles) "1" else "0", clipRange, proxyUrl,
                            if (liveFromStart) "1" else "0", extractorArgs,
                            if (saveThumbnail) "1" else "0",
                            if (forceIpv4) "1" else "0",
                            if (concurrentFragments > 1) concurrentFragments.toString() else "",
                            if (noCheckCertificates) "1" else "0",
                            if (sleepIntervalSeconds > 0) sleepIntervalSeconds.toString() else "",
                            customHeaders,
                            formatSort,
                            if (verboseLogging) "1" else "0",
                            if (embedChapters) "1" else "0",
                            if (saveSubtitleFiles) "1" else "0",
                            if (restrictFilenames) "1" else "0",
                            if (trimFilenames) "1" else "0",
                            fragmentRetries,
                            socketTimeoutSeconds,
                            bufferSizeKb,
                            if (youtubeClientRotation) "1" else "0",
                            if (impersonate) "1" else "0",
                            aria2Path,
                            aria2LibDir,
                            ffmpegLibDir,
                            overrideTitle,
                            overrideArtist,
                            // The preview sheet's saved extraction of this same URL, if any —
                            // reused instead of extracting again (see yt_dlp_wrapper.download).
                            GalleryDlListing.ytDlpInfoCacheFile(applicationContext, url).absolutePath,
                        ),
                        actualCallback,
                    )

                // Always audio-only (Spotify links have no video concept at all — see
                // VideoSiteRouter's own doc comment on why this is its own dedicated engine) —
                // no quality/subtitle options apply, so this passes a much smaller argv than
                // runYtDlp's own. spotify_wrapper.py delegates the actual per-track download to
                // yt_dlp_wrapper.py's own download() internally, which is where ffmpeg/aria2c/
                // js-runtime actually get used.
                suspend fun runSpotify(): Int =
                    PythonRuntime.run(
                        applicationContext, "spotify_wrapper.py",
                        listOf(
                            // filenameFormat deliberately NOT passed through — it's gallery-dl's
                            // own "{keyword}" template syntax (see runYtDlp's own identical "",
                            // "" for the same reason), meaningless to yt_dlp_wrapper.py's own
                            // outtmpl and reproduced live as a literal, unsubstituted "{uploader|
                            // category} - {title|category} [{filename}].{extension}" filename that
                            // failed ffmpeg outright ("Invalid argument") the first time this was
                            // tried without this fix.
                            "download", url, stagingDir.absolutePath, cookiesArg,
                            "", ytDlpArchivePath, jsRuntimePath,
                            ffmpegPath, ffmpegLibDir, aria2Path, aria2LibDir,
                            if (restrictFilenames) "1" else "0",
                            if (trimFilenames) "1" else "0",
                            if (verboseLogging) "1" else "0",
                            if (saveThumbnail) "1" else "0",
                            ytDlpPlaylistItems,
                            overrideTitle,
                            overrideArtist,
                        ),
                        actualCallback,
                    )

                // Instagram posts/reels (see VideoSiteRouter.resolveEngine). Same staging dir,
                // cookies copy and output protocol as the other wrappers, so the callback above
                // handles its files exactly like gallery-dl's. The picker's itemFilter passes
                // straight through — its "num"s are the same 1-based carousel positions.
                suspend fun runInstaloader(): Int =
                    PythonRuntime.run(
                        applicationContext, "instaloader_wrapper.py",
                        listOf(
                            "download", url, stagingDir.absolutePath, cookiesArg,
                            entity?.itemFilter.orEmpty(), instaloaderArchivePath,
                            if (writeInfoFiles) "1" else "0", proxyUrl, socketTimeoutSeconds,
                            // The preview's saved post, reused instead of fetching it again.
                            GalleryDlListing.instaloaderInfoCacheFile(applicationContext, url).absolutePath,
                        ),
                        actualCallback,
                    )

                // The routing every link got before Instaloader existed — also the fallback when
                // Instaloader saves nothing for an Instagram post.
                suspend fun runClassic(classicEngine: DownloadEngine) {
                    when (classicEngine) {
                        DownloadEngine.YT_DLP -> runYtDlp()
                        DownloadEngine.SPOTIFY -> runSpotify()
                        DownloadEngine.INSTALOADER -> runInstaloader()
                        DownloadEngine.GALLERY_DL -> {
                            // classify() only routed here because this host isn't in the hardcoded
                            // videoOnlyHosts/spotifyHosts fast paths — not because gallery-dl is
                            // actually known to support it. A live, no-network probe against the
                            // real bundled packages (see EngineProbe's own doc comment) tells us
                            // whether either engine has a genuine extractor for this URL, so an
                            // engine-exclusive link never wastes an attempt on the wrong one.
                            val probe = EngineProbe.probeBoth(applicationContext, url)

                            // `== false`/`== true`, not `!probe.x`/plain truthiness — probe.kt's own
                            // Result fields are Boolean? (null means the probe itself failed to run,
                            // genuinely unknown), and only a *confirmed* negative on gallery-dl plus a
                            // *confirmed* positive on yt-dlp justifies skipping gallery-dl entirely.
                            // Anything involving an unknown (a probe crash) must fall through to the
                            // normal gallery-dl attempt below instead — a probe subprocess crash is
                            // not evidence gallery-dl can't handle this URL.
                            if (probe.galleryDlHasExtractor == false && probe.ytDlpHasExtractor == true) {
                                // gallery-dl has nothing for this URL at all, yt-dlp does — skip the
                                // doomed gallery-dl attempt entirely.
                                runYtDlp()
                            } else {
                                // Always excluded, not just when hasVideoItem's own listing pass
                                // happened to succeed: gallery-dl has its own *unconfigured* internal
                                // yt-dlp delegation for video posts on sites like Instagram (no
                                // ffmpeg_location, no js_runtimes), which silently produces broken
                                // split video/audio fragments instead of the one properly-merged file
                                // our own yt_dlp_wrapper (below) produces. Relying on hasVideoItem
                                // here would mean any listing failure — Instagram rate-limits this
                                // extra lookup fairly readily — falls straight through to that broken
                                // path with no exclusion at all.
                                runGalleryDl(excludeVideo = true)
                                if (!isStopped) {
                                    // hasVideoItem reflects gallery-dl's own listing, which uses the
                                    // same extractor code path as the real download pass — a real,
                                    // reproduced case: Instagram's API silently omitted media info for
                                    // exactly the video child of an otherwise-fine photo carousel, so
                                    // gallery-dl's own listing genuinely never saw a video to report,
                                    // and this would otherwise skip yt-dlp entirely with no trace of a
                                    // video ever having existed. See
                                    // VideoSiteRouter.alwaysSupplementsVideo's own doc comment for
                                    // which hosts get this always-on attempt and why.
                                    val alwaysTryVideo = VideoSiteRouter.alwaysSupplementsVideo(url)
                                    // Neither branch below gates on the probe's own
                                    // galleryDlHasExtractor/ytDlpHasExtractor result any more — a
                                    // no-network regex probe against the raw, pre-redirect/share URL
                                    // saying yt-dlp has "no extractor" is not trustworthy enough to
                                    // skip a fallback/supplement pass that's already been earned by a
                                    // stronger, real signal (gallery-dl having actually run and found
                                    // something, or its own listing confirming/suspecting a video).
                                    // Originally this WAS gated by a `skipYtDlpFallback` flag — removed
                                    // from the savedCount==0 branch first, after it was reproduced live
                                    // wrongly suppressing the fallback for a Reddit share link (.../s/
                                    // <code>) whose only content was an external redgifs video:
                                    // gallery-dl's own redirect-following extractor found it fine, but
                                    // probe.ytDlpHasExtractor came back false for the *raw, unresolved*
                                    // share link (yt-dlp's dedicated reddit extractor's own regex
                                    // requires "/comments/<id>", which a bare "/s/<code>" redirect
                                    // never has) — even though yt-dlp's generic extractor (deliberately
                                    // excluded from probe()'s "real extractor" check, same "opt-in"
                                    // reasoning as gallery-dl's — see EngineProbe's own doc comment)
                                    // DOES follow that exact redirect and finds the same video fine on
                                    // its own (confirmed live, --simulate). The hasVideoItem/
                                    // alwaysTryVideo branch below was left gated by the same flag at
                                    // first — same underlying probe, same class of false negative, so
                                    // the same fix applies here too.
                                    if (savedCount.get() == 0) {
                                        // gallery-dl having already run at all by this point is itself
                                        // the signal that this URL resolves to *something* real —
                                        // worth yt-dlp's cheap attempt regardless of what the probe
                                        // alone could ever know.
                                        runYtDlp()
                                    } else if (hasVideoItem || alwaysTryVideo) {
                                        // gallery-dl already grabbed the pictures (video excluded
                                        // from its own pass above); yt-dlp now handles this same
                                        // post's video, since it has real format/quality selection
                                        // gallery-dl doesn't.
                                        runYtDlp()
                                    }
                                }
                            }
                        }
                    }
                }

                // Transfer monitor. Only yt-dlp reports progress while a file downloads; gallery-dl
                // and Instaloader only announce a file once it's complete, so their speed used to
                // change only between files, and sat on the last number through a stall (e.g.
                // "167 KB/s" for 2 minutes). All three write in-progress files into the staging
                // folder, so the bytes arriving there (plus the files already saved out of it) are
                // the real transfer — measured once a second, smoothed, and zeroed when nothing
                // has arrived for STALL_AFTER_MS. Once an engine reports progress itself (yt-dlp),
                // only the stall check applies: measuring its folder would count its ffmpeg merge
                // output as download speed.
                kotlinx.coroutines.coroutineScope {
                    val stallWatchdog = launch {
                        var previousTotal = -1L
                        var previousAt = 0L
                        var smoothed = 0f
                        var zeroed = false
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            val now = System.currentTimeMillis()
                            val staged = runCatching {
                                stagingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            }.getOrDefault(0L)
                            val total = bytesSoFar.get() + staged
                            if (previousTotal >= 0 && total > previousTotal) lastDataAt.set(now)
                            val engineReporting = lastProgressLineAt.get() > 0L
                            if (previousTotal >= 0 && !engineReporting) {
                                val seconds = ((now - previousAt) / 1000f).coerceAtLeast(0.2f)
                                // A saved file leaves staging a moment before bytesSoFar counts it,
                                // which reads as a dip; never negative.
                                val instant = ((total - previousTotal).coerceAtLeast(0L) / (1024f * 1024f)) / seconds
                                smoothed = if (smoothed == 0f) instant else smoothed * 0.6f + instant * 0.4f
                                if (now - lastDataAt.get() > STALL_AFTER_MS) {
                                    smoothed = 0f
                                    measuredSpeedMbs.set(0f)
                                    if (!zeroed) dao.resetSpeed(downloadId)
                                    zeroed = true
                                } else if (smoothed > 0f) {
                                    zeroed = false
                                    measuredSpeedMbs.set(smoothed)
                                    dao.updateSpeed(downloadId, smoothed)
                                    DownloadNotifications.updateProgress(
                                        applicationContext, downloadId, displayTitle, savedCount.get(), computeProgressPercent(),
                                        speedMbs = smoothed, currentBytes = total, expectedBytes = expectedBytesRef.get(),
                                    )
                                }
                            } else if (engineReporting) {
                                val stalled = now - lastDataAt.get() > STALL_AFTER_MS
                                if (stalled && !zeroed) dao.resetSpeed(downloadId)
                                zeroed = stalled
                            }
                            previousTotal = total
                            previousAt = now
                        }
                    }
                    try {
                        if (engine == DownloadEngine.INSTALOADER) {
                            runInstaloader()
                            // Nothing saved (private post, rate limit, Instagram changed something, ...):
                            // hand the same link to the classic path. Its own error only shows if that
                            // fails too — lastErrorLine is first-wins, so Instaloader's reason is kept.
                            if (!isStopped && savedCount.get() == 0) runClassic(VideoSiteRouter.classify(url))
                        } else {
                            runClassic(engine)
                        }
                    } finally {
                        stallWatchdog.cancel()
                    }
                }

                if (writeInfoFiles || saveThumbnail || saveSubtitleFiles) {
                    // gallery-dl's --write-metadata, yt-dlp's --write-description/--write-info-json,
                    // and yt-dlp's own --write-thumbnail (this sheet's "Save thumbnail" chip, see
                    // saveThumbnail above) all write these silently to disk — neither engine ever
                    // prints their path the way it prints the actual media file's, so the normal
                    // line-by-line callback above never sees them, never moves them out of
                    // stagingDir, and they'd otherwise just be wiped out by the
                    // deleteRecursively() below along with the rest of the now-empty staging dir.
                    // (Reproduced live: "Save thumbnail" appeared to work — no error, download
                    // finished normally — but nothing ever showed up in the gallery, because this
                    // sweep used to run only for writeInfoFiles and only matched .json/.description.)
                    stagingDir.walkTopDown()
                        .filter {
                            it.isFile && (
                                it.extension == "json" || it.name.endsWith(".description") ||
                                    it.extension in setOf("jpg", "jpeg", "png", "webp") ||
                                    // Save subtitle files (Settings > Processing) — the sidecar
                                    // .srt/.vtt yt_dlp_wrapper.py's own writesubtitles produces
                                    // when requested independently of embedding.
                                    it.extension in setOf("srt", "vtt")
                                )
                        }
                        .forEach { sidecarFile ->
                            runCatching { MediaStoreHelper.saveMediaToGallery(applicationContext, sidecarFile) }
                        }
                }

                stagingDir.deleteRecursively()

                // Superseded, not just stopped: some other reschedule already moved this row onto a
                // *new* WorkManager job before this one's own cancellation was actually observed —
                // suspendForSchedule() (called from this same actualCallback above when the
                // schedule window closes mid-download) cancels this worker's own job and writes a
                // fresh workRequestId + SCHEDULED status, but that cancellation is only "noticed at
                // its own next suspension point" (see its call site's doc comment above), not
                // synchronous. If gallery-dl/yt-dlp finishes its last file in that exact window,
                // execution reaches here with isStopped still false, and without this check the
                // code below would overwrite the newer job's SCHEDULED status with FINISHED/ERRORED
                // — while that newer job is still separately armed to run this same download again
                // later, sometimes flipping an already-finished download back to ERRORED once it
                // finds nothing new via the download-archive. Comparing this worker's own [id]
                // against the row's current workRequestId is a general check, not specific to the
                // schedule case — it's true for a plain pause/cancel racing the same way too.
                if (isStopped || dao.getById(downloadId)?.workRequestId != id.toString()) {
                    // Defensive fallback only now — a genuine Pause/Cancel mid-download normally
                    // throws CancellationException straight out of runGalleryDl()/runYtDlp() these
                    // days (PythonRuntime kills the subprocess the moment this coroutine's Job is
                    // cancelled, which is what isStopped flipping true actually means; see its own
                    // doc comment), caught by the outer catch block below instead of reaching here.
                    // Leave whatever status pauseDownload()/cancelDownload()/suspendForSchedule()
                    // already set instead of overwriting it either way.
                    // Result.success() — see the isStopped branch above for why.
                    DownloadNotifications.cancel(applicationContext, downloadId)
                    return@withContext Result.success()
                }

                if (savedCount.get() == 0) {
                    // Neither engine found anything to save. Previously this silently reported
                    // FINISHED with 0 items regardless — a real error now, since there's a genuine
                    // reason to show the user (unsupported link, blocked request, nothing there).
                    // Sanitized the same way GalleryDlListing's own error messages are — reproduced
                    // live a second time here: a real download attempt (not just the listing/
                    // preview step) can also surface gallery-dl's raw HTML/CSS-blob exception text
                    // verbatim as its "error", which used to show up unfiltered in this terminal
                    // failure notification and toast.
                    val errorMsg = GalleryDlListing.sanitizeErrorMessage(lastErrorLine.get() ?: "No downloadable content found at this link")
                        ?: "No downloadable content found at this link"
                    dao.updateError(downloadId, DownloadStatus.ERRORED, errorMsg)
                    DownloadNotifications.notifyFailed(applicationContext, downloadId, displayTitle, errorMsg)
                    DownloadDispatcher.forgetLock(downloadId)
                    // Result.success(), not failure() — see the isStopped branch above for why:
                    // this download's own ERRORED status is already recorded in our DB; returning
                    // failure() here would additionally auto-kill every other download still
                    // queued behind this one in the same WorkManager concurrency slot.
                    return@withContext Result.success()
                }

                val finalThumbnail = dao.getById(downloadId)?.thumbnailPath
                DownloadNotifications.notifyFinished(applicationContext, downloadId, displayTitle, savedCount.get(), finalThumbnail)
                if (entity?.incognito == true) {
                    // Imported from YTDLnis's own Incognito setting (see
                    // GalleryDlPreferences.isIncognitoDefault's doc comment): the real file is
                    // already saved to the gallery/Downloads folder by this point (savedCount > 0
                    // is what got here at all) — only this row, the record of the download ever
                    // having happened in this app's own Library/queue, is removed.
                    dao.delete(downloadId)
                } else {
                    dao.updateStatus(downloadId, DownloadStatus.FINISHED)
                }
                DownloadDispatcher.forgetLock(downloadId)
                Result.success()
            } catch (e: CancellationException) {
                // A paused/cancelled download: leave whatever status pauseDownload()/cancelDownload()
                // already set instead of overwriting it with an error.
                DownloadNotifications.cancel(applicationContext, downloadId)
                throw e
            } catch (e: Exception) {
                // Result.success() in both branches below, not failure() — same reasoning as the
                // isStopped/savedCount==0 branches above: this download's outcome is already
                // recorded in our own DB (or intentionally left alone, for the isStopped case), and
                // failure() would cascade to kill every other download sharing this WorkManager
                // concurrency slot without ever running them.
                if (isStopped) {
                    DownloadNotifications.cancel(applicationContext, downloadId)
                    Result.success()
                } else {
                    val sanitized = e.localizedMessage?.let { GalleryDlListing.sanitizeErrorMessage(it) }
                    dao.updateError(downloadId, DownloadStatus.ERRORED, sanitized)
                    DownloadNotifications.notifyFailed(applicationContext, downloadId, displayTitle, sanitized)
                    // Imported from YTDLnis's own "Cleanup leftover downloads" setting — a genuine
                    // failure here (unlike the savedCount==0 branch above, which already ran the
                    // unconditional deleteRecursively() before this catch could ever see it) leaves
                    // whatever partial fragments/staging files exist untouched by default, so this
                    // is the one real place leftover files actually accumulate. Deleting them is
                    // still the default (matches every prior release's behavior); off lets a user
                    // inspect or manually resume a failed download's partial files instead.
                    if (GalleryDlPreferences.isDeleteLeftoverOnFailure(applicationContext)) {
                        // stagingDir itself is out of scope here (declared inside the try block
                        // above) — reconstructed the same way DownloadDispatcher's own
                        // deleteStagingDir() does, from the one thing both always agree on: this
                        // download's id.
                        runCatching {
                            File(applicationContext.cacheDir, "gallery-dl-staging/$downloadId").deleteRecursively()
                        }
                    }
                    DownloadDispatcher.forgetLock(downloadId)
                    Result.success()
                }
            }
        }
        } finally {
            DownloadConcurrencyGate.release()
        }
        } finally {
            DownloadNotifications.markForegroundStopped(applicationContext)
            // Matches the temp file created above for the cookies-normalization fix — cleaned up
            // unconditionally here (success, failure, or cancellation) rather than only on the
            // success path, same reasoning as markForegroundStopped needing its own finally.
            File(applicationContext.cacheDir, "cookies-normalized-$downloadId.txt").delete()
        }
    }
}

/** Both gallery_dl_wrapper.py's and yt_dlp_wrapper.py's default filename formats are shaped
 * "poster - caption [unique-id].ext" specifically so this can recover a real display title from
 * whatever they actually saved to disk, without needing a separate metadata channel from either
 * engine — stripping the bracketed id/hash and the extension leaves exactly the poster/caption
 * text. Returns null for a name that doesn't look like that shape (e.g. the user configured a
 * custom filename format), leaving the placeholder title in place rather than showing something
 * worse. */
private fun derivePosterCaptionTitle(filename: String): String? {
    val withoutExtension = filename.substringBeforeLast('.', "").ifBlank { return null }
    val stripped = withoutExtension.replace(Regex("\\s*\\[[^\\[\\]]*]$"), "").trim()
    return stripped.ifBlank { null }
}

// gallery-dl/yt-dlp's own extractor-level log lines are shaped "[extractor_name][error]
// <message>" (both bracketed, e.g. "[instagram][error] HTTP redirect to login page") — a
// different shape from yt-dlp's top-level "[error] <message>", confirmed live via a real
// Instagram failure whose actual root cause (a login redirect) was silently missed by the plain
// "[error] " prefix check, letting a vaguer fallback error overwrite it instead.
private val GALLERY_DL_ERROR_LINE = Regex("^\\[[\\w.]+\\]\\[error\\] ")

// Same "[<extractor>][<level>] " shape as GALLERY_DL_ERROR_LINE above, but at gallery-dl's own
// [info] level — its logger genuinely doesn't treat "found nothing" as an error, so this never
// reaches GALLERY_DL_ERROR_LINE at all. See the actualCallback branch that uses this for the full
// story (a real access failure reported this way, then masked by a less useful yt-dlp fallback
// error).
private val GALLERY_DL_NO_RESULTS_LINE = Regex("^\\[[\\w.]+\\]\\[info\\] No results for ")

// Pulls the comma-separated item numbers out of SharePickerScreen's gallery-dl-syntax
// --filter string ("num in {1,3,4}") — the same numbers double as yt-dlp's own native
// playlist_items syntax once stripped of the surrounding "num in {...}" (see runYtDlp's own
// ytDlpPlaylistItems). No match (itemFilter null, or a "download everything" case where the
// picker never set one at all) just yields an empty string, same as any other unset arg here.
private val ITEM_FILTER_NUMS_RE = Regex("""\{([\d,]+)\}""")


/** No data for this long counts as stalled; see the stall watchdog in doWork(). */
private const val STALL_AFTER_MS = 6_000L
