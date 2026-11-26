// The word-by-word fill is adapted from Mixify (https://github.com/MixifyTeam/Mixify, GPL-3.0), as
// Comfort is: its sweepTo, with the lit part's leading edge feathered.
package com.comfort.app.ui.music

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.music.lyrics.LyricLine

/** The song's position, moved on every frame while it plays (the player reports it only a few
 * times a second, and a word's fill stepped along with it). */
@Composable
internal fun rememberSmoothPosition(positionMs: Long, playing: Boolean): () -> Long {
    var base by remember { mutableLongStateOf(positionMs) }
    var baseAt by remember { mutableLongStateOf(System.nanoTime()) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(positionMs) { base = positionMs; baseAt = System.nanoTime() }
    LaunchedEffect(playing) {
        while (playing) withFrameMillis { now = System.nanoTime() }
    }
    return { if (playing) base + ((now - baseAt) / 1_000_000L).coerceIn(0L, 1_000L) else base }
}

/** How many of [line]'s characters are sung at [positionMs], fractional within the word being sung. */
private fun revealedChars(line: LyricLine, positionMs: Long): Float {
    val spans = line.wordSpans
    var revealed = 0f
    line.words.forEachIndexed { i, word ->
        val span = spans.getOrNull(i) ?: return revealed
        if (positionMs < word.startMs) return revealed
        if (positionMs >= word.endMs) {
            revealed = (span.last + 1).toFloat()
        } else {
            val f = (positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
            return span.first + (span.last + 1 - span.first) * f
        }
    }
    return revealed
}

/** [line] lit word by word as it's sung, the rest of it dimmed ([dimAlpha]). */
@Composable
internal fun SweptLyricLine(line: LyricLine, position: () -> Long, style: TextStyle, dimAlpha: Float, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier) {
        Text(line.text, style = style, fontWeight = FontWeight.Bold, color = Ink.copy(alpha = dimAlpha), onTextLayout = { layout = it })
        Text(
            line.text, style = style, fontWeight = FontWeight.Bold, color = Ink,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    val l = layout ?: return@drawWithContent
                    sweepTo(l, revealedChars(line, position()))
                },
        )
    }
}

/** Draws the content up to [revealedChars] characters in, line by line, its edge feathered ahead. */
private fun ContentDrawScope.sweepTo(layout: TextLayoutResult, revealedChars: Float) {
    if (revealedChars <= 0f) return
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) { drawContent(); return }
    val feather = 30.dp.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine)
        if (revealedChars <= lineStart) return
        val rtl = layout.getParagraphDirection(lineStart.coerceAtMost(textLen - 1)) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)
        val cut = revealedChars < lineEnd
        val x = if (cut) {
            val i = revealedChars.toInt().coerceIn(lineStart, lineEnd - 1)
            val a = layout.getHorizontalPosition(i, usePrimaryDirection = true)
            val b = layout.getHorizontalPosition((i + 1).coerceAtMost(lineEnd), usePrimaryDirection = true)
            a + (b - a) * (revealedChars - i)
        } else if (rtl) lineLeft else lineRight
        val edge = x.coerceIn(lineLeft, lineRight)
        val featherPx = if (cut) minOf(feather, if (rtl) lineRight - edge else edge - lineLeft) else 0f
        val lead = if (rtl) (edge - featherPx).coerceAtLeast(lineLeft) else (edge + featherPx).coerceAtMost(lineRight)
        val (left, right) = if (rtl) lead to lineRight else lineLeft to lead
        if (left >= right) continue
        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        clipRect(left = left, top = top, right = right, bottom = bottom) { this@sweepTo.drawContent() }
        if (featherPx < 0.5f) continue
        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.White, 1f to Color.Transparent, startX = edge, endX = if (rtl) edge - featherPx else edge + featherPx),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}
