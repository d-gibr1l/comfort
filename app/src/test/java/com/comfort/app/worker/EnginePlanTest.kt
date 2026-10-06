package com.comfort.app.worker

import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.DownloadEngine.GALLERY_DL
import com.comfort.app.data.DownloadEngine.INSTALOADER
import com.comfort.app.data.DownloadEngine.SPOTIFY
import com.comfort.app.data.DownloadEngine.YT_DLP
import com.comfort.app.util.EngineProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EnginePlanTest {
    /** Records which engines ran; each engine "saves" the number of files given in [saves]. */
    private class FakeExecutor(
        private val saves: Map<DownloadEngine, Int> = emptyMap(),
        private val probeResult: EngineProbe.Result = EngineProbe.Result(galleryDlHasExtractor = true, ytDlpHasExtractor = true),
        private val stopAfter: DownloadEngine? = null,
    ) : EngineExecutor {
        val ran = mutableListOf<String>()
        override var savedCount = 0
        override var isStopped = false
        override suspend fun run(engine: DownloadEngine, excludeVideo: Boolean) {
            ran += if (engine == GALLERY_DL) "$engine(excludeVideo=$excludeVideo)" else engine.name
            savedCount += saves[engine] ?: 0
            if (engine == stopAfter) isStopped = true
        }
        override suspend fun probe() = probeResult
    }

    private suspend fun ranFor(engine: DownloadEngine, classic: DownloadEngine = engine, video: Boolean = false, executor: FakeExecutor): List<String> {
        EnginePlan.planFor(engine, classic, video).execute(executor)
        return executor.ran
    }

    @Test
    fun singleEngines() = runTest {
        assertEquals(listOf("YT_DLP"), ranFor(YT_DLP, executor = FakeExecutor()))
        assertEquals(listOf("SPOTIFY"), ranFor(SPOTIFY, executor = FakeExecutor()))
    }

    @Test
    fun instaloaderThatSavesSomethingIsTheWholeDownload() = runTest {
        assertEquals(listOf("INSTALOADER"), ranFor(INSTALOADER, GALLERY_DL, video = true, executor = FakeExecutor(mapOf(INSTALOADER to 3))))
    }

    @Test
    fun instaloaderThatSavesNothingFallsBackToTheClassicRoute() = runTest {
        assertEquals(
            listOf("INSTALOADER", "GALLERY_DL(excludeVideo=true)", "YT_DLP"),
            ranFor(INSTALOADER, GALLERY_DL, video = true, executor = FakeExecutor(mapOf(GALLERY_DL to 2))),
        )
    }

    @Test
    fun stoppedInstaloaderDoesNotFallBack() = runTest {
        assertEquals(listOf("INSTALOADER"), ranFor(INSTALOADER, GALLERY_DL, executor = FakeExecutor(stopAfter = INSTALOADER)))
    }

    @Test
    fun galleryDlWithoutVideoStopsWhenItSavedSomething() = runTest {
        assertEquals(listOf("GALLERY_DL(excludeVideo=true)"), ranFor(GALLERY_DL, executor = FakeExecutor(mapOf(GALLERY_DL to 4))))
    }

    @Test
    fun galleryDlWithVideoGetsTheYtDlpSupplement() = runTest {
        assertEquals(
            listOf("GALLERY_DL(excludeVideo=true)", "YT_DLP"),
            ranFor(GALLERY_DL, video = true, executor = FakeExecutor(mapOf(GALLERY_DL to 4))),
        )
    }

    @Test
    fun galleryDlThatSavedNothingFallsBackToYtDlp() = runTest {
        assertEquals(listOf("GALLERY_DL(excludeVideo=true)", "YT_DLP"), ranFor(GALLERY_DL, executor = FakeExecutor()))
    }

    @Test
    fun stoppedGalleryDlRunsNothingAfter() = runTest {
        assertEquals(listOf("GALLERY_DL(excludeVideo=true)"), ranFor(GALLERY_DL, video = true, executor = FakeExecutor(stopAfter = GALLERY_DL)))
    }

    @Test
    fun probeThatOnlyYtDlpKnowsTheLinkSkipsGalleryDl() = runTest {
        val executor = FakeExecutor(probeResult = EngineProbe.Result(galleryDlHasExtractor = false, ytDlpHasExtractor = true))
        assertEquals(listOf("YT_DLP"), ranFor(GALLERY_DL, executor = executor))
    }

    @Test
    fun probeThatFailedToRunIsNoReasonToSkipGalleryDl() = runTest {
        val executor = FakeExecutor(probeResult = EngineProbe.Result(galleryDlHasExtractor = null, ytDlpHasExtractor = true))
        assertEquals(listOf("GALLERY_DL(excludeVideo=true)", "YT_DLP"), ranFor(GALLERY_DL, executor = executor))
    }
}
