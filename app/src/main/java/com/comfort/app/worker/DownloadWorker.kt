package com.comfort.app.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.DownloadedFileRecord
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.FfmpegRuntime
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.MediaStoreHelper
import com.comfort.app.util.PythonRuntime
import com.comfort.app.util.QuickJsRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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

    suspend fun acquire(context: Context) {
        while (true) {
            val limit = GalleryDlPreferences.getConcurrentDownloads(context).coerceAtLeast(1)
            // Not perfectly atomic against another waiter passing this same check at the same
            // moment — worst case briefly overshoots the limit by however many racing waiters all
            // read a stale "still under limit" value together, and self-corrects on the very next
            // recheck once their increments are visible. An acceptable trade for not needing a
            // real lock around a value that has to be re-read fresh from preferences every
            // iteration anyway (a plain Semaphore can't be resized once constructed).
            if (active.get() < limit) {
                active.incrementAndGet()
                return
            }
            withTimeoutOrNull(POLL_FALLBACK_MS) { slotFreed.first() }
        }
    }

    fun release() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
        slotFreed.tryEmit(Unit)
    }
}

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

        // Blocks here (not a hard failure) until a concurrency slot is actually free — see
        // DownloadConcurrencyGate's own doc comment for why this exists alongside the round-robin
        // unique-work-chain system rather than trusting that alone. release() is in this function's
        // own outer finally below — the try starts right here, not any later, specifically so that
        // finally still runs (and releases the slot) even if something between here and the
        // existing try block below throws (setForegroundSafely, updateProgress, ...).
        DownloadConcurrencyGate.acquire(applicationContext)
        try {

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
        setForegroundSafely(
            ForegroundInfo(
                DownloadNotifications.FOREGROUND_SERVICE_NOTIFICATION_ID,
                DownloadNotifications.foregroundServiceNotification(applicationContext),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        )
        // The real, per-download notification the user actually reads — posted as a plain notify()
        // to its own id, entirely separate from the foreground-service one above, so it's always
        // freely updatable/cancellable regardless of that service's lifecycle.
        DownloadNotifications.updateProgress(applicationContext, downloadId, displayTitle, entity?.downloadedItems ?: 0, initialPercent)

        // Reproduced live: WorkManager's own teardown of the shared foreground notification once
        // this (or every concurrently running) worker finishes isn't reliable on this device/OS
        // build — dumpsys still showed it stuck with ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE
        // minutes after the worker returned SUCCESS *and* dumpsys activity services confirmed no
        // service was even running any more. markForegroundStarted/Stopped keep our own count of
        // how many downloads are actually still using it, and explicitly cancel it once that count
        // hits zero — not tied to (or trusting) WorkManager's own foreground-service bookkeeping.
        DownloadNotifications.markForegroundStarted()
        try {
            return withContext(Dispatchers.IO) {
                try {
                dao.updateStatus(downloadId, DownloadStatus.RUNNING)
                val startTime = System.currentTimeMillis()
                dao.setStartTime(downloadId, startTime)

                val url = resolveRedditShareLink(url)

                // Sites gallery-dl either can't parse at all or (TikTok specifically) handles
                // more weakly than yt-dlp skip the gallery-dl attempt entirely; everything else
                // goes through gallery-dl first since that's the engine with real gallery/image
                // support, with yt-dlp used afterward as a fallback or a same-post video
                // supplement (see below).
                val engine = VideoSiteRouter.classify(url)

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
                if (engine == DownloadEngine.GALLERY_DL && (entity?.totalItems ?: 0) <= 0 && entity?.itemFilter == null) {
                    val listed = GalleryDlListing.listItems(applicationContext, url).items
                    if (listed.isNotEmpty() && listed.size < GalleryDlListing.MAX_ITEMS) {
                        dao.setTotalItems(downloadId, listed.size)
                        totalItemsRef.set(listed.size)
                    }
                    hasVideoItem = listed.any { item -> item.filename?.let(VideoSiteRouter::isVideoFilename) == true }
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
                        writeText(cookiesPath.readText().replace("\r\n", "\n"))
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

                // suspend, not a plain lambda — PythonRuntime's onLine has no JNI-reentrancy
                // constraint (unlike Chaquopy's old synchronous callback), so every DB write below
                // is a direct, awaited suspend call instead of a fire-and-launch job collected into
                // a separate list and joined afterward.
                val actualCallback: suspend (String) -> Unit = actualCallback@{ line ->
                    android.util.Log.d("DownloadEngine", "Python output: $line")
                    when {
                        // yt-dlp-only signals (see yt_dlp_wrapper.py's progress_hook) — gallery-dl
                        // never emits these, so gallery-dl-routed downloads just never hit this
                        // branch and keep using the item-count progress path below untouched.
                        line.startsWith("[size] ") -> {
                            val bytes = line.removePrefix("[size] ").trim().toLongOrNull()
                            if (bytes != null) {
                                dao.setExpectedBytes(downloadId, bytes)
                                expectedBytesRef.set(bytes)
                            }
                        }
                        line.startsWith("[progress] ") -> {
                            val rest = line.removePrefix("[progress] ")
                            val downloaded = Regex("downloaded=(\\d+)").find(rest)?.groupValues?.get(1)?.toLongOrNull()
                            val speedBps = Regex("speed=([\\d.]+)").find(rest)?.groupValues?.get(1)?.toFloatOrNull()
                            if (downloaded != null) {
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
                            lastErrorLine.getAndUpdate { current ->
                                if (current == null || GalleryDlListing.sanitizeErrorMessage(current) != current) candidate else current
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
                                    val savedUri = MediaStoreHelper.saveMediaToGallery(applicationContext, candidate)
                                    if (savedUri != null) {
                                        candidate.delete()
                                        val count = savedCount.incrementAndGet()
                                        val totalBytes = bytesSoFar.addAndGet(fileSize)
                                        // This file's bytes now live in bytesSoFar (via addAndGet
                                        // above) instead of being "in flight" — without resetting
                                        // this, the next file's own progress would double-count
                                        // everything the previous file already contributed.
                                        currentFileBytesRef.set(0)
                                        val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.5f)
                                        val speedMbs = (totalBytes / (1024f * 1024f)) / elapsedSeconds
                                        dao.updateLiveProgress(downloadId, count, speedMbs)
                                        if (singleItemDownload) {
                                            dao.setThumbnail(downloadId, savedUri.toString())
                                        } else {
                                            dao.setThumbnailIfAbsent(downloadId, savedUri.toString())
                                        }
                                        dao.addBytes(downloadId, fileSize)
                                        if (hasPlaceholderTitle) {
                                            derivePosterCaptionTitle(candidate.name)?.let { dao.updateTitle(downloadId, it) }
                                        }
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
                val filenameFormat = GalleryDlPreferences.getFilenameFormat(applicationContext)
                val extraArgs = GalleryDlPreferences.getExtraArgs(applicationContext)
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
                val limitRate = GalleryDlPreferences.getSpeedLimit(applicationContext)
                val networkRetries = GalleryDlPreferences.getNetworkRetries(applicationContext).toString()

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
                            networkRetries,
                        ),
                        actualCallback,
                    )

                // Bundled as jniLibs/<abi>/libqjs.so and libffmpeg.so respectively — see
                // QuickJsRuntime's and FfmpegRuntime's doc comments for why sites like YouTube
                // need the former just to extract real download URLs, and the latter to merge
                // the separate video/audio streams those URLs point to into one playable file.
                val jsRuntimePath = QuickJsRuntime.getExecutablePath(applicationContext).orEmpty()
                val ffmpegPath = FfmpegRuntime.getExecutablePath(applicationContext).orEmpty()

                // A per-download override the share-sheet picker set (only offered when its
                // listing found a video item) takes priority over the global Settings default —
                // falls back to it when null, same as before this override existed.
                val videoQuality = entity?.videoQuality?.let { stored -> runCatching { VideoQuality.valueOf(stored) }.getOrNull() }
                    ?: GalleryDlPreferences.getVideoQuality(applicationContext)
                val audioOnly = videoQuality == VideoQuality.AUDIO_ONLY
                val downloadSubtitles = GalleryDlPreferences.isDownloadSubtitles(applicationContext)
                val subtitleLangs = GalleryDlPreferences.getSubtitleLanguages(applicationContext)
                val embedThumbnail = GalleryDlPreferences.isEmbedThumbnail(applicationContext)
                val embedMetadata = GalleryDlPreferences.isEmbedMetadata(applicationContext)
                val noPlaylist = GalleryDlPreferences.isNoPlaylist(applicationContext)
                val outputFormat = GalleryDlPreferences.getOutputFormat(applicationContext)

                suspend fun runYtDlp(): Int =
                    // Neither gallery-dl's filename-format template syntax nor its extra-args
                    // string mean anything to yt-dlp, so those two aren't passed through — cookies
                    // and the speed limit use compatible formats for both engines and are shared.
                    PythonRuntime.run(
                        applicationContext, "yt_dlp_wrapper.py",
                        listOf(
                            "download", url, stagingDir.absolutePath, cookiesArg,
                            "", "", ytDlpArchivePath, limitRate, "",
                            jsRuntimePath, ffmpegPath,
                            if (audioOnly) "1" else "0", if (downloadSubtitles) "1" else "0", subtitleLangs,
                            if (embedThumbnail) "1" else "0", if (embedMetadata) "1" else "0", if (noPlaylist) "1" else "0",
                            videoQuality.resolutionCap()?.toString().orEmpty(),
                            outputFormat.extension, networkRetries,
                        ),
                        actualCallback,
                    )

                when (engine) {
                    DownloadEngine.YT_DLP -> runYtDlp()
                    DownloadEngine.GALLERY_DL -> {
                        // Always excluded, not just when hasVideoItem's own listing pass happened
                        // to succeed: gallery-dl has its own *unconfigured* internal yt-dlp
                        // delegation for video posts on sites like Instagram (no ffmpeg_location,
                        // no js_runtimes), which silently produces broken split video/audio
                        // fragments instead of the one properly-merged file our own yt_dlp_wrapper
                        // (below) produces. Relying on hasVideoItem here would mean any listing
                        // failure — Instagram rate-limits this extra lookup fairly readily — falls
                        // straight through to that broken path with no exclusion at all.
                        runGalleryDl(excludeVideo = true)
                        if (!isStopped) {
                            // hasVideoItem reflects gallery-dl's own listing, which uses the same
                            // extractor code path as the real download pass — a real, reproduced
                            // case: Instagram's API silently omitted media info for exactly the
                            // video child of an otherwise-fine photo carousel, so gallery-dl's own
                            // listing genuinely never saw a video to report, and this would
                            // otherwise skip yt-dlp entirely with no trace of a video ever having
                            // existed. Instagram (unlike most gallery-dl sites — image boards, art
                            // platforms — which never carry embedded video at all) routinely mixes
                            // video into posts, so it's worth an always-on supplementary attempt
                            // there specifically: yt-dlp fails fast and silently (no user-facing
                            // error; see actualCallback's default branch) on a genuinely video-less
                            // post, so the cost is a few extra seconds, not a broken download.
                            val alwaysTryVideo = VideoSiteRouter.isInstagram(url)
                            if (savedCount.get() == 0) {
                                // gallery-dl found nothing at all — unsupported URL, blocked
                                // request, or a genuinely empty gallery. Try yt-dlp on the same
                                // link before giving up on the download entirely.
                                runYtDlp()
                            } else if (hasVideoItem || alwaysTryVideo) {
                                // gallery-dl already grabbed the pictures (video excluded from its
                                // own pass above); yt-dlp now handles this same post's video, since
                                // it has real format/quality selection gallery-dl doesn't.
                                runYtDlp()
                            }
                        }
                    }
                }

                stagingDir.deleteRecursively()

                if (isStopped) {
                    // Defensive fallback only now — a genuine Pause/Cancel mid-download normally
                    // throws CancellationException straight out of runGalleryDl()/runYtDlp() these
                    // days (PythonRuntime kills the subprocess the moment this coroutine's Job is
                    // cancelled, which is what isStopped flipping true actually means; see its own
                    // doc comment), caught by the outer catch block below instead of reaching here.
                    // Leave whatever status pauseDownload()/cancelDownload() already set instead of
                    // overwriting it either way.
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
                    // Result.success(), not failure() — see the isStopped branch above for why:
                    // this download's own ERRORED status is already recorded in our DB; returning
                    // failure() here would additionally auto-kill every other download still
                    // queued behind this one in the same WorkManager concurrency slot.
                    return@withContext Result.success()
                }

                dao.updateStatus(downloadId, DownloadStatus.FINISHED)
                val finalThumbnail = dao.getById(downloadId)?.thumbnailPath
                DownloadNotifications.notifyFinished(applicationContext, downloadId, displayTitle, savedCount.get(), finalThumbnail)
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
                    Result.success()
                }
            }
        }
        } finally {
            DownloadNotifications.markForegroundStopped(applicationContext)
            // Matches the temp file created above for the cookies-normalization fix — cleaned up
            // unconditionally here (success, failure, or cancellation) rather than only on the
            // success path, same reasoning as markForegroundStopped needing its own finally.
            File(applicationContext.cacheDir, "cookies-normalized-$downloadId.txt").delete()
        }
        } finally {
            DownloadConcurrencyGate.release()
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

private val REDDIT_SHARE_LINK = Regex("^https?://(www\\.)?reddit\\.com/r/[^/]+/s/[A-Za-z0-9]+/?")

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

/** Reddit's mobile Share button produces a `reddit.com/r/<sub>/s/<code>` short link that 302s to
 * the real `/comments/...` post URL — the bundled yt-dlp's Reddit extractor only recognizes
 * `/comments/...` URLs (confirmed by reading its `_VALID_URL` regex), so a raw share link falls
 * through to yt-dlp's generic extractor, which gets an HTTP 403 from Reddit trying to resolve the
 * redirect itself (reproduced live: "[generic] ...: Unable to download webpage: HTTP Error 403").
 * Following the redirect here first, with a real browser User-Agent, avoids that. A no-op for any
 * other URL, or if resolution fails for any reason — the original URL is always a safe fallback,
 * so a network hiccup here degrades back to today's (broken-for-this-one-case) behavior rather
 * than failing the whole download. */
private fun resolveRedditShareLink(url: String): String {
    if (!REDDIT_SHARE_LINK.containsMatchIn(url)) return url
    var current = url
    repeat(5) {
        val connection = runCatching {
            (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                )
            }
        }.getOrNull() ?: return url
        val code = runCatching { connection.responseCode }.getOrNull()
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (code != null && code in 300..399 && location != null) {
            current = runCatching { java.net.URL(java.net.URL(current), location).toString() }.getOrDefault(current)
            if ("/comments/" in current) return current
        } else {
            return current
        }
    }
    return current
}
