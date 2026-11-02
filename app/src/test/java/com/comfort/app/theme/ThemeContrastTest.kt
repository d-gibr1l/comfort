package com.comfort.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Test

/** Every built-in theme's text/background pairings meet WCAG's contrast (4.5:1 for text, 3:1 for
 * outlines): light, dark, and dark with pure black. Dynamic (wallpaper) colours come from the
 * system and aren't checked. */
class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    private fun pureBlack(s: ColorScheme) = s.copy(
        background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF0D0D0D),
        surfaceContainerHigh = Color(0xFF141414), surfaceContainerHighest = Color(0xFF1A1A1A),
    )

    private fun pairs(s: ColorScheme) = listOf(
        "onBackground/background" to (s.onBackground to s.background),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurface/surfaceContainer" to (s.onSurface to s.surfaceContainer),
        "onSurface/surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
        "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant/surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
        "onSurfaceVariant/surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
        "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
        "primary/background" to (s.primary to s.background),
        "primary/surfaceContainer" to (s.primary to s.surfaceContainer),
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "error/surfaceContainer" to (s.error to s.surfaceContainer),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
        "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
        "outline/surface (3)" to (s.outline to s.surface),
    )

    @Test
    fun everyPairingIsReadable() {
        val bad = mutableListOf<String>()
        for (t in AppTheme.entries.filter { !it.isDynamic }) {
            val schemes = listOfNotNull(
                t.light?.let { "light" to it }, t.dark?.let { "dark" to it }, t.dark?.let { "dark+black" to pureBlack(it) },
            )
            for ((mode, s) in schemes) for ((name, p) in pairs(s)) {
                val r = ratio(p.first, p.second)
                val need = if (name.endsWith("(3)")) 3.0 else 4.5
                if (r < need) bad += "%-11s %-10s %-42s %.2f".format(t.name, mode, name, r)
            }
        }
        org.junit.Assert.assertTrue("Below target:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
