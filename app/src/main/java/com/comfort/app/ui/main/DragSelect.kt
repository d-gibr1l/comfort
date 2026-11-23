package com.comfort.app.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Long-press an item and drag: every item from it to the finger is selected, in the list's order
 * (or unselected, when the one long-pressed already was), the list scrolling on near its top or
 * bottom — the photo-gallery gesture. The item's own long press starts it ([start]), so its
 * haptic, its TalkBack action and a long press without a drag all stay as they were; the list's
 * [dragToSelect] carries the same finger on from there.
 */
@Stable
class DragSelectState internal constructor() {
    /** Whether a drag is selecting now (the list doesn't scroll by the finger meanwhile). */
    var active by mutableStateOf(false)
        internal set
    internal var pointerDown = false
    internal var pointer = Offset.Zero
    internal var anchor: String? = null
    internal var current: String? = null
    internal var base: Set<String> = emptySet()
    internal var adds = true
    // Where the finger was when the long press started the drag; see [dragToSelect]'s scrolling.
    internal var startPointer = Offset.Zero
    internal var scrollArmed = false

    /** An item's long press: [id] is picked, or unpicked if [selection] has it, and while the
     * finger stays down a drag goes on doing the same to the items it passes. Returns the new
     * selection. (TalkBack's long press, with no finger down, just picks or unpicks.) */
    fun start(id: String, selection: Set<String>): Set<String> {
        base = selection
        adds = id !in selection
        anchor = id
        current = id
        startPointer = pointer
        scrollArmed = false
        if (pointerDown) active = true
        return if (adds) selection + id else selection - id
    }
}

@Composable
fun rememberDragSelectState(): DragSelectState = remember { DragSelectState() }

/** The id (an item's String key) of a [LazyListState]'s item under [point], in the list's own
 * coordinates; null between items or on one keyed otherwise (a header). */
fun LazyListState.idAt(point: Offset): String? {
    val y = point.y + layoutInfo.viewportStartOffset
    return layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? String
}

/** [LazyListState.idAt] for a grid. */
fun LazyGridState.idAt(point: Offset): String? {
    val y = point.y + layoutInfo.viewportStartOffset
    return layoutInfo.visibleItemsInfo.firstOrNull {
        point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
            y >= it.offset.y && y < it.offset.y + it.size.height
    }?.key as? String
}

/**
 * On a lazy list or grid: carries a drag started by [DragSelectState.start] on, selecting the
 * items in [ids] (their order) from the long-pressed one to the one under the finger
 * ([itemAt]), with a tick on each, and scrolling ([scrollBy]) while the finger is near the top or
 * bottom. [topInset]/[bottomInset] keep those zones off what covers the list's ends (a header, the
 * navigation bar and pill), so both are as easy to reach. The finger's moves are taken before the
 * list and the items see them, so the list doesn't scroll and no swipe starts meanwhile.
 */
@Composable
fun Modifier.dragToSelect(
    state: DragSelectState,
    ids: List<String>,
    itemAt: (Offset) -> String?,
    scrollBy: suspend (Float) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestIds by rememberUpdatedState(ids)
    val latestItemAt by rememberUpdatedState(itemAt)
    val latestOnChange by rememberUpdatedState(onSelectionChange)
    val height = remember { intArrayOf(0) }
    val topPx = with(density) { topInset.toPx() }
    val bottomPx = with(density) { bottomInset.toPx() }
    val edgePx = with(density) { 64.dp.toPx() }
    val maxSpeedPx = with(density) { 20.dp.toPx() } // a frame, deep in a zone
    val armPx = with(density) { 24.dp.toPx() }

    fun update() {
        // Not until the finger has moved: the list can move under a finger held still (the
        // Library's header shrinking as selecting starts), and the card that slid under it got
        // picked too (reported).
        if (!state.scrollArmed) return
        val y = state.pointer.y.coerceIn(topPx, (height[0] - bottomPx - 1f).coerceAtLeast(topPx))
        val id = latestItemAt(Offset(state.pointer.x, y)) ?: return
        if (id == state.current) return
        val list = latestIds
        val from = list.indexOf(state.anchor)
        val to = list.indexOf(id)
        if (from < 0 || to < 0) return
        state.current = id
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val range = list.subList(minOf(from, to), maxOf(from, to) + 1)
        latestOnChange(if (state.adds) state.base + range else state.base - range.toSet())
    }

    LaunchedEffect(state.active) {
        while (state.active) {
            val bottom = height[0] - bottomPx
            val up = ((topPx + edgePx - state.pointer.y) / edgePx).coerceIn(0f, 1f)
            val down = ((state.pointer.y - (bottom - edgePx)) / edgePx).coerceIn(0f, 1f)
            // Not until the finger has moved from where it pressed: an item near the bottom (or the
            // top) is already in a scroll zone, and pressing it scrolled the list at once,
            // selecting everything below (reported). Once it's moved, the zones work as before.
            if (!state.scrollArmed && (state.pointer - state.startPointer).getDistance() > armPx) state.scrollArmed = true
            val speed = if (state.scrollArmed) (down - up) * maxSpeedPx else 0f
            if (speed != 0f) {
                scrollBy(speed)
                update()
            }
            withFrameNanos { }
        }
    }

    return this
        .onSizeChanged { height[0] = it.height }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                state.pointerDown = true
                // Kept up to date while the finger is down, so a long press that starts a drag
                // has it where the finger is (not at 0,0, which read as the top scroll zone).
                state.pointer = down.position
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        state.pointer = change.position
                        if (state.active) {
                            if (!state.scrollArmed && (state.pointer - state.startPointer).getDistance() > armPx) state.scrollArmed = true
                            if (change.positionChange() != Offset.Zero) change.consume()
                            update()
                        }
                    }
                } finally {
                    state.pointerDown = false
                    state.active = false
                }
            }
        }
}
