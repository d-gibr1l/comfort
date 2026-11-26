package com.comfort.app.music.lyrics

import com.comfort.app.music.Lyrics
import com.comfort.app.music.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player's lyrics from the web (Mixify's sources, [LyricsRepository]), for a song whose own
 * lyrics aren't timed word by word. Each song's answer is kept for the session, and how each
 * source fared is published ([status]) for the sources sheet.
 */
object OnlineLyrics {
    /** How a source fared for the song asked about last. */
    enum class State { Searching, Found, NotFound }

    data class Status(val songId: String?, val sources: Map<LyricsSource, State> = emptyMap())

    private val _status = MutableStateFlow(Status(null))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val cache = object : LinkedHashMap<String, Lyrics?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 30
    }

    /** [song]'s lyrics from the first source in order that has them (word-timed ones preferred),
     * or null. [durationMs]: the song's length, which the sources match on. */
    suspend fun find(song: Song, durationMs: Long): Lyrics? {
        synchronized(cache) { if (cache.containsKey(song.id)) return cache[song.id] }
        _status.value = Status(song.id)
        fun mark(source: LyricsSource, state: State) {
            val now = _status.value
            if (now.songId == song.id) _status.value = now.copy(sources = now.sources + (source to state))
        }
        val result = runCatching {
            LyricsRepository.lyrics(
                videoId = song.id,
                title = song.title,
                artist = song.artist.orEmpty(),
                durationMs = durationMs,
                album = song.album,
                prioritizeSyllableSync = true,
                onSourceStarted = { mark(it, State.Searching) },
                onSourceResult = { source, found -> mark(source, if (found != null) State.Found else State.NotFound) },
                onSourceCancelled = { source -> val now = _status.value; if (now.songId == song.id) _status.value = now.copy(sources = now.sources - source) },
            )
        }.getOrNull()
        val lyrics = result?.let { Lyrics.fromRich(it.lines, it.source) }
        android.util.Log.i("OnlineLyrics", "${result?.source ?: "none"}: ${lyrics?.lines?.size ?: 0} lines, word-timed=${lyrics?.wordSynced}")
        synchronized(cache) { cache[song.id] = lyrics }
        return lyrics
    }
}
