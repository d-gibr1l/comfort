package com.comfort.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.theme.DarkColorScheme
import com.comfort.app.theme.LightColorScheme
import com.comfort.app.theme.Shapes
import com.comfort.app.theme.ThemeMode
import com.comfort.app.theme.ThemePreferences
import com.comfort.app.theme.Typography
import com.comfort.app.ui.main.SharePickerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHEET_ANIM_MS = 280

/** Handles a shared link as a translucent floating sheet instead of a normal Activity: to the
 * user it never looks like they left Twitter/Reddit/the browser they shared from — technically
 * this is still an Activity, but its window is fully transparent (see Theme.GalleryDL.Transparent)
 * so the calling app stays visible behind the bottom sheet.
 *
 * The dim-behind scrim is done at the native Android window level (FLAG_DIM_BEHIND + dimAmount)
 * rather than by drawing a semi-transparent Box in Compose — this is the same mechanism a normal
 * Android Dialog uses for its own scrim, so it's guaranteed to composite correctly regardless of
 * Compose internals. (Note: `adb shell screencap` renders this whole effect as solid black even
 * when it's genuinely working — verify visually on-device, not from an adb screenshot.) */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        com.comfort.app.util.AppImageLoader.install(applicationContext)

        val sharedText = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)
            // Direct link taps (twitter.com, instagram.com, pixiv.net — see the manifest's
            // ACTION_VIEW intent-filter) arrive with the URL as the intent's data, not an extra.
            intent.action == Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val matcher = sharedText?.let { Patterns.WEB_URL.matcher(it) }
        val url = if (matcher != null && matcher.find()) sharedText.substring(matcher.start(), matcher.end()) else null

        if (url == null) {
            finish()
            return
        }

        if (GalleryDlPreferences.isInstantShareEnabled(this)) {
            // No sheet, no frame ever drawn — just enqueue in the background and close.
            lifecycleScope.launch {
                DownloadDispatcher.enqueueDownload(applicationContext, url, "Downloading from ${VideoSiteRouter.siteName(url)}")
                Toast.makeText(applicationContext, "Download started", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }

        // Compose's root view sets its own opaque window background by default, which silently
        // overrides the theme's windowIsTranslucent/windowBackground=transparent — without this,
        // the sheet renders correctly but everything behind it is solid black instead of the
        // calling app showing through.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.4f)

        setContent {
            val context = LocalContext.current
            val themeMode = remember { ThemePreferences.getThemeMode(context) }
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

            MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = Shapes) {
                SharePickerSheet(url = url, onFinished = { finish() })
            }
        }
    }
}

@Composable
private fun SharePickerSheet(url: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { visible = true }
    BackHandler { visible = false }

    // Waits for the exit animation to actually play before finishing the Activity, instead of
    // cutting the sheet away mid-slide.
    LaunchedEffect(visible) {
        if (!visible) {
            delay(SHEET_ANIM_MS.toLong())
            onFinished()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Invisible tap-to-dismiss catcher over the dimmed (window-level) area above the sheet.
        // No .background() here — drawing anything here is exactly what broke transparency.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { visible = false }
        )

        var sheetHeight by remember { mutableStateOf(280.dp) }
        val animatedHeight by animateDpAsState(targetValue = sheetHeight, label = "sheetHeight")

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(SHEET_ANIM_MS), initialOffsetY = { it }),
            exit = slideOutVertically(tween(SHEET_ANIM_MS), targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(animatedHeight),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                SharePickerScreen(
                    url = url,
                    onDismiss = { visible = false },
                    onDownload = { downloadUrl, itemFilter, totalItems, videoQuality ->
                        scope.launch {
                            DownloadDispatcher.enqueueDownload(context, downloadUrl, "Downloading from ${VideoSiteRouter.siteName(downloadUrl)}", itemFilter, totalItems, videoQuality)
                        }
                        visible = false
                    },
                    onHeightChange = { sheetHeight = it },
                )
            }
        }
    }
}
