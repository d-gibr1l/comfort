package com.comfort.app.util

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Android's own reduce-motion setting (Settings > Accessibility > Remove animations) works by
 * zeroing [Settings.Global.ANIMATOR_DURATION_SCALE] system-wide — there's no separate Compose- or
 * app-level flag to read, this *is* the flag, same one the platform's own View animator system
 * checks. Mirrors [rememberIsNetworkAvailable] (a live [ContentObserver] rather than a one-shot
 * read, so toggling the setting while a screen is open updates it immediately) — better-interface
 * review: QueueScreen's per-item slide-in and the wavy progress indicator's wave motion both
 * animated unconditionally with nothing checking this. */
@Composable
fun rememberIsReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(readAnimatorScale(context) == 0f) }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                reduced = readAnimatorScale(context) == 0f
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    return reduced
}

private fun readAnimatorScale(context: android.content.Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
