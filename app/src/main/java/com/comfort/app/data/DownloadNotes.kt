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
}
