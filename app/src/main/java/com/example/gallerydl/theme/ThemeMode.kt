package com.example.gallerydl.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val setMode: (ThemeMode) -> Unit = {},
)

val LocalThemeState = staticCompositionLocalOf { ThemeState() }

private const val PREFS_NAME = "gallerydl_theme_prefs"
private const val KEY_THEME_MODE = "theme_mode"

object ThemePreferences {
    fun getThemeMode(context: Context): ThemeMode {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(stored ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }
}
