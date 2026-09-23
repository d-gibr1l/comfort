package com.comfort.app.ui.main

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

/** Shared plumbing for every "this screen/overlay goes away on back, revealing the page under it"
 * spot in the app (Download Queue over the current tab, a tab back to Home, a Settings sub-screen
 * back to the root list).
 *
 * The motion is TachiyomiJ2K's back transition, ported from its BackHandlerControllerInterface /
 * CrossFadeChangeHandler (chosen live over the Material 3 shrink):
 *  - while swiping, the gesture progress is eased as q = (progress * 0.5)^0.6; the page in front
 *    slides right by q * 15% of its width and fades to 1 - q, with a soft shadow along its left
 *    edge; the page behind trails it 20% of a width further left and fades *in* to q;
 *  - on release (or an on-screen back button, which plays the same thing from the start) the
 *    front page carries on to +20% and fades out while the page behind slides into place and
 *    fades fully in, over up to 150ms, decelerating;
 *  - a cancelled gesture eases everything back.
 * Opening a page plays J2K's push, the mirror image (see [BackRevealState.animateEnter]).
 *
 * Callers apply [Modifier.predictiveBackReveal] to the page in front and
 * [Modifier.predictiveBackBehind] to the page it reveals, and must keep that page composed
 * underneath. While a page is covered and nothing is happening, the page behind is drawn at
 * alpha 0 — it's fully hidden anyway, so this also saves drawing it. */
@Stable
class BackRevealState internal constructor(
    private val scope: CoroutineScope,
    private val onBack: State<() -> Unit>,
) {
    // Fractions of the page width / plain alphas — read only in the draw phase (see modifiers).
    internal val frontX = Animatable(0f)
    internal val frontAlpha = Animatable(1f)
    internal val behindX = Animatable(-BEHIND_OFFSET)
    internal val behindAlpha = Animatable(0f)
    internal val shadow = Animatable(0f)
    private var finishing = false

    /** An on-screen back button: the same transition as releasing a back swipe, from the start. */
    fun animateBack() {
        if (finishing) return
        finishing = true
        scope.launch { commit() }
    }

    /** Opens the page in front — J2K's push, the mirror of its back: the new page comes in from
     * 20% to the right while fading in, and the page behind slides 20% left while fading out, over
     * 200ms. [change] is what makes the new page appear; it runs after the starting values are
     * set, in the same frame, so the page never shows for a frame at rest first.
     * [behindVisible] is false when the page behind is already hidden (switching between two
     * pages that both sit on top of it, e.g. Library to Settings), so only the new page moves. */
    fun animateEnter(behindVisible: Boolean = true, onFinished: () -> Unit = {}, change: () -> Unit) {
        if (finishing) {
            change()
            onFinished()
            return
        }
        finishing = true
        scope.launch {
            try {
                frontX.snapTo(ENTER_FROM_X)
                frontAlpha.snapTo(0f)
                behindX.snapTo(0f)
                behindAlpha.snapTo(if (behindVisible) 1f else 0f)
                shadow.snapTo(0f)
                change()
                // The new page's first frame is a long one (it's composed from scratch — Settings
                // took ~70ms on-device), and an animation started now would spend its first
                // ~150ms of 200 inside it: recorded live, the page jumped in over ~3 frames. So
                // wait out that frame, and start the clock on the first normal one after it. The
                // page sits at the start values (invisible, to the right) meanwhile.
                withFrameNanos { }
                withFrameNanos { }
                val spec = tween<Float>(ENTER_MS, easing = ACCELERATE_DECELERATE)
                coroutineScope {
                    launch { frontX.animateTo(0f, spec) }
                    launch { frontAlpha.animateTo(1f, spec) }
                    launch { behindX.animateTo(-BEHIND_OFFSET, spec) }
                    launch { behindAlpha.animateTo(0f, spec) }
                }
            } finally {
                finishing = false
                onFinished()
            }
        }
    }

    internal suspend fun track(progress: Float) {
        val q = ((progress.takeIf { it > 0.001f } ?: 0f) * 0.5f).pow(0.6f)
        frontX.snapTo(q * FRONT_DRAG)
        frontAlpha.snapTo(1f - q)
        behindX.snapTo(q * FRONT_DRAG - BEHIND_OFFSET)
        behindAlpha.snapTo(q)
        shadow.snapTo(1f)
    }

    internal suspend fun finishFromGesture() {
        if (finishing) return
        finishing = true
        commit()
    }

    internal suspend fun cancelGesture() = coroutineScope {
        val spec = tween<Float>(CANCEL_MS, easing = DECELERATE)
        launch { frontX.animateTo(0f, spec) }
        launch { frontAlpha.animateTo(1f, spec) }
        launch { behindX.animateTo(-BEHIND_OFFSET, spec) }
        launch { behindAlpha.animateTo(0f, spec) }
        launch { shadow.animateTo(0f, spec) }
    }

    private suspend fun commit() {
        try {
            // Time scales with the distance still to cover, like CrossFadeChangeHandler's pop.
            val remaining = ((EXIT_X - frontX.value) / EXIT_X).coerceIn(0f, 1f)
            val spec = tween<Float>((remaining * COMMIT_MS).roundToInt().coerceAtLeast(60), easing = DECELERATE)
            coroutineScope {
                launch { frontX.animateTo(EXIT_X, spec) }
                launch { frontAlpha.animateTo(0f, spec) }
                launch { behindX.animateTo(0f, spec) }
                launch { behindAlpha.animateTo(1f, spec) }
                launch { shadow.animateTo(0f, spec) }
            }
            // The state change and the reset below land in the same recomposition, so the page
            // behind (now the visible page) is already back to identity when the front one goes.
            onBack.value()
            frontX.snapTo(0f)
            frontAlpha.snapTo(1f)
            behindX.snapTo(-BEHIND_OFFSET)
            behindAlpha.snapTo(0f)
            shadow.snapTo(0f)
        } finally {
            // Always: a stuck flag made every later animateEnter()/animateBack() skip its motion.
            finishing = false
        }
    }

    private companion object {
        const val FRONT_DRAG = 0.15f
        const val BEHIND_OFFSET = 0.2f
        const val EXIT_X = 0.2f
        const val COMMIT_MS = 150f
        const val CANCEL_MS = 150
        // LinearOutSlowIn — CrossFadeChangeHandler's own fallback when there's no fling velocity.
        val DECELERATE = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        // Push: CrossFadeChangeHandler's 200ms with the Animator default interpolator
        // (AccelerateDecelerate, approximated as a cubic).
        const val ENTER_FROM_X = 0.2f
        const val ENTER_MS = 200
        val ACCELERATE_DECELERATE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    }
}

@Composable
fun rememberBackRevealState(enabled: Boolean, onBack: () -> Unit): BackRevealState {
    val scope = rememberCoroutineScope()
    val currentOnBack = rememberUpdatedState(onBack)
    val state = remember { BackRevealState(scope, currentOnBack) }
    PredictiveBackHandler(enabled = enabled) { gestureProgress ->
        try {
            gestureProgress.collect { backEvent -> state.track(backEvent.progress) }
            // Gesture completed (finger lifted past the commit threshold).
            state.finishFromGesture()
        } catch (e: CancellationException) {
            state.cancelGesture()
        }
    }
    return state
}

/** The page in front: slides right and fades, with J2K's soft shadow along its left edge (drawn
 * just outside its bounds, inside its own layer so it moves and fades with it). */
fun Modifier.predictiveBackReveal(state: BackRevealState): Modifier = this
    .graphicsLayer {
        translationX = state.frontX.value * size.width
        alpha = state.frontAlpha.value
    }
    .drawBehind {
        val s = state.shadow.value
        if (s > 0f) {
            val width = 16.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.33f * s)),
                    startX = -width, endX = 0f,
                ),
                topLeft = Offset(-width, 0f),
                size = Size(width, size.height),
            )
        }
    }

/** The page revealed underneath, while [active] (a page is actually on top of it): trails the
 * front page from the left and fades in as it goes. Inactive, it's left untouched — it's then
 * simply the visible page. */
fun Modifier.predictiveBackBehind(state: BackRevealState, active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        this.graphicsLayer {
            translationX = state.behindX.value * size.width
            alpha = state.behindAlpha.value
        }
    }
