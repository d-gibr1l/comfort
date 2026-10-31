package com.comfort.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DispatchTimingTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    /** [hh]:[mm]:[ss] on the given day, in [zone] (the window is in the phone's own zone). */
    private fun at(year: Int, month: Int, day: Int, hh: Int, mm: Int, ss: Int = 0, zone: String = "UTC"): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear()
            set(year, month - 1, day, hh, mm, ss)
        }

    private fun window(startMin: Int, endMin: Int, now: Calendar) = DispatchTiming.windowDelayMillis(startMin, endMin, now)

    // --- The schedule window ---

    @Test fun openWindowIsNoWait() {
        assertEquals(0L, window(9 * 60, 17 * 60, at(2026, 10, 30, 12, 0)))
        // Both ends count as open.
        assertEquals(0L, window(9 * 60, 17 * 60, at(2026, 10, 30, 9, 0)))
        assertEquals(0L, window(9 * 60, 17 * 60, at(2026, 10, 30, 17, 0)))
    }

    @Test fun beforeTheWindowWaitsUntilItOpens() {
        assertEquals(2 * hour, window(9 * 60, 17 * 60, at(2026, 10, 30, 7, 0)))
    }

    @Test fun afterTheWindowWaitsUntilTomorrow() {
        assertEquals(15 * hour, window(9 * 60, 17 * 60, at(2026, 10, 30, 18, 0)))
    }

    @Test fun windowPastMidnight() {
        val start = 22 * 60
        val end = 6 * 60
        assertEquals(0L, window(start, end, at(2026, 10, 30, 23, 0)))
        assertEquals(0L, window(start, end, at(2026, 10, 30, 3, 0)))
        assertEquals(10 * hour, window(start, end, at(2026, 10, 30, 12, 0)))
    }

    @Test fun sameStartAndEndMeansNoRestriction() {
        assertEquals(0L, window(8 * 60, 8 * 60, at(2026, 10, 30, 20, 0)))
    }

    @Test fun waitsToTheStartExactlyWithSeconds() {
        // Queued at 10:00:30 for an 11:00 window: 59 min 30 s, not a whole hour.
        assertEquals(59 * minute + 30_000L, window(11 * 60, 12 * 60, at(2026, 10, 30, 10, 0, 30)))
    }

    @Test fun springForwardIsAnHourShorter() {
        // New York, 2026-03-08: clocks go 02:00 -> 03:00. From 01:00 to 04:00 is 2 real hours.
        assertEquals(2 * hour, window(4 * 60, 6 * 60, at(2026, 3, 8, 1, 0, zone = "America/New_York")))
    }

    @Test fun fallBackIsAnHourLonger() {
        // New York, 2026-11-01: clocks go 02:00 -> 01:00. From 00:30 to 03:00 is 3.5 real hours.
        assertEquals(3 * hour + 30 * minute, window(3 * 60, 6 * 60, at(2026, 11, 1, 0, 30, zone = "America/New_York")))
    }

    // --- One download's timing ---

    private val now = 1_800_000_000_000L
    private fun timing(
        status: DownloadStatus = DownloadStatus.QUEUED,
        scheduledAt: Long? = null,
        forceImmediate: Boolean = false,
        windowDelay: Long = 0L,
        downloadDelay: Long = 0L,
    ) = DispatchTiming.timing(status, scheduledAt, forceImmediate, windowDelay, downloadDelay, now)

    @Test fun plainDownloadOnlyWaitsForTheDownloadDelay() {
        val t = timing(downloadDelay = 5_000L)
        assertEquals(5_000L, t.delayMillis)
        assertFalse("a short Download Delay is still In Queue", t.scheduled)
    }

    @Test fun closedWindowIsScheduled() {
        val t = timing(windowDelay = 3 * hour, downloadDelay = 5_000L)
        assertEquals(3 * hour + 5_000L, t.delayMillis)
        assertTrue(t.scheduled)
    }

    @Test fun startNowSkipsTheWindowAndTheDelay() {
        val t = timing(forceImmediate = true, windowDelay = 3 * hour, downloadDelay = 5_000L)
        assertEquals(0L, t.delayMillis)
        assertFalse(t.scheduled)
    }

    @Test fun ownStartTimeReplacesTheWindowAndTheDelay() {
        // A set time inside a closed window's hours still starts at that time.
        val t = timing(scheduledAt = now + 20 * minute, windowDelay = 3 * hour, downloadDelay = 5_000L)
        assertEquals(20 * minute, t.delayMillis)
        assertTrue(t.scheduled)
        assertFalse(t.clearStartAt)
    }

    @Test fun ownStartTimeAlreadyPassedStartsNow() {
        // A download woken by its alarm a moment late, still in the queue.
        val t = timing(scheduledAt = now - minute, windowDelay = 3 * hour)
        assertEquals(0L, t.delayMillis)
        assertFalse(t.scheduled)
        assertFalse(t.clearStartAt)
    }

    @Test fun startNowWinsOverAnOwnStartTime() {
        assertEquals(0L, timing(scheduledAt = now + hour, forceImmediate = true).delayMillis)
    }

    @Test fun retryOfAFinishedRunForgetsItsStartTime() {
        // Failed after starting at its set time; retried, it waits for the window like any other.
        for (status in listOf(DownloadStatus.ERRORED, DownloadStatus.CANCELLED, DownloadStatus.FINISHED)) {
            val t = timing(status = status, scheduledAt = now - hour, windowDelay = 2 * hour)
            assertEquals("$status", 2 * hour, t.delayMillis)
            assertTrue("$status", t.scheduled)
            assertTrue("$status", t.clearStartAt)
        }
    }

    @Test fun pausedDownloadKeepsItsStartTime() {
        // "Start at…" on a paused card: dispatched as PAUSED, its time is for this run.
        val t = timing(status = DownloadStatus.PAUSED, scheduledAt = now + 30 * minute)
        assertEquals(30 * minute, t.delayMillis)
        assertFalse(t.clearStartAt)
    }
}
