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

    /** "num in {1,3,4}" (the app-wide item-filter syntax), or null for "everything" — no list, or
     * nothing deselected. */
    val itemFilter: StateFlow<String?> = combine(_preview, _selectedNums) { preview, selected ->
        val tracks = preview?.tracks.orEmpty()
        if (tracks.isEmpty() || selected.size == tracks.size) null else "num in {${selected.sorted().joinToString(",")}}"
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
            val result = preloaded ?: GalleryDlListing.fetchPreviewInfo(context, url, onStatus = { _status.value = it })
            _preview.value = result
            result?.tracks?.takeIf { it.isNotEmpty() }?.let { tracks -> _selectedNums.value = tracks.map { it.num }.toSet() }
            if (_editedTitle.value == null) result?.title?.let { _editedTitle.value = cleanTrackTitle(it) }
            if (_editedArtist.value == null) result?.artist?.let { _editedArtist.value = cleanArtistName(it) }
            _loading.value = false
        }
        viewModelScope.launch {
            itemFilter.collectLatest { filter -> _isDuplicate.value = DownloadDispatcher.isDuplicate(context, url, filter) }
        }
    }

    fun toggleItem(num: Int) {
        _selectedNums.value = _selectedNums.value.let { if (num in it) it - num else it + num }
    }

    fun toggleAll() {
        val tracks = _preview.value?.tracks.orEmpty()
        _selectedNums.value = if (_selectedNums.value.size == tracks.size) emptySet() else tracks.map { it.num }.toSet()
    }

    fun setEditedTitle(title: String) { _editedTitle.value = title }
    fun setEditedArtist(artist: String) { _editedArtist.value = artist }
}
