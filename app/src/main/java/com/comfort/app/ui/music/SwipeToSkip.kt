package com.comfort.app.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.comfort.app.music.MusicPlayer
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Swipe sideways to change song: left for the next, right for the previous. The content follows
 * the finger; past a short distance it slides out, the song changes and the new one slides in
 * from the other side. With no song that way (the queue's end, repeat off) it springs back.
 */
/** Shared by a [swipeToSkip] and the [SongTransition] inside it: a change of song a swipe made
 * already has its own slide (the cover going off under the finger, the next one coming in), so the
 * transition doesn't run its own as well — it slid twice. */
class SwipeSkipState {
    /** The playId a swipe is changing from: the transition away from it runs no slide of its own.
     * Cleared when the swipe ends, whatever happened, so it can't eat a later change's slide. */
    internal var from: Int? = null
}

@Composable
fun rememberSwipeSkipState() = remember { SwipeSkipState() }

@Composable
fun Modifier.swipeToSkip(skip: SwipeSkipState? = null): Modifier {
    val state by MusicPlayer.state.collectAsState()
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    // A flick this fast changes song however short it was.
    val flingVelocity = with(density) { 600.dp.toPx() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // While the song is changing (the slide out, then the wait for the new one). A swipe made then
    // waits for it and goes next; the slide back in after it is caught by the next swipe where it
    // is. So quick swipes one after another each change song.
    var changing by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }

    // Where a swipe ends: [dx] how far the finger went (px; the content may have been part way in
    // from the swipe before), [velocity] how fast (px/s).
    fun release(dx: Float, velocity: Float, width: Float) {
        if (changing) {
            scope.launch {
                androidx.compose.runtime.snapshotFlow { changing }.first { !it }
                release(dx, velocity, width)
            }
            return
        }
        settle?.cancel()
        val goNext = (dx < -threshold || (velocity < -flingVelocity && dx < 0f)) && state.hasNext
        val goPrevious = !goNext && (dx > threshold || (velocity > flingVelocity && dx > 0f)) && state.hasPrevious
        // Set here, not in the coroutine: a swipe waiting on this one sees it at once.
        if (goNext || goPrevious) changing = true
        settle = scope.launch {
            try {
                if (goNext || goPrevious) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                    // Off the way the finger went, the song changes, and the next one comes in
                    // from the other side. Quicker the faster it was flicked.
                    val outMs = if (abs(velocity) > flingVelocity * 3) 90 else 140
                    offset.animateTo(if (goNext) -width else width, androidx.compose.animation.core.tween(outMs))
                    val before = MusicPlayer.state.value.playId
                    skip?.from = before
                    if (goNext) MusicPlayer.next() else MusicPlayer.previousSong()
                    // Wait for the new song to be on (a moment), so the old one isn't shown
                    // coming back in. By playId: two queued copies of one song too.
                    kotlinx.coroutines.withTimeoutOrNull(400) {
                        MusicPlayer.state.first { it.playId != before }
                    }
                    offset.snapTo(if (goNext) width * 0.6f else -width * 0.6f)
                    changing = false
                }
                offset.animateTo(0f, spring(stiffness = 500f))
            } finally {
                changing = false
                skip?.from = null
            }
        }
    }

    return this
        .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (abs(offset.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.7f)
        }
        // Not keyed on hasNext/hasPrevious (they change with every skip, restarting the detector
        // mid-gesture): `state` is read fresh each time.
        .pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dx = 0f
                var dy = 0f
                var dragging = false
                var released = false
                try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val step = change.positionChange()
                    tracker.addPointerInputChange(change)
                    if (!change.pressed) {
                        // A very fast flick does most of its distance in the lift itself (one
                        // move, or none, then the up): never past the slop while held, it was
                        // dropped. Clearly sideways and past the slop with the lift, it counts.
                        val totalX = dx + step.x
                        if (!dragging && (abs(totalX) <= slop || abs(totalX) <= abs(dy + step.y))) break
                        change.consume()
                        val tracked = tracker.calculateVelocity().x
                        val elapsed = (change.uptimeMillis - down.uptimeMillis).coerceAtLeast(1L)
                        // Down and up alone give the tracker nothing: the whole distance over the
                        // whole time.
                        release(totalX, if (tracked != 0f) tracked else totalX * 1000f / elapsed, size.width.toFloat())
                        released = true
                        break
                    }
                    dx += step.x
                    dy += step.y
                    if (!dragging) {
                        // Up or down first: not this swipe's (the player's own drags).
                        if (abs(dy) > slop && abs(dy) >= abs(dx)) break
                        if (abs(dx) > slop && abs(dx) > abs(dy)) {
                            dragging = true
                            // Catches the slide back in of the swipe before, where it is (not while
                            // the song is still changing: that one runs on, this one after it).
                            if (!changing) settle?.cancel()
                        } else continue
                    }
                    change.consume()
                    if (changing) continue
                    // Resists past an end with nothing there.
                    val by = step.x
                    val blocked = (offset.value + by < 0 && !state.hasNext) || (offset.value + by > 0 && !state.hasPrevious)
                    scope.launch { offset.snapTo(offset.value + if (blocked) by * 0.3f else by) }
                }
                } finally {
                    // Ended some other way (cancelled, another finger): back to rest.
                    if (dragging && !released && !changing) scope.launch { offset.animateTo(0f, spring(stiffness = 500f)) }
                }
            }
        }
}

/**
 * [content] for the song on, slid sideways when the song changes, whatever changed it (a button,
 * a swipe, a song ending, a pick in the queue): the new one comes in from the right going forward,
 * from the left going back.
 */
@Composable
fun SongTransition(
    modifier: Modifier = Modifier,
    skip: SwipeSkipState? = null,
    // False for what's drawn past its bounds (the player cover's glow).
    clip: Boolean = true,
    content: @Composable (com.comfort.app.music.Song?) -> Unit,
) {
    val state by MusicPlayer.state.collectAsState()
    // Keyed on playId, not the song: going from one queued copy of a song to the next is a change.
    androidx.compose.animation.AnimatedContent(
        targetState = state.playId to state.song,
        contentKey = { it.first },
        transitionSpec = {
            if (skip?.from != null && skip.from == initialState.first) {
                // A swipe changed it: its own slide is already on screen.
                return@AnimatedContent (androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None) using
                    androidx.compose.animation.SizeTransform(clip = clip)
            }
            val forward = state.forward
            (androidx.compose.animation.slideInHorizontally(spring(stiffness = 400f)) { if (forward) it else -it } +
                androidx.compose.animation.fadeIn()) togetherWith
                (androidx.compose.animation.slideOutHorizontally(spring(stiffness = 400f)) { if (forward) -it else it } +
                    androidx.compose.animation.fadeOut()) using androidx.compose.animation.SizeTransform(clip = clip)
        },
        label = "song",
        modifier = modifier,
    ) { (_, song) -> content(song) }
}
