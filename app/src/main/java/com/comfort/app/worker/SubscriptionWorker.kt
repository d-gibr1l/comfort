package com.comfort.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.comfort.app.data.Subscriptions

/** Checks subscriptions for new uploads (see [Subscriptions]). Two shapes: the hourly periodic run,
 * which checks every source that's due, and a one-off run for a single source ([KEY_SUBSCRIPTION_ID]
 * set) — a new subscription's first check, or the page's Check now, which runs even if the source
 * is paused or not due, since someone asked for it. */
class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SUBSCRIPTION_ID)
        try {
            if (id != null) Subscriptions.check(applicationContext, id) else Subscriptions.checkDue(applicationContext)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A check's own failures (no connection, a removed channel) are saved on the source;
            // anything reaching here is unexpected. Not retried: the next hourly run tries again.
            android.util.Log.e("SubscriptionWorker", "Subscription check failed", e)
        }
        return Result.success()
    }

    companion object {
        const val KEY_SUBSCRIPTION_ID = "subscriptionId"
    }
}
