package com.comfort.app.ui.main

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/** Shared plumbing for every "this whole screen/overlay animates away on back, revealing whatever
 * renders behind it" spot in the app (Download Queue over the current tab, a tab back to Home, a
 * Settings sub-screen back to the root list, ...) — same "peek behind as you swipe" effect apps
 * like Tachiyomi use for predictive back, instead of an instant show/hide.
 *
 * Returns a 0f (fully covering) .. 1f (fully swiped away) progress value tracking the live
 * gesture. Callers render the "front" content with [Modifier.predictiveBackReveal] applied using
 * that progress, and must keep whatever's "behind" it actually composed underneath — there's
 * nothing to reveal if the caller unconditionally replaces the whole tree the way a bare `if` /
 * early return would.
 *
 * [onBack] fires once, only when the gesture actually completes — not on every progress tick, and
 * not when the gesture is cancelled partway through (the progress instead springs back to 0). */
@Composable
fun rememberPredictiveBackProgress(enabled: Boolean, onBack: () -> Unit): Float {
    val progress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = enabled) { gestureProgress ->
        try {
            gestureProgress.collect { backEvent -> progress.snapTo(backEvent.progress) }
            // Gesture completed (finger lifted past the commit threshold).
            onBack()
            progress.snapTo(0f)
        } catch (e: CancellationException) {
            // Gesture cancelled (dragged back, or lifted too early) — spring back to fully
            // covering instead of leaving the content stuck mid-peek.
            progress.animateTo(0f, animationSpec = tween(200))
        }
    }
    return progress.value
}

/** Shrinks, nudges toward the bottom-right, and rounds the corners of whatever this is applied to
 * as [progress] climbs from 0 to 1 — paired with [rememberPredictiveBackProgress]'s live gesture
 * progress so the front layer visibly peels away and reveals the content composed behind it. */
fun Modifier.predictiveBackReveal(progress: Float): Modifier = this
    .graphicsLayer {
        val scale = 1f - progress * 0.15f
        scaleX = scale
        scaleY = scale
        translationX = size.width * 0.05f * progress
        translationY = size.height * 0.03f * progress
    }
    .clip(RoundedCornerShape((progress * 28).dp))
