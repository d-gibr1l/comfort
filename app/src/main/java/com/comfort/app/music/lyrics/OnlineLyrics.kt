package com.comfort.app.music.lyrics

import com.comfort.app.music.Lyrics
import com.comfort.app.music.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The player's lyrics from the web (Mixify's sources, [LyricsRepository]), for a song whose own
 * lyrics aren't timed word by word, and which lyrics are showing (the Lyrics source sheet picks).
 * Each song's answers are kept for the session.
 */
object OnlineLyrics {
    /** How a source fared for a song. */
    enum class State { Searching, Found, NotFound }

    /** A song's sources: how each fared, and the lyrics of each that had them. */
    data class Status(
        val songId: String?,
        val sources: Map<LyricsSource, State> = emptyMap(),
        val found: Map<LyricsSource, Lyrics> = emptyMap(),
    )

    /** What's showing for a song: its own lyrics ([source] null) or a source's. */
    data class Shown(val songId: String, val lyrics: Lyrics?, val source: LyricsSource?)

    private val _status = MutableStateFlow(Status(null))
    /** The sources of the song asked about last. */
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _shown = MutableStateFlow<Shown?>(null)
    /** The lyrics showing for the song asked about last (null: not settled yet). */
    val shown: StateFlow<Shown?> = _shown.asStateFlow()

    // The song's own lyrics, for the sheet's "In the song".
    private val _own = MutableStateFlow<Pair<String, Lyrics?>?>(null)
    val own: StateFlow<Pair<String, Lyrics?>?> = _own.asStateFlow()

    private val statuses = object : LinkedHashMap<String, Status>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Status>?) = size > 30
    }
    private val picks = HashMap<String, Shown>()

    /** [song]'s lyrics to show: [own] (its own, from its tags) unless a source's are better (timed
     * where they aren't, or word by word), or the one picked in the sheet. [durationMs]: the
     * song's length, which the sources match on; [lookUp] off: its own only. */
    suspend fun settle(song: Song, own: Lyrics?, durationMs: Long, lookUp: Boolean) {
        _own.value = song.id to own
        synchronized(picks) { picks[song.id] }?.let { _shown.value = it; _status.value = statusOf(song.id); return }
        _shown.value = Shown(song.id, own, null)
        if (!lookUp || own?.wordSynced == true) { _status.value = statusOf(song.id); return }
        val cached = synchronized(statuses) { statuses[song.id] }
        val best = if (cached != null) { _status.value = cached; bestOf(cached) } else search(song, durationMs, null)
        if (best != null && (own == null || best.second.wordSynced || (best.second.synced && !own.synced))) {
            if (_shown.value?.songId == song.id) _shown.value = Shown(song.id, best.second, best.first)
        }
    }

    /** Shows [source]'s lyrics for [song] (null: its own), searching that source first if needed. */
    suspend fun pick(song: Song, source: LyricsSource?, durationMs: Long) {
        if (source == null) {
            val own = _own.value?.takeIf { it.first == song.id }?.second
            Shown(song.id, own, null).also { synchronized(picks) { picks[song.id] = it }; _shown.value = it }
            return
        }
        val have = statusOf(song.id).found[source] ?: run {
            search(song, durationMs, source)
            statusOf(song.id).found[source]
        } ?: return
        Shown(song.id, have, source).also { synchronized(picks) { picks[song.id] = it }; _shown.value = it }
    }

    private fun statusOf(songId: String) = synchronized(statuses) { statuses[songId] } ?: Status(songId)

    private fun bestOf(status: Status): Pair<LyricsSource, Lyrics>? =
        LyricsSource.offered.mapNotNull { s -> status.found[s]?.let { s to it } }.let { list ->
            list.firstOrNull { it.second.wordSynced } ?: list.firstOrNull { it.second.synced } ?: list.firstOrNull()
        }

    /** Asks the sources ([only]: just that one) for [song]; their answers go into its [Status]. */
    private suspend fun search(song: Song, durationMs: Long, only: LyricsSource?): Pair<LyricsSource, Lyrics>? {
        fun change(block: (Status) -> Status) {
            val next = synchronized(statuses) { block(statuses[song.id] ?: Status(song.id)).also { statuses[song.id] = it } }
            if (_status.value.songId == song.id || _status.value.songId == null) _status.value = next
        }
        _status.value = statusOf(song.id)
        val result = runCatching {
            LyricsRepository.lyrics(
                videoId = song.id,
                title = song.title,
                artist = song.artist.orEmpty(),
                durationMs = durationMs,
                album = song.album,
                sources = only?.let { setOf(it) } ?: LyricsSource.entries.toSet(),
                order = only?.let { listOf(it) } ?: LyricsSource.entries,
                prioritizeSyllableSync = true,
                onSourceStarted = { s -> change { it.copy(sources = it.sources + (s to State.Searching)) } },
                onSourceResult = { s, found ->
                    val lyrics = found?.let { Lyrics.fromRich(it.lines, it.source) }
                    change {
                        it.copy(
                            sources = it.sources + (s to if (lyrics != null) State.Found else State.NotFound),
                            found = if (lyrics != null) it.found + (s to lyrics) else it.found,
                        )
                    }
                },
                // Not reached: a race already won. Not asked, as far as the sheet goes.
                onSourceCancelled = { s -> change { it.copy(sources = it.sources - s) } },
            )
        }.getOrNull()
        android.util.Log.i("OnlineLyrics", "${result?.source ?: "none"}: ${result?.lines?.size ?: 0} lines")
        return result?.let { r -> statusOf(song.id).found[r.source]?.let { r.source to it } }
    }
}
