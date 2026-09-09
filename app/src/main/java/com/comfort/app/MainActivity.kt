package com.comfort.app

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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.comfort.app.theme.GalleryDLTheme
import com.comfort.app.theme.LocalThemeState
import com.comfort.app.theme.ThemePreferences
import com.comfort.app.theme.ThemeState
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.util.AppImageLoader
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.comfort.app.theme.ThemeMode

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Must be called before super.onCreate() — it reads the activity's theme (Theme.App.Starting,
    // set in the manifest) to know which splash to show, and installs the exit-animation hook
    // before the window's normal onCreate machinery runs.
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)

    // The androidx compat SplashScreen dismisses as soon as the first frame is drawn — for a
    // lightweight Compose screen like this one that can happen well before the 700ms staggered
    // entrance (COM/FOR/arrow) has actually finished playing, cutting the animation short
    // (reproduced live: it visibly vanished mid-animation). windowSplashScreenAnimationDuration in
    // the theme is only used for the library's own exit-transition bookkeeping — it does not by
    // itself hold the splash up. Holding it here with a real elapsed-time check is the officially
    // documented fix for exactly this gap.
    val splashStartTime = System.currentTimeMillis()
    val splashMinDurationMs = 700L
    splashScreen.setKeepOnScreenCondition {
      System.currentTimeMillis() - splashStartTime < splashMinDurationMs
    }

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

      // One-shot per cold start — catches a download stuck showing "Downloading..." forever
      // because the process that owned it died before its worker could write a terminal status.
      // See DownloadDispatcher.repairOrphanedRunning()'s own doc comment for the full story.
      LaunchedEffect(Unit) {
        DownloadDispatcher.repairOrphanedRunning(context)
        // Same idea, for QUEUED/SCHEDULED rows — see repairOrphanedQueue's own doc comment for
        // why this needed a startup call too, not just its existing reactive ones.
        DownloadDispatcher.repairOrphanedQueue(context)
      }

      // (Re)applies the "Clean-up leftover downloads" interval every cold start — a
      // PeriodicWorkRequest's interval is fixed at enqueue time, so this is what makes a change
      // from a previous session actually take effect again. See its own doc comment.
      LaunchedEffect(Unit) {
        DownloadDispatcher.rescheduleStagingCleanup(context)
      }

      // Re-arms the Schedule window's exact-alarm backstop every cold start — an AlarmManager
      // alarm doesn't survive a reboot on its own, so this is what re-establishes it. See
      // DownloadDispatcher.scheduleWindowAlarm's own doc comment.
      LaunchedEffect(Unit) {
        DownloadDispatcher.scheduleWindowAlarm(context)
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
