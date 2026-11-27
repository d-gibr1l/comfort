package com.comfort.app.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.comfort.app.R

private val Default = FontFamily.Default

// Used only for the big page-title headers (Settings root, every Settings sub-page) — not part of
// the app-wide Typography above, which stays on the platform default for everything else.
val HeaderFontFamily = FontFamily(Font(R.font.googlesans_bold_subset, FontWeight.Bold))

// Every line reads in its own script's direction: an English song title in the Arabic app sits on
// the left, an Arabic one in the English app on the right, and the app's own (translated) text
// follows the app's language as before. Laid out as the app's direction instead, a long English
// title in Arabic was cut off at its start, not its end (Android 9).
private val ByContent = androidx.compose.ui.text.style.TextDirection.Content

val Typography = Typography(
    displayLarge = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.sp),
    displaySmall = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.sp),

    headlineLarge = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),

    titleLarge = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    bodyLarge = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),

    labelLarge = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(textDirection = ByContent, fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)
