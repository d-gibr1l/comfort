package com.comfort.app.data

import java.util.Calendar

/** When a dispatched download may start, as pure functions of their inputs — no Context, no clock
 * of their own — so the rules that kept breaking (the schedule window, a set start time, Start now,
 * the Download Delay, a retry of a finished run) are unit-tested (DispatchTimingTest).
 * DownloadDispatcher reads the settings and the row, and asks these. */
internal object DispatchTiming {

    /** A download that's over: retried, it's a new run, and a start time set for the old one ends. */
    val FINISHED_STATUSES = setOf(DownloadStatus.FINISHED, DownloadStatus.SAVED, DownloadStatus.ERRORED, DownloadStatus.CANCELLED, DownloadStatus.DELETED)

    /** Millis from [now] until the schedule window [startMin]–[endMin] (minutes of the day, both
     * inclusive) next opens, or 0 if it's open at [now]. [now]'s time zone is the one the window is
     * in. */
    fun windowDelayMillis(startMin: Int, endMin: Int, now: Calendar): Long {
        // Identical Start/End would otherwise fall into the non-wrapping branch below and read as
        // "open" only during that exact one minute of the day, closed the other 1439 — surprising
        // for a value nobody would deliberately pick to mean "almost never download." Treating it
        // as no restriction is the reading that can't ever silently starve every download.
        if (startMin == endMin) return 0L
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val withinWindow = if (startMin <= endMin) {
            nowMin in startMin..endMin
        } else {
            // window wraps past midnight, e.g. 22:00-06:00
            nowMin >= startMin || nowMin <= endMin
        }
        if (withinWindow) return 0L

        // The window's next start as a real moment, not a count of clock minutes from now: across
        // a daylight-saving change the clock skips or repeats an hour, and "180 minutes until
        // 03:00" was an hour off (Android sends no broadcast for DST, so ClockChangeReceiver never
        // corrected it). Calendar lands on the start's actual instant either side of the change.
        // To the window's start exactly, seconds included: each job used to wake up to a minute
        // after the window opened, at the seconds-past-the-minute it was queued at (seen live:
        // queued at :04, :11, :19 past, they started at 16:28:04, :11, :19).
        val start = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, startMin / 60)
            set(Calendar.MINUTE, startMin % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return (start.timeInMillis - now.timeInMillis).coerceAtLeast(1L)
    }

    /** What [timing] decided for one download: how long its job waits, whether it shows as
     * Scheduled (waiting for a time) rather than In Queue, and whether its start time has to be
     * cleared from the row (it belonged to a run that's over). */
    data class Timing(val delayMillis: Long, val scheduled: Boolean, val clearStartAt: Boolean)

    /** One download's wait, dispatched at [now] with the row's [status] and [scheduledAt], Start
     * now's [forceImmediate], the schedule window's [windowDelay] and the Download Delay
     * [downloadDelay] (both as the settings give them right now). */
    fun timing(
        status: DownloadStatus,
        scheduledAt: Long?,
        forceImmediate: Boolean,
        windowDelay: Long,
        downloadDelay: Long,
        now: Long,
    ): Timing {
        // A start time of its own ("Start at…", see DownloadDispatcher.scheduleAt) replaces the
        // window, and the Download Delay with it: it starts at the time the user picked.
        // Only for the run it was set for: a download that has since finished, failed or been
        // cancelled and is now retried waits for the window like any other. Kept, its time long
        // past read as "start now" and the retry skipped the window.
        val clearStartAt = status in FINISHED_STATUSES && scheduledAt != null
        val ownStartAt = if (clearStartAt) null else scheduledAt
        val ownStartDelay = ownStartAt?.let { (it - now).coerceAtLeast(0L) }
        val windowDelayMillis = when {
            forceImmediate -> 0L
            ownStartDelay != null -> ownStartDelay
            else -> windowDelay
        }
        val delayMillis = windowDelayMillis + if (forceImmediate || ownStartDelay != null) 0L else downloadDelay
        // SCHEDULED vs QUEUED distinguishes "waiting on the schedule window" (or its own time) from
        // "waiting on a concurrency slot" — the generic Download Delay is a short wait that still
        // counts as plain "In Queue".
        return Timing(delayMillis, scheduled = windowDelayMillis > 0, clearStartAt = clearStartAt)
    }
}
