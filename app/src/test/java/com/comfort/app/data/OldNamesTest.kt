package com.comfort.app.data

import com.comfort.app.util.OldNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OldNamesTest {
    @Test fun repeatedPosterAndLinkOnlyCaption() = assertEquals(
        "Shitpost_2048 [1eglnp].mkv",
        OldNames.tidy("Shitpost_2048 - Shitpost_2048_-_https_-_t.co_cFUYmh8Vc6 [2107543837467889664].mkv"),
    )

    @Test fun repeatedPosterWithCaption() = assertEquals(
        "ITSBIZKIT - New_York_is_not_a_real_place_lol [rkp5wh].mkv",
        OldNames.tidy("ITSBIZKIT - ITSBIZKIT_-_New_York_is_not_a_real_place_lol [2107438375510605825].mkv"),
    )

    @Test fun posterOnly() = assertEquals(
        "Everxon_Terra [22c10l].mkv",
        OldNames.tidy("Everxon_Terra - Everxon_Terra [2107413781852848128].mkv"),
    )

    @Test fun musicArtistTwice() = assertEquals(
        "Rick_Astley - Never_Gonna_Give_You_Up [dQw4w9WgXcQ].mkv",
        OldNames.tidy("Rick_Astley - Rick_Astley_-_Never_Gonna_Give_You_Up [dQw4w9WgXcQ].mkv"),
    )

    @Test fun siteNameGetsPosterFromLink() = assertEquals(
        "NASA [2lx6ya].jpg",
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

    @Test fun shortIdMatchesTheWrappers() {
        assertEquals("1eglnp", OldNames.shortId("2107543837467889664"))
        assertEquals("2bgyfc", OldNames.shortId("802030041_18445976851198186_8150678025604663036_n"))
        assertEquals("dQw4w9WgXcQ", OldNames.shortId("dQw4w9WgXcQ"))
    }

    @Test fun libraryTitles() {
        assertEquals("Shitpost 2048", OldNames.tidyTitle("Shitpost 2048 - https://t.co/cFUYmh8Vc6"))
        assertEquals("ClipsTubeX", OldNames.tidyTitle("twitter - twitter", "ClipsTubeX"))
        assertEquals("Everxon Terra", OldNames.tidyTitle("Everxon Terra - \uD83D\uDE02"))
        assertNull(OldNames.tidyTitle("Gunna - on one tonight"))
        assertNull(OldNames.tidyTitle("vids that go hard"))
    }

    @Test fun xPosterFromLink() {
        assertEquals("Shitpost_2048", OldNames.xPoster("https://x.com/Shitpost_2048/status/2107682329984405924"))
        assertNull(OldNames.xPoster("https://x.com/i/status/1"))
        assertNull(OldNames.xPoster("https://reddit.com/r/pics"))
    }
}
