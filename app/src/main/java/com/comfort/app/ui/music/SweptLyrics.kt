// The word-by-word fill is adapted from Mixify (https://github.com/MixifyTeam/Mixify, GPL-3.0), as
// Comfort is: its sweepTo, with the lit part's leading edge feathered, and its words' lift, swell
// and bloom (LyricLine.growingWords).
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText
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

/** [line] lit word by word as it's sung, the rest of it dimmed ([dimAlpha]). [moving]: Apple
 * Music's motion as well (the full lyrics): each word sits a little low until it's sung and rises
 * as it is, and a word held long swells letter by letter with a glow (Mixify's GrowingWord). */
@Composable
internal fun SweptLyricLine(
    line: LyricLine,
    position: () -> Long,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    // The lit part's own alpha (the line under the cover fades as it rolls away).
    litAlpha: Float = 1f,
    maxLines: Int = Int.MAX_VALUE,
    moving: Boolean = false,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val overflow = if (maxLines == Int.MAX_VALUE) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis
    val density = androidx.compose.ui.platform.LocalDensity.current
    val liftPx = with(density) { WORD_LIFT.toPx() }
    val fontPx = with(density) { style.fontSize.toPx() }
    val ink = Ink
    // The glow is light on a dark player only: a dark one on white reads as a smudge.
    val glows = !LocalPlayerLight.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 16)
    val growth = remember { com.comfort.app.music.lyrics.CharGrowth() }
    val boldStyle = remember(style) { style.copy(fontWeight = FontWeight.Bold) }
    Box(modifier) {
        Text(
            line.text, style = style, fontWeight = FontWeight.Bold, color = ink.copy(alpha = dimAlpha), maxLines = maxLines, overflow = overflow, onTextLayout = { layout = it },
            modifier = if (!moving) Modifier else Modifier.drawWithContent {
                val l = layout
                if (l == null) drawContent() else drawWordsMoving(l, line, position(), liftPx, fontPx, growth) { drawContent() }
            },
        )
        Text(
            line.text, style = style, fontWeight = FontWeight.Bold, color = ink.copy(alpha = litAlpha), maxLines = maxLines, overflow = overflow,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    val l = layout ?: return@drawWithContent
                    val pos = position()
                    val revealed = line.revealedChars(pos)
                    if (!moving) {
                        sweepTo(l, revealed)
                        return@drawWithContent
                    }
                    if (revealed <= 0f) return@drawWithContent
                    drawWordsMoving(l, line, pos, liftPx, fontPx, growth) { drawContent() }
                    if (glows) drawGlow(l, line, pos, liftPx, fontPx, growth, measurer, boldStyle, ink.copy(alpha = litAlpha))
                    hideUnsung(l, revealed)
                },
        )
    }
}

/** How far a word sits below the line until it's sung (Apple Music's rise). */
internal val WORD_LIFT = 3.dp

/** How long a word takes to rise once it starts: its own length, within these. */
private const val RISE_MIN_MS = 250f
private const val RISE_MAX_MS = 700f

private fun riseOf(word: com.comfort.app.music.lyrics.LyricWord, positionMs: Long): Float {
    val riseMs = (word.endMs - word.startMs).toFloat().coerceIn(RISE_MIN_MS, RISE_MAX_MS)
    val t = ((positionMs - word.startMs) / riseMs).coerceIn(0f, 1f)
    // Quick off the floor, easing into rest: the word lands rather than slides.
    return 1f - (1f - t) * (1f - t) * (1f - t)
}

/** Each word of [line] (with the space or punctuation after it) drawn by [draw] at its own
 * height: [liftPx] low before it's sung, rising as it is; a held word letter by letter, swollen. */
private fun ContentDrawScope.drawWordsMoving(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    liftPx: Float,
    fontPx: Float,
    growth: com.comfort.app.music.lyrics.CharGrowth,
    draw: ContentDrawScope.() -> Unit,
) {
    val spans = line.wordSpans
    val textLen = layout.layoutInput.text.length
    if (spans.isEmpty() || textLen == 0) {
        draw()
        return
    }
    spans.forEachIndexed { i, span ->
        // From the word to the next one (the first takes anything before it too).
        val from = (if (i == 0) 0 else span.first).coerceIn(0, textLen)
        val to = (spans.getOrNull(i + 1)?.first ?: textLen).coerceIn(from, textLen)
        if (from >= to) return@forEachIndexed
        val word = line.words[i]
        val restDy = liftPx * (1f - riseOf(word, positionMs))
        val grow = line.growingAt(i)
        if (grow == null) {
            drawRange(layout, from, to, 0f, restDy, 1f, draw)
            return@forEachIndexed
        }
        // Held: each letter on its own move; anything around it with the word.
        val wordStart = span.first.coerceIn(from, to)
        val wordEnd = (span.last + 1).coerceIn(wordStart, to)
        for (c in wordStart until wordEnd) {
            grow.sampleInto((c - span.first).coerceIn(0, word.text.length - 1), positionMs, growth)
            drawRange(layout, c, c + 1, growth.shift * fontPx, liftPx * (1f - growth.rise), growth.scale, draw)
        }
        if (from < wordStart) drawRange(layout, from, wordStart, 0f, restDy, 1f, draw)
        if (wordEnd < to) drawRange(layout, wordEnd, to, 0f, restDy, 1f, draw)
    }
}

/** Characters [from] until [to] of the drawn text, moved by ([dx], [dy]) and scaled by [scale]
 * about their middle, row by row where they wrap. */
private fun ContentDrawScope.drawRange(
    layout: TextLayoutResult,
    from: Int,
    to: Int,
    dx: Float,
    dy: Float,
    scale: Float,
    draw: ContentDrawScope.() -> Unit,
) {
    val content = this
    val firstLine = layout.getLineForOffset(from)
    val lastLine = layout.getLineForOffset(to - 1)
    for (visualLine in firstLine..lastLine) {
        val a = maxOf(from, layout.getLineStart(visualLine))
        val b = minOf(to, layout.getLineEnd(visualLine))
        if (a >= b) continue
        val first = layout.getBoundingBox(a)
        val last = layout.getBoundingBox(b - 1)
        val left = minOf(first.left, last.left)
        val right = maxOf(first.right, last.right)
        if (right <= left) continue
        // The row's whole height, and past it at the top and bottom of the text.
        val top = if (visualLine == 0) -size.height else layout.getLineTop(visualLine)
        val bottom = if (visualLine == layout.lineCount - 1) size.height * 2 else layout.getLineBottom(visualLine)
        val pivot = androidx.compose.ui.geometry.Offset((left + right) / 2f, (layout.getLineTop(visualLine) + layout.getLineBottom(visualLine)) / 2f)
        withTransform({
            translate(dx, dy)
            if (scale != 1f) scale(scale, scale, pivot)
            clipRect(left, top, right, bottom)
        }) { content.draw() }
    }
}

/** A soft glow around the letters of [line]'s held words, as far as each has bloomed. */
private fun ContentDrawScope.drawGlow(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    liftPx: Float,
    fontPx: Float,
    growth: com.comfort.app.music.lyrics.CharGrowth,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    color: Color,
) {
    val textLen = layout.layoutInput.text.length
    for (grow in line.growingWords) {
        if (positionMs < grow.startMs || positionMs > grow.restsAtMs) continue
        val span = line.wordSpans.getOrNull(grow.index) ?: continue
        for (c in span.first until (span.last + 1).coerceAtMost(textLen)) {
            grow.sampleInto(c - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val glyph = measurer.measure(line.text.substring(c, c + 1), style)
            val box = layout.getBoundingBox(c)
            val visualLine = layout.getLineForOffset(c)
            val y = layout.getLineBaseline(visualLine) - glyph.firstBaseline
            val pivot = androidx.compose.ui.geometry.Offset(box.center.x, (layout.getLineTop(visualLine) + layout.getLineBottom(visualLine)) / 2f)
            withTransform({
                translate(growth.shift * fontPx, liftPx * (1f - growth.rise))
                scale(growth.scale, growth.scale, pivot)
            }) {
                drawText(
                    glyph,
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(box.left + (box.width - glyph.size.width) / 2f, y),
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color.copy(alpha = color.alpha * (0.9f * growth.bloom).coerceAtMost(1f)),
                        blurRadius = fontPx * 0.3f * growth.bloom,
                    ),
                )
            }
        }
    }
}

/** Takes away what hasn't been sung yet, past [revealedChars] characters in, row by row, the edge
 * feathered ahead (the moving words' counterpart of [sweepTo]). */
private fun ContentDrawScope.hideUnsung(layout: TextLayoutResult, revealedChars: Float) {
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) return
    val feather = 30.dp.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine)
        if (revealedChars >= lineEnd) continue
        val top = if (visualLine == 0) -size.height else layout.getLineTop(visualLine)
        val bottom = if (visualLine == layout.lineCount - 1) size.height * 2 else layout.getLineBottom(visualLine)
        if (revealedChars <= lineStart) {
            drawRect(Color.Black, androidx.compose.ui.geometry.Offset(-size.width, top), androidx.compose.ui.geometry.Size(size.width * 3, bottom - top), blendMode = BlendMode.DstOut)
            continue
        }
        val rtl = layout.getParagraphDirection(lineStart.coerceAtMost(textLen - 1)) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
        val i = revealedChars.toInt().coerceIn(lineStart, lineEnd - 1)
        val a = layout.getHorizontalPosition(i, usePrimaryDirection = true)
        val b = layout.getHorizontalPosition((i + 1).coerceAtMost(lineEnd), usePrimaryDirection = true)
        val edge = (a + (b - a) * (revealedChars - i)).coerceIn(layout.getLineLeft(visualLine), layout.getLineRight(visualLine))
        val far = if (rtl) -size.width else size.width * 2
        val lead = if (rtl) edge - feather else edge + feather
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = edge, endX = lead),
            topLeft = androidx.compose.ui.geometry.Offset(minOf(edge, far), top),
            size = androidx.compose.ui.geometry.Size(kotlin.math.abs(far - edge), bottom - top),
            blendMode = BlendMode.DstOut,
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
