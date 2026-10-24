package com.comfort.app.ui.main

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/** A text's size, as a factor of the normal one between [min] and [max]: pinched bigger or
 * smaller, and kept for next time under [key] in its own preferences. */
@Stable
internal class TextZoom(private val prefs: SharedPreferences, private val key: String, private val min: Float, private val max: Float) {
    private val state = mutableFloatStateOf(prefs.getFloat(key, 1f).coerceIn(min, max))

    val factor: Float get() = state.floatValue

    internal fun scaleBy(by: Float) {
        state.floatValue = (state.floatValue * by).coerceIn(min, max)
    }

    internal fun save() {
        prefs.edit().putFloat(key, state.floatValue).apply()
    }
}

/** The text size saved in [prefsName] under [key], kept between [min] and [max]. Each place that
 * zooms has its own file and key, so one's size doesn't change the other's. */
@Composable
internal fun rememberTextZoom(prefsName: String, key: String, min: Float, max: Float): TextZoom {
    val context = LocalContext.current
    return remember { TextZoom(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), key, min, max) }
}

/** Two fingers pinch the text bigger or smaller; one finger still scrolls it. Only a two-finger
 * move is taken, and it's taken before the list sees it, so a pinch doesn't also scroll while a
 * single finger is left to the list. The size is saved once the pinch ends, not on every move. */
internal fun Modifier.pinchToZoomText(zoom: TextZoom): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinched = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) {
                    zoom.scaleBy(factor)
                    pinched = true
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinched) zoom.save()
    }
}
