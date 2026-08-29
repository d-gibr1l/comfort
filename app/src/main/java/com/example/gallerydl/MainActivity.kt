package com.example.gallerydl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.gallerydl.theme.GalleryDLTheme
import com.example.gallerydl.theme.LocalThemeState
import com.example.gallerydl.theme.ThemePreferences
import com.example.gallerydl.theme.ThemeState
import com.example.gallerydl.util.AppImageLoader
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.example.gallerydl.theme.ThemeMode

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    AppImageLoader.install(applicationContext)

    setContent {
      val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
      ) {}

      val context = LocalContext.current
      var themeMode by remember { mutableStateOf(ThemePreferences.getThemeMode(context)) }
      
      val isSystemDark = isSystemInDarkTheme()
      val isDark = when (themeMode) {
          ThemeMode.LIGHT -> false
          ThemeMode.DARK -> true
          ThemeMode.SYSTEM -> isSystemDark
      }

      DisposableEffect(isDark) {
          val style = if (isDark) {
              SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
          } else {
              SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
          }
          enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
          onDispose {}
      }

      LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }

      var lightTheme by remember { mutableStateOf(ThemePreferences.getLightTheme(context)) }
      var darkTheme by remember { mutableStateOf(ThemePreferences.getDarkTheme(context)) }
      var pureBlack by remember { mutableStateOf(ThemePreferences.isPureBlack(context)) }
      val themeState = remember(themeMode, lightTheme, darkTheme, pureBlack) {
        ThemeState(
          mode = themeMode,
          setMode = { newMode ->
            themeMode = newMode
            ThemePreferences.setThemeMode(context, newMode)
          },
          lightTheme = lightTheme,
          setLightTheme = { newTheme ->
            lightTheme = newTheme
            ThemePreferences.setLightTheme(context, newTheme)
          },
          darkTheme = darkTheme,
          setDarkTheme = { newTheme ->
            darkTheme = newTheme
            ThemePreferences.setDarkTheme(context, newTheme)
          },
          pureBlack = pureBlack,
          setPureBlack = { enabled ->
            pureBlack = enabled
            ThemePreferences.setPureBlack(context, enabled)
          },
        )
      }

      CompositionLocalProvider(LocalThemeState provides themeState) {
        GalleryDLTheme(themeMode = themeMode, lightTheme = lightTheme, darkTheme = darkTheme, pureBlack = pureBlack) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() }
        }
      }
    }
  }
}
