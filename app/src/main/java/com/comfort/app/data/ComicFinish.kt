package com.comfort.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A comic download's first Cancel: stop fetching pages and pack the ones already here into the
 * chapter's .cbz (DownloadWorker.packComic), the card saying it's converting meanwhile. A second
 * Cancel while that happens cancels for real, and the pages are kept as they are.
 */
object ComicFinish {
    private val _requested = MutableStateFlow<Set<String>>(emptySet())
    val requested: StateFlow<Set<String>> = _requested.asStateFlow()

    fun request(downloadId: String) = _requested.update { it + downloadId }

    fun clear(downloadId: String) {
        if (downloadId in _requested.value) _requested.update { it - downloadId }
    }

    fun isRequested(downloadId: String) = downloadId in _requested.value
}
