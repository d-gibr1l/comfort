package com.comfort.app.ui.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.sp
import com.comfort.app.music.Lyrics
import com.comfort.app.music.lyrics.LyricAlignment
import com.comfort.app.music.lyrics.LyricLine
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.comfort.app.ui.music.karaoke.composable.lyrics.KaraokeLyricsView

/**
 * Synced lyrics drawn by accompanist-lyrics-ui (Apache-2.0, as MeiloX's): Apple Music's look,
 * words lifting and filling as they're sung, breathing dots through intros and breaks, spring
 * motion and blur by distance. Fed Comfort's lines (word-timed where the source has them).
 */
@Composable
internal fun KaraokeLyrics(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, zoom: Float, modifier: Modifier = Modifier) {
    val synced = remember(lyrics) { lyrics.toSyncedLyrics() } ?: return
    val playing by com.comfort.app.music.MusicPlayer.state.let { flow ->
        androidx.compose.runtime.produceState(flow.value.isPlaying, flow) { flow.collect { value = it.isPlaying } }
    }
    val position = rememberSmoothPosition(positionMs, playing)
    val light = LocalPlayerLight.current
    val base = androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily)
    // Opened already at the line being sung (and again for each song's lyrics): the library's
    // follower moves one line at a time, a quick nudge while that line is on screen, but starts
    // with an animated scroll when it isn't, which never finished (seen on a phone: the lyrics
    // stayed where they opened while the song went on, the sung line creeping out of view).
    val listState = remember(synced) {
        val focus = synced.getCurrentFirstHighlightLineIndexByTime(positionMs.toInt()).coerceIn(0, (synced.lines.size - 1).coerceAtLeast(0))
        androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = (focus - 1).coerceAtLeast(0))
    }
    KaraokeLyricsView(
        listState = listState,
        lyrics = synced,
        currentPosition = { position().toInt() },
        onLineClicked = { line -> onSeek(line.start.toLong()) },
        onLinePressed = {},
        modifier = modifier,
        normalLineTextStyle = base.copy(fontSize = 32.sp * zoom, lineHeight = 40.sp * zoom, fontWeight = FontWeight.Bold, textMotion = TextMotion.Animated),
        accompanimentLineTextStyle = base.copy(fontSize = 20.sp * zoom, lineHeight = 26.sp * zoom, fontWeight = FontWeight.Bold, textMotion = TextMotion.Animated),
        textColor = Ink,
        // Its glow adds light: right on the dark player, not on the white one.
        blendMode = if (light) BlendMode.SrcOver else BlendMode.Plus,
        // Blur needs Android 12+.
        useBlurEffect = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
        // Where the sung line sits, from the top (plus its 100dp keep-alive zone): about a third of
        // the way down, Apple Music's upper-middle (its own 32dp put it near the top).
        offset = androidx.compose.ui.unit.Dp(120f),
    )
}

/** Comfort's lyrics as the library's: word-timed lines as karaoke lines (their background vocals
 * attached), the rest as timed lines. Null for unsynced lyrics. */
private fun Lyrics.toSyncedLyrics(): SyncedLyrics? {
    val rich = rich?.filterNot { it.isGap }?.sortedBy { it.timeMs }
    val lines: List<ISyncedLine> = if (!rich.isNullOrEmpty()) {
        rich.mapIndexed { i, line ->
            val nextStart = rich.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 5_000)
            if (line.isWordSynced) line.toKaraoke(nextStart) else SyncedLine(line.text, null, line.timeMs.toInt(), nextStart.toInt())
        }
    } else {
        val plain = this.lines ?: return null
        plain.mapIndexed { i, line ->
            val nextStart = plain.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 5_000)
            SyncedLine(line.text, null, line.timeMs.toInt(), nextStart.toInt())
        }
    }
    return SyncedLyrics(lines)
}

private fun LyricLine.alignmentFor(): KaraokeAlignment =
    if (alignment == LyricAlignment.End) KaraokeAlignment.End else KaraokeAlignment.Start

/** The line's syllables, timed one by one where the source has them (lrc.red's and LyricsPlus's
 * are), so a long word's wipe lingers or hurries as it's sung; a whole word otherwise. A word's
 * last piece carries the space after it (the library joins pieces as they are, and tells words
 * apart by those spaces, which its lift and wave go by). */
private fun LyricLine.syllables(): List<KaraokeSyllable> {
    val spans = wordSpans
    return words.flatMapIndexed { i, word ->
        val span = spans.getOrNull(i)
        val full = if (span != null) {
            val upTo = spans.getOrNull(i + 1)?.first ?: text.length
            text.substring(span.first, upTo.coerceIn(span.first, text.length))
        } else word.text
        val parts = word.syllables.filter { it.startChar in 1 until word.text.length || it.startChar == 0 }
        if (parts.size < 2) return@flatMapIndexed listOf(KaraokeSyllable(full, word.startMs.toInt(), word.endMs.toInt()))
        parts.mapIndexed { j, syllable ->
            // From this syllable's first character to the next one's (the last: to the word's end,
            // its space included), so the pieces cover the word whole.
            val from = if (j == 0) 0 else syllable.startChar.coerceIn(0, full.length)
            val to = parts.getOrNull(j + 1)?.startChar?.coerceIn(from, full.length) ?: full.length
            KaraokeSyllable(full.substring(from, to), syllable.startMs.toInt(), syllable.endMs.toInt())
        }.filter { it.content.isNotEmpty() }
    }
}

private fun LyricLine.toKaraoke(nextStart: Long): KaraokeLine.MainKaraokeLine {
    val syllables = syllables()
    val end = (sungUntilMs ?: words.lastOrNull()?.endMs ?: nextStart).toInt()
    val backing = background?.takeIf { it.isWordSynced }?.let { bg ->
        val bgSyllables = bg.syllables()
        KaraokeLine.AccompanimentKaraokeLine(
            syllables = bgSyllables,
            translation = null,
            alignment = alignmentFor(),
            start = bgSyllables.firstOrNull()?.start ?: bg.timeMs.toInt(),
            end = bgSyllables.lastOrNull()?.end ?: end,
        )
    }
    return KaraokeLine.MainKaraokeLine(
        syllables = syllables,
        translation = null,
        alignment = alignmentFor(),
        start = syllables.firstOrNull()?.start ?: timeMs.toInt(),
        end = end,
        accompanimentLines = backing?.let { listOf(it) },
    )
}
