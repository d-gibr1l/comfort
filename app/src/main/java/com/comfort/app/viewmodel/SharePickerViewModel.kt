package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.GalleryItem
import com.comfort.app.util.ListingResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ListingState { LOADING, LOADED, UNAVAILABLE, ERROR }

/** The gallery item-picker's data for one link: the listing, which items are selected, and
 * whether that selection was already downloaded. Created per sheet (SharePickerScreen scopes it to
 * its own composition), so every share starts fresh. [preloaded] is ShareActivity's own listing
 * pass, reused instead of fetching the same link twice. */
class SharePickerViewModel(
    application: Application,
    val url: String,
    preloaded: ListingResult?,
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    private val _state = MutableStateFlow(ListingState.LOADING)
    val state: StateFlow<ListingState> = _state.asStateFlow()

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    /** The listing's error (needs login, network error, ...) while [state] is ERROR. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Selected items — GalleryItem.num, seeded to every item when the listing arrives. */
    private val _selectedNums = MutableStateFlow<Set<Int>>(emptySet())
    val selectedNums: StateFlow<Set<Int>> = _selectedNums.asStateFlow()

    // Selection is by GalleryItem.num because that's all the download's item filter can express —
    // so items sharing a num (a Wikimedia file page's archived revisions, item 1 of several posts in
    // one listing) are one unit: they're ticked, counted and downloaded together. Everything below
    // compares against the distinct nums, not the item count; comparing nums to items used to show
    // "1 of 4 selected" with all four ticked, and a "Select all" that could never finish.

    /** Items that will download with the current selection. */
    val selectedCount: StateFlow<Int> = combine(_items, _selectedNums) { items, selected -> items.count { it.num in selected } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val allSelected: StateFlow<Boolean> = combine(_items, _selectedNums) { items, selected -> isAll(items, selected) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** "num in {1,3,4}", or null for "the whole gallery" — nothing or everything selected, which
     * is what a plain shared link with no selection would enqueue as. */
    val itemFilter: StateFlow<String?> = combine(_items, _selectedNums) { items, selected ->
        if (selected.isEmpty() || isAll(items, selected)) null else "num in {${selected.sorted().joinToString(",")}}"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether this link with the current selection is already queued, running or finished.
     * Re-checked on every selection change: DownloadDao matches on (url, itemFilter) together, so a
     * previously downloaded 3-item subset is a duplicate only while that same subset is selected. */
    private val _isDuplicate = MutableStateFlow(false)
    val isDuplicate: StateFlow<Boolean> = _isDuplicate.asStateFlow()

    private var listing: Job? = null

    init {
        load(preloaded)
        viewModelScope.launch {
            itemFilter.collectLatest { filter -> _isDuplicate.value = DownloadDispatcher.isDuplicate(context, url, filter) }
        }
    }

    /** Lists the link again — after a login saved new cookies, the same link is worth re-listing
     * rather than leaving the user on the error they just fixed. */
    fun retry() = load(null)

    private fun load(preloaded: ListingResult?) {
        listing?.cancel()
        _state.value = ListingState.LOADING
        _errorMessage.value = null
        listing = viewModelScope.launch {
            val result = preloaded ?: GalleryDlListing.listItemsForSheet(context, url)
            when {
                result.items.isNotEmpty() -> {
                    _items.value = result.items
                    _selectedNums.value = result.items.map { it.num }.toSet()
                    _state.value = ListingState.LOADED
                }
                // A genuine failure (needs login, network error, ...) — surfaced to the user instead
                // of silently falling through to a download that's going to fail the same way a
                // moment later with no explanation.
                result.errorMessage != null -> {
                    _errorMessage.value = result.errorMessage
                    _state.value = ListingState.ERROR
                }
                // A source that can't be listed this way (single-file links, unsupported
                // extractors) falls back to a normal whole-gallery download.
                else -> _state.value = ListingState.UNAVAILABLE
            }
        }
    }

    fun toggleItem(num: Int) {
        _selectedNums.value = _selectedNums.value.let { if (num in it) it - num else it + num }
    }

    fun toggleAll() {
        val items = _items.value
        _selectedNums.value = if (isAll(items, _selectedNums.value)) emptySet() else items.map { it.num }.toSet()
    }

    private fun isAll(items: List<GalleryItem>, selected: Set<Int>) = items.isNotEmpty() && items.all { it.num in selected }
}
