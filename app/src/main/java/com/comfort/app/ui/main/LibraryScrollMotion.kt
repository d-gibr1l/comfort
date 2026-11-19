package com.comfort.app.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The Library list's scroll motion, both drawing-only (nothing composed or laid out again, read
 * from the list's layout as each frame is drawn):
 * - [riseIn]: a card coming up from behind the bars at the bottom grows from 94% to full size,
 *   rises the last 16dp;
 * - [LocalThumbnailParallax]: a thumbnail's picture moves a little slower than its card, as if
 *   seen through it (ShapedThumbnail).
 */

/** Where the card sits on screen, -1 (top) to 1 (bottom), for the thumbnail's parallax; read as
 * it's drawn. Null outside the Library list (no parallax). */
internal val LocalThumbnailParallax = staticCompositionLocalOf<(() -> Float)?> { null }

/** [key]'s card's place in the list's viewport, -1 at the top edge to 1 at the bottom. */
internal fun LazyListState.viewportPosition(key: Any): Float {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
    val half = (info.viewportEndOffset - info.viewportStartOffset) / 2f
    if (half <= 0f) return 0f
    val centre = info.viewportStartOffset + half
    return ((item.offset + item.size / 2f - centre) / half).coerceIn(-1f, 1f)
}

/** How far [key]'s card has come up from behind the bars at the bottom: 0 at their top, 1 once
 * it's 140dp above it; null while it isn't on screen (composed ahead of being scrolled to). */
internal fun LazyListState.riseProgress(key: Any, density: androidx.compose.ui.unit.Density): Float? {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
    // From just under the top of what floats over the list's foot (the navigation bar, and the mini
    // player when it's there: the list's bottom padding), up — lower, it all happened out of sight.
    val edge = info.viewportEndOffset - info.afterContentPadding + with(density) { 24.dp.toPx() }
    return ((edge - item.offset) / with(density) { 140.dp.toPx() }).coerceIn(0f, 1f)
}

/**
 * [key]'s card rising in: it grows from 94% to full size and rises the last 16dp. Not faded: put
 * on each thing drawn (no layer of its own), a fading card showed through itself, a dark blotch
 * on the dark theme; as a layer, it would cost a pass of its own. Follows
 * where it is ([riseProgress]) and, held back by [timed] (0 to 1 over a moment from when it started
 * coming up), never runs ahead of that — a fast fling carried a card through in a frame or two,
 * and the rise wasn't seen.
 */
internal fun Modifier.riseIn(state: LazyListState, key: Any, timed: () -> Float): Modifier = graphicsLayer {
    val p = minOf(state.riseProgress(key, this) ?: return@graphicsLayer, timed())
    if (p >= 1f) return@graphicsLayer
    val eased = 1f - (1f - p) * (1f - p)
    scaleX = 0.94f + 0.06f * eased
    scaleY = scaleX
    translationY = (1f - eased) * 16.dp.toPx()
    transformOrigin = TransformOrigin(0.5f, 1f)
}
