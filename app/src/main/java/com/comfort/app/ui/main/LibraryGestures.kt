package com.comfort.app.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

// The Library grid's pinch: change tile size.

/** Pinch: one [onStep] per gesture, +1 for spreading the fingers past [threshold] (bigger tiles),
 * -1 for pinching in. Only two-finger movement is consumed, so one finger still scrolls. */
fun Modifier.pinchSteps(onStep: (Int) -> Unit, threshold: Float = 1.25f): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var stepped = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
                if (!stepped && zoom >= threshold) { onStep(+1); stepped = true }
                if (!stepped && zoom <= 1f / threshold) { onStep(-1); stepped = true }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** The grid's three tile sizes: (target row height, minimum tile width, maximum row height), each
 * a fraction of the grid's width. 1 is the default. */
val LIBRARY_GRID_SIZES = listOf(
    Triple(0.4f, 0.2f, 0.42f),
    Triple(0.62f, 0.3f, 0.6f),
    Triple(0.95f, 0.45f, 0.9f),
)
