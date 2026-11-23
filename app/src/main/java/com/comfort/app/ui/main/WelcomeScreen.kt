package com.comfort.app.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.comfort.app.R
import com.comfort.app.data.AudioFormat
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.ShareMode
import com.comfort.app.data.VideoQuality
import com.comfort.app.theme.HeaderFontFamily

/** Whether the first-run welcome has been seen. An install that already has downloads or settings
 * (an update from before the welcome existed) counts as having seen it. */
object Welcome {
    private const val PREFS = "welcome"
    private const val KEY_DONE = "done"

    fun needed(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_DONE, false)) return false
        // Settings already there (checked on the main thread at launch, so not the database): an
        // install from before the welcome. A first run has written next to none yet.
        val settings = context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, Context.MODE_PRIVATE).all.size
        val dbFile = context.getDatabasePath("gallerydl_database")
        if (settings > 3 || (dbFile.exists() && dbFile.length() > 64 * 1024)) {
            markDone(context)
            return false
        }
        return true
    }

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

private val Wordmark = FontFamily(Font(R.font.crystal_radio_kit))

/** The first-run welcome: what the app does, the few defaults worth choosing up front (video
 * quality and format, audio format, what sharing a link does), and notifications. [onDone] once
 * it's finished or skipped; every choice is in Settings afterwards. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }
    var quality by remember { mutableStateOf(GalleryDlPreferences.getVideoQuality(context)) }
    var format by remember { mutableStateOf(GalleryDlPreferences.getOutputFormat(context)) }
    var audio by remember { mutableStateOf(GalleryDlPreferences.getAudioFormat(context)) }
    var share by remember { mutableStateOf(GalleryDlPreferences.getShareMode(context)) }
    fun finish() {
        GalleryDlPreferences.setVideoQuality(context, quality)
        GalleryDlPreferences.setOutputFormat(context, format)
        GalleryDlPreferences.setAudioFormat(context, audio)
        GalleryDlPreferences.setShareMode(context, share)
        Welcome.markDone(context)
        onDone()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            // Where you are, and Skip (the defaults stay as they are).
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .height(6.dp)
                            .width(if (i == page) 28.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (i <= page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (page < 2) TextButton(onClick = { finish() }) { Text(stringResource(R.string.welcome_skip)) }
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "welcomePage",
            ) { p ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    when (p) {
                        0 -> IntroPage()
                        1 -> DefaultsPage(quality, { quality = it }, format, { format = it }, audio, { audio = it }, share, { share = it })
                        else -> NotificationsPage()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page > 0) {
                    OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.action_back)) }
                }
                Button(
                    onClick = { if (page < 2) page++ else finish() },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(
                        stringResource(if (page < 2) R.string.welcome_next else R.string.welcome_start),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily),
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroPage() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            "COMFORT",
            style = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontFamily = Wordmark, fontSize = 56.sp, textAlign = TextAlign.Center),
            maxLines = 1,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        // One card for the three, plain icons in it, as the Settings pages' rows (the grey
        // circles each icon sat in looked dropped in).
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Feature(Icons.Outlined.Share, R.string.welcome_feature_share)
                Feature(Icons.Outlined.PhotoLibrary, R.string.welcome_feature_library)
                Feature(Icons.Outlined.MusicNote, R.string.welcome_feature_music)
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultsPage(
    quality: VideoQuality, onQuality: (VideoQuality) -> Unit,
    format: OutputFormat, onFormat: (OutputFormat) -> Unit,
    audio: AudioFormat, onAudio: (AudioFormat) -> Unit,
    share: ShareMode, onShare: (ShareMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.welcome_defaults_title), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.welcome_defaults_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        ChoiceGroup(stringResource(R.string.welcome_video_quality), listOf(VideoQuality.BEST, VideoQuality.P1080, VideoQuality.P720, VideoQuality.P480, VideoQuality.AUDIO_ONLY), quality, { qualityLabel(it) }, onQuality)
        ChoiceGroup(stringResource(R.string.welcome_video_format), OutputFormat.entries, format, { it.label }, onFormat)
        ChoiceGroup(stringResource(R.string.welcome_audio_format), AudioFormat.entries, audio, { if (it == AudioFormat.ORIGINAL) stringResource(R.string.audio_track_original) else it.label }, onAudio)
        ChoiceGroup(stringResource(R.string.sharing_mode), ShareMode.entries, share, { shareLabel(it) }, onShare)
        Text(stringResource(R.string.welcome_change_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun qualityLabel(q: VideoQuality): String = when (q) {
    VideoQuality.BEST -> stringResource(R.string.best_available)
    VideoQuality.AUDIO_ONLY -> stringResource(R.string.quality_audio_only)
    else -> q.label
}

@Composable
private fun shareLabel(m: ShareMode): String = when (m) {
    ShareMode.CONFIGURE -> stringResource(R.string.share_mode_configure)
    ShareMode.INSTANT -> stringResource(R.string.share_mode_instant)
    ShareMode.ALWAYS_ASK -> stringResource(R.string.share_mode_always_ask)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onPick(option) },
                label = { Text(label(option)) },
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun NotificationsPage() {
    val context = LocalContext.current
    fun granted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = granted() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.welcome_notifications_title), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_notifications_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (allowed) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.welcome_notifications_on)) }, leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null) })
        } else {
            FilledTonalButton(onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.welcome_notifications_allow))
            }
        }
        Spacer(Modifier.height(24.dp))
        BatteryCard()
    }
}

/** Unrestricted battery (as Settings › Downloads offers): without it, Android's battery saver can
 * freeze a download waiting to start while the app is in the background. */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    fun unrestricted(): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    var on by remember { mutableStateOf(unrestricted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { on = unrestricted() }
    // Back from the system's own screen for it (the result isn't always reported).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) on = unrestricted() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.unrestricted_background_activity), style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (on) R.string.unrestricted_downloads_can_keep_running_in else R.string.welcome_battery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!on) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        )
                        // Some skins have no screen for this one: the general battery list instead.
                        runCatching { launcher.launch(intent) }
                            .onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.allow_unrestricted_background_activity))
                }
            }
        }
    }
}
