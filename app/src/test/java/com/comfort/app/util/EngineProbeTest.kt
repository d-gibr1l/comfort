package com.comfort.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun redirectCheckOnlyGoesThroughAProxyItCanUse() {
        assertEquals(java.net.Proxy.NO_PROXY, EngineProbe.proxyFor(""))
        assertEquals(java.net.Proxy.Type.HTTP, EngineProbe.proxyFor("http://10.0.0.2:8080")?.type())
        assertEquals(java.net.Proxy.Type.SOCKS, EngineProbe.proxyFor("socks5://127.0.0.1:1080")?.type())
        // Never skipped silently: a proxy it can't use means no request at all.
        assertNull(EngineProbe.proxyFor("socks5://user:pass@127.0.0.1:1080"))
        assertNull(EngineProbe.proxyFor("https://proxy.example:443"))
        assertNull(EngineProbe.proxyFor("http://proxy.example"))
    }
}
