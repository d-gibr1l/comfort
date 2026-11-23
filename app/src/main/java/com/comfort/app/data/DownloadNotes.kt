package com.comfort.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Short live notes about a running download that the database has no column for — today, an
 * engine waiting out a site's rate limit ("Rate-limited by Reddit — waiting until 21:19"), which
 * otherwise looks like a frozen card. In memory only: DownloadWorker runs in the app's process,
 * sets a note when the engine reports the wait and clears it when data moves or the run ends. */
object DownloadNotes {
    private val _notes = MutableStateFlow<Map<String, String>>(emptyMap())
    val notes: StateFlow<Map<String, String>> = _notes.asStateFlow()

    fun set(downloadId: String, note: String) = _notes.update { it + (downloadId to note) }

    fun clear(downloadId: String) {
        if (downloadId in _notes.value) _notes.update { it - downloadId }
    }

    /** The item a multi-item download is on now ("Artist - Song" of a playlist), for its card. */
    private val _items = MutableStateFlow<Map<String, String>>(emptyMap())
    val items: StateFlow<Map<String, String>> = _items.asStateFlow()

    fun setItem(downloadId: String, title: String) = _items.update { it + (downloadId to title) }

    fun clearItem(downloadId: String) {
        if (downloadId in _items.value) _items.update { it - downloadId }
    }
}
