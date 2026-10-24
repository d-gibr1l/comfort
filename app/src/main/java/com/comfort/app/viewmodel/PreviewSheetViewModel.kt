package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.ui.main.cleanArtistName
import com.comfort.app.ui.main.cleanTrackTitle
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.PreviewInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The download preview sheet's data for one link: the listing pass's [preview], its live
 * [status] text, which items are selected, the editable song title/artist, and whether the
 * current selection was already downloaded. Created per sheet (DownloadPreviewSheet scopes it to
 * its own composition), so every opening starts fresh, as before. The sheet's own UI state — its
 * slide-in state, which sub-screen is showing — stays in the composable. */
class PreviewSheetViewModel(
    application: Application,
    val url: String,
    /** Already fetched by whoever opened the sheet (LinkRouterViewModel's yt-dlp fallback). */
    preloaded: PreviewInfo? = null,
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    private val _preview = MutableStateFlow<PreviewInfo?>(null)
    /** Everything the listing pass found — the same pass the share picker uses. */
    val preview: StateFlow<PreviewInfo?> = _preview.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Live text from the listing ("Fetching info…", "Found 40 tracks…") while [loading]; null
     * falls back to "Loading…". */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Selected items of a multi-item listing — 1-based TrackPreview.num, seeded to every item
     * when the listing arrives. */
    private val _selectedNums = MutableStateFlow<Set<Int>>(emptySet())
    val selectedNums: StateFlow<Set<Int>> = _selectedNums.asStateFlow()

    /** A YouTube channel: listed a page at a time, newest first, with nothing checked to start —
     * a channel has hundreds of videos, and one tap on Download queued all of them. */
    val isChannel: Boolean = com.comfort.app.data.VideoSiteRouter.isYouTubeChannel(url)

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /** "num in {1,3,4}" (the app-wide item-filter syntax), or null for "everything" — no list, or
     * nothing deselected. Never "everything" for a channel: only part of it is listed, and
     * "everything" would download the whole channel, not the checked videos. */
    val itemFilter: StateFlow<String?> = combine(_preview, _selectedNums) { preview, selected ->
        val tracks = preview?.tracks.orEmpty()
        if (tracks.isEmpty() || (!isChannel && selected.size == tracks.size)) null
        else "num in {${selected.sorted().joinToString(",")}}"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether this link — with the current selection — is already queued, running or finished:
     * the Download button reads "Redownload" then. Re-checked when the selection changes, since a
     * subset and the whole album are different downloads. */
    private val _isDuplicate = MutableStateFlow(false)
    val isDuplicate: StateFlow<Boolean> = _isDuplicate.asStateFlow()

    // A song's editable title/artist, seeded once from the listing (cleaned of upload clutter), so a
    // user's edit is never overwritten by a later preview update.
    private val _editedTitle = MutableStateFlow<String?>(null)
    val editedTitle: StateFlow<String?> = _editedTitle.asStateFlow()
    private val _editedArtist = MutableStateFlow<String?>(null)
    val editedArtist: StateFlow<String?> = _editedArtist.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val result = preloaded ?: GalleryDlListing.fetchPreviewInfo(
                    context, url, onStatus = { _status.value = it },
                    limit = if (isChannel) CHANNEL_PAGE else null,
                )
                _preview.value = result
                if (!isChannel) {
                    result?.tracks?.takeIf { it.isNotEmpty() }?.let { tracks -> _selectedNums.value = tracks.map { it.num }.toSet() }
                }
                if (_editedTitle.value == null) result?.title?.let { _editedTitle.value = cleanTrackTitle(it).trim() }
                if (_editedArtist.value == null) result?.artist?.let { _editedArtist.value = cleanArtistName(it).trim() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _status.value = context.getString(com.comfort.app.R.string.failed_to_load_preview)
            } finally {
                _loading.value = false
            }
        }
        viewModelScope.launch {
            itemFilter.collectLatest { filter ->
                try {
                    _isDuplicate.value = DownloadDispatcher.isDuplicate(context, url, filter)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _isDuplicate.value = false
                }
            }
        }
    }

    /** The next [CHANNEL_PAGE] of a channel's videos, or all the rest ([all]). The list is fetched
     * again from the top (a flat listing, so mostly paging through YouTube's list); entries keep
     * their numbers, so what's checked stays checked, and the new ones come in unchecked. */
    fun loadMore(all: Boolean) {
        val current = _preview.value ?: return
        if (_loadingMore.value || !current.hasMore) return
        _loadingMore.value = true
        viewModelScope.launch {
            try {
                val more = GalleryDlListing.fetchPreviewInfo(
                    context, url,
                    limit = if (all) null else current.tracks.size + CHANNEL_PAGE,
                )
                // A failed fetch keeps what's shown (and the button, to try again).
                if (more != null && more.tracks.size >= current.tracks.size) {
                    _preview.value = more.copy(hasMore = if (all) false else more.hasMore && more.tracks.size > current.tracks.size)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                _loadingMore.value = false
            }
        }
    }

    fun toggleItem(num: Int) {
        if (num <= 0) return
        _selectedNums.update { current -> if (num in current) current - num else current + num }
    }

    fun toggleAll() {
        val tracks = _preview.value?.tracks.orEmpty()
        _selectedNums.update { current -> if (current.size == tracks.size) emptySet() else tracks.map { it.num }.toSet() }
    }

    private companion object {
        /** How many of a channel's videos a page lists. */
        const val CHANNEL_PAGE = 50
    }

    fun setEditedTitle(title: String) { _editedTitle.value = title }
    fun setEditedArtist(artist: String) { _editedArtist.value = artist }
}
