package com.comfort.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFeedTest {
    private val sample = """{"title": "Some Channel - Videos", "uploader": "Some Channel", "thumbnail": "https://i.ytimg.com/c.jpg", "webpage_url": "https://www.youtube.com/@some/videos", "entries": [
        {"id": "aaa", "url": "https://www.youtube.com/watch?v=aaa", "title": "Newest", "upload_date": "20261023", "duration": 61.5, "thumbnail": "https://i.ytimg.com/a.jpg"},
        {"id": "bbb", "url": "https://www.youtube.com/watch?v=bbb", "title": null, "upload_date": null, "duration": null, "thumbnail": null}
    ]}"""

    @Test
    fun parse_readsFeedAndEntriesInOrder() {
        val feed = SubscriptionFeed.parse(listOf(sample.replace("\n", ""))).getOrThrow()
        assertEquals("Some Channel - Videos", feed.title)
        assertEquals("Some Channel", feed.uploader)
        assertEquals("https://i.ytimg.com/c.jpg", feed.thumbnail)
        assertEquals("https://www.youtube.com/@some/videos", feed.webpageUrl)
        assertEquals(listOf("aaa", "bbb"), feed.entries.map { it.id })
        val first = feed.entries[0]
        assertEquals("Newest", first.title)
        assertEquals("20261023", first.uploadDate)
        assertEquals(61L, first.durationSeconds)
        val second = feed.entries[1]
        assertNull(second.title)
        assertNull(second.uploadDate)
        assertNull(second.durationSeconds)
        assertNull(second.thumbnail)
    }

    @Test
    fun parse_usesTheLastNonBlankLine() {
        val lines = listOf("WARNING: something on stderr", "{\"not\": \"this\"}", """{"title": "T", "webpage_url": "u", "entries": []}""", "   ", "")
        val feed = SubscriptionFeed.parse(lines).getOrThrow()
        assertEquals("T", feed.title)
        assertTrue(feed.entries.isEmpty())
    }

    @Test
    fun parse_errorObjectFailsWithItsMessage() {
        val result = SubscriptionFeed.parse(listOf("""{"error": "This channel does not exist."}"""))
        assertTrue(result.isFailure)
        assertEquals("This channel does not exist.", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_nonJsonOrNoOutputFails() {
        val notJson = SubscriptionFeed.parse(listOf("Traceback (most recent call last):", "KeyError: 'x'"))
        assertTrue(notJson.isFailure)
        assertEquals("KeyError: 'x'", notJson.exceptionOrNull()?.message)
        assertTrue(SubscriptionFeed.parse(emptyList()).isFailure)
        assertTrue(SubscriptionFeed.parse(listOf("[1, 2]")).isFailure)
    }

    @Test
    fun parse_entryIdFallsBackToUrlAndEntriesWithNeitherAreDropped() {
        val line = """{"title": "P", "webpage_url": "w", "entries": [
            {"url": "https://www.youtube.com/watch?v=xyz"},
            {"id": "onlyid"},
            {"title": "nothing to identify it"},
            "not an object"
        ]}""".replace("\n", "")
        val entries = SubscriptionFeed.parse(listOf(line)).getOrThrow().entries
        assertEquals(2, entries.size)
        assertEquals("https://www.youtube.com/watch?v=xyz", entries[0].id)
        assertEquals("onlyid", entries[1].id)
        assertEquals("https://www.youtube.com/watch?v=onlyid", entries[1].url)
    }

    @Test
    fun parse_titleFallsBackToUploaderThenUrl() {
        assertEquals("Up", SubscriptionFeed.parse(listOf("""{"title": null, "uploader": "Up", "webpage_url": "w", "entries": []}""")).getOrThrow().title)
        assertEquals("w", SubscriptionFeed.parse(listOf("""{"webpage_url": "w", "entries": []}""")).getOrThrow().title)
    }
}
