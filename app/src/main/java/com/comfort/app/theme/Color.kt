package com.comfort.app.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand
val Indigo10 = Color(0xFF0D0B4F)
val Indigo20 = Color(0xFF14127A)
val Indigo40 = Color(0xFF4F46E5)
val Indigo80 = Color(0xFFA5AEFF)
val Indigo90 = Color(0xFFE0E1FF)

// Accent (used for progress / success states)
val Teal40 = Color(0xFF0D9488)
val Teal80 = Color(0xFF5EEAD4)
val Teal90 = Color(0xFFCCFBF1)

// Brand accent — the floating nav bar's palette, reused anywhere else in the app that wants to
// visually match it (e.g. the Library toolbar's active filter chips).
val AccentSalmon = Color(0xFFEA7B7B)
val AccentRed = Color(0xFFD25353)
val AccentRedDark = Color(0xFF9E3B3B)
val AccentCream = Color(0xFFFFEAD3)

// Error
val Red40 = Color(0xFFDC2626)
val Red80 = Color(0xFFF87171)
val Red10 = Color(0xFF450A0A)
val Red90 = Color(0xFFFEE2E2)

// Neutrals
val Neutral99 = Color(0xFFFAFAFC)
val Neutral95 = Color(0xFFF1F0F7)
val Neutral90 = Color(0xFFE4E2ED)
val Neutral80 = Color(0xFFC9C7D6)
val Neutral60 = Color(0xFF908E9F)
val Neutral50 = Color(0xFF706E7E)
val Neutral30 = Color(0xFF423F52)
val Neutral20 = Color(0xFF2A2836)
val Neutral10 = Color(0xFF1B1A24)
val Neutral05 = Color(0xFF121118)
val Neutral00 = Color(0xFF0B0A0F)

val LightColorScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo20,

    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Teal90,
    onSecondaryContainer = Color(0xFF00201C),

    tertiary = Teal40,
    onTertiary = Color.White,

    background = Neutral99,
    onBackground = Neutral10,

    surface = Color.White,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = Neutral50,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Neutral99,
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

val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Color(0xFF33339E),
    onPrimaryContainer = Indigo90,

    secondary = Teal80,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF0B4A42),
    onSecondaryContainer = Teal90,

    tertiary = Teal80,
    onTertiary = Color(0xFF00201C),

    background = Neutral00,
    onBackground = Neutral90,

    surface = Neutral05,
    onSurface = Neutral90,
    surfaceVariant = Neutral20,
    onSurfaceVariant = Neutral80,
    surfaceContainerLowest = Neutral00,
    surfaceContainerLow = Neutral05,
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
