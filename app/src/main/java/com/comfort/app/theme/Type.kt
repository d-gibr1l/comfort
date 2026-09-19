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

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = Default, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = Default, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.sp),
    displaySmall = TextStyle(fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.sp),

    headlineLarge = TextStyle(fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),

    titleLarge = TextStyle(fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    bodyLarge = TextStyle(fontFamily = Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),

    labelLarge = TextStyle(fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)
