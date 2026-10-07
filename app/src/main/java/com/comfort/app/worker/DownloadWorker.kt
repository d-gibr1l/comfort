package com.comfort.app.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.DownloadNotes
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.DownloadedFileRecord
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.EngineProbe
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.MediaStoreHelper
import com.comfort.app.util.PythonRuntime
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
    // A plain class (identity equality), not a data class: every acquire() gets its own Place, and
    // cleanup removes only *that* instance. A download re-enqueued while its previous worker is
    // still winding down at the gate (Up next, schedule change, ...) has two acquire() calls for
    // the same id briefly overlapping; removing by id alone let the old one's cleanup delete the
    // new one's entry, after which the new worker could never be "first in line" again.
    private class Place(val queueOrder: Int, val dateAdded: Long)
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, Place>()
    private val lineOrder = compareBy<Map.Entry<String, Place>>({ it.value.queueOrder }, { it.value.dateAdded }, { it.key })

    suspend fun acquire(context: Context, downloadId: String, queueOrder: Int, dateAdded: Long) {
        val place = Place(queueOrder, dateAdded)
        waiting[downloadId] = place
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
                        waiting.remove(downloadId, place)
                        // The next in line may also fit (concurrency limit above 1).
                        slotFreed.tryEmit(Unit)
                        return
                    }
                }

                withTimeoutOrNull(POLL_FALLBACK_MS) { slotFreed.first() }
            }
        } finally {
            // Cancelled while waiting (paused/cancelled): leave the line so it can't block others.
            waiting.remove(downloadId, place)
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

    /** Adds lyrics to a downloaded song (Settings › Processing › Lyrics; lyrics.py looks it up on
     * LRCLIB by its tags and embeds them). Returns the .lrc file it wrote beside [song], if any.
     * Best-effort and capped: a song with no lyrics found, or no network, saves as it is. */
    private suspend fun addLyrics(song: File, title: String, artist: String, downloadId: String): File? {
        val mode = GalleryDlPreferences.getLyricsMode(applicationContext)
        if (mode == GalleryDlPreferences.LYRICS_OFF) return null
        val writeLrc = GalleryDlPreferences.isLyricsLrcFile(applicationContext)
        // What the app already knows about the song, for a file whose own tags can't be read
        // (a .webm): the engine's artist and track, or its "Artist - Title" title.
        val entity = runCatching { AppDatabase.getDatabase(applicationContext).downloadDao().getById(downloadId) }.getOrNull()
        val knownArtist = entity?.artist.orEmpty()
        val knownTitle = entity?.track?.takeIf { it.isNotBlank() }
            ?: entity?.title.orEmpty().removePrefix("$knownArtist - ").takeIf { knownArtist.isNotBlank() }.orEmpty()
        val seconds = runCatching {
            android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(song.absolutePath)
                    extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)
                } finally { release() }
            }
        }.getOrNull()
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(LYRICS_CAP_MS) {
                PythonRuntime.run(
                    applicationContext, "lyrics.py",
                    listOf(
                        song.absolutePath, mode, if (writeLrc) "1" else "0", title, artist,
                        knownTitle, knownArtist, seconds?.toString().orEmpty(),
                    ),
                ) { }
            }
        }
        return File(song.parentFile, song.nameWithoutExtension + ".lrc").takeIf { writeLrc && it.isFile }
    }

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
        // passed to the Python wrappers already uses. Computed here (not in EngineCommands with
        // the other wrapper arguments) since the final-file handling block, well above that point in
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
            // Inside the try (not before it): acquire() has already taken a slot, so anything that
            // throws between there and the finally's release() — this notification call included —
            // would otherwise leak it permanently and starve every other queued download.
            try {
            DownloadNotifications.updateProgress(applicationContext, downloadId, displayTitle, entity?.downloadedItems ?: 0, initialPercent)
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
                // Every listed item is a video — see EnginePlan.GalleryDlFirst. Only trusted from a
                // complete listing (under MAX_ITEMS), so a truncated one can't hide an image.
                var onlyVideos = false
                // The listing is only a helper (item count, video check), so it gets LISTING_CAP_MS
                // before the download goes ahead without it. On Reddit gallery-dl's shared client ID
                // can be rate-limited, and gallery-dl then sleeps silently until the limit resets —
                // 6 minutes seen live (2026-10-06), with the queue card showing nothing. Cancelling
                // kills the Python job (PythonRuntime.run).
                var listingTimedOut = false
                // Engines switched on, and their order (Settings › Updates and engines).
                val engineChoice = EngineChoice.load(applicationContext)
                // Pictures alongside videos — so a yt-dlp-first order still hands them to gallery-dl.
                var hasImageItem = false
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
                // The Configure sheet's engine pick, if any: that engine alone runs (see below).
                val engineOverride = entity?.engineOverride?.let { name -> DownloadEngine.entries.firstOrNull { it.name == name } }
                if (engine == DownloadEngine.GALLERY_DL && entity?.itemFilter == null && engineChoice.isOn(DownloadEngine.GALLERY_DL) &&
                    (engineOverride == null || engineOverride == DownloadEngine.GALLERY_DL)
                ) {
                    val listing = withTimeoutOrNull(LISTING_CAP_MS) { GalleryDlListing.listItems(applicationContext, url) }
                    listingTimedOut = listing == null
                    val listed = listing?.items.orEmpty()
                    if ((entity?.totalItems ?: 0) <= 0 && listed.isNotEmpty() && listed.size < GalleryDlListing.MAX_ITEMS) {
                        dao.setTotalItems(downloadId, listed.size)
                        totalItemsRef.set(listed.size)
                    }
                    hasVideoItem = listed.any { item -> item.filename?.let(VideoSiteRouter::isVideoFilename) == true }
                    onlyVideos = listed.isNotEmpty() && listed.size < GalleryDlListing.MAX_ITEMS &&
                        listed.all { item -> item.filename?.let(VideoSiteRouter::isVideoFilename) == true }
                    hasImageItem = listed.any { item -> item.filename?.let(VideoSiteRouter::isVideoFilename) != true }
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
                // Each engine's own error, for the errored card's info sheet (lastErrorLine above is
                // the one-line summary). Keyed by engine name in the order the engines ran; an
                // engine that ran without an error is listed too (see errorDetailsJson below).
                val engineErrors = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
                val attemptedEngines = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
                val currentEngine = java.util.concurrent.atomic.AtomicReference<String?>(null)
                fun recordEngineError(message: String) {
                    val engine = currentEngine.get() ?: return
                    // First error per engine wins, unless it's the unusable kind (see
                    // sanitizeErrorMessage) — same rule as lastErrorLine.
                    engineErrors.merge(engine, message) { current, new ->
                        if (GalleryDlListing.sanitizeErrorMessage(current) != current) new else current
                    }
                }
                fun errorDetailsJson(extra: Pair<String, String>? = null): String? {
                    val entries = org.json.JSONArray()
                    synchronized(attemptedEngines) {
                        for (engine in attemptedEngines) {
                            val message = engineErrors[engine] ?: "Ran, but found nothing to download (no error reported)"
                            entries.put(org.json.JSONObject().put("engine", engine).put("message", message.take(2000)))
                        }
                    }
                    extra?.let { (engine, message) ->
                        entries.put(org.json.JSONObject().put("engine", engine).put("message", message.take(2000)))
                    }
                    return if (entries.length() == 0) null else entries.toString()
                }

                // The placeholder title set at enqueue time (see DownloadDispatcher) always starts
                // this way — used below to tell "still showing the placeholder" apart from "the
                // user already renamed this" so a real poster/caption title only ever replaces the
                // former, never clobbers a deliberate rename.
                val hasPlaceholderTitle = entity?.title?.startsWith("Downloading") != false
                // Set once an engine has named the download itself (EngineEvent.Title). Its title is
                // the real one; the filename-derived one would replace it with restricted-filename
                // underscores ("jawed - Me_at_the_zoo"). First one wins: Spotify's own beats the
                // matched YouTube video's that its yt-dlp run sends after.
                val engineNamedTitle = AtomicBoolean(false)
                // The video sub-file's format tags ("240p|MP4"). A merge reports the audio's last, so
                // the card kept "106 kbps OPUS" for a finished video; restored once the file lands.
                val videoFormatTags = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val downloadingAudio = AtomicBoolean(false)

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
                // forceStart is startNow()'s own marker for "the user explicitly jumped
                // this past the schedule window" (see DownloadDispatcher.startNow/repairIfJobDead).
                // Without this, the periodic check below would pause a Start-Now'd download within
                // its first minute anyway the instant the window happens to already be closed —
                // silently undoing the very override the user just tapped. Snapshotted once from the
                // entity fetched at the top of doWork(), not re-read live, so this exemption covers
                // this entire run exactly like the initial forceImmediate skip already did.
                val startedViaStartNow = entity?.forceStart == true

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

                    // What the line reports — see EngineEventParser for every shape it understands.
                    when (val event = EngineEventParser.parse(line)) {
                        is EngineEvent.Size -> {
                            dao.setExpectedBytes(downloadId, event.bytes)
                            expectedBytesRef.set(event.bytes)
                        }
                        // instaloader_wrapper.py's item count (after the picker's filter), known
                        // before the first file lands — the upfront gallery-dl listing pass that
                        // normally provides this is skipped for Instaloader-routed downloads.
                        is EngineEvent.Total -> {
                            if (totalItemsRef.get() <= 0) {
                                dao.setTotalItems(downloadId, event.count)
                                totalItemsRef.set(event.count)
                            }
                        }
                        // Sent once per sub-file (a merge's separate audio track, or an audio-only
                        // download's sole file) — see DownloadEntity.downloadingAudioTrack.
                        is EngineEvent.Phase -> {
                            downloadingAudio.set(event.isAudio)
                            dao.setDownloadingAudioTrack(downloadId, event.isAudio)
                        }
                        // Same per-sub-file timing — see DownloadEntity.formatTags.
                        is EngineEvent.Format -> {
                            if (!downloadingAudio.get()) videoFormatTags.set(event.tags)
                            dao.setFormatTags(downloadId, event.tags)
                        }
                        is EngineEvent.Progress -> {
                            DownloadNotes.clear(downloadId)
                            lastDataAt.set(System.currentTimeMillis())
                            lastProgressLineAt.set(System.currentTimeMillis())
                            val speedMbs = event.speedBytesPerSecond / (1024f * 1024f)
                            dao.updateLiveBytes(downloadId, event.downloadedBytes, speedMbs)
                            currentFileBytesRef.set(event.downloadedBytes)
                            // The wrappers already throttle these to about one a second. This is
                            // what moves the notification's bar during a single large file (the
                            // other updateProgress call only fires once per finished file).
                            DownloadNotifications.updateProgress(
                                applicationContext, downloadId, displayTitle, savedCount.get(), computeProgressPercent(),
                                speedMbs = speedMbs, currentBytes = bytesSoFar.get() + event.downloadedBytes, expectedBytes = expectedBytesRef.get(),
                            )
                        }
                        // Sent as soon as extraction finishes, so the card shows a real preview for
                        // the whole transfer. IfAbsent: a resumed download keeps its thumbnail, and
                        // the real local file still replaces it once it lands (singleItemDownload).
                        is EngineEvent.Thumbnail -> dao.setThumbnailIfAbsent(downloadId, event.url)
                        // Also sent before the first byte lands, so the card stops showing the
                        // "Downloading from X" placeholder. gallery-dl has no such early hook — its
                        // downloads rely on derivePosterCaptionTitle once a file lands instead.
                        is EngineEvent.Title -> if (hasPlaceholderTitle && engineNamedTitle.compareAndSet(false, true)) {
                            dao.updateTitle(downloadId, event.title)
                        }
                        // yt-dlp's info_dict metadata or Spotify's scraped metadata — see
                        // DownloadEntity.artist/album/track.
                        is EngineEvent.Artist -> dao.setArtistIfAbsent(downloadId, event.artist)
                        is EngineEvent.Album -> dao.setAlbumIfAbsent(downloadId, event.album)
                        is EngineEvent.Track -> dao.setTrackIfAbsent(downloadId, event.track)
                        // First error wins, unless it's unusable garbage: when gallery-dl and a
                        // yt-dlp fallback both fail, gallery-dl's message is normally the real cause
                        // and yt-dlp's a symptom — except gallery-dl's raw HTML/CSS-blob
                        // AbortExtraction (reproduced on Reddit), where yt-dlp's message ("Account
                        // authentication is required") is the useful one. sanitizeErrorMessage
                        // changing the text is the signal that the kept error is that garbage.
                        is EngineEvent.Error -> {
                            lastErrorLine.getAndUpdate { current ->
                                if (current == null || GalleryDlListing.sanitizeErrorMessage(current) != current) event.message else current
                            }
                            recordEngineError(event.message)
                        }
                        // gallery-dl's "no results" outcome is an info line, not an error. Captured
                        // so it wins (first-wins) over a less relevant yt-dlp fallback error — a
                        // tweet gallery-dl couldn't see used to show yt-dlp's "No video could be
                        // found in this tweet" for a picture carousel.
                        is EngineEvent.NoResults -> {
                            recordEngineError("No content found at this link (gallery-dl found no results)")
                            lastErrorLine.getAndUpdate { current ->
                                if (current == null || GalleryDlListing.sanitizeErrorMessage(current) != current) {
                                    "No content found at this link — it may need cookies for a logged-in session, or be unavailable"
                                } else current
                            }
                        }
                        // Listing-only progress text, and the engines' warnings/debug/status lines.
                        is EngineEvent.RateLimited -> DownloadNotes.set(downloadId, "Rate-limited by ${event.site} — waiting until ${event.until}")
                        is EngineEvent.Status, EngineEvent.Ignored -> Unit
                        is EngineEvent.File -> {
                            DownloadNotes.clear(downloadId)
                            try {
                                val candidate = File(event.path)
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
                                    // Extension-derived, except for Spotify: this app's stripped
                                    // ffmpeg has no ogg/opus muxer, so an opus/vorbis extraction
                                    // lands as a bare .webm — a container AUDIO_EXTENSIONS can't
                                    // list without misclassifying real webm video. Every Spotify
                                    // download is audio, so that engine check covers it. Computed
                                    // before saveMediaToGallery so it can override webm's default
                                    // video MIME (else the track lands in Movies, not Music).
                                    val isAudioFile = candidate.extension.lowercase() in AUDIO_EXTENSIONS ||
                                        engine == DownloadEngine.SPOTIFY
                                    // Lyrics go into the staging file's tags before it's saved;
                                    // a synced .lrc lands beside it and is saved after the song.
                                    val lrcFile = if (isAudioFile) addLyrics(candidate, overrideTitle, overrideArtist, downloadId) else null
                                    val savedUri = MediaStoreHelper.saveMediaToGallery(
                                        applicationContext, candidate, forceAudioMime = isAudioFile,
                                    )
                                    if (savedUri != null && lrcFile != null) {
                                        runCatching { MediaStoreHelper.saveMediaToGallery(applicationContext, lrcFile) }
                                    }
                                    lrcFile?.delete()
                                    if (savedUri != null) {
                                        lastDataAt.set(System.currentTimeMillis())
                                        candidate.delete()
                                        val count = savedCount.incrementAndGet()
                                        val totalBytes = bytesSoFar.addAndGet(fileSize)
                                        // This file's bytes now live in bytesSoFar — without resetting
                                        // this, the next file's progress would double-count them.
                                        currentFileBytesRef.set(0)
                                        val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.5f)
                                        val speedMbs = measuredSpeedMbs.get().takeIf { it > 0f }
                                            ?: ((totalBytes / (1024f * 1024f)) / elapsedSeconds)
                                        dao.updateLiveProgress(downloadId, count, speedMbs)
                                        // An audio file's own Uri has no frame Coil can decode, so its
                                        // embedded cover art is pulled out to its own file when there
                                        // is one (extractAudioArtworkUri). Only then does thumbnailPath
                                        // stop being the real file's Uri, so only then does mediaUri
                                        // need to carry it separately (Library's tap-to-open).
                                        // isAudio is extension-derived and always right for a given
                                        // file, so it's set unconditionally, not IfAbsent.
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
                                        // Its bytes are in totalBytes now; the card adds liveBytes on top.
                                        dao.updateLiveBytes(downloadId, 0, speedMbs)
                                        if (hasPlaceholderTitle && !engineNamedTitle.get()) {
                                            derivePosterCaptionTitle(candidate.name)?.let { dao.updateTitle(downloadId, it) }
                                        }
                                        // The merged file is the video: show its tags again, and stop
                                        // saying "downloading audio". (Audio-only downloads have no
                                        // video tags and keep their own.)
                                        videoFormatTags.get()?.let { dao.setFormatTags(downloadId, it) }
                                        if (downloadingAudio.getAndSet(false)) dao.setDownloadingAudioTrack(downloadId, false)
                                        // The song preview sheet's editable title/artist — applied
                                        // last, unconditionally, so the user's edit always wins in the
                                        // Library, matching what the wrappers embedded in the file's tags.
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
                // Each engine's command line, from this download's own choices and the Settings.
                val commands = EngineCommands(
                    applicationContext, entity, url, downloadId, engine, stagingDir, cookiesArg, overrideTitle, overrideArtist,
                )

                // Each download is its own OS subprocess (see PythonRuntime), so downloads run
                // genuinely concurrently up to the "Concurrent downloads" setting.
                // Wraps actualCallback so every line is filed under the engine that printed it (see
                // engineErrors). Created as each runner starts, which also marks that engine as run.
                fun callbackFor(engine: String): suspend (String) -> Unit {
                    attemptedEngines.add(engine)
                    return { line ->
                        currentEngine.set(engine)
                        actualCallback(line)
                    }
                }

                val savedSoFar = savedCount
                val executor = object : EngineExecutor {
                    override suspend fun run(engine: DownloadEngine, excludeVideo: Boolean) {
                        val command = commands.forEngine(engine, excludeVideo)
                        PythonRuntime.run(applicationContext, command.script, command.args, callbackFor(command.label))
                    }
                    // A live, no-network check against the bundled engines (EngineProbe) — only a
                    // gallery-dl-routed plan asks, to skip a gallery-dl attempt that can't work.
                    override suspend fun probe() = EngineProbe.probeBoth(applicationContext, url)
                    override val savedCount: Int get() = savedSoFar.get()
                    override val isStopped: Boolean get() = this@DownloadWorker.isStopped
                    override fun fail(message: String) {
                        lastErrorLine.compareAndSet(null, message)
                        recordEngineError(message)
                    }
                }
                // Which engines run and when one falls back to another — see EnginePlan. A gallery-dl
                // download also hands its video to yt-dlp when its listing found one, or on hosts
                // whose listings miss them (VideoSiteRouter.alwaysSupplementsVideo — an Instagram
                // carousel's video can be missing from the API response entirely).
                val plan = if (engineOverride != null) EnginePlan.Single(engineOverride) else EnginePlan.planFor(
                    engine,
                    classicEngine = VideoSiteRouter.classify(url),
                    supplementVideo = hasVideoItem || VideoSiteRouter.alwaysSupplementsVideo(url),
                    onlyVideos = onlyVideos,
                    listingTimedOut = listingTimedOut,
                    choice = engineChoice,
                    hasImageItem = hasImageItem,
                    galleryDlOnly = VideoSiteRouter.isGalleryDlOnly(url),
                )

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
                                // Progress too, not just speed: these engines only announce a file once
                                // it's complete (Instaloader streams it into a ".temp" file here), so the
                                // bar sat at 0 and jumped to done — reported on an 11 MB Instagram reel.
                                // What's in staging is this file's bytes so far.
                                if (staged != currentFileBytesRef.get()) {
                                    currentFileBytesRef.set(staged)
                                    dao.updateLiveBytes(downloadId, staged, smoothed)
                                }
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
                        try {
                            plan.execute(executor)
                        } finally {
                            DownloadNotes.clear(downloadId)
                        }
                    } finally {
                        stallWatchdog.cancel()
                    }
                }

                if (commands.writeInfoFiles || commands.saveThumbnail || commands.saveSubtitleFiles) {
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
                    // throws CancellationException straight out of plan.execute() these
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
                    dao.setErrorDetails(downloadId, errorDetailsJson())
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
                    // Not an engine's error (the app itself failed), and the per-engine record is out
                    // of scope here: clear any older details so the sheet shows this message.
                    dao.setErrorDetails(downloadId, null)
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

/** No data for this long counts as stalled; see the stall watchdog in doWork(). */
private const val STALL_AFTER_MS = 6_000L

/** How long the pre-download listing may take before the download goes ahead without it. 30 s, not
 * less: a real listing on a slow connection (600 ms round trips seen) takes several seconds. */
private const val LISTING_CAP_MS = 30_000L

// lyrics.py makes at most two LRCLIB requests (10s timeout each); past this the song saves without.
private const val LYRICS_CAP_MS = 25_000L
