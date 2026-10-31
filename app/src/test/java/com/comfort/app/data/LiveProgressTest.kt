package com.comfort.app.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

class LiveProgressTest {
    private val ids = listOf("a", "b", "c")

    @After fun tearDown() = ids.forEach(LiveProgress::clear)

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
        LiveProgress.bytes(rec.dao, "a", 500, 2f)
        LiveProgress.bytes(rec.dao, "b", 700, 3f)
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
        LiveProgress.speed("a", 1.5f)
        val out = LiveProgress.overlay(listOf(row("a", DownloadStatus.RUNNING, liveBytes = 900)), LiveProgress.live.value)
        assertEquals(900L, out[0].liveBytes)
        assertEquals(1.5f, out[0].speedMbs)
    }

    @Test fun bytesReachTheDatabaseFirstThenOnlyNowAndThen() = runTest {
        val rec = RecordingDao()
        LiveProgress.bytes(rec.dao, "a", 100, 1f)
        LiveProgress.bytes(rec.dao, "a", 200, 1f)
        LiveProgress.bytes(rec.dao, "a", 300, 1f)
        assertEquals(listOf("a" to 100L), rec.writes)
        // A file just saved: its bytes are in totalBytes now, so 0 is written at once.
        LiveProgress.bytes(rec.dao, "a", 0, 1f)
        assertEquals(listOf("a" to 100L, "a" to 0L), rec.writes)
    }

    @Test fun flushWritesTheLatestBytes() = runTest {
        val rec = RecordingDao()
        LiveProgress.bytes(rec.dao, "a", 100, 1f)
        LiveProgress.bytes(rec.dao, "a", 250, 1f)
        LiveProgress.flush(rec.dao, "a")
        assertEquals("a" to 250L, rec.writes.last())
    }

    @Test fun clearForgetsTheDownload() = runTest {
        val rec = RecordingDao()
        LiveProgress.bytes(rec.dao, "a", 100, 1f)
        LiveProgress.clear("a")
        assertEquals(emptyMap<String, LiveProgress.Live>(), LiveProgress.live.value)
        // A new run writes its first bytes straight away again.
        LiveProgress.bytes(rec.dao, "a", 10, 1f)
        assertEquals("a" to 10L, rec.writes.last())
    }
}
