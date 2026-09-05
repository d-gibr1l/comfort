package com.comfort.app.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun GalleryDLTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    lightTheme: AppTheme = AppTheme.MONOCHROME,
    darkTheme: AppTheme = AppTheme.MONOCHROME,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val selected = if (isDark) darkTheme else lightTheme
    val context = LocalContext.current

    var colorScheme = if (selected.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        (if (isDark) selected.dark else selected.light) ?: (if (isDark) DarkColorScheme else LightColorScheme)
    }

    if (isDark && pureBlack) {
        colorScheme = colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF0D0D0D),
            surfaceContainerHigh = Color(0xFF141414),
            surfaceContainerHighest = Color(0xFF1A1A1A),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // System bars are handled by enableEdgeToEdge in MainActivity
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
