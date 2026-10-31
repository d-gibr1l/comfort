package com.comfort.app.viewmodel

import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.LiveProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** DownloadsViewModel.queueFlow's rows (queueRows): Undo-hidden rows, pending bulk moves, and the
 * running downloads' live bytes and speed. */
class QueueRowsTest {
    private fun row(
        id: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
        order: Int = 0,
        added: Long = 0,
        liveBytes: Long = 0,
        speed: Float = 0f,
    ) = DownloadEntity(
        id = id, url = "https://example.com/$id", title = id, thumbnailPath = null, status = status, progress = 0f,
        downloadedItems = 0, totalItems = 0, speedMbs = speed, etaSeconds = 0, errorMessage = null, dateAdded = added,
        queueOrder = order, liveBytes = liveBytes,
    )

    private fun ids(rows: List<DownloadEntity>) = rows.map { it.id }

    @Test fun nothingToChangeIsTheSameList() {
        val queue = listOf(row("a"), row("b"))
        assertSame(queue, queueRows(queue, emptySet(), emptyMap(), emptyMap()))
    }

    @Test fun rowsBeingDeletedAreHidden() {
        val queue = listOf(row("a"), row("b"), row("c"))
        assertEquals(listOf("a", "c"), ids(queueRows(queue, setOf("b"), emptyMap(), emptyMap())))
    }

    @Test fun aPendingMoveAppliesWhileTheRowIsStillInItsOldStatus() {
        val queue = listOf(row("a", DownloadStatus.RUNNING), row("b", DownloadStatus.QUEUED))
        val pause = PendingMove(DownloadStatus.RUNNING) { it.copy(status = DownloadStatus.PAUSED, speedMbs = 0f) }
        val out = queueRows(queue, emptySet(), mapOf("a" to pause), emptyMap())
        assertEquals(DownloadStatus.PAUSED, out.single { it.id == "a" }.status)
    }

    @Test fun aPendingMoveEndsOnceTheDatabaseHasMovedOn() {
        // Its row is in another status now (the database caught up, or something else happened).
        val queue = listOf(row("a", DownloadStatus.ERRORED))
        val pause = PendingMove(DownloadStatus.RUNNING) { it.copy(status = DownloadStatus.PAUSED) }
        assertEquals(DownloadStatus.ERRORED, queueRows(queue, emptySet(), mapOf("a" to pause), emptyMap()).single().status)
    }

    @Test fun pendingMovesResortTheQueue() {
        // Start now gives "c" the most negative order: it goes to the front at once.
        val queue = listOf(row("a", order = 0, added = 1), row("b", order = 0, added = 2), row("c", order = 0, added = 3))
        val startNow = PendingMove(DownloadStatus.QUEUED) { it.copy(queueOrder = -5) }
        assertEquals(listOf("c", "a", "b"), ids(queueRows(queue, emptySet(), mapOf("c" to startNow), emptyMap())))
    }

    @Test fun runningRowsGetTheirLiveBytesAndSpeed() {
        val queue = listOf(row("a", DownloadStatus.RUNNING, liveBytes = 10), row("b", DownloadStatus.QUEUED))
        val out = queueRows(queue, emptySet(), emptyMap(), mapOf("a" to LiveProgress.Live(500, 2.5f)))
        assertEquals(500L, out[0].liveBytes)
        assertEquals(2.5f, out[0].speedMbs)
        assertSame(queue[1], out[1])
    }

    @Test fun aRunningRowJustPausedShowsNoLiveSpeed() {
        // The pause shows before the database has it; the run's last speed mustn't stay on the card.
        val queue = listOf(row("a", DownloadStatus.RUNNING, speed = 3f))
        val pause = PendingMove(DownloadStatus.RUNNING) { it.copy(status = DownloadStatus.PAUSED, speedMbs = 0f) }
        val out = queueRows(queue, emptySet(), mapOf("a" to pause), mapOf("a" to LiveProgress.Live(700, 4f)))
        assertEquals(DownloadStatus.PAUSED, out.single().status)
        assertEquals(0f, out.single().speedMbs)
    }

    @Test fun aHiddenRowGetsNoLiveValuesBack() {
        val queue = listOf(row("a", DownloadStatus.RUNNING))
        assertEquals(emptyList<DownloadEntity>(), queueRows(queue, setOf("a"), emptyMap(), mapOf("a" to LiveProgress.Live(1, 1f))))
    }
}
