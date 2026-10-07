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
        override fun fail(message: String) { ran += "FAIL: $message" }
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
    fun listingOfOnlyVideosSkipsTheGalleryDlPassAndTheProbe() = runTest {
        // A probe saying gallery-dl knows the link would otherwise send it through gallery-dl first.
        val executor = FakeExecutor(mapOf(YT_DLP to 1))
        EnginePlan.planFor(GALLERY_DL, GALLERY_DL, supplementVideo = true, onlyVideos = true).execute(executor)
        assertEquals(listOf("YT_DLP"), executor.ran)
    }

    @Test
    fun onlyVideosDoesNotChangeTheInstaloaderRoute() = runTest {
        val executor = FakeExecutor(mapOf(INSTALOADER to 1))
        EnginePlan.planFor(INSTALOADER, GALLERY_DL, supplementVideo = true, onlyVideos = true).execute(executor)
        assertEquals(listOf("INSTALOADER"), executor.ran)
    }

    @Test
    fun timedOutListingTriesYtDlpFirstAndKeepsGalleryDlForImagePosts() = runTest {
        val video = FakeExecutor(mapOf(YT_DLP to 1))
        EnginePlan.planFor(GALLERY_DL, GALLERY_DL, supplementVideo = false, listingTimedOut = true).execute(video)
        assertEquals(listOf("YT_DLP"), video.ran)

        val images = FakeExecutor(mapOf(GALLERY_DL to 3))
        EnginePlan.planFor(GALLERY_DL, GALLERY_DL, supplementVideo = false, listingTimedOut = true).execute(images)
        assertEquals(listOf("YT_DLP", "GALLERY_DL(excludeVideo=true)"), images.ran)
    }

    @Test
    fun probeThatFailedToRunIsNoReasonToSkipGalleryDl() = runTest {
        val executor = FakeExecutor(probeResult = EngineProbe.Result(galleryDlHasExtractor = null, ytDlpHasExtractor = true))
        assertEquals(listOf("GALLERY_DL(excludeVideo=true)", "YT_DLP"), ranFor(GALLERY_DL, executor = executor))
    }

    private fun choice(vararg order: DownloadEngine, off: Set<DownloadEngine> = emptySet()) =
        EngineChoice(EngineChoice.ORDERABLE.toSet() - off, order.toList())

    private suspend fun ranWith(engine: DownloadEngine, choice: EngineChoice, classic: DownloadEngine = engine, executor: FakeExecutor = FakeExecutor(), images: Boolean = false): List<String> {
        EnginePlan.planFor(engine, classic, supplementVideo = false, choice = choice, hasImageItem = images).execute(executor)
        return executor.ran
    }

    @Test
    fun ytDlpFirstOrderTriesYtDlpThenGalleryDlForImages() = runTest {
        val order = choice(INSTALOADER, YT_DLP, GALLERY_DL)
        assertEquals(listOf("YT_DLP"), ranWith(GALLERY_DL, order, executor = FakeExecutor(mapOf(YT_DLP to 1))))
        assertEquals(listOf("YT_DLP", "GALLERY_DL(excludeVideo=true)"), ranWith(GALLERY_DL, order, executor = FakeExecutor(mapOf(GALLERY_DL to 2))))
        // A mixed post: yt-dlp saved its video, gallery-dl still gets the pictures.
        assertEquals(listOf("YT_DLP", "GALLERY_DL(excludeVideo=true)"), ranWith(GALLERY_DL, order, executor = FakeExecutor(mapOf(YT_DLP to 1)), images = true))
    }

    @Test
    fun galleryDlOffSendsItsLinksToYtDlpAlone() = runTest {
        assertEquals(listOf("YT_DLP"), ranWith(GALLERY_DL, choice(INSTALOADER, GALLERY_DL, YT_DLP, off = setOf(GALLERY_DL))))
    }

    @Test
    fun ytDlpOffLeavesGalleryDlAloneWithItsVideos() = runTest {
        assertEquals(listOf("GALLERY_DL(excludeVideo=false)"), ranWith(GALLERY_DL, choice(INSTALOADER, GALLERY_DL, YT_DLP, off = setOf(YT_DLP))))
    }

    @Test
    fun linksOnlyATurnedOffEngineCanHandleFailWithAReason() = runTest {
        val ytOff = choice(INSTALOADER, GALLERY_DL, YT_DLP, off = setOf(YT_DLP))
        assertEquals(listOf("FAIL: ${EnginePlan.OFF_YT_DLP}"), ranWith(YT_DLP, ytOff))
        assertEquals(listOf("FAIL: ${EnginePlan.OFF_SPOTIFY}"), ranWith(SPOTIFY, ytOff))
        val bothOff = choice(INSTALOADER, GALLERY_DL, YT_DLP, off = setOf(YT_DLP, GALLERY_DL))
        assertEquals(listOf("FAIL: ${EnginePlan.OFF_BOTH}"), ranWith(GALLERY_DL, bothOff))
    }

    @Test
    fun aGalleryDlOnlySiteNeverRunsYtDlp() = runTest {
        val executor = FakeExecutor()
        EnginePlan.planFor(GALLERY_DL, GALLERY_DL, supplementVideo = true, galleryDlOnly = true).execute(executor)
        assertEquals(listOf("GALLERY_DL(excludeVideo=false)"), executor.ran)

        val off = FakeExecutor()
        EnginePlan.planFor(GALLERY_DL, GALLERY_DL, supplementVideo = true, galleryDlOnly = true, choice = choice(INSTALOADER, GALLERY_DL, YT_DLP, off = setOf(GALLERY_DL))).execute(off)
        assertEquals(listOf("FAIL: ${EnginePlan.OFF_GALLERY_DL_ONLY}"), off.ran)
    }

    @Test
    fun instaloaderFollowsItsPlaceInTheOrder() = runTest {
        assertEquals(
            listOf("GALLERY_DL(excludeVideo=true)", "YT_DLP", "INSTALOADER"),
            ranWith(INSTALOADER, choice(GALLERY_DL, YT_DLP, INSTALOADER), classic = GALLERY_DL),
        )
        assertEquals(
            listOf("INSTALOADER", "GALLERY_DL(excludeVideo=true)", "YT_DLP"),
            ranWith(INSTALOADER, choice(INSTALOADER, GALLERY_DL, YT_DLP), classic = GALLERY_DL),
        )
        // With both classic engines off, Instaloader is the whole download.
        assertEquals(
            listOf("INSTALOADER"),
            ranWith(INSTALOADER, choice(INSTALOADER, GALLERY_DL, YT_DLP, off = setOf(GALLERY_DL, YT_DLP)), classic = GALLERY_DL),
        )
    }
}
