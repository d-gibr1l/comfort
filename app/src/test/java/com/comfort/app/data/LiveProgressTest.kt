package com.comfort.app.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class LiveProgressTest {
    private var now = 1_000_000L
    private val runs = mutableListOf<LiveProgress.Run>()

    @Before fun setUp() {
        LiveProgress.clock = { now }
    }

    @After fun tearDown() {
        runs.forEach(LiveProgress::end)
        LiveProgress.clock = System::currentTimeMillis
    }

    private fun begin(id: String) = LiveProgress.begin(id).also { runs += it }

    /** A DownloadDao that only records setLiveBytes (id to bytes); anything else fails the test. */
    private class RecordingDao {
        val writes = mutableListOf<Pair<String, Long>>()
        val dao = Proxy.newProxyInstance(DownloadDao::class.java.classLoader, arrayOf(DownloadDao::class.java)) { _, method, args ->
            check(method.name == "setLiveBytes") { "unexpected ${method.name}" }
            writes += args[0] as String to args[1] as Long
            Unit
        } as DownloadDao
    }

    private fun row(id: String, status: DownloadStatus, liveBytes: Long = 0, speed: Float = 0f) = DownloadEntity(
        id = id, url = "https://example.com/$id", title = id, thumbnailPath = null, status = status, progress = 0f,
        downloadedItems = 0, totalItems = 0, speedMbs = speed, etaSeconds = 0, errorMessage = null, dateAdded = 0,
        liveBytes = liveBytes,
    )

    @Test fun overlayOnlyChangesRunningRowsWithLiveValues() = runTest {
        val rec = RecordingDao()
        LiveProgress.bytes(rec.dao, begin("a"), 500, 2f)
        LiveProgress.bytes(rec.dao, begin("b"), 700, 3f)
        val running = row("a", DownloadStatus.RUNNING, liveBytes = 100)
        val paused = row("b", DownloadStatus.PAUSED, liveBytes = 100) // just paused: no speed shown
        val other = row("c", DownloadStatus.RUNNING, liveBytes = 42)
        val out = LiveProgress.overlay(listOf(running, paused, other), LiveProgress.live.value)
        assertEquals(500L, out[0].liveBytes)
        assertEquals(2f, out[0].speedMbs)
        assertSame("a row that isn't running is left as it is", paused, out[1])
        assertSame("a row with nothing live is left as it is", other, out[2])
    }

    @Test fun speedAloneKeepsTheDatabasesBytes() {
        LiveProgress.speed(begin("a"), 1.5f)
        val out = LiveProgress.overlay(listOf(row("a", DownloadStatus.RUNNING, liveBytes = 900)), LiveProgress.live.value)
        assertEquals(900L, out[0].liveBytes)
        assertEquals(1.5f, out[0].speedMbs)
    }

    @Test fun bytesReachTheDatabaseFirstThenEvery15Seconds() = runTest {
        val rec = RecordingDao()
        val run = begin("a")
        LiveProgress.bytes(rec.dao, run, 100, 1f)
        now += 5_000
        LiveProgress.bytes(rec.dao, run, 200, 1f)
        now += 9_999
        LiveProgress.bytes(rec.dao, run, 300, 1f)
        assertEquals(listOf("a" to 100L), rec.writes)
        now += 1
        LiveProgress.bytes(rec.dao, run, 400, 1f)
        assertEquals(listOf("a" to 100L, "a" to 400L), rec.writes)
    }

    @Test fun aSavedFilesZeroIsWrittenAtOnce() = runTest {
        // Its bytes are in totalBytes now: the database must not keep adding them on top.
        val rec = RecordingDao()
        val run = begin("a")
        LiveProgress.bytes(rec.dao, run, 100, 1f)
        LiveProgress.bytes(rec.dao, run, 0, 1f)
        assertEquals(listOf("a" to 100L, "a" to 0L), rec.writes)
    }

    @Test fun flushWritesTheLatestBytes() = runTest {
        val rec = RecordingDao()
        val run = begin("a")
        LiveProgress.bytes(rec.dao, run, 100, 1f)
        LiveProgress.bytes(rec.dao, run, 250, 1f)
        LiveProgress.flush(rec.dao, run)
        assertEquals("a" to 250L, rec.writes.last())
    }

    @Test fun endForgetsTheDownload() = runTest {
        val rec = RecordingDao()
        val run = begin("a")
        LiveProgress.bytes(rec.dao, run, 100, 1f)
        LiveProgress.end(run)
        assertEquals(emptyMap<String, LiveProgress.Live>(), LiveProgress.live.value)
    }

    @Test fun anOlderRunWindingDownDoesNothingOnceANewOneBegan() = runTest {
        // Re-enqueued (waiting for the network, Start now) while the old run is still cleaning up.
        val rec = RecordingDao()
        val old = begin("a")
        LiveProgress.bytes(rec.dao, old, 100, 1f)
        val new = begin("a")
        LiveProgress.bytes(rec.dao, new, 10, 2f) // a new run's first bytes are written at once
        LiveProgress.bytes(rec.dao, old, 999, 9f) // a late line from the old run
        LiveProgress.speed(old, 9f)
        LiveProgress.flush(rec.dao, old)
        LiveProgress.end(old)
        assertEquals(listOf("a" to 100L, "a" to 10L), rec.writes)
        assertEquals(LiveProgress.Live(10, 2f), LiveProgress.live.value["a"])
        LiveProgress.end(new)
        assertEquals(null, LiveProgress.live.value["a"])
    }
}
