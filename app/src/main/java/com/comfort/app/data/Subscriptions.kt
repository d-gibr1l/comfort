package com.comfort.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.comfort.app.util.SubscriptionFeed
import com.comfort.app.worker.DownloadNotifications
import com.comfort.app.worker.SubscriptionWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The download choices a subscription queues its new uploads with — the preview sheet's own, at
 * the moment Subscribe was tapped. */
data class SubscriptionOptions(
    val videoQuality: VideoQuality?,
    val outputFormat: OutputFormat?,
    val audioFormat: AudioFormat?,
    val saveThumbnail: Boolean?,
    val extraCommands: String?,
    val filenameTemplate: String?,
)

/** Watched YouTube channels and playlists (Settings › Subscriptions): subscribing, checking a
 * source for new uploads and queueing them, and the background schedule that does it. A check's
 * downloads go through [DownloadDispatcher.enqueueDownload] like any other, so the schedule
 * window, Wi-Fi only, concurrency and the duplicates rule all apply to them. */
object Subscriptions {
    private const val PERIODIC_WORK_NAME = "subscription-checks"
    private fun checkWorkName(id: String) = "subscription-check-$id"

    sealed class SubscribeResult {
        class Added(val subscription: Subscription) : SubscribeResult()
        class AlreadySubscribed(val subscription: Subscription) : SubscribeResult()
    }

    // One check per source at a time: the hourly worker and a "Check now" landing together would
    // otherwise both see the same uploads as new and queue each twice.
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of the sources being checked right now, for the page's progress indicator. */
    val checking: StateFlow<Set<String>> = _checking.asStateFlow()

    private fun dao(context: Context) = AppDatabase.getDatabase(context).subscriptionDao()

    fun observeAll(context: Context): Flow<List<Subscription>> = dao(context).observeAll()

    /** Saves [url] as a subscription and runs its first check, which only records what the source
     * already has (see [SubscriptionChecks.plan]) — subscribing never downloads the listing. */
    suspend fun subscribe(context: Context, url: String, title: String, thumbnail: String?, options: SubscriptionOptions): SubscribeResult {
        val trimmed = url.trim()
        dao(context).findByUrl(trimmed)?.let { return SubscribeResult.AlreadySubscribed(it) }
        val subscription = Subscription(
            id = UUID.randomUUID().toString(),
            url = trimmed,
            title = title.trim().ifBlank { trimmed },
            thumbnail = thumbnail,
            intervalHours = SubscriptionChecks.DEFAULT_INTERVAL_HOURS,
            videoQuality = options.videoQuality?.name,
            outputFormat = options.outputFormat?.name,
            audioFormat = options.audioFormat?.name,
            saveThumbnail = options.saveThumbnail,
            extraCommands = options.extraCommands,
            filenameTemplate = options.filenameTemplate,
            createdAt = System.currentTimeMillis(),
        )
        dao(context).insert(subscription)
        reschedule(context)
        requestCheck(context, subscription.id)
        return SubscribeResult.Added(subscription)
    }

    suspend fun setPaused(context: Context, id: String, paused: Boolean) = dao(context).setPaused(id, paused)

    suspend fun setInterval(context: Context, id: String, hours: Int) = dao(context).setInterval(id, hours)

    /** Removes the source and its seen uploads. Downloads it already queued are left alone. */
    suspend fun remove(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(checkWorkName(id))
        dao(context).delete(id)
        reschedule(context)
    }

    /** Checks one source soon, in WorkManager (so it finishes even if the page is left), once
     * there's a connection. A check already waiting for that source is kept, not doubled. */
    fun requestCheck(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<SubscriptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SubscriptionWorker.KEY_SUBSCRIPTION_ID to id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(checkWorkName(id), ExistingWorkPolicy.KEEP, request)
    }

    /** Arms the hourly background check while there's anything to check, and cancels it when the
     * last source is removed. Called at app start too, like the staging cleanup's schedule:
     * KEEP leaves a schedule that's already running on its own timing. WorkManager's periodic
     * minimum is 15 minutes and sources are due every 6-24 hours, so the worker runs hourly and
     * checks only the sources whose interval has passed ([SubscriptionChecks.isDue]). */
    suspend fun reschedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (dao(context).count() == 0) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Checks every source that's due; the periodic worker's job. */
    suspend fun checkDue(context: Context) {
        val now = System.currentTimeMillis()
        for (subscription in dao(context).getAllOnce()) {
            if (!SubscriptionChecks.isDue(subscription, now)) continue
            // One source failing unexpectedly shouldn't stop the rest being checked.
            try {
                check(context, subscription.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("Subscriptions", "Checking ${subscription.url} failed", e)
            }
        }
    }

    /** Checks one source now: lists its newest uploads, queues the new ones and remembers them.
     * Returns how many downloads it queued, or null if the check failed (the error is saved on
     * the source). */
    suspend fun check(context: Context, id: String): Int? = locks.computeIfAbsent(id) { Mutex() }.withLock {
        val dao = dao(context)
        // Re-read under the lock: a check that just finished changed what's seen and initialized.
        val subscription = dao.getById(id) ?: return@withLock null
        _checking.update { it + id }
        try {
            val feed = SubscriptionFeed.fetch(context, subscription.url, SubscriptionChecks.FETCH_LIMIT).getOrElse { error ->
                if (error is CancellationException) throw error
                dao.setCheckResult(id, System.currentTimeMillis(), error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
                return@withLock null
            }
            val plan = SubscriptionChecks.plan(feed.entries, dao.getSeenIds(id).toSet(), subscription.initialized)
            var queued = 0
            for (entry in plan.toDownload) {
                val result = DownloadDispatcher.enqueueDownload(
                    context,
                    url = entry.url,
                    title = entry.title ?: entry.url,
                    videoQuality = subscription.videoQuality?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() },
                    extraCommands = subscription.extraCommands,
                    outputFormat = subscription.outputFormat?.let { runCatching { OutputFormat.valueOf(it) }.getOrNull() },
                    filenameTemplate = subscription.filenameTemplate,
                    saveThumbnail = subscription.saveThumbnail,
                    audioFormat = subscription.audioFormat?.let { runCatching { AudioFormat.valueOf(it) }.getOrNull() },
                )
                if (result is EnqueueResult.Started) queued++
                // Seen as soon as it's queued, not after the loop: if the process dies part-way,
                // the next check doesn't queue these again.
                dao.insertSeen(listOf(SubscriptionSeenEntry(id, entry.id)))
            }
            dao.insertSeen(plan.toMarkSeen.map { SubscriptionSeenEntry(id, it) })
            dao.setCheckSucceeded(id, System.currentTimeMillis(), feed.title.ifBlank { subscription.title }, feed.thumbnail)
            if (queued > 0) DownloadNotifications.notifySubscriptionDownloads(context, id, feed.title.ifBlank { subscription.title }, queued)
            queued
        } finally {
            _checking.update { it - id }
        }
    }
}
