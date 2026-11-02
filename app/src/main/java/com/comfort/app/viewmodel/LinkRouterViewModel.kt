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
import com.comfort.app.util.PreviewInfo
import com.comfort.app.util.shouldUsePreviewSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which sheet a link gets: [Listing] while the listing pass decides, then [Preview] (a single
 * video, a song, a playlist — see ListingResult.shouldUsePreviewSheet) or [Picker] (a real
 * multi-item gallery, carrying the listing so the picker doesn't fetch it again). */
sealed interface LinkRoute {
    val url: String
    data class Listing(override val url: String) : LinkRoute
    /** [preloaded]: the preview, when the router already had to fetch it (yt-dlp, after the
     * gallery-dl listing timed out) — the sheet shows it instead of fetching again. */
    data class Preview(override val url: String, val preloaded: PreviewInfo? = null) : LinkRoute
    data class Picker(override val url: String, val listing: ListingResult) : LinkRoute
    data class Browse(override val url: String) : LinkRoute
}

/** Opening a link and starting its download — shared by Home's paste flow (one per activity) and
 * the share sheet (one per share, so a second share starts fresh).
 *
 * Every enqueue here runs in enqueueScope, so it isn't cancelled when the sheet, or the share
 * screen, that asked for it closes; [onDone] runs on the main thread once the row is in the queue. */
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
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        listing?.cancel()
        _route.value = LinkRoute.Listing(trimmed)
        _isDuplicate.value = false
        listing = viewModelScope.launch {
            try {
                launch {
                    try {
                        _isDuplicate.value = DownloadDispatcher.isDuplicate(context, trimmed)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        _isDuplicate.value = false
                    }
                }
                val result = GalleryDlListing.listItemsForSheet(context, trimmed)
                if (result.timedOut && !com.comfort.app.data.VideoSiteRouter.isGalleryDlOnly(trimmed)) {
                    // gallery-dl is stuck (a rate limit, typically), but yt-dlp reaches the site its own
                    // way — a video link still gets its normal preview. Only if yt-dlp can't show
                    // anything either (an image post, or the site really is down) does the picker's
                    // "taking too long" screen come up.
                    val preview = withTimeoutOrNull(YT_DLP_PREVIEW_CAP_MS) { GalleryDlListing.fetchPreviewInfo(context, trimmed, ytDlpOnly = true) }
                    _route.value = if (preview?.hasContent() == true) LinkRoute.Preview(trimmed, preview) else LinkRoute.Picker(trimmed, result)
                    return@launch
                }
                _route.value = if (result.shouldUsePreviewSheet(trimmed)) LinkRoute.Preview(trimmed) else LinkRoute.Picker(trimmed, result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _route.value = LinkRoute.Picker(trimmed, ListingResult(items = emptyList(), errorMessage = e.message ?: getApplication<Application>().getString(com.comfort.app.R.string.failed_to_load_link), timedOut = false))
            }
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
        // A channel's checked videos: each its own download, by its own link (see itemUrls).
        options.itemUrls?.let { links ->
            return@enqueue links.map { link -> enqueueWith(link, options.copy(itemFilter = null, totalItems = 0, itemUrls = null)) }.last()
        }
        enqueueWith(url, options)
    }

    private suspend fun enqueueWith(url: String, options: DownloadOptions): EnqueueResult =
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
            engineOverride = options.engine,
            audioFormat = options.audioFormat,
            audioLanguage = options.audioLanguage,
            subtitleLanguages = options.subtitleLanguages,
            scheduledAt = options.scheduledAt,
            forceDuplicate = true,
        )

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
    fun downloadAll(urls: List<String>, onDone: (duplicates: Int) -> Unit): Job = enqueueScope.launch {
        var duplicates = 0
        urls.forEach { url ->
            if (runEnqueue { DownloadDispatcher.enqueueDownload(context, url, titleFor(url)) } is EnqueueResult.Duplicate) duplicates++
        }
        onDone(duplicates)
    }

    private fun enqueue(onDone: (EnqueueResult) -> Unit, block: suspend () -> EnqueueResult): Job =
        enqueueScope.launch { onDone(runEnqueue(block)) }

    /** [block], with a failure as [EnqueueResult.Failed] instead of an exception: thrown out of
     * the scope, it crashed the app, and its caller (the share sheet) never heard back. */
    private suspend fun runEnqueue(block: suspend () -> EnqueueResult): EnqueueResult = try {
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.e("LinkRouter", "Couldn't queue the download", e)
        EnqueueResult.Failed(e)
    }

    private fun PreviewInfo.hasContent() = title != null || thumbnail != null || streamUrls.isNotEmpty()

    private companion object {
        const val YT_DLP_PREVIEW_CAP_MS = 30_000L

        /** Where downloads are enqueued: not viewModelScope, so the share screen can close the
         * moment one is handed off. Enqueueing can take seconds — a Reddit share link is looked
         * up online to check for a duplicate — and the share screen used to wait it out, a dimmed
         * window over the app it was shared from that took no taps. */
        val enqueueScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    }

    private fun titleFor(url: String) = getApplication<Application>().getString(com.comfort.app.R.string.downloading_from_x, VideoSiteRouter.siteName(url, getApplication<Application>().getString(com.comfort.app.R.string.this_link)))
}

/** The Toast after a one-tap download: duplicates are skipped and recorded under Library >
 * Duplicates (DownloadDispatcher's "Prevent duplicate downloads"). */
fun startedMessage(context: android.content.Context, result: EnqueueResult): String = context.getString(startedMessageRes(result))

/** Which message [startedMessage] shows, as a string resource. */
@androidx.annotation.StringRes
fun startedMessageRes(result: EnqueueResult): Int = when (result) {
    is EnqueueResult.Duplicate -> com.comfort.app.R.string.already_downloaded_see_duplicates
    is EnqueueResult.Failed -> com.comfort.app.R.string.couldnt_start_download
    else -> com.comfort.app.R.string.download_started
}
