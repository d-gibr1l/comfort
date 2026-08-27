package com.example.gallerydl.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.gallerydl.data.AppDatabase
import com.example.gallerydl.data.DownloadEngine
import com.example.gallerydl.data.DownloadStatus
import com.example.gallerydl.data.DownloadedFileRecord
import com.example.gallerydl.data.GalleryDlPreferences
import com.example.gallerydl.data.VideoQuality
import com.example.gallerydl.data.VideoSiteRouter
import com.example.gallerydl.util.FfmpegRuntime
import com.example.gallerydl.util.GalleryDlListing
import com.example.gallerydl.util.MediaStoreHelper
import com.example.gallerydl.util.PythonRuntime
import com.example.gallerydl.util.QuickJsRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString("downloadId") ?: return Result.failure()
        val url = inputData.getString("url") ?: return Result.failure()

        val dao = AppDatabase.getDatabase(applicationContext).downloadDao()
        val entity = dao.getById(downloadId)
        val displayTitle = entity?.title?.ifBlank { url } ?: url

        setForeground(
            ForegroundInfo(
                DownloadNotifications.notificationId(downloadId),
                DownloadNotifications.progressNotification(applicationContext, displayTitle, downloadId, entity?.downloadedItems ?: 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        )

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
                var hasVideoItem = false
                if (engine == DownloadEngine.GALLERY_DL && (entity?.totalItems ?: 0) <= 0 && entity?.itemFilter == null) {
                    val listed = GalleryDlListing.listItems(applicationContext, url)
                    if (listed.isNotEmpty() && listed.size < GalleryDlListing.MAX_ITEMS) {
                        dao.setTotalItems(downloadId, listed.size)
                    }
                    hasVideoItem = listed.any { item -> item.filename?.let(VideoSiteRouter::isVideoFilename) == true }
                }
                if (isStopped) {
                    DownloadNotifications.cancel(applicationContext, downloadId)
                    return@withContext Result.failure()
                }

                val cookiesPath = applicationContext.filesDir.resolve("cookies.txt")

                // gallery-dl needs a real filesystem path to write to; stage downloads here,
                // then move each finished file into the public gallery via MediaStore so it's
                // actually visible in the Photos/Gallery app instead of stuck in private storage.
                val stagingDir = File(applicationContext.cacheDir, "gallery-dl-staging/$downloadId").apply { mkdirs() }
                val savedCount = AtomicInteger(entity?.downloadedItems ?: 0)
                val bytesSoFar = AtomicLong(0)
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
                            if (bytes != null) dao.setExpectedBytes(downloadId, bytes)
                        }
                        line.startsWith("[progress] ") -> {
                            val rest = line.removePrefix("[progress] ")
                            val downloaded = Regex("downloaded=(\\d+)").find(rest)?.groupValues?.get(1)?.toLongOrNull()
                            val speedBps = Regex("speed=([\\d.]+)").find(rest)?.groupValues?.get(1)?.toFloatOrNull()
                            if (downloaded != null) {
                                val speedMbs = (speedBps ?: 0f) / (1024f * 1024f)
                                dao.updateLiveBytes(downloadId, downloaded, speedMbs)
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
                            // First error wins, not last: when gallery-dl (the primary engine) and
                            // a yt-dlp fallback/supplement pass both fail, gallery-dl's own message
                            // is normally the actual root cause (e.g. that login-redirect) and
                            // yt-dlp's is just a downstream symptom of the same block ("no video
                            // formats found" — of course not, it's not logged in either) — showing
                            // whichever came first keeps the diagnostic one instead of the vaguer
                            // one that happened to run last.
                            lastErrorLine.compareAndSet(null, line.substringAfter("[error] ").trim())
                        }
                        // yt-dlp's own non-fatal warnings — never file paths, nothing to act on,
                        // just kept out of the file-path branch below.
                        line.startsWith("[warning] ") -> Unit
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
                                        DownloadNotifications.updateProgress(applicationContext, downloadId, displayTitle, count)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("DownloadEngine", "Failed to process output line: $line", e)
                            }
                        }
                    }
                }

                val cookiesArg = if (cookiesPath.exists()) cookiesPath.absolutePath else ""
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
                        ),
                        actualCallback,
                    )

                // Bundled as jniLibs/<abi>/libqjs.so and libffmpeg.so respectively — see
                // QuickJsRuntime's and FfmpegRuntime's doc comments for why sites like YouTube
                // need the former just to extract real download URLs, and the latter to merge
                // the separate video/audio streams those URLs point to into one playable file.
                val jsRuntimePath = QuickJsRuntime.getExecutablePath(applicationContext).orEmpty()
                val ffmpegPath = FfmpegRuntime.getExecutablePath(applicationContext).orEmpty()

                val videoQuality = GalleryDlPreferences.getVideoQuality(applicationContext)
                val audioOnly = videoQuality == VideoQuality.AUDIO_ONLY
                val downloadSubtitles = GalleryDlPreferences.isDownloadSubtitles(applicationContext)
                val subtitleLangs = GalleryDlPreferences.getSubtitleLanguages(applicationContext)
                val embedThumbnail = GalleryDlPreferences.isEmbedThumbnail(applicationContext)
                val embedMetadata = GalleryDlPreferences.isEmbedMetadata(applicationContext)
                val noPlaylist = GalleryDlPreferences.isNoPlaylist(applicationContext)

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
                    DownloadNotifications.cancel(applicationContext, downloadId)
                    return@withContext Result.failure()
                }

                val isCookieError = lastErrorLine.get()?.let { msg ->
                    listOf("cookie", "logged-in", "log in", "sign in", "login", "private", "authentication", "credentials", "netrc")
                        .any { msg.contains(it, ignoreCase = true) }
                } == true

                if (savedCount.get() == 0 || isCookieError) {
                    // Neither engine found anything to save, or the engine was blocked by a login
                    // requirement. Previously, an Instagram carousel redirecting to login would
                    // run the yt-dlp fallback, fetch 1 video, and mask the overall failure.
                    dao.updateError(downloadId, DownloadStatus.ERRORED, lastErrorLine.get() ?: "No downloadable content found at this link")
                    DownloadNotifications.notifyFailed(applicationContext, downloadId, displayTitle)
                    return@withContext Result.failure()
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
                if (isStopped) {
                    DownloadNotifications.cancel(applicationContext, downloadId)
                    Result.failure()
                } else {
                    dao.updateError(downloadId, DownloadStatus.ERRORED, e.localizedMessage)
                    DownloadNotifications.notifyFailed(applicationContext, downloadId, displayTitle)
                    Result.failure()
                }
            }
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
