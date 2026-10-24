package com.comfort.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadLogTest {
    @Test
    fun blanksAProxysLogin() {
        assertEquals(
            "proxy socks5h://***:***@10.0.0.5:1080",
            DownloadLog.redact("proxy socks5h://alice:s3cret@10.0.0.5:1080"),
        )
    }

    @Test
    fun blanksCookieAndAuthorisationHeaders() {
        val out = DownloadLog.redact(
            "10-23 07:49:03 D DownloadEngine: Cookie: sessionid=abc123; csrftoken=xyz\n" +
                "Authorization: Bearer eyJhbGciOi.long.token\n",
        )
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("xyz"))
        assertFalse(out.contains("eyJhbGciOi"))
        assertEquals("Authorization: ***", out.lines()[1])
    }

    @Test
    fun blanksSessionAndTokenFields() {
        val out = DownloadLog.redact("""{"sessionid": "abc123", "token":"t0k", "password=hunter2"}""")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("t0k"))
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun keepsTheLinksDownloaded() {
        val line = "start https://x.com/someone/status/1234567890"
        assertEquals(line, DownloadLog.redact(line))
    }
}
