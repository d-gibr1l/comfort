package com.comfort.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlKeyTest {
    private fun same(a: String, b: String) = assertEquals("$a vs $b", UrlKey.of(a), UrlKey.of(b))
    private fun differ(a: String, b: String) = assertNotEquals("$a vs $b", UrlKey.of(a), UrlKey.of(b))

    @Test fun xShareTokensAreIgnored() {
        // The copied-link format seen in October 2026: a fresh "a" token every copy.
        same("https://x.com/FearedBuck/status/2114384587770274126?a=HeNY-USjLsvGg58Oubc&s=09",
            "https://x.com/FearedBuck/status/2114384587770274126?a=Qx81-zzPp0Lmn22Abcd&s=46")
        same("https://x.com/FearedBuck/status/2114384587770274126?a=HeNY-USjLsvGg58Oubc&s=09",
            "https://x.com/FearedBuck/status/2114384587770274126")
        same("https://twitter.com/FearedBuck/status/2114384587770274126?s=20&t=abc",
            "https://x.com/i/status/2114384587770274126")
        same("https://mobile.twitter.com/a/status/1/", "https://x.com/i/web/status/1")
        differ("https://x.com/a/status/1", "https://x.com/a/status/2")
    }

    @Test fun otherTrackingIsIgnored() {
        same("https://www.instagram.com/reel/ABC123/?igsh=xyz", "https://instagram.com/reel/ABC123")
        same("https://www.youtube.com/watch?v=jNQXAC9IVRw&si=abc&pp=x", "https://youtube.com/watch?v=jNQXAC9IVRw")
        same("https://example.com/post/5?utm_source=a&utm_medium=b#top", "http://example.com/post/5")
    }

    @Test fun postsAreTheirIdsOnOtherSitesToo() {
        // Real copied links from the Library, each with its share token.
        same("https://www.instagram.com/reel/DaomDuAIuqC/?stkn=MXkxeXhwNHoyNnQzeQ==", "https://instagram.com/reel/DaomDuAIuqC/?igsh=abc")
        same("https://www.instagram.com/p/DdD_uMAicjW/?img_index=3&stkn=MXFvOGxxeDF3YXM3ZQ==", "https://www.instagram.com/p/DdD_uMAicjW/")
        same("https://www.instagram.com/reel/Db_xPxmRgNA/?vrfl=MmR6N2YyazJtMHo0", "https://www.instagram.com/someone/reel/Db_xPxmRgNA")
        differ("https://www.instagram.com/p/DdD_uMAicjW/", "https://www.instagram.com/p/DaomDuAIuqC/")
        same("https://www.tiktok.com/@balleronibolonga/video/7664096922206014733?_r=1&preview_pb=0&sharer_language=en&_d=f34&share_link_id=x",
            "https://tiktok.com/@balleronibolonga/video/7664096922206014733")
        same("https://www.reddit.com/r/pics/comments/1abcde/some_title/?share_id=X&utm_source=share", "https://old.reddit.com/comments/1abcde")
        same("https://open.spotify.com/track/0nj9Bq5sHDiTxSHunhgkFb?si=HXhWoOW5S6WUekPq2nInFw", "https://open.spotify.com/intl-de/track/0nj9Bq5sHDiTxSHunhgkFb")
        differ("https://open.spotify.com/track/0nj9Bq5sHDiTxSHunhgkFb", "https://open.spotify.com/album/0nj9Bq5sHDiTxSHunhgkFb")
    }

    @Test fun meaningfulParametersStay() {
        differ("https://www.youtube.com/watch?v=jNQXAC9IVRw", "https://www.youtube.com/watch?v=ix2sOI40wKc")
        differ("https://www.youtube.com/playlist?list=A", "https://www.youtube.com/playlist?list=B")
        same("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=A", "https://www.youtube.com/watch?list=A&v=jNQXAC9IVRw")
    }

    @Test fun youtubeShareFormsAreOneVideo() {
        same("https://youtu.be/jNQXAC9IVRw?si=abc", "https://www.youtube.com/watch?v=jNQXAC9IVRw")
        same("https://youtube.com/shorts/jNQXAC9IVRw?feature=share", "https://m.youtube.com/watch?v=jNQXAC9IVRw")
        // YouTube Music is downloaded as a song: not the same as the video.
        differ("https://music.youtube.com/watch?v=jNQXAC9IVRw", "https://www.youtube.com/watch?v=jNQXAC9IVRw")
    }

    @Test fun likePatternMatchesEveryFormOfTheLink() {
        fun like(pattern: String, url: String): Boolean {
            val regex = pattern.replace("\\_", "\u0001").replace("\\%", "\u0002")
                .split('%').joinToString(".*") { Regex.escape(it.replace('\u0001', '_').replace('\u0002', '%')) }
            return Regex(regex, RegexOption.IGNORE_CASE).matches(url)
        }
        val x = UrlKey.likePattern("https://x.com/i/status/2114384587770274126?a=Z&s=09")
        assertTrue(like(x, "https://x.com/FearedBuck/status/2114384587770274126?a=HeNY-USjLsvGg58Oubc&s=09"))
        val yt = UrlKey.likePattern("https://youtu.be/ab_cdEFgh-1")
        assertTrue(like(yt, "https://www.youtube.com/watch?v=ab_cdEFgh-1&si=x"))
        assertTrue(like(yt, "https://youtube.com/shorts/ab_cdEFgh-1"))
        val ig = UrlKey.likePattern("https://www.instagram.com/p/DaomDuAIuqC/")
        assertTrue(like(ig, "https://www.instagram.com/reel/DaomDuAIuqC/?stkn=MXkxeXhwNHoyNnQzeQ=="))
    }
}
