package com.comfort.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreHelperTest {

    @Test
    fun subfolderFor_routesMediaCorrectly() {
        assertEquals("Pictures", MediaStoreHelper.subfolderFor("photo.jpg", "image/jpeg"))
        assertEquals("Pictures", MediaStoreHelper.subfolderFor("graphic.PNG", "image/png"))
        assertEquals("Pictures", MediaStoreHelper.subfolderFor("animation.webp", "image/webp"))
        
        assertEquals("Videos", MediaStoreHelper.subfolderFor("movie.mp4", "video/mp4"))
        assertEquals("Videos", MediaStoreHelper.subfolderFor("clip.mkv", "video/x-matroska"))
        assertEquals("Videos", MediaStoreHelper.subfolderFor("stream.webm", "video/webm"))
        assertEquals("Videos", MediaStoreHelper.subfolderFor("subtitles.srt", "application/x-subrip"))
        assertEquals("Videos", MediaStoreHelper.subfolderFor("captions.vtt", "text/vtt"))

        assertEquals("Music", MediaStoreHelper.subfolderFor("song.mp3", "audio/mpeg"))
        assertEquals("Music", MediaStoreHelper.subfolderFor("track.flac", "audio/flac"))
        assertEquals("Music", MediaStoreHelper.subfolderFor("audio.m4a", "audio/mp4"))
        assertEquals("Music", MediaStoreHelper.subfolderFor("song.lrc", null))
        assertEquals("Music", MediaStoreHelper.subfolderFor("  padded.opus  ", "audio/ogg"))

        assertEquals("Other", MediaStoreHelper.subfolderFor("info.json", "application/json"))
        assertEquals("Other", MediaStoreHelper.subfolderFor("README.txt", "text/plain"))
        assertEquals("Other", MediaStoreHelper.subfolderFor("archive.zip", "application/zip"))
    }

    @Test
    fun mediaSniffer_kindOf_detectsAllCategories() {
        assertEquals(MediaSniffer.Kind.STREAM, MediaSniffer.kindOf("https://example.com/live/playlist.m3u8"))
        assertEquals(MediaSniffer.Kind.STREAM, MediaSniffer.kindOf("https://example.com/manifest.mpd?query=1"))
        
        assertEquals(MediaSniffer.Kind.SEGMENT, MediaSniffer.kindOf("https://example.com/segment12.ts"))
        assertEquals(MediaSniffer.Kind.SEGMENT, MediaSniffer.kindOf("https://example.com/chunk.m4s"))

        assertEquals(MediaSniffer.Kind.VIDEO, MediaSniffer.kindOf("https://example.com/video.mp4"))
        assertEquals(MediaSniffer.Kind.VIDEO, MediaSniffer.kindOf("https://example.com/video.webm#t=10"))

        assertEquals(MediaSniffer.Kind.AUDIO, MediaSniffer.kindOf("https://example.com/sound.mp3"))
        assertEquals(MediaSniffer.Kind.AUDIO, MediaSniffer.kindOf("https://example.com/song.opus"))
        assertEquals(MediaSniffer.Kind.AUDIO, MediaSniffer.kindOf("https://example.com/track.flac"))

        assertEquals(MediaSniffer.Kind.IMAGE, MediaSniffer.kindOf("https://example.com/pic.jpg"))
        assertEquals(MediaSniffer.Kind.IMAGE, MediaSniffer.kindOf("https://example.com/banner.avif"))

        assertNull(MediaSniffer.kindOf("ftp://example.com/video.mp4"))
        assertNull(MediaSniffer.kindOf("https://example.com/page.html"))
    }

    @Test
    fun mediaSniffer_sizeOf_extractsResolution() {
        val fromElement = MediaSniffer.Caught(
            url = "https://example.com/video.mp4",
            kind = MediaSniffer.Kind.VIDEO,
            width = 1920,
            height = 1080,
        )
        assertEquals(1920 to 1080, MediaSniffer.sizeOf(fromElement))
        assertEquals(2073600L, MediaSniffer.pixelsOf(fromElement))

        val fromFilename = MediaSniffer.Caught(
            url = "https://example.com/download_1280x720_preview.mp4",
            kind = MediaSniffer.Kind.VIDEO,
        )
        assertEquals(1280 to 720, MediaSniffer.sizeOf(fromFilename))

        val fromHeight = MediaSniffer.Caught(
            url = "https://example.com/clip_720p.mp4",
            kind = MediaSniffer.Kind.VIDEO,
        )
        assertEquals(1280 to 720, MediaSniffer.sizeOf(fromHeight))
    }

    @Test
    fun mediaSniffer_ranked_filtersAdHostsAndSegments() {
        val items = listOf(
            MediaSniffer.Caught(url = "https://adservice.google.com/ad.mp4", kind = MediaSniffer.Kind.VIDEO, bytes = 500_000),
            MediaSniffer.Caught(url = "https://example.com/segment1.ts", kind = MediaSniffer.Kind.SEGMENT),
            MediaSniffer.Caught(url = "https://example.com/video.mp4", kind = MediaSniffer.Kind.VIDEO, width = 1920, height = 1080, bytes = 5_000_000),
            MediaSniffer.Caught(url = "https://example.com/small_audio.mp3", kind = MediaSniffer.Kind.AUDIO, bytes = 20_000), // Below MIN_MEDIA_BYTES
            MediaSniffer.Caught(url = "https://example.com/good_audio.mp3", kind = MediaSniffer.Kind.AUDIO, bytes = 3_000_000),
        )

        val ranked = MediaSniffer.ranked(items, "https://example.com/watch")
        
        assertEquals(2, ranked.size)
        assertEquals("https://example.com/video.mp4", ranked[0].url)
        assertEquals("https://example.com/good_audio.mp3", ranked[1].url)
    }
}
