package com.comfort.app.ui.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.music.Lyrics

/**
 * The song's lyrics, in place of the cover. Synced ones follow the song: the line being sung is
 * bright and kept a third of the way down, the rest dimmed; a tap on a line plays from there.
 * Scrolling by hand leaves them where they are for a few seconds before following again.
 * Plain lyrics are just text to scroll through.
 */
@Composable
internal fun LyricsView(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val lines = lyrics.lines
    val zoom = rememberLyricsZoom()
    if (lines.isNullOrEmpty()) {
        val base = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily)
        Box(modifier.pinchToZoom(zoom).verticalScroll(rememberScrollState())) {
            Text(
                lyrics.plain.orEmpty(),
                style = base.copy(fontSize = base.fontSize * zoom.floatValue, lineHeight = base.lineHeight * zoom.floatValue),
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        return
    }

    val current = lyrics.lineAt(positionMs)
    val listState = rememberLazyListState()
    // When the user last scrolled by hand; following waits a few seconds after that.
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolledAt = System.currentTimeMillis()
        }
    }

    // Google Sans, as the app's titles (its bundled bold cut; other scripts fall back to the system font).
    val lineStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily).let { base ->
        base.copy(fontSize = base.fontSize * zoom.floatValue, lineHeight = base.lineHeight * zoom.floatValue)
    }
    BoxWithConstraints(modifier.pinchToZoom(zoom)) {
        val height = constraints.maxHeight
        // The line being sung sits a third of the way down.
        val anchor = height / 3
        val density = LocalDensity.current
        LaunchedEffect(current, userScrolledAt) {
            if (current < 0) return@LaunchedEffect
            val sinceUser = System.currentTimeMillis() - userScrolledAt
            if (sinceUser < FOLLOW_AGAIN_AFTER_MS) {
                kotlinx.coroutines.delay(FOLLOW_AGAIN_AFTER_MS - sinceUser)
            }
            autoScrolling = true
            try {
                // The top content padding is the anchor: an item scrolled to the start sits there.
                listState.animateScrollToItem(current)
            } finally {
                autoScrolling = false
            }
        }
        LazyColumn(
            state = listState,
            // Lines fade out toward the top and bottom edges rather than being cut off there.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Transparent, 0.12f to Color.Black, 0.85f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
            // Room above the first line and below the last, so either can sit at the anchor.
            contentPadding = PaddingValues(top = with(density) { anchor.toDp() }, bottom = with(density) { (height - anchor).toDp() }),
        ) {
            itemsIndexed(lines) { index, line ->
                val active = index == current
                val alpha by animateFloatAsState(
                    when {
                        active -> 1f
                        index < current -> 0.45f
                        else -> 0.55f
                    },
                    animationSpec = tween(300),
                    label = "lyricAlpha",
                )
                val scale by animateFloatAsState(if (active) 1f else 0.96f, animationSpec = tween(300), label = "lyricScale")
                val color by animateColorAsState(if (active) Color.White else Color.White.copy(alpha = 0.9f), label = "lyricColor")
                Text(
                    line.text.ifBlank { "♪" },
                    style = lineStyle,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSeek(line.timeMs) }
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

private const val FOLLOW_AGAIN_AFTER_MS = 3_000L

/**
 * Spotify's lyric line under the cover, as two: the line being sung, bright, and the next one,
 * dimmed under it. When the song moves on, both slide up a line. Before the first line starts,
 * the first two show dimmed. A tap opens the full lyrics.
 */
@Composable
internal fun LyricsPreview(lyrics: Lyrics, positionMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lines = lyrics.lines ?: return
    val current = lyrics.lineAt(positionMs)
    androidx.compose.animation.AnimatedContent(
        targetState = current,
        transitionSpec = {
            val forward = targetState > initialState
            (androidx.compose.animation.slideInVertically(tween(320)) { if (forward) it / 2 else -it / 2 } +
                androidx.compose.animation.fadeIn(tween(320))) togetherWith
                (androidx.compose.animation.slideOutVertically(tween(320)) { if (forward) -it / 2 else it / 2 } +
                    androidx.compose.animation.fadeOut(tween(200))) using
                androidx.compose.animation.SizeTransform(clip = true)
        },
        label = "lyricsPreview",
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) { index ->
        val first = lines.getOrNull(index)
        val second = lines.getOrNull(index + 1)
        val style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily)
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Text(
                (if (index < 0) lines[0] else first)?.text?.ifBlank { "♪" } ?: "",
                style = style,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = if (index < 0) 0.55f else 1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                (if (index < 0) lines.getOrNull(1) else second)?.text?.ifBlank { "♪" } ?: "",
                style = style,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** The lyrics' text size, as a factor of the normal one: pinched bigger or smaller, and kept for
 * next time (the music player's own settings). */
@Composable
private fun rememberLyricsZoom(): androidx.compose.runtime.MutableFloatState {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        androidx.compose.runtime.mutableFloatStateOf(
            context.getSharedPreferences(ZOOM_PREFS, android.content.Context.MODE_PRIVATE).getFloat(ZOOM_KEY, 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        )
    }
}

/** Two fingers pinch the lyrics bigger or smaller; one finger still scrolls them (only a
 * two-finger move is taken, before the list sees it). */
@Composable
private fun Modifier.pinchToZoom(zoom: androidx.compose.runtime.MutableFloatState): Modifier {
    val context = androidx.compose.ui.platform.LocalContext.current
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            var pinched = false
            do {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val factor = event.calculateZoom()
                    if (factor != 1f) {
                        zoom.floatValue = (zoom.floatValue * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        pinched = true
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (pinched) {
                context.getSharedPreferences(ZOOM_PREFS, android.content.Context.MODE_PRIVATE).edit().putFloat(ZOOM_KEY, zoom.floatValue).apply()
            }
        }
    }
}

private const val ZOOM_PREFS = "music_player"
private const val ZOOM_KEY = "lyrics_zoom"
private const val MIN_ZOOM = 0.7f
private const val MAX_ZOOM = 1.8f
