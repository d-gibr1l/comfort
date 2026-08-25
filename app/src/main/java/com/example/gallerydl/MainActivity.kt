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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (!Python.isStarted()) {
        Python.start(AndroidPlatform(this))
    }
    AppImageLoader.install(applicationContext)

    enableEdgeToEdge()
    setContent {
      val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
      ) {}

      LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }

      val context = LocalContext.current
      var themeMode by remember { mutableStateOf(ThemePreferences.getThemeMode(context)) }
      val themeState = remember(themeMode) {
        ThemeState(mode = themeMode, setMode = { newMode ->
          themeMode = newMode
          ThemePreferences.setThemeMode(context, newMode)
        })
      }

      CompositionLocalProvider(LocalThemeState provides themeState) {
        GalleryDLTheme(themeMode = themeMode) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() }
        }
      }
    }
  }
}
