package com.comfort.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRulesTest {
    @After
    fun reset() = VideoSiteRouter.applySiteRules(emptySet(), emptySet(), emptySet())

    @Test
    fun defaultsAreUnchangedWithNoRules() {
        assertEquals(DownloadEngine.YT_DLP, VideoSiteRouter.classify("https://www.youtube.com/watch?v=x"))
        assertEquals(DownloadEngine.GALLERY_DL, VideoSiteRouter.classify("https://www.reddit.com/r/aww/comments/1/x/"))
        assertEquals(DownloadEngine.SPOTIFY, VideoSiteRouter.classify("https://open.spotify.com/track/1"))
    }

    @Test
    fun anAddedSiteGoesToItsEngine() {
        VideoSiteRouter.applySiteRules(ytDlp = setOf("reddit.com"), galleryDl = emptySet(), removedDefaults = emptySet())
        assertEquals(DownloadEngine.YT_DLP, VideoSiteRouter.classify("https://old.reddit.com/r/aww/comments/1/x/"))
        assertTrue("reddit.com" in VideoSiteRouter.ytDlpSites())
    }

    @Test
    fun aBuiltInVideoSiteCanBeMovedToGalleryDlOrRemoved() {
        VideoSiteRouter.applySiteRules(ytDlp = emptySet(), galleryDl = setOf("vimeo.com"), removedDefaults = setOf("vimeo.com"))
        assertEquals(DownloadEngine.GALLERY_DL, VideoSiteRouter.classify("https://vimeo.com/123"))
        assertFalse("vimeo.com" in VideoSiteRouter.ytDlpSites())

        VideoSiteRouter.applySiteRules(ytDlp = emptySet(), galleryDl = emptySet(), removedDefaults = setOf("twitch.tv"))
        assertEquals(DownloadEngine.GALLERY_DL, VideoSiteRouter.classify("https://www.twitch.tv/videos/1"))
    }

    @Test
    fun theGalleryDlListMeansGalleryDlOnly() {
        VideoSiteRouter.applySiteRules(ytDlp = emptySet(), galleryDl = setOf("reddit.com"), removedDefaults = emptySet())
        assertTrue(VideoSiteRouter.isGalleryDlOnly("https://www.reddit.com/r/aww/comments/1/x/"))
        assertFalse(VideoSiteRouter.isGalleryDlOnly("https://x.com/a/status/1"))
    }

    @Test
    fun spotifyStaysOnItsOwnEngine() {
        VideoSiteRouter.applySiteRules(ytDlp = emptySet(), galleryDl = setOf("spotify.com"), removedDefaults = emptySet())
        assertEquals(DownloadEngine.SPOTIFY, VideoSiteRouter.classify("https://open.spotify.com/track/1"))
    }

    @Test
    fun typedSitesAndPastedLinksBecomeHosts() {
        assertEquals("tiktok.com", VideoSiteRouter.siteFromInput("https://www.tiktok.com/@x/video/1"))
        assertEquals("tiktok.com", VideoSiteRouter.siteFromInput("  TikTok.com "))
        assertEquals("m.example.org", VideoSiteRouter.siteFromInput("m.example.org/page"))
        assertNull(VideoSiteRouter.siteFromInput("not a site"))
        assertNull(VideoSiteRouter.siteFromInput("localhost"))
    }
}
