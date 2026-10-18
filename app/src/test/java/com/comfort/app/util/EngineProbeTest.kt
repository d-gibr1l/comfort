package com.comfort.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProbeTest {
    @Test
    fun shareLinksThatRedirectMakeYtDlpsNoUnreliable() {
        assertTrue(EngineProbe.ytDlpNoIsUnreliable("https://www.reddit.com/r/funny/s/bJVYWIzraL"))
        assertTrue(EngineProbe.ytDlpNoIsUnreliable("https://reddit.com/u/someone/s/AbCdEf"))
        assertTrue(EngineProbe.ytDlpNoIsUnreliable("https://redd.it/1abcde"))
        assertTrue(EngineProbe.ytDlpNoIsUnreliable("https://pin.it/4AbCdEfGh"))
        assertTrue(EngineProbe.ytDlpNoIsUnreliable("https://www.instagram.com/share/reel/BAbCdEfGh"))
    }

    @Test
    fun realAddressesKeepYtDlpsNo() {
        // A site yt-dlp really doesn't do, and real post addresses (the probe is right there).
        assertFalse(EngineProbe.ytDlpNoIsUnreliable("https://www.pixiv.net/artworks/123456789"))
        assertFalse(EngineProbe.ytDlpNoIsUnreliable("https://www.reddit.com/r/funny/comments/1abcde/title/"))
        assertFalse(EngineProbe.ytDlpNoIsUnreliable("https://v.redd.it/abc123xyz"))
        assertFalse(EngineProbe.ytDlpNoIsUnreliable("https://www.tiktok.com/@user/photo/7300000000000000000"))
    }
}
