package com.comfort.app.data

import com.comfort.app.util.OldNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OldNamesTest {
    @Test fun repeatedPosterAndLinkOnlyCaption() = assertEquals(
        "Shitpost_2048 [2107543837467889664].mkv",
        OldNames.tidy("Shitpost_2048 - Shitpost_2048_-_https_-_t.co_cFUYmh8Vc6 [2107543837467889664].mkv"),
    )

    @Test fun repeatedPosterWithCaption() = assertEquals(
        "ITSBIZKIT - New_York_is_not_a_real_place_lol [2107438375510605825].mkv",
        OldNames.tidy("ITSBIZKIT - ITSBIZKIT_-_New_York_is_not_a_real_place_lol [2107438375510605825].mkv"),
    )

    @Test fun posterOnly() = assertEquals(
        "Everxon_Terra [2107413781852848128].mkv",
        OldNames.tidy("Everxon_Terra - Everxon_Terra [2107413781852848128].mkv"),
    )

    @Test fun musicArtistTwice() = assertEquals(
        "Rick_Astley - Never_Gonna_Give_You_Up [dQw4w9WgXcQ].mkv",
        OldNames.tidy("Rick_Astley - Rick_Astley_-_Never_Gonna_Give_You_Up [dQw4w9WgXcQ].mkv"),
    )

    @Test fun siteNameGetsPosterFromLink() = assertEquals(
        "NASA [HS2-6fybsAAwhqp].jpg",
        OldNames.tidy("twitter - twitter [HS2-6fybsAAwhqp].jpg", "NASA"),
    )

    @Test fun keepsDuplicateSuffixAndSidecarExtension() {
        assertEquals("Yog - Hi [1] (1).mp4", OldNames.tidy("Yog - Yog_-_Hi [1] (1).mp4"))
        assertEquals("Yog - Hi [1].info.json", OldNames.tidy("Yog - Yog_-_Hi [1].info.json"))
    }

    @Test fun alreadyTidyIsUnchanged() {
        assertNull(OldNames.tidy("ITSBIZKIT - New York is not a real place lol [abc].mp4"))
        assertEquals("twitter [x].jpg", OldNames.tidy("twitter - twitter [x].jpg"))
        assertNull(OldNames.tidy("no id here.jpg"))
    }

    @Test fun xPosterFromLink() {
        assertEquals("Shitpost_2048", OldNames.xPoster("https://x.com/Shitpost_2048/status/2107682329984405924"))
        assertNull(OldNames.xPoster("https://x.com/i/status/1"))
        assertNull(OldNames.xPoster("https://reddit.com/r/pics"))
    }
}
