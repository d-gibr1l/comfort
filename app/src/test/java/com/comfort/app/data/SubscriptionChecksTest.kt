package com.comfort.app.data

import com.comfort.app.util.FeedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionChecksTest {
    private fun entry(id: String) = FeedEntry(id, "https://www.youtube.com/watch?v=$id", id, null, null, null)

    /** Newest first, as the feed lists them: e0 is the newest. */
    private fun entries(n: Int) = (0 until n).map { entry("e$it") }

    private val hour = 60 * 60 * 1000L

    private fun sub(intervalHours: Int = 24, paused: Boolean = false, lastCheckedAt: Long? = null, lastError: String? = null) =
        Subscription(
            id = "s", url = "https://www.youtube.com/@x", title = "x", intervalHours = intervalHours,
            paused = paused, createdAt = 0L, lastCheckedAt = lastCheckedAt, lastError = lastError,
        )

    @Test
    fun firstCheck_recordsEverythingAndDownloadsNothing() {
        val plan = SubscriptionChecks.plan(entries(25), seen = emptySet(), initialized = false)
        assertTrue(plan.toDownload.isEmpty())
        assertEquals(25, plan.toMarkSeen.size)
    }

    @Test
    fun laterCheck_queuesOnlyUnseenOldestFirst() {
        val seen = setOf("e2", "e3", "e4")
        val plan = SubscriptionChecks.plan(entries(5), seen, initialized = true)
        assertEquals(listOf("e1", "e0"), plan.toDownload.map { it.id })
        assertEquals(listOf("e0", "e1"), plan.toMarkSeen)
    }

    @Test
    fun laterCheck_nothingNew() {
        val plan = SubscriptionChecks.plan(entries(3), setOf("e0", "e1", "e2"), initialized = true)
        assertTrue(plan.toDownload.isEmpty())
        assertTrue(plan.toMarkSeen.isEmpty())
    }

    @Test
    fun laterCheck_capsDownloadsAtTheNewestAndMarksTheRestSeen() {
        val plan = SubscriptionChecks.plan(entries(30), seen = emptySet(), initialized = true)
        assertEquals(SubscriptionChecks.MAX_NEW_PER_CHECK, plan.toDownload.size)
        // The newest ten (e0..e9), queued oldest first.
        assertEquals((9 downTo 0).map { "e$it" }, plan.toDownload.map { it.id })
        assertEquals(30, plan.toMarkSeen.size)
        assertTrue(plan.toDownload.all { it.id in plan.toMarkSeen })
    }

    @Test
    fun plan_customCapAndDuplicates() {
        val list = listOf(entry("a"), entry("a"), entry("b"), entry("c"))
        val plan = SubscriptionChecks.plan(list, emptySet(), initialized = true, cap = 2)
        assertEquals(listOf("b", "a"), plan.toDownload.map { it.id })
        assertEquals(listOf("a", "b", "c"), plan.toMarkSeen)
        assertTrue(SubscriptionChecks.plan(list, emptySet(), initialized = true, cap = 0).toDownload.isEmpty())
    }

    @Test
    fun isDue_neverChecked() {
        assertTrue(SubscriptionChecks.isDue(sub(), now = 1000L))
    }

    @Test
    fun isDue_pausedNever() {
        assertFalse(SubscriptionChecks.isDue(sub(paused = true), now = 1000L))
        assertFalse(SubscriptionChecks.isDue(sub(paused = true, lastCheckedAt = 0L), now = 100 * hour))
    }

    @Test
    fun isDue_followsTheIntervalWithTolerance() {
        val last = 1_000 * hour
        assertFalse(SubscriptionChecks.isDue(sub(intervalHours = 6, lastCheckedAt = last), now = last + 5 * hour))
        // Five minutes early still counts: the hourly worker would otherwise wait a whole hour.
        assertTrue(SubscriptionChecks.isDue(sub(intervalHours = 6, lastCheckedAt = last), now = last + 6 * hour - 5 * 60 * 1000L))
        assertTrue(SubscriptionChecks.isDue(sub(intervalHours = 6, lastCheckedAt = last), now = last + 6 * hour))
        assertFalse(SubscriptionChecks.isDue(sub(intervalHours = 24, lastCheckedAt = last), now = last + 12 * hour))
        assertTrue(SubscriptionChecks.isDue(sub(intervalHours = 24, lastCheckedAt = last), now = last + 24 * hour))
    }

    @Test
    fun isDue_retriesSoonerAfterAnError() {
        val last = 1_000 * hour
        val failed = sub(intervalHours = 24, lastCheckedAt = last, lastError = "network")
        assertFalse(SubscriptionChecks.isDue(failed, now = last + 30 * 60 * 1000L))
        assertTrue(SubscriptionChecks.isDue(failed, now = last + hour))
    }

    @Test
    fun isDue_clockSetBack() {
        assertTrue(SubscriptionChecks.isDue(sub(lastCheckedAt = 10 * hour), now = 5 * hour))
    }
}
