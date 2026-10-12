package com.comfort.app.ui.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Swiping sideways between pages — the main ones (Home, Library, Settings, in that order), and the
 * Queue's status filters (Running, In Queue, ...): the page
 * follows the finger with its neighbour coming in beside it, and on release settles on whichever
 * one the swipe carried far or fast enough.
 *
 * Who gets a sideways swipe is decided where it starts:
 * - on a swipe-to-remove card ([pageSwipeEdges]), its middle removes the card and its outer
 *   strips (the thumbnail and the ⋮ menu columns) turn the page — taken before the card sees it;
 * - anywhere else, the page turns only if nothing on it wanted the drag first: a row that
 *   scrolls sideways, a slider, a list scrolling up and down all keep their gestures.
 * Taps on the nav bar and Back keep their own transitions; this only adds the swipe.
 */
@Stable
class PageSwipeState internal constructor(
    private val scope: CoroutineScope,
    private val current: State<Int>,
    private val pageCount: State<Int>,
    private val allowed: State<Boolean>,
    private val onSettled: State<(Int) -> Unit>,
) {
    /** The drag as a fraction of the page width; negative is toward the next page. Read only
     * while drawing ([pageSwipeLayer]), so following the finger recomposes nothing. */
    internal var offset by mutableFloatStateOf(0f)

    /** The page being swiped toward, from the moment a swipe is taken until it has settled. */
    var neighbor by mutableStateOf<Int?>(null)
        private set

    /** Set by a page while it needs sideways drags for itself (Library while selecting). */
    var blocked by mutableStateOf(false)

    /** Pointers that came down in a card's page-turning strips (see [pageSwipeEdges]). */
    internal val edgeDowns = mutableSetOf<PointerId>()

    /** Whether [page] is one of the two pages a swipe is moving right now. */
    fun involves(page: Int): Boolean {
        val n = neighbor ?: return false
        return page == n || page == current.value
    }

    internal fun canStart() = allowed.value && !blocked && neighbor == null

    internal fun neighborFor(dx: Float): Int? {
        val page = if (dx < 0) current.value + 1 else current.value - 1
        return page.takeIf { it in 0 until pageCount.value }
    }

    internal fun begin(target: Int) {
        offset = 0f
        neighbor = target
    }

    internal fun dragBy(fraction: Float) {
        val target = neighbor ?: return
        offset = if (target > current.value) (offset + fraction).coerceIn(-1f, 0f) else (offset + fraction).coerceIn(0f, 1f)
    }

    /** The finger lifted: on to the neighbour past half way or on a flick toward it, else back.
     * A gesture the system cancelled (a notification sliding in, say) always goes back. */
    internal fun release(velocity: Float, cancelled: Boolean = false) {
        val target = neighbor ?: return
        val toward = if (target > current.value) -1f else 1f
        val progress = offset * toward
        val flung = velocity * toward > FLING_WIDTHS_PER_SECOND
        val flungBack = velocity * toward < -FLING_WIDTHS_PER_SECOND
        val commit = !cancelled && !flungBack && (progress > 0.5f || (flung && progress > FLING_MIN_PROGRESS))
        scope.launch {
            animate(
                initialValue = offset,
                targetValue = if (commit) toward else 0f,
                initialVelocity = if (cancelled) 0f else velocity,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            ) { value, _ -> offset = value }
            // One snapshot, so no frame shows the new page selected but still shifted aside.
            Snapshot.withMutableSnapshot {
                if (commit) onSettled.value(target)
                neighbor = null
                offset = 0f
            }
        }
    }

    private companion object {
        // A flick this fast (page widths per second) turns the page however short it was (a quick
        // flick barely moves it before the finger's off); slower, it has to pass half way.
        const val FLING_WIDTHS_PER_SECOND = 0.6f
        const val FLING_MIN_PROGRESS = 0.02f
    }
}

@Composable
fun rememberPageSwipeState(current: Int, allowed: Boolean, pageCount: Int = 3, onSettled: (Int) -> Unit): PageSwipeState {
    val scope = rememberCoroutineScope()
    val currentState = rememberUpdatedState(current)
    val pageCountState = rememberUpdatedState(pageCount)
    val allowedState = rememberUpdatedState(allowed)
    val onSettledState = rememberUpdatedState(onSettled)
    return remember { PageSwipeState(scope, currentState, pageCountState, allowedState, onSettledState) }
}

/** The page swipe the content belongs to (the main pages', or the Queue's filters'), for the
 * swipe-to-remove cards' page-turning strips; null where there's none. */
val LocalPageSwipe = staticCompositionLocalOf<PageSwipeState?> { null }

/** Where [page] sits while a swipe moves it: its place beside the selected page, plus the drag. */
fun Modifier.pageSwipeLayer(state: PageSwipeState, page: Int, selected: Int): Modifier =
    graphicsLayer { translationX = (page - selected + state.offset) * size.width }

/**
 * Takes sideways swipes over the pages. Watches each gesture until it's clearly sideways —
 * [TAKE_AFTER_SLOPS] touch slops across, more than it moved up or down — and then takes it if it
 * started in a card's page-turning strip, or if nothing on the page has used it by then (a card's
 * own swipe, a sideways row or a scrolling list consume it at one touch slop, so they always get
 * the first chance). From then on every move is the page's, consumed before anything under it.
 */
fun Modifier.pageSwipeArea(state: PageSwipeState): Modifier = pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        // Cards add to this as they see the same down, after this (they're further in).
        state.edgeDowns.clear()
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!state.canStart()) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var target: Int? = null
        while (target == null) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val step = change.positionChangeIgnoreConsumed()
            dx += step.x
            dy += step.y
            tracker.addPosition(change.uptimeMillis, change.position)
            if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
            if (down.id in state.edgeDowns) {
                if (abs(dx) > slop) {
                    target = state.neighborFor(dx) ?: return@awaitEachGesture
                    change.consume()
                }
                continue
            }
            // Everything else: this same move once the page has had it.
            val after = awaitPointerEvent(PointerEventPass.Main)
            val mine = after.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (mine.isConsumed) return@awaitEachGesture
            if (abs(dx) > slop * TAKE_AFTER_SLOPS) {
                target = state.neighborFor(dx) ?: return@awaitEachGesture
                mine.consume()
            }
        }
        state.begin(target)
        val width = size.width.toFloat().coerceAtLeast(1f)
        var cancelled = true
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // Compose turns a cancelled gesture into a release of its own: already
                    // consumed, at the same time as the event before. A real lift is neither.
                    cancelled = change.isConsumed || change.uptimeMillis == change.previousUptimeMillis
                    if (!cancelled) tracker.addPosition(change.uptimeMillis, change.position)
                    break
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                state.dragBy(change.positionChangeIgnoreConsumed().x / width)
                change.consume()
            }
        } finally {
            // Also here when the gesture ended any other way (this handler cancelled): back.
            state.release(tracker.calculateVelocity().x / width, cancelled)
        }
    }
}

// How far past the touch slop an ordinary swipe has to go before the page takes it: the page's
// own sideways gestures start at one slop, so they always get the swipe first.
private const val TAKE_AFTER_SLOPS = 1.5f

/** On a swipe-to-remove card: a touch landing within [zone] of either side (the thumbnail and ⋮
 * columns) turns the page instead of removing the card. Only marks the touch; [pageSwipeArea]
 * decides once it's clearly sideways, and takes it before the card can. */
fun Modifier.pageSwipeEdges(state: PageSwipeState, zone: Dp = 80.dp): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val z = zone.toPx()
        if (down.position.x < z || down.position.x > size.width - z) state.edgeDowns.add(down.id)
    }
}
