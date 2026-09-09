package com.comfort.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.comfort.app.data.AppDatabase
import java.io.File

/** Periodic sweep, ported from YTDLnis's own "Clean-up leftover downloads (cancelled, errored)"
 * setting (Settings > Downloads). [isDeleteLeftoverOnFailure][com.comfort.app.data.GalleryDlPreferences.isDeleteLeftoverOnFailure]
 * only cleans a failed download's staging directory from inside DownloadWorker's own catch
 * block — it never runs at all if the process was killed outright (an OS out-of-memory kill,
 * force-stop, a crash) before that code got a chance to execute, or if the setting was off at
 * the time. This worker instead looks at what's actually left behind: every CANCELLED or
 * ERRORED download's staging directory, regardless of when or why the per-download cleanup
 * didn't happen, and removes it. Scheduled by
 * [DownloadDispatcher.rescheduleStagingCleanup][com.comfort.app.data.DownloadDispatcher.rescheduleStagingCleanup].
 */
class StagingCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = AppDatabase.getDatabase(applicationContext).downloadDao()
        val leftoverIds = dao.getCancelledOrErroredIdsOnce().toSet()
        val stagingRoot = File(applicationContext.cacheDir, "gallery-dl-staging")
        val dirs = stagingRoot.listFiles() ?: return Result.success()
        for (dir in dirs) {
            // Only ever touch a subdirectory whose name is a known cancelled/errored download's
            // id — never a bare age-based sweep, so an id that's still QUEUED/RUNNING/PAUSED (or
            // simply not in the DB, e.g. staging's own transient state) is never at risk.
            if (dir.isDirectory && dir.name in leftoverIds) {
                dir.deleteRecursively()
            }
        }
        return Result.success()
    }
}
