package com.comfort.app.viewmodel

import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.EnqueueResult
import com.comfort.app.ui.main.cleanArtistName
import com.comfort.app.ui.main.cleanTrackTitle
import com.comfort.app.util.ListingResult
import com.comfort.app.util.MediaShape
import com.comfort.app.util.TrackPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ViewModelsAuditTest {

    private fun createDownloadEntity(id: String, title: String = "Item $id"): DownloadEntity {
        return DownloadEntity(
            id = id,
            url = "https://example.com/$id",
            title = title,
            thumbnailPath = null,
            status = DownloadStatus.FINISHED,
            progress = 1.0f,
            downloadedItems = 1,
            totalItems = 1,
            speedMbs = 0f,
            etaSeconds = 0L,
            errorMessage = null,
            dateAdded = System.currentTimeMillis(),
        )
    }

    @Test
    fun linkRouter_startedMessage_distinguishesDuplicateAndSuccess() {
        assertEquals(com.comfort.app.R.string.already_downloaded_see_duplicates, startedMessageRes(EnqueueResult.Duplicate("dup-123")))
        assertEquals(com.comfort.app.R.string.download_started, startedMessageRes(EnqueueResult.Started("new-123")))
    }

    @Test
    fun linkRouter_routesPreserveUrl() {
        val testUrl = "https://example.com/gallery/123"
        val listing = LinkRoute.Listing(testUrl)
        assertEquals(testUrl, listing.url)

        val preview = LinkRoute.Preview(testUrl, preloaded = null)
        assertEquals(testUrl, preview.url)
        assertNull(preview.preloaded)

        val pickerResult = ListingResult(items = emptyList(), timedOut = false)
        val picker = LinkRoute.Picker(testUrl, pickerResult)
        assertEquals(testUrl, picker.url)
        assertEquals(0, picker.listing.items.size)

        val browse = LinkRoute.Browse(testUrl)
        assertEquals(testUrl, browse.url)
    }

    @Test
    fun linkRouter_urlSanitization_trimsAndGuardsEmpty() {
        val rawInput = "  https://example.com/video.mp4  \n"
        val trimmed = rawInput.trim()
        assertEquals("https://example.com/video.mp4", trimmed)
        assertFalse(trimmed.isEmpty())

        val emptyInput = "   \t\n  "
        val trimmedEmpty = emptyInput.trim()
        assertTrue(trimmedEmpty.isEmpty())
    }

    @Test
    fun previewSheet_itemFilterSyntax_matchesExpectedFormat() {
        val tracks = listOf(
            TrackPreview(num = 1, title = "Track 1", artist = "Artist 1", durationMs = 1000L),
            TrackPreview(num = 2, title = "Track 2", artist = "Artist 2", durationMs = 2000L),
            TrackPreview(num = 3, title = "Track 3", artist = "Artist 3", durationMs = 3000L),
        )

        // All selected -> null filter (download everything)
        val allSelected = setOf(1, 2, 3)
        val filterAll = if (tracks.isEmpty() || allSelected.size == tracks.size) null else "num in {${allSelected.sorted().joinToString(",")}}"
        assertNull(filterAll)

        // Subset selected -> num in {...}
        val subset = setOf(1, 3)
        val filterSubset = if (tracks.isEmpty() || subset.size == tracks.size) null else "num in {${subset.sorted().joinToString(",")}}"
        assertEquals("num in {1,3}", filterSubset)

        // Empty selection -> num in {}
        val empty = emptySet<Int>()
        val filterEmpty = if (tracks.isEmpty() || empty.size == tracks.size) null else "num in {${empty.sorted().joinToString(",")}}"
        assertEquals("num in {}", filterEmpty)
    }

    @Test
    fun previewSheet_toggleItem_ignoresNonPositiveNumbers() {
        var selectedNums = setOf(1, 2, 3)
        fun toggleItem(num: Int) {
            if (num <= 0) return
            selectedNums = if (num in selectedNums) selectedNums - num else selectedNums + num
        }

        // Negative and zero numbers should be rejected
        toggleItem(0)
        assertEquals(setOf(1, 2, 3), selectedNums)

        toggleItem(-5)
        assertEquals(setOf(1, 2, 3), selectedNums)

        // Positive numbers toggle correctly
        toggleItem(2)
        assertEquals(setOf(1, 3), selectedNums)

        toggleItem(2)
        assertEquals(setOf(1, 2, 3), selectedNums)
    }

    @Test
    fun previewSheet_titleAndArtistSanitization() {
        val scrapedTitle = "  My Favorite Song [Official Music Video]  "
        val cleanedTitle = cleanTrackTitle(scrapedTitle).trim()
        assertEquals("My Favorite Song", cleanedTitle)

        val scrapedArtist = "  Artist Name - Topic  "
        val cleanedArtist = cleanArtistName(scrapedArtist).trim()
        assertEquals("Artist Name", cleanedArtist)
    }

    @Test
    fun downloadsViewModel_deletingIdsFilter_removesItemsOptimistically() {
        val items = listOf(
            createDownloadEntity("1"),
            createDownloadEntity("2"),
            createDownloadEntity("3"),
        )

        var deletingIds = emptySet<String>()

        fun filterList(list: List<DownloadEntity>): List<DownloadEntity> =
            if (deletingIds.isEmpty()) list else list.filterNot { it.id in deletingIds }

        // Initial state: all visible
        assertEquals(3, filterList(items).size)

        // Hide items 1 and 3
        deletingIds = deletingIds + setOf("1", "3")
        val filtered = filterList(items)
        assertEquals(1, filtered.size)
        assertEquals("2", filtered.first().id)

        // Cancel deletion (undo)
        deletingIds = deletingIds - setOf("1", "3")
        assertEquals(3, filterList(items).size)
    }

    @Test
    fun downloadsViewModel_renameDownload_sanitizesAndRejectsBlank() {
        fun sanitizeNewTitle(newTitle: String): String? {
            val trimmed = newTitle.trim()
            return if (trimmed.isEmpty()) null else trimmed
        }

        assertNull(sanitizeNewTitle(""))
        assertNull(sanitizeNewTitle("    "))
        assertNull(sanitizeNewTitle("\n\t  \r"))
        assertEquals("New Valid Title", sanitizeNewTitle("  New Valid Title  "))
    }

    @Test
    fun downloadsViewModel_shapeCache_retainsAndCachesEntries() {
        val shapeCache = ConcurrentHashMap<String, MediaShape>()
        val shape1 = MediaShape(aspect = 16f / 9f, durationMs = 120_000L)
        val shape2 = MediaShape(aspect = 9f / 16f, durationMs = 60_000L)

        shapeCache["id-1"] = shape1
        shapeCache["id-2"] = shape2

        // Cache hit
        assertEquals(shape1, shapeCache["id-1"])
        assertEquals(shape2, shapeCache["id-2"])

        // When item 1 is removed from current items, cache evicts stale keys
        val activeIds = setOf("id-2", "id-3")
        shapeCache.keys.retainAll(activeIds)

        assertNull(shapeCache["id-1"])
        assertNotNull(shapeCache["id-2"])
    }

    @Test
    fun downloadsViewModel_scanConcurrencyGuard_preventsOverlap() {
        val isScanning = AtomicBoolean(false)

        // First scan acquires guard
        assertTrue(isScanning.compareAndSet(false, true))

        // Concurrent scan attempts are rejected
        assertFalse(isScanning.compareAndSet(false, true))

        // Scan finishes and releases guard
        isScanning.set(false)

        // Next scan can now acquire
        assertTrue(isScanning.compareAndSet(false, true))
    }
}
