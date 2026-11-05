package com.comfort.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.util.EngineUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Periodically checks and updates the Python engines (yt-dlp, gallery-dl, Instaloader) in the
 * background when auto-update is enabled. Ensures extractors stay fresh even if the user hasn't
 * opened the main app screen in days. Runs when connected to a network and battery is not low. */
class EngineUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!GalleryDlPreferences.isAutoUpdateEnginesEnabled(applicationContext)) {
            return Result.success()
        }
        return try {
            EngineUpdater.checkAndAutoUpdate(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("EngineUpdateWorker", "Background engine update check failed", e)
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "comfort_engine_update_periodic"

        /** Schedules or cancels the periodic background engine update worker based on whether
         * [GalleryDlPreferences.isAutoUpdateEnginesEnabled] is true. */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<EngineUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
