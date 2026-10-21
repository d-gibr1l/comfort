package com.comfort.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueLinkTest {
    @Test
    fun keepsWhatSaysWhichVideo() {
        assertEquals("youtube.com/watch?v=TD74a5g5rp0", shortQueueLink("https://www.youtube.com/watch?v=TD74a5g5rp0"))
        assertEquals("youtube.com/watch?v=abc&list=PL1", shortQueueLink("https://youtube.com/watch?v=abc&list=PL1&si=xyz"))
    }

    @Test
    fun dropsShareTracking() {
        assertEquals("youtu.be/dQw4w9WgXcQ", shortQueueLink("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("instagram.com/reel/DAbCd", shortQueueLink("https://www.instagram.com/reel/DAbCd/?igsh=abc&utm_source=ig"))
        assertEquals("x.com/user/status/1", shortQueueLink("https://x.com/user/status/1?s=46&t=AbC"))
        assertEquals("tiktok.com/tiktoklite", shortQueueLink("https://www.tiktok.com/tiktoklite"))
    }
}
