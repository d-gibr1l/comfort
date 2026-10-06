package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.EnqueueResult
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.ui.main.DownloadOptions
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.ListingResult
import com.comfort.app.util.shouldUsePreviewSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which sheet a link gets: [Listing] while the listing pass decides, then [Preview] (a single
 * video, a song, a playlist — see ListingResult.shouldUsePreviewSheet) or [Picker] (a real
 * multi-item gallery, carrying the listing so the picker doesn't fetch it again). */
sealed interface LinkRoute {
    val url: String
    data class Listing(override val url: String) : LinkRoute
    data class Preview(override val url: String) : LinkRoute
    data class Picker(override val url: String, val listing: ListingResult) : LinkRoute
}

/** Opening a link and starting its download — shared by Home's paste flow (one per activity) and
 * the share sheet (one per share, so a second share starts fresh).
 *
 * Every enqueue here goes through viewModelScope, so it isn't cancelled when the sheet that asked
 * for it closes; [onDone] runs on the main thread once the row is in the queue. */
class LinkRouterViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    private val _route = MutableStateFlow<LinkRoute?>(null)
    val route: StateFlow<LinkRoute?> = _route.asStateFlow()

    /** Whether [route]'s link (as a whole) was already downloaded — checked off the url alone, so
     * the loading sheet's "Download now" can say "Redownload" before the listing resolves. */
    private val _isDuplicate = MutableStateFlow(false)
    val isDuplicate: StateFlow<Boolean> = _isDuplicate.asStateFlow()

    private var listing: Job? = null

    /** Lists [url] to decide between the preview sheet and the item picker — so a pasted or shared
     * multi-image gallery gets a picker instead of a single-video card with nothing to pick. */
    fun open(url: String) {
        listing?.cancel()
        _route.value = LinkRoute.Listing(url)
        _isDuplicate.value = false
        listing = viewModelScope.launch {
            launch { _isDuplicate.value = DownloadDispatcher.isDuplicate(context, url) }
            val result = GalleryDlListing.listItemsForSheet(context, url)
            _route.value = if (result.shouldUsePreviewSheet(url)) LinkRoute.Preview(url) else LinkRoute.Picker(url, result)
        }
    }

    /** Closes whichever sheet is up, cancelling a listing still in flight. */
    fun close() {
        listing?.cancel()
        _route.value = null
    }

    /** The preview sheet's Download. forceDuplicate: its button already said "Redownload" if this
     * was one, so tapping it is the confirmation. */
    fun download(url: String, options: DownloadOptions, onDone: (EnqueueResult) -> Unit = {}): Job = enqueue(onDone) {
        DownloadDispatcher.enqueueDownload(
            context = context,
            url = url,
            title = titleFor(url),
            itemFilter = options.itemFilter,
            totalItems = options.totalItems,
            videoQuality = options.quality,
            clipRange = options.clipRange,
            extraCommands = options.extraCommands,
            outputFormat = options.outputFormat,
            filenameTemplate = options.filenameTemplate,
            saveThumbnail = options.saveThumbnail,
            overrideTitle = options.overrideTitle,
            overrideArtist = options.overrideArtist,
            forceDuplicate = true,
        )
    }

    /** The item picker's Download, or a plain whole-link download (the Download/Instant buttons,
     * the loading sheet's "Download now") with the defaults from Settings. */
    fun download(
        url: String,
        itemFilter: String? = null,
        totalItems: Int = 0,
        videoQuality: VideoQuality? = null,
        forceDuplicate: Boolean = false,
        onDone: (EnqueueResult) -> Unit = {},
    ): Job = enqueue(onDone) {
        DownloadDispatcher.enqueueDownload(context, url, titleFor(url), itemFilter, totalItems, videoQuality, forceDuplicate = forceDuplicate)
    }

    /** Several shared links at once — each enqueued with the defaults; [onDone] gets how many were
     * already downloaded. */
    fun downloadAll(urls: List<String>, onDone: (duplicates: Int) -> Unit): Job = viewModelScope.launch {
        var duplicates = 0
        urls.forEach { url ->
            if (DownloadDispatcher.enqueueDownload(context, url, titleFor(url)) is EnqueueResult.Duplicate) duplicates++
        }
        onDone(duplicates)
    }

    private fun enqueue(onDone: (EnqueueResult) -> Unit, block: suspend () -> EnqueueResult): Job =
        viewModelScope.launch { onDone(block()) }

    private fun titleFor(url: String) = "Downloading from ${VideoSiteRouter.siteName(url)}"
}

/** The Toast after a one-tap download: duplicates are skipped and recorded under Library >
 * Duplicates (DownloadDispatcher's "Prevent duplicate downloads"). */
fun startedMessage(result: EnqueueResult): String =
    if (result is EnqueueResult.Duplicate) "Already downloaded — see Library > Duplicates" else "Download started"
