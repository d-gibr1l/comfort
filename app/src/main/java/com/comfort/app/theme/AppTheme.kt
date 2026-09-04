package com.comfort.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Builds a full light/dark ColorScheme from just the handful of roles that actually differ
// between named themes (primary/secondary/background/surface); everything else (neutrals,
// outlines, error) is shared so every theme still looks like the same app, just re-tinted.
private fun buildLight(primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color, secondary: Color, background: Color, surface: Color) = lightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = Teal90,
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = secondary,
    onTertiary = Color.White,
    background = background,
    onBackground = Neutral10,
    surface = surface,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = Neutral50,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = background,
    surfaceContainer = Neutral95,
    surfaceContainerHigh = Neutral90,
    surfaceContainerHighest = Neutral80,
    outline = Neutral80,
    outlineVariant = Neutral90,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
)

private fun buildDark(primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color, secondary: Color, background: Color, surface: Color) = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF0B4A42),
    onSecondaryContainer = Teal90,
    tertiary = secondary,
    onTertiary = Color(0xFF00201C),
    background = background,
    onBackground = Neutral90,
    surface = surface,
    onSurface = Neutral90,
    surfaceVariant = Neutral20,
    onSurfaceVariant = Neutral80,
    surfaceContainerLowest = background,
    surfaceContainerLow = surface,
    surfaceContainer = Neutral10,
    surfaceContainerHigh = Neutral20,
    surfaceContainerHighest = Neutral30,
    outline = Neutral30,
    outlineVariant = Neutral20,
    error = Red80,
    onError = Red10,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Red90,
)

// A selectable named theme — mirrors the "Appearance" screen pattern from TachiyomiJ2K (a light
// row + a dark row of preview cards), adapted to this app's own palette. Every non-dynamic entry
// carries both a light and dark ColorScheme so it can appear in either row; DYNAMIC has neither —
// its scheme is derived on the fly from the device wallpaper (Android 12+ only), so callers must
// special-case `isDynamic` rather than reading `light`/`dark` for it.
enum class AppTheme(
    val lightLabel: String,
    val darkLabel: String,
    val isDynamic: Boolean = false,
    val light: ColorScheme? = null,
    val dark: ColorScheme? = null,
) {
    DYNAMIC(
        lightLabel = "Dynamic (from wallpaper)",
        darkLabel = "Dynamic (from wallpaper)",
        isDynamic = true,
    ),
    DEFAULT(
        lightLabel = "Pure White",
        darkLabel = "Dark",
        light = LightColorScheme,
        dark = DarkColorScheme,
    ),
    SUNSET(
        lightLabel = "Sunset Coral",
        darkLabel = "Ember Night",
        light = buildLight(
            primary = AccentRed, onPrimary = Color.White,
            primaryContainer = AccentCream, onPrimaryContainer = AccentRedDark,
            secondary = AccentRedDark,
            background = Color(0xFFFFF6F0), surface = Color.White,
        ),
        dark = buildDark(
            primary = AccentSalmon, onPrimary = Color(0xFF3D0D0D),
            primaryContainer = AccentRedDark, onPrimaryContainer = AccentCream,
            secondary = AccentCream,
            background = Color(0xFF160A08), surface = Color(0xFF1D0F0C),
        ),
    ),
    BLOSSOM(
        lightLabel = "Spring Blossom",
        darkLabel = "Midnight Dusk",
        light = buildLight(
            primary = Color(0xFFC43C97), onPrimary = Color.White,
            primaryContainer = Color(0xFFFCDCF0), onPrimaryContainer = Color(0xFF5C1147),
            // Darkened from 0xFFF02475 (same hue) — better-colors review: paired with fixed-white
            // text/icons (see buildLight's own onSecondary), that measured 4.03:1, under WCAG AA's
            // 4.5:1. Now 4.54:1.
            secondary = Color(0xFFE61065),
            background = Color(0xFFFAF3F8), surface = Color.White,
        ),
        dark = buildDark(
            primary = Color(0xFFFF8AD1), onPrimary = Color(0xFF4A0A38),
            primaryContainer = Color(0xFF6B1D53), onPrimaryContainer = Color(0xFFFCDCF0),
            secondary = Color(0xFFFF6FA0),
            background = Color(0xFF150910), surface = Color(0xFF1D0E16),
        ),
    ),
    OCEAN(
        lightLabel = "Teal Tide",
        darkLabel = "Sapphire Dusk",
        light = buildLight(
            primary = Teal40, onPrimary = Color.White,
            primaryContainer = Teal90, onPrimaryContainer = Color(0xFF00201C),
            secondary = Color(0xFF0369A1),
            background = Color(0xFFEFF8F7), surface = Color.White,
        ),
        dark = buildDark(
            primary = Teal80, onPrimary = Color(0xFF00201C),
            primaryContainer = Color(0xFF0B4A42), onPrimaryContainer = Teal90,
            secondary = Color(0xFF7DD3FC),
            background = Color(0xFF08120F), surface = Color(0xFF0D1815),
        ),
    ),
    LAVENDER(
        lightLabel = "Lavender",
        darkLabel = "Violet Night",
        light = buildLight(
            primary = Color(0xFF7B46AF), onPrimary = Color.White,
            primaryContainer = Color(0xFFEBDDFB), onPrimaryContainer = Color(0xFF350A64),
            secondary = Color(0xFF9333EA),
            background = Color(0xFFF6F1FB), surface = Color.White,
        ),
        dark = buildDark(
            primary = Color(0xFFB69DFF), onPrimary = Color(0xFF2C0A5C),
            primaryContainer = Color(0xFF4A2585), onPrimaryContainer = Color(0xFFEBDDFB),
            secondary = Color(0xFFC4B5FD),
            background = Color(0xFF120C18), surface = Color(0xFF19121F),
        ),
    ),
    TOKYO(
        lightLabel = "Tokyo Light",
        darkLabel = "Tokyo Night",
        light = buildLight(
            primary = Color(0xFF0072B2), onPrimary = Color.White,
            primaryContainer = Color(0xFFD6E9F8), onPrimaryContainer = Color(0xFF00304D),
            // Darkened from 0xFF3B82F6 (same hue) — better-colors review: paired with fixed-white
            // text/icons (see buildLight's own onSecondary), that measured 3.68:1, under WCAG AA's
            // 4.5:1. Now 4.52:1.
            secondary = Color(0xFF1E6FF5),
            background = Color(0xFFF1F5FA), surface = Color.White,
        ),
        dark = buildDark(
            primary = Color(0xFF82AAFF), onPrimary = Color(0xFF00234A),
            primaryContainer = Color(0xFF1B3A73), onPrimaryContainer = Color(0xFFD6E9F8),
            secondary = Color(0xFF7DCFFF),
            background = Color(0xFF0C0E1A), surface = Color(0xFF121525),
        ),
    ),
    LIME(
        lightLabel = "Lime Fizz",
        darkLabel = "Flat Lime",
        light = buildLight(
            primary = Color(0xFF12803B), onPrimary = Color.White,
            primaryContainer = Color(0xFFD9F2DF), onPrimaryContainer = Color(0xFF0A3D1D),
            // Darkened from 0xFF65A30D (same hue) — better-colors review: paired with fixed-white
            // text/icons (see buildLight's own onSecondary), that measured 3.09:1, the worst
            // offender of the set, well under WCAG AA's 4.5:1. Now 4.50:1.
            secondary = Color(0xFF52840B),
            background = Color(0xFFF1F8F0), surface = Color.White,
        ),
        dark = buildDark(
            primary = Color(0xFF7CF7A5), onPrimary = Color(0xFF0A3D1D),
            primaryContainer = Color(0xFF19542C), onPrimaryContainer = Color(0xFFD9F2DF),
            secondary = Color(0xFFA3E635),
            background = Color(0xFF0A120D), surface = Color(0xFF0F1911),
        ),
    ),
    MONOCHROME(
        lightLabel = "Yang",
        darkLabel = "Yin",
        light = buildLight(
            primary = Color(0xFF1A1A1A), onPrimary = Color.White,
            primaryContainer = Color(0xFFE0E0E0), onPrimaryContainer = Color(0xFF1A1A1A),
            secondary = Color(0xFF4D4D4D),
            background = Neutral99, surface = Color.White,
        ),
        dark = buildDark(
            primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF1A1A1A),
            primaryContainer = Color(0xFF3D3D3D), onPrimaryContainer = Color(0xFFF2F2F2),
            secondary = Color(0xFFB3B3B3),
            background = Color(0xFF000000), surface = Color(0xFF0A0A0A),
        ),
    ),
}
