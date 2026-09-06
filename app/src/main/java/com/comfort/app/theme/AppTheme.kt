package com.comfort.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

// better-colors review: secondaryContainer/onSecondaryContainer used to be hardcoded to a fixed
// teal (Teal90/#00201C light, #0B4A42/Teal90 dark) in every non-DEFAULT theme, regardless of that
// theme's own secondary hue — e.g. BLOSSOM's secondary is pink but its container stayed teal.
// Currently unused anywhere in the app (grepped), so nothing rendered wrong yet, but any future
// chip/badge that reads these roles would have silently gone off-theme. These two derive a
// same-hue container pair from `secondary` instead, at roughly Material3's own tonal-container
// steps (~90/~20 light, ~28/~90 dark), so a new theme can't reintroduce the mismatch by omission.
private fun containerToneOf(base: Color, containerLightness: Float, containerSaturationScale: Float): Color {
    val (h, s, _) = base.toHsl()
    return hslToColor(h, s * containerSaturationScale, containerLightness)
}

private fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red; val g = green; val b = blue
    val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
    val l = (maxC + minC) / 2f
    if (maxC == minC) return Triple(0f, 0f, l * 100f)
    val d = maxC - minC
    val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
    val h = when (maxC) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f))
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return Triple(h, s * 100f, l * 100f)
}

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val hh = h / 360f; val ss = s / 100f; val ll = l / 100f
    if (ss == 0f) return Color(ll, ll, ll)
    fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
    val p = 2f * ll - q
    return Color(hue2rgb(p, q, hh + 1f / 3f), hue2rgb(p, q, hh), hue2rgb(p, q, hh - 1f / 3f))
}

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
    secondaryContainer = containerToneOf(secondary, containerLightness = 90f, containerSaturationScale = 0.45f),
    onSecondaryContainer = containerToneOf(secondary, containerLightness = 20f, containerSaturationScale = 0.9f),
    tertiary = secondary,
    onTertiary = Color.White,
    // tertiary === secondary here (this app has never had a genuinely distinct third accent
    // hue), so its container is derived the same way secondaryContainer is above — without this,
    // Compose's lightColorScheme() falls back to a hardcoded stock-M3 purple/pink tertiaryContainer
    // that has nothing to do with the active theme (surfaced by the Settings screen redesign,
    // which reads tertiaryContainer for one of its list-row colors).
    tertiaryContainer = containerToneOf(secondary, containerLightness = 90f, containerSaturationScale = 0.45f),
    onTertiaryContainer = containerToneOf(secondary, containerLightness = 20f, containerSaturationScale = 0.9f),
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
    secondaryContainer = containerToneOf(secondary, containerLightness = 28f, containerSaturationScale = 0.6f),
    onSecondaryContainer = containerToneOf(secondary, containerLightness = 90f, containerSaturationScale = 0.5f),
    tertiary = secondary,
    onTertiary = Color(0xFF00201C),
    // See buildLight's identical comment.
    tertiaryContainer = containerToneOf(secondary, containerLightness = 28f, containerSaturationScale = 0.6f),
    onTertiaryContainer = containerToneOf(secondary, containerLightness = 90f, containerSaturationScale = 0.5f),
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
    // The canonical Material 3 baseline "Purple" scheme (both light and dark stops are the
    // published M3 baseline values, not derived via buildLight/buildDark like the other themes
    // above) — unlike those, this one needs a tertiaryContainer distinct from secondaryContainer,
    // which buildLight/buildDark don't expose. Paired with ExpressiveShapes (see Shape.kt) and
    // MotionScheme.expressive() in Theme.kt's GalleryDLTheme switch.
    EXPRESSIVE(
        lightLabel = "Expressive Purple",
        darkLabel = "Expressive Purple",
        // Medium-contrast M3 purple: a deeper primary (#4C3889) and darker onSurfaceVariant/outline
        // than the baseline scheme this started from, which is what the download preview sheet's
        // own design was drawn against.
        light = lightColorScheme(
            primary = Color(0xFF4C3889), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF32226F),
            secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE9DDFD), onSecondaryContainer = Color(0xFF342C45),
            tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFDDAE1), onTertiaryContainer = Color(0xFF521F2E),
            background = Color(0xFFFAF8FE), onBackground = Color(0xFF1C1B1F),
            surface = Color(0xFFFAF8FE), onSurface = Color(0xFF1C1B1F),
            surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF3D3A44),
            surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F3F8),
            surfaceContainer = Color(0xFFEFEDF2), surfaceContainerHigh = Color(0xFFE9E7ED),
            surfaceContainerHighest = Color(0xFFE4E1E7),
            outline = Color(0xFF615D68), outlineVariant = Color(0xFFA09CA8),
            inverseSurface = Color(0xFF313034), inverseOnSurface = Color(0xFFF2F0F5), inversePrimary = Color(0xFFD2BCFC),
            error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
            secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
            tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
            tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
            background = Color(0xFF1C1B1F), onBackground = Color(0xFFE6E1E5),
            surface = Color(0xFF141218), onSurface = Color(0xFFE6E1E5),
            surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
            surfaceContainerLowest = Color(0xFF0F0D13), surfaceContainerLow = Color(0xFF1D1B20),
            surfaceContainer = Color(0xFF211F26), surfaceContainerHigh = Color(0xFF2B2930),
            surfaceContainerHighest = Color(0xFF36343B),
            outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
            inverseSurface = Color(0xFFE6E1E5), inverseOnSurface = Color(0xFF313033), inversePrimary = Color(0xFF6750A4),
            error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
        ),
    ),
}
