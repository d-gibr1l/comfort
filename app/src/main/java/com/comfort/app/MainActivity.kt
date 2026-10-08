package com.comfort.app

import android.Manifest
import android.content.Intent
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.util.AppImageLoader
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.comfort.app.theme.ThemeMode

class MainActivity : ComponentActivity() {
  companion object {
    /** Set on the Intent that opens/refocuses this Activity to jump straight to the Download
     * Queue — used by the download-in-progress notifications (DownloadNotifications.kt) so
     * tapping one while a download is running takes you right to it instead of just to Home. */
    const val EXTRA_OPEN_QUEUE = "open_queue"

    // The launcher icon's long-press shortcuts (res/xml/shortcuts.xml).
    const val SHORTCUT_PASTE_DOWNLOAD = "com.comfort.app.action.PASTE_DOWNLOAD"
    const val SHORTCUT_OPEN_LIBRARY = "com.comfort.app.action.OPEN_LIBRARY"
    const val SHORTCUT_OPEN_QUEUE = "com.comfort.app.action.OPEN_QUEUE"
    const val SHORTCUT_SEARCH_LIBRARY = "com.comfort.app.action.SEARCH_LIBRARY"
  }

  /** The last shortcut used, with a count so using the same one twice still acts. */
  private val shortcut = mutableStateOf<com.comfort.app.ui.main.ShortcutRequest?>(null)

  // A plain Compose State field (not `remember`ed — this Activity, not a composable, owns it) so
  // onNewIntent below can bump it from outside composition and still have setContent's read of it
  // trigger recomposition, the standard way to thread an Activity-level Intent into Compose state.
  private val openQueueSignal = mutableIntStateOf(0)

  // Re-applies the status/nav bar icon colors for the current light/dark theme. Removing the
  // Android 12+ splash resets them to the base theme's own (core-splashscreen re-applies the
  // theme's system bar appearance), which left white icons on the light theme's near-white page —
  // reported as "hard to see". Set from the composition below, called again once the splash is gone.
  private var applySystemBarStyle: (() -> Unit)? = null

  private fun consumeOpenQueueExtra(intent: Intent) {
    if (intent.getBooleanExtra(EXTRA_OPEN_QUEUE, false)) {
      openQueueSignal.intValue++
    }
    val action = intent.action
    if (action in setOf(SHORTCUT_PASTE_DOWNLOAD, SHORTCUT_OPEN_LIBRARY, SHORTCUT_OPEN_QUEUE, SHORTCUT_SEARCH_LIBRARY)) {
      shortcut.value = com.comfort.app.ui.main.ShortcutRequest(action!!, (shortcut.value?.seq ?: 0) + 1)
      // Handled: a later recreation mustn't act on it again.
      intent.action = Intent.ACTION_MAIN
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeOpenQueueExtra(intent)
  }

  // A theme switch in Appearance changes the app's night mode (ThemePreferences.applyNightMode),
  // which reaches this activity as a uiMode change (handled, see the manifest) — and the window
  // re-applies its theme's bar style with it, after the app's own. Put the app's back once that's
  // done.
  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    window.decorView.post { applySystemBarStyle?.invoke() }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Must be called before super.onCreate() — it reads the activity's theme (Theme.App.Starting,
    // set in the manifest) to know which splash to show, and installs the exit-animation hook
    // before the window's normal onCreate machinery runs.
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)

    // Cold start (app wasn't running) carries the extra on this initial Intent instead of
    // reaching onNewIntent, which only fires for an already-running singleTask instance.
    consumeOpenQueueExtra(intent)

    // The splash is released as soon as the first frame is ready. The logo's staggered entrance
    // (COM/FOR/arrow, 680ms — windowSplashScreenAnimationDuration says 700) used to be protected
    // by a fixed 700ms hold counted from here, but Android starts that animation when the app is
    // tapped, before this runs — so the splash then sat still after the animation for however long
    // the process took to start. Now, on Android 12+, it waits only for whatever of the animation
    // is actually left (measured from when the system really started it), then fades out.
    // Android 7-11 can't animate the icon at all (core-splashscreen shows it static there), so
    // there's nothing to wait for: the logo shows only while the app is really starting.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      splashScreen.setOnExitAnimationListener { provider ->
        val animationEnd = provider.iconAnimationStartMillis + provider.iconAnimationDurationMillis
        // On Android 12+ core-splashscreen reports the start as a wall-clock time (the platform
        // SplashScreenView's Instant), not uptime: subtracting uptimeMillis gave a delay of decades
        // and the splash never went away (found live). Pick the clock by magnitude, and never wait
        // longer than the whole animation whatever the numbers say.
        val now = if (animationEnd > 1_000_000_000_000L) System.currentTimeMillis() else android.os.SystemClock.uptimeMillis()
        val remaining = (animationEnd - now).coerceIn(0L, 700L)
        provider.view.animate()
          .alpha(0f)
          .setStartDelay(remaining)
          .setDuration(200L)
          .withEndAction {
            provider.remove()
            applySystemBarStyle?.invoke()
          }
          .start()
      }
    }

    AppImageLoader.install(applicationContext)
    // Unpack + precompile the Python engines ahead of the first preview/download (no-op once done
    // for this build) — see PythonRuntime.warmUpInBackground.
    com.comfort.app.util.PythonRuntime.warmUpInBackground(applicationContext)

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
          val apply = { enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style) }
          apply()
          applySystemBarStyle = apply
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
        // After the repairs above, so a download a dead process left "RUNNING" no longer protects
        // its cookie copy from the sweep.
        com.comfort.app.util.GalleryDlListing.sweepStaleCookieCopies(context)
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
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainNavigation(openQueueSignal = openQueueSignal.intValue, shortcut = shortcut.value)
          }
        }
      }
    }
  }
}
