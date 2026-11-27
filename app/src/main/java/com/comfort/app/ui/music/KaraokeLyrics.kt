package com.comfort.app.ui.music

import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView

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
    KaraokeLyricsView(
        listState = rememberLazyListState(),
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

/** Each word a syllable, carrying the space after it (the library joins them as they are). */
private fun LyricLine.syllables(): List<KaraokeSyllable> {
    val spans = wordSpans
    return words.mapIndexed { i, word ->
        val span = spans.getOrNull(i)
        val content = if (span != null) {
            val upTo = spans.getOrNull(i + 1)?.first ?: text.length
            text.substring(span.first, upTo.coerceIn(span.first, text.length))
        } else word.text
        KaraokeSyllable(content, word.startMs.toInt(), word.endMs.toInt())
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
