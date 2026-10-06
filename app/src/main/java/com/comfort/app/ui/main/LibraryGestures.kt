package com.comfort.app.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

// The Library grid's two gestures: hold a tile to peek at it, pinch the grid to change tile size.

/** Hold to peek: once the press becomes a long press, [onPeekStart]; releasing calls [onPeekEnd].
 * Moving the finger noticeably while peeking instead ends the peek and calls [onSelect] — what a
 * long press did before, so selecting still starts from a hold. After the long press every event
 * is consumed in the Initial pass, so the grid doesn't scroll under the finger and the tile's own
 * click doesn't fire on release. Before it, nothing is consumed: a drag still scrolls the grid
 * (which cancels the long press) and a quick tap still clicks. [key] restarts the detector when
 * the tile shows a different item. */
fun Modifier.holdToPeek(
    key: Any?,
    onPeekStart: () -> Unit,
    onPeekEnd: () -> Unit,
    onSelect: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        onPeekStart()
        val start = longPress.position
        val moveToSelect = viewConfiguration.touchSlop * 2
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            val change = event.changes.firstOrNull { it.id == longPress.id } ?: event.changes.first()
            if (!change.pressed) {
                onPeekEnd()
                return@awaitEachGesture
            }
            if ((change.position - start).getDistance() > moveToSelect) {
                onPeekEnd()
                onSelect()
                // Swallow the rest of this touch.
                while (true) {
                    val rest = awaitPointerEvent(PointerEventPass.Initial)
                    rest.changes.forEach { it.consume() }
                    if (rest.changes.none { it.pressed }) return@awaitEachGesture
                }
            }
        }
    }
}

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
