package com.comfort.app.data

import com.comfort.app.util.FeedEntry

/** The rules of a subscription check, kept free of Android so they're unit-tested: when a source
 * is due, and which of the uploads a check found get downloaded. [Subscriptions] applies them. */
object SubscriptionChecks {
    /** The check intervals Settings › Subscriptions offers, in hours. */
    val INTERVAL_CHOICES = listOf(6, 12, 24)
    const val DEFAULT_INTERVAL_HOURS = 24

    /** How many of a source's newest uploads a check asks yt-dlp for. Comfortably more than any
     * channel posts in a day, so nothing slips between two checks; a longer list only makes the
     * check slower. */
    const val FETCH_LIMIT = 30

    /** The most downloads one check queues for one source. Anything a check sees for the first
     * time counts as new, and a channel that reorganises its uploads (re-ordering a playlist,
     * re-uploading, an old video made public again) can make a whole page of them look new at
     * once — this keeps that to a handful of downloads instead of a flooded queue. The ones past
     * the cap are still marked seen, so they don't come back on every later check. */
    const val MAX_NEW_PER_CHECK = 10

    /** A failed check is retried after this rather than after the source's full interval, so a
     * dropped connection costs an hour, not a day. */
    const val RETRY_AFTER_ERROR_MS = 60 * 60 * 1000L

    /** The worker runs about hourly and WorkManager runs periodic work a little early or late, so
     * a source due in five minutes is checked now rather than an hour from now. */
    const val DUE_TOLERANCE_MS = 10 * 60 * 1000L

    /** What a check does with the uploads it found. [toDownload] is queued in this order (oldest
     * first, so the queue follows upload order); every id in [toMarkSeen] is remembered, the
     * downloaded ones included. */
    data class Plan(val toDownload: List<FeedEntry>, val toMarkSeen: List<String>)

    /** [entries] newest first, as the feed lists them. A source's first successful check
     * ([initialized] false) only records what's there: subscribing to a channel shouldn't
     * download its whole back catalogue. After that, uploads not in [seen] are new: the newest
     * [cap] are downloaded and the rest are only marked seen (see [MAX_NEW_PER_CHECK]). */
    fun plan(entries: List<FeedEntry>, seen: Set<String>, initialized: Boolean, cap: Int = MAX_NEW_PER_CHECK): Plan {
        val fresh = entries.distinctBy { it.id }.filter { it.id !in seen }
        val ids = fresh.map { it.id }
        if (!initialized) return Plan(emptyList(), ids)
        return Plan(fresh.take(cap.coerceAtLeast(0)).reversed(), ids)
    }

    /** Whether the background worker should check this source at [now]. */
    fun isDue(subscription: Subscription, now: Long): Boolean {
        if (subscription.paused) return false
        val last = subscription.lastCheckedAt ?: return true
        // A check "in the future" means the clock was set back; waiting for it to catch up could
        // take days.
        if (last > now) return true
        val intervalMs = subscription.intervalHours.coerceAtLeast(1) * 60 * 60 * 1000L
        val wait = if (subscription.lastError != null) minOf(intervalMs, RETRY_AFTER_ERROR_MS) else intervalMs
        return now - last >= wait - DUE_TOLERANCE_MS
    }
}
