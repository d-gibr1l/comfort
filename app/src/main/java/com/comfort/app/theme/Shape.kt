package com.comfort.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val PillShape = RoundedCornerShape(50)

// Used only when AppTheme.EXPRESSIVE is the active theme (see Theme.kt's GalleryDLTheme) — M3
// Expressive pushes corners noticeably larger than the app's default scale: medium/large cover
// the spec's "32dp cards", extraLarge covers "about 40dp dialogs and sheets" (both AlertDialog
// and ModalBottomSheet read shapes.extraLarge by default).
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(32.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

/** A pill whose round ends flow into its long sides (iOS's "continuous" capsule): from each side, the
 * curve bends gradually (its curvature rising from zero, a clothoid) over 30° before turning into
 * the half-height circle, and eases out again the same way. A plain pill's ends meet the sides
 * with a sudden change of curvature, which shows as a faint kink. Not meant as a clip: a clip to a
 * path costs a mask every frame (see the Library's thumbnails). */
object ContinuousCapsuleShape : androidx.compose.ui.graphics.Shape {
    // One end, from the top side round to the bottom, for a height of 1: x goes left from 0.
    private val unitEnd: FloatArray = run {
        val steps = 64
        val turn = Math.toRadians(30.0)
        val ramp = 2 * turn // the clothoid's length, for a final radius of 1
        val total = 2 * ramp + (Math.PI - 2 * turn)
        fun heading(u: Double) = when {
            u <= ramp -> u * u / (2 * ramp)
            u <= total - ramp -> turn + (u - ramp)
            else -> (total - u).let { v -> Math.PI - v * v / (2 * ramp) }
        }
        val out = FloatArray((steps + 1) * 2)
        var x = 0.0
        var y = 0.0
        val du = total / steps
        for (i in 1..steps) {
            val p = heading((i - 0.5) * du)
            x -= Math.cos(p) * du
            y += Math.sin(p) * du
            out[i * 2] = x.toFloat()
            out[i * 2 + 1] = y.toFloat()
        }
        val height = y.toFloat()
        for (i in out.indices) out[i] /= height
        out
    }
    // How far an end reaches along the sides, in heights.
    private val reach: Float = -(0 until unitEnd.size / 2).minOf { unitEnd[it * 2] }

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val h = size.height
        val w = size.width
        if (w < 2 * reach * h) return androidx.compose.ui.graphics.Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, androidx.compose.ui.geometry.CornerRadius(minOf(w, h) / 2f))
        )
        val n = unitEnd.size / 2
        val path = androidx.compose.ui.graphics.Path()
        val left = reach * h
        // Left end, top to bottom, then the right end (the left one turned half round).
        for (i in 0 until n) {
            val x = left + unitEnd[i * 2] * h
            val y = unitEnd[i * 2 + 1] * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in 0 until n) path.lineTo(w - left - unitEnd[i * 2] * h, h - unitEnd[i * 2 + 1] * h)
        path.close()
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}
