/*

// Pinch, double-tap and drag zoom for the viewer's pages.
 * Ported from Photon Gallery (https://github.com/Bn5prS/Photon_Gallery), GPL-3.0, like Comfort.
 * Adapted for Comfort: package, imports and the pieces Photon's app provides that Comfort doesn't
 * (see the notes where they were changed).
 */
package com.comfort.app.ui.viewer


import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures


import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch

/** The viewer's zoom: pinch, double-tap and pan (with a fling) a page's [scale] and offsets, for a
 * photo and a video alike. Before the page's graphicsLayer, so the pointer events arrive in layout
 * space (see the photo's note). A single tap is [onTap]; [onScaleChange] hears the scale when the
 * page goes in or out of being zoomed (the pager and swipe-to-dismiss are off while zoomed). */
internal fun Modifier.viewerZoom(
    scale: androidx.compose.runtime.MutableFloatState,
    offsetX: androidx.compose.runtime.MutableFloatState,
    offsetY: androidx.compose.runtime.MutableFloatState,
    animJob: androidx.compose.runtime.MutableState<kotlinx.coroutines.Job?>,
    scope: kotlinx.coroutines.CoroutineScope,
    currentScale: () -> Float,
    onScaleChange: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onTap: () -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { centroid ->
                animJob.value?.cancel()
                animJob.value = scope.launch {
                    val composableW = size.width.toFloat()
                    val composableH = size.height.toFloat()
                    if (scale.floatValue > 1f) {
                        // Zoom out to fit
                        onScaleChange(1f)
                        launch { androidx.compose.animation.core.animate(scale.floatValue, 1f, animationSpec = MotionTokens.gentleSpring()) { v, _ -> scale.floatValue = v } }
                        launch { androidx.compose.animation.core.animate(offsetX.floatValue, 0f, animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetX.floatValue = v } }
                        launch { androidx.compose.animation.core.animate(offsetY.floatValue, 0f, animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetY.floatValue = v } }
                    } else {
                        // Zoom in to tapped point
                        val targetScale = 3.5f
                        onScaleChange(targetScale)
                        val targetX = -(centroid.x - composableW / 2f) * (targetScale - 1)
                        val targetY = -(centroid.y - composableH / 2f) * (targetScale - 1)
                        val maxX = (composableW * (targetScale - 1)) / 2f
                        val maxY = (composableH * (targetScale - 1)) / 2f
                        launch { androidx.compose.animation.core.animate(scale.floatValue, targetScale, animationSpec = MotionTokens.gentleSpring()) { v, _ -> scale.floatValue = v } }
                        launch { androidx.compose.animation.core.animate(offsetX.floatValue, targetX.coerceIn(-maxX, maxX), animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetX.floatValue = v } }
                        launch { androidx.compose.animation.core.animate(offsetY.floatValue, targetY.coerceIn(-maxY, maxY), animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetY.floatValue = v } }
                    }
                }
            }
        )
    }
    .pointerInput(Unit) {
        // Unified gesture handler: pinch-zoom, pan
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            val downTime = System.currentTimeMillis()
            val downPosition = firstDown.position

            var shouldStartZoomPan = scale.floatValue > 1f
            if (!shouldStartZoomPan) {
                // Wait to see if a second finger is placed down (pinch-to-zoom)
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break
                    if (event.changes.size > 1) {
                        shouldStartZoomPan = true
                        break
                    }
                    if (!event.changes.any { it.pressed }) break
                }
            }

            if (shouldStartZoomPan) {
                var accumulatedZoom = 1f
                var accumulatedPan = Offset.Zero
                var pastTouchSlop = false
                val touchSlop = viewConfiguration.touchSlop
                val composableW = size.width.toFloat()
                val composableH = size.height.toFloat()
                val velocityTracker = VelocityTracker()
                var maxPointerCount = 0

                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break

                    val pressedCount = event.changes.count { it.pressed }
                    maxPointerCount = maxOf(maxPointerCount, pressedCount)

                    if (pressedCount == 1) {
                        event.changes.firstOrNull { it.pressed }
                            ?.let { velocityTracker.addPointerInputChange(it) }
                    }

                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()

                    if (!pastTouchSlop) {
                        accumulatedZoom *= zoomChange
                        accumulatedPan += panChange
                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                        val zoomMotion = kotlin.math.abs(1 - accumulatedZoom) * centroidSize
                        val panMotion = accumulatedPan.getDistance()
                        if (zoomMotion > touchSlop || panMotion > touchSlop) {
                            pastTouchSlop = true
                        }
                    }

                    if (pastTouchSlop && scale.floatValue <= 1.05f && maxPointerCount == 1) {
                        break
                    }

                    if (pastTouchSlop && (zoomChange != 1f || panChange != Offset.Zero)) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        animJob.value?.cancel()
                        val newScale = (scale.floatValue * zoomChange).coerceIn(1f, 20f)

                        if (newScale > 1.05f && currentScale() <= 1.05f) {
                            onScaleChange(newScale)
                        } else if (newScale <= 1.05f && currentScale() > 1.05f) {
                            onScaleChange(newScale)
                        }

                        val maxX = (composableW * (newScale - 1)) / 2f
                        val maxY = (composableH * (newScale - 1)) / 2f

                        val focalX = centroid.x - composableW / 2f
                        val focalY = centroid.y - composableH / 2f
                        val effectiveZoom = newScale / scale.floatValue
                        var newOffsetX = (offsetX.floatValue - focalX) * effectiveZoom + focalX + panChange.x
                        var newOffsetY = (offsetY.floatValue - focalY) * effectiveZoom + focalY + panChange.y

                        newOffsetX = newOffsetX.coerceIn(-maxX, maxX)
                        newOffsetY = newOffsetY.coerceIn(-maxY, maxY)

                        scale.floatValue = newScale
                        offsetX.floatValue = newOffsetX
                        offsetY.floatValue = newOffsetY

                        if (newScale > 1f) {
                            val hittingLeft = newOffsetX >= maxX && panChange.x > 0
                            val hittingRight = newOffsetX <= -maxX && panChange.x < 0
                            if (!hittingLeft && !hittingRight) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }

                    if (!event.changes.any { it.pressed }) break
                }

                // The controls hide once the fingers are up, not during the pinch: hiding them
                // there (the system bars, the backdrop, the controls all animating at once) made
                // the pinch stutter.
                if (scale.floatValue > 1.02f) onZoomIn()

                if (pastTouchSlop && scale.floatValue > 1.05f && maxPointerCount == 1) {
                    val velocity = velocityTracker.calculateVelocity()
                    val currentMaxX = (composableW * (scale.floatValue - 1)) / 2f
                    val currentMaxY = (composableH * (scale.floatValue - 1)) / 2f
                    val flingFactor = 0.4f
                    val targetFlingX = (offsetX.floatValue + velocity.x * flingFactor)
                        .coerceIn(-currentMaxX, currentMaxX)
                    val targetFlingY = (offsetY.floatValue + velocity.y * flingFactor)
                        .coerceIn(-currentMaxY, currentMaxY)
                    animJob.value?.cancel()
                    animJob.value = scope.launch {
                        launch {
                            androidx.compose.animation.core.animate(
                                initialValue = offsetX.floatValue,
                                targetValue = targetFlingX,
                                initialVelocity = velocity.x,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessVeryLow
                                )
                            ) { v, _ -> offsetX.floatValue = v }
                        }
                        launch {
                            androidx.compose.animation.core.animate(
                                initialValue = offsetY.floatValue,
                                targetValue = targetFlingY,
                                initialVelocity = velocity.y,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessVeryLow
                                )
                            ) { v, _ -> offsetY.floatValue = v }
                        }
                    }
                }
            }
        }
    }
