package com.comfort.app.ui.music

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val GLIDE_MS = 900f
// Held at the old place a moment first: run at once, the glide was over behind the player's
// slide in (or the app's splash) before it could be seen.
private const val GLIDE_DELAY_MS = 280L
private val GlideEasing = CubicBezierEasing(0.3f, 0f, 0.15f, 1f)

// When the app last came on screen (MainActivity.onStart): opened, or back from outside it.
@Volatile private var appStartedAt = 0L

internal fun markAppStarted() {
    appStartedAt = android.os.SystemClock.uptimeMillis()
}

// A player starting within this of the app coming on screen came with it (the app reopened with
// it showing, or back to it from outside); later, it was opened from inside the app.
private const val CAME_WITH_APP_MS = 1500L

/** The playing position a player shows, from [live] (ms): the same as it, except when you come
 * back to the app — from outside it, or reopening it — when it glides there from where it was
 * instead of jumping. Not when a player is opened from inside the app: it's simply there (asked
 * for). The progress bar and the lyrics read this, so they glide together. */
@Composable
internal fun rememberGlidingPosition(live: () -> Long): State<Long> {
    // Opened from inside the app: at the song's place from the start. Come up with the app (it
    // was reopened): from the start, gliding to it.
    val shown = remember {
        val withApp = android.os.SystemClock.uptimeMillis() - appStartedAt < CAME_WITH_APP_MS
        mutableLongStateOf(if (withApp) 0L else live())
    }
    // Bumped on each ON_START: the first one comes as the observer is added (the player opening).
    var glides by remember { mutableIntStateOf(0) }
    val away = remember { booleanArrayOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    away[0] = false
                    // Only coming back to the app (the first ON_START also comes as the observer
                    // is added: the player opening, which glides only if the app just came up).
                    if (android.os.SystemClock.uptimeMillis() - appStartedAt < CAME_WITH_APP_MS) glides++
                }
                // Kept where it was while the app is away: what's drawn freezes then, and the glide
                // on coming back starts from it (followed on, it had already jumped by then).
                Lifecycle.Event.ON_STOP -> away[0] = true
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(glides) {
        if (glides > 0) {
            // From what was last drawn (frozen while the app was away) to the live position, which
            // keeps moving meanwhile: read every frame, so the glide ends right on it.
            val from = shown.longValue
            kotlinx.coroutines.delay(GLIDE_DELAY_MS)
            val start = withFrameNanos { it }
            while (true) {
                val t = ((withFrameNanos { it } - start) / 1_000_000f / GLIDE_MS).coerceAtMost(1f)
                val to = live()
                shown.longValue = from + ((to - from) * GlideEasing.transform(t)).toLong()
                if (t >= 1f) break
            }
        }
        snapshotFlow { live() }.collect { if (!away[0]) shown.longValue = it }
    }
    return shown
}
