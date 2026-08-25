package com.example.gallerydl.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.gallerydl.data.AppDatabase
import com.example.gallerydl.data.DownloadStatus
import com.example.gallerydl.data.GalleryDlPreferences
import com.example.gallerydl.util.MediaStoreHelper
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.Collections
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

                val python = Python.getInstance()
                val galleryWrapper = python.getModule("gallery_dl_wrapper")
                val cookiesPath = applicationContext.filesDir.resolve("cookies.txt")

                // gallery-dl needs a real filesystem path to write to; stage downloads here,
                // then move each finished file into the public gallery via MediaStore so it's
                // actually visible in the Photos/Gallery app instead of stuck in private storage.
                val stagingDir = File(applicationContext.cacheDir, "gallery-dl-staging/$downloadId").apply { mkdirs() }
                val savedCount = AtomicInteger(entity?.downloadedItems ?: 0)
                val bytesSoFar = AtomicLong(0)
                val callbackScope = CoroutineScope(Dispatchers.IO)
                val pendingJobs = Collections.synchronizedList(mutableListOf<Job>())

                // gallery-dl streams items one at a time and doesn't report a total up front, so
                // there's no reliable "N of M" count to show here — only how many have landed so far.
                val actualCallback = { line: String ->
                    android.util.Log.d("GalleryDL", "Python output: $line")
                    val job = callbackScope.launch {
                        try {
                            val candidate = File(line.trim())
                            if (candidate.isAbsolute && candidate.isFile &&
                                candidate.canonicalPath.startsWith(stagingDir.canonicalPath)
                            ) {
                                val fileSize = candidate.length()
                                val savedUri = MediaStoreHelper.saveImageToGallery(applicationContext, candidate)
                                if (savedUri != null) {
                                    candidate.delete()
                                    val count = savedCount.incrementAndGet()
                                    val totalBytes = bytesSoFar.addAndGet(fileSize)
                                    val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.5f)
                                    val speedMbs = (totalBytes / (1024f * 1024f)) / elapsedSeconds
                                    dao.updateLiveProgress(downloadId, count, speedMbs)
                                    dao.setThumbnailIfAbsent(downloadId, savedUri.toString())
                                    dao.addBytes(downloadId, fileSize)
                                    DownloadNotifications.updateProgress(applicationContext, downloadId, displayTitle, count)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("GalleryDL", "Failed to process output line: $line", e)
                        }
                    }
                    pendingJobs.add(job)
                    Unit
                }

                // Call python download function
                val cookiesArg = if (cookiesPath.exists()) cookiesPath.absolutePath else null
                val filenameFormat = GalleryDlPreferences.getFilenameFormat(applicationContext)
                val extraArgs = GalleryDlPreferences.getExtraArgs(applicationContext).ifBlank { null }
                // Tracks already-fetched item IDs across retries, so pausing/retrying a download
                // resumes where it left off instead of starting the whole gallery over.
                val archivePath = File(applicationContext.filesDir, "archives/$downloadId.sqlite3")
                    .apply { parentFile?.mkdirs() }
                    .absolutePath
                val limitRate = GalleryDlPreferences.getSpeedLimit(applicationContext).ifBlank { null }
                galleryWrapper.callAttr(
                    "download", url, stagingDir.absolutePath, cookiesArg, actualCallback,
                    filenameFormat, extraArgs, archivePath, limitRate, entity?.itemFilter,
                )
                // Wait for every in-flight move-to-gallery callback to finish before cleaning up staging.
                pendingJobs.toList().joinAll()
                stagingDir.deleteRecursively()

                dao.updateStatus(downloadId, DownloadStatus.FINISHED)
                val finalThumbnail = dao.getById(downloadId)?.thumbnailPath
                DownloadNotifications.notifyFinished(applicationContext, downloadId, displayTitle, savedCount.get(), finalThumbnail)
                Result.success()
            } catch (e: CancellationException) {
                // A paused/cancelled download: leave whatever status pauseDownload() already set
                // (CANCELLED) instead of overwriting it with an error.
                DownloadNotifications.cancel(applicationContext, downloadId)
                throw e
            } catch (e: Exception) {
                dao.updateError(downloadId, DownloadStatus.ERRORED, e.localizedMessage)
                DownloadNotifications.notifyFailed(applicationContext, downloadId, displayTitle)
                Result.failure()
            }
        }
    }
}
