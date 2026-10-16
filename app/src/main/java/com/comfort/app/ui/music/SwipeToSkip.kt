package com.comfort.app.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    internal var swiped = false
}

@Composable
fun rememberSwipeSkipState() = remember { SwipeSkipState() }

@Composable
fun Modifier.swipeToSkip(skip: SwipeSkipState? = null): Modifier {
    val state by MusicPlayer.state.collectAsState()
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    return this
        .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (abs(offset.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.7f)
        }
        .pointerInput(state.hasNext, state.hasPrevious) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        val width = size.width.toFloat()
                        val v = offset.value
                        val toNext = v < -threshold && state.hasNext
                        val toPrevious = v > threshold && state.hasPrevious
                        if (toNext || toPrevious) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                            // Off the way the finger went, the song changes, and the next one
                            // comes in from the other side.
                            offset.animateTo(if (toNext) -width else width, androidx.compose.animation.core.tween(140))
                            val before = MusicPlayer.state.value.song?.id
                            skip?.swiped = true
                            if (toNext) MusicPlayer.next() else MusicPlayer.previousSong()
                            // Wait for the new song to be on (a moment), so the old one isn't
                            // shown coming back in.
                            kotlinx.coroutines.withTimeoutOrNull(400) {
                                MusicPlayer.state.first { it.song?.id != before }
                            }
                            offset.snapTo(if (toNext) width * 0.6f else -width * 0.6f)
                        }
                        offset.animateTo(0f, spring(stiffness = 500f))
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, spring(stiffness = 500f)) } },
            ) { change, dragAmount ->
                change.consume()
                // Resists past an end with nothing there.
                val blocked = (offset.value + dragAmount < 0 && !state.hasNext) || (offset.value + dragAmount > 0 && !state.hasPrevious)
                scope.launch { offset.snapTo(offset.value + if (blocked) dragAmount * 0.3f else dragAmount) }
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
    content: @Composable (com.comfort.app.music.Song?) -> Unit,
) {
    val state by MusicPlayer.state.collectAsState()
    androidx.compose.animation.AnimatedContent(
        targetState = state.song,
        contentKey = { it?.id },
        transitionSpec = {
            if (skip?.swiped == true) {
                // A swipe changed it: its own slide is already on screen.
                skip.swiped = false
                return@AnimatedContent androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            }
            val forward = state.forward
            (androidx.compose.animation.slideInHorizontally(spring(stiffness = 400f)) { if (forward) it else -it } +
                androidx.compose.animation.fadeIn()) togetherWith
                (androidx.compose.animation.slideOutHorizontally(spring(stiffness = 400f)) { if (forward) -it else it } +
                    androidx.compose.animation.fadeOut())
        },
        label = "song",
        modifier = modifier,
    ) { song -> content(song) }
}
