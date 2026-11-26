package com.comfort.app.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryDLTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    lightTheme: AppTheme = AppTheme.CUSTOM,
    darkTheme: AppTheme = AppTheme.CUSTOM,
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
        colorScheme = pureBlack(colorScheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // System bars are handled by enableEdgeToEdge in MainActivity
    }

    val isExpressive = selected == AppTheme.EXPRESSIVE
    // Settings › Look & feel › Size: the density (and with it every dp and sp, text included)
    // scaled, so the whole app is drawn smaller or larger.
    val size by AppSize.scale.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = remember(density, size) {
        androidx.compose.ui.unit.Density(density.density * size, density.fontScale)
    }
    // The screen's size in dp too, which some screens size things from (sheets' heights, the
    // share picker's grid, the nav rail's switch), measured in the same scaled dp.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val scaledConfiguration = remember(configuration, size) {
        if (size == 1f) configuration else android.content.res.Configuration(configuration).apply {
            screenWidthDp = (configuration.screenWidthDp / size).toInt()
            screenHeightDp = (configuration.screenHeightDp / size).toInt()
            smallestScreenWidthDp = (configuration.smallestScreenWidthDp / size).toInt()
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaled,
        androidx.compose.ui.platform.LocalConfiguration provides scaledConfiguration,
    ) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        // The expressive corners in every theme: the app looks the same whichever colours are
        // picked (they were only Expressive Purple's, every other theme squarer).
        shapes = ExpressiveShapes,
        motionScheme = if (isExpressive) MotionScheme.expressive() else MotionScheme.standard(),
        content = content,
    )
    }
}

/** [scheme] on pure black (Settings › Appearance › Pure black dark mode): black pages, and cards a
 * few steps up. surfaceContainerHighest (unselected chips and options on a card) is a clear step
 * above the cards: at #1A1A1A on #141414 they all but vanished. */
fun pureBlack(scheme: ColorScheme): ColorScheme = scheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF2A2A2A),
)
