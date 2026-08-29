package com.example.gallerydl.ui.main

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gallerydl.data.GalleryDlPreferences
import com.example.gallerydl.data.VideoQuality
import com.example.gallerydl.theme.LocalThemeState
import com.example.gallerydl.theme.ThemeMode
import dev.darkokoa.datetimewheelpicker.WheelTimePicker
import dev.darkokoa.datetimewheelpicker.core.format.TimeFormat
import dev.darkokoa.datetimewheelpicker.core.format.timeFormatter
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import compose.icons.FeatherIcons
import compose.icons.feathericons.*

private enum class SettingsRoute { ROOT, APPEARANCE, DOWNLOADS, ADVANCED, COOKIES, ABOUT }

@Composable
fun MoreScreen() {
    var route by remember { mutableStateOf(SettingsRoute.ROOT) }

    Box(modifier = Modifier.fillMaxSize()) {
        // The root settings list is what a sub-screen's back gesture reveals — kept composed
        // underneath whenever we're not already on it, same reasoning as MainScreen's Home-behind-
        // a-tab treatment, purely so there's something real to peek at mid-swipe.
        if (route != SettingsRoute.ROOT) {
            SettingsRootScreen(onNavigate = { route = it })
        }

        val backProgress = rememberPredictiveBackProgress(enabled = route != SettingsRoute.ROOT) {
            route = SettingsRoute.ROOT
        }
        Box(modifier = Modifier.fillMaxSize().predictiveBackReveal(backProgress)) {
            when (route) {
                SettingsRoute.ROOT -> SettingsRootScreen(onNavigate = { route = it })
                SettingsRoute.APPEARANCE -> AppearanceScreen(onBack = { route = SettingsRoute.ROOT })
                SettingsRoute.DOWNLOADS -> DownloadsSettingsScreen(onBack = { route = SettingsRoute.ROOT })
                SettingsRoute.ADVANCED -> AdvancedSettingsScreen(onBack = { route = SettingsRoute.ROOT })
                SettingsRoute.COOKIES -> CookiesSettingsScreen(onBack = { route = SettingsRoute.ROOT })
                SettingsRoute.ABOUT -> AboutScreen(onBack = { route = SettingsRoute.ROOT })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRootScreen(onNavigate: (SettingsRoute) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val themeState = LocalThemeState.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val effectiveDark = when (themeState.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    val themeSummary = if (effectiveDark) themeState.darkTheme.darkLabel else themeState.lightTheme.lightLabel
    val filenameFormat = remember { GalleryDlPreferences.getFilenameFormat(context) }
    val hasCookies = remember { GalleryDlPreferences.getCookies(context).isNotBlank() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            GroupedRows {
                SettingsListRow(
                    icon = FeatherIcons.Sun,
                    title = "Appearance",
                    summary = themeSummary,
                    onClick = { onNavigate(SettingsRoute.APPEARANCE) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsListRow(
                    icon = FeatherIcons.Download,
                    title = "Downloads",
                    summary = filenameFormat,
                    onClick = { onNavigate(SettingsRoute.DOWNLOADS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsListRow(
                    icon = FeatherIcons.Terminal,
                    title = "Advanced",
                    summary = "Extra gallery-dl arguments",
                    onClick = { onNavigate(SettingsRoute.ADVANCED) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsListRow(
                    icon = FeatherIcons.Lock,
                    title = "Cookies & Login",
                    summary = if (hasCookies) "Configured" else "Not set",
                    onClick = { onNavigate(SettingsRoute.COOKIES) },
                )
            }

            GroupedRows {
                SettingsListRow(
                    icon = FeatherIcons.Info,
                    title = "About",
                    summary = "Version, credits & source",
                    onClick = { onNavigate(SettingsRoute.ABOUT) },
                )
            }

            // Clears the floating nav pill overlaying this screen (see MainScreen's own comment
            // on why it overlays instead of reserving Scaffold space) so this list can scroll
            // fully clear of it instead of ending up hidden behind.
            Spacer(Modifier.height(NAV_BAR_RESERVED_HEIGHT))
        }
    }
}

@Composable
private fun GroupedRows(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsListRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(FeatherIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                // Extra bottom inset beyond the normal 20dp: the floating nav bar overlays the
                // bottom of the screen without reserving space, so without this the last section
                // (e.g. Schedule's Start/End time buttons) scrolls to right underneath it and is
                // unreachable/unreadable.
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

@Composable
private fun DownloadsSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var filenameFormat by remember { mutableStateOf(GalleryDlPreferences.getFilenameFormat(context)) }
    var filenameFormatSaved by remember { mutableStateOf(false) }
    var concurrentDownloads by remember { mutableStateOf(GalleryDlPreferences.getConcurrentDownloads(context)) }
    var wifiOnly by remember { mutableStateOf(GalleryDlPreferences.isWifiOnly(context)) }
    var scheduleEnabled by remember { mutableStateOf(GalleryDlPreferences.isScheduleEnabled(context)) }
    var scheduleStartMin by remember { mutableStateOf(GalleryDlPreferences.getScheduleStartMinutes(context)) }
    var scheduleEndMin by remember { mutableStateOf(GalleryDlPreferences.getScheduleEndMinutes(context)) }
    var speedLimit by remember { mutableStateOf(GalleryDlPreferences.getSpeedLimit(context)) }
    var instantShare by remember { mutableStateOf(GalleryDlPreferences.isInstantShareEnabled(context)) }
    var videoQuality by remember { mutableStateOf(GalleryDlPreferences.getVideoQuality(context)) }
    var noPlaylist by remember { mutableStateOf(GalleryDlPreferences.isNoPlaylist(context)) }
    var downloadSubtitles by remember { mutableStateOf(GalleryDlPreferences.isDownloadSubtitles(context)) }
    var subtitleLanguages by remember { mutableStateOf(GalleryDlPreferences.getSubtitleLanguages(context)) }
    var embedThumbnail by remember { mutableStateOf(GalleryDlPreferences.isEmbedThumbnail(context)) }
    var embedMetadata by remember { mutableStateOf(GalleryDlPreferences.isEmbedMetadata(context)) }
    var downloadLocationUri by remember { mutableStateOf(GalleryDlPreferences.getDownloadLocationUri(context)) }
    val downloadLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            downloadLocationUri = uri
            GalleryDlPreferences.setDownloadLocationUri(context, uri)
        }
    }

    SettingsSubScaffold(title = "Downloads", onBack = onBack) {
        SettingsSection(title = "Filename format", icon = FeatherIcons.Type) {
            Text(
                "Filename format applied to every downloaded file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = filenameFormat,
                onValueChange = { filenameFormat = it; filenameFormatSaved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filename format") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Placeholders: {uploader} {title} {id} {extension} and more — see gallery-dl's format string docs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val toSave = filenameFormat.ifBlank { GalleryDlPreferences.DEFAULT_FILENAME_FORMAT }
                    filenameFormat = toSave
                    sharedPreferences.edit().putString(GalleryDlPreferences.KEY_FILENAME_FORMAT, toSave).apply()
                    filenameFormatSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(FeatherIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save format")
            }

            if (filenameFormatSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = FeatherIcons.CheckCircle, text = "Filename format saved", tint = MaterialTheme.colorScheme.secondary)
            }
        }

        SettingsSection(title = "Download location", icon = FeatherIcons.Folder) {
            val locationName = remember(downloadLocationUri) {
                downloadLocationUri?.let { uri ->
                    runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                }
            }
            Text(
                if (locationName != null) "Saving to \"$locationName\"." else "Saving to the default Pictures/gallery-dl folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locationName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Custom folders won't automatically appear in Photos/Gallery apps — only the default location is indexed as media.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { downloadLocationLauncher.launch(null) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(FeatherIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose folder")
                }
                if (locationName != null) {
                    OutlinedButton(
                        onClick = {
                            downloadLocationUri = null
                            GalleryDlPreferences.setDownloadLocationUri(context, null)
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Use default")
                    }
                }
            }
        }

        SettingsSection(title = "Video downloads", icon = FeatherIcons.Film) {
            Text("Quality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Applies to video links handled by yt-dlp (YouTube, Twitter/X, and similar).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                // Bleeds past the settings page's own 20dp side margin so the row's scrollable
                // viewport spans the full screen width, then re-adds that 20dp as inner padding so
                // the resting position still looks inset like the rest of the page — chips can now
                // scroll flush to the true screen edge instead of getting clipped mid-chip right at
                // the page margin, which read as "cut off." Widens via a custom layout rather than
                // Modifier.padding with a negative value — Compose's padding() throws at runtime on
                // negative dp, it isn't a supported way to do this.
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val bleed = 20.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                        layout(placeable.width - bleed * 2, placeable.height) {
                            placeable.placeRelative(-bleed, 0)
                        }
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VideoQuality.entries.forEach { quality ->
                    val selected = videoQuality == quality
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = {
                            videoQuality = quality
                            GalleryDlPreferences.setVideoQuality(context, quality)
                        },
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                quality.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = FeatherIcons.List,
                title = "Single video only",
                subtitle = "A link that's part of a playlist or channel downloads just that one video. Can noticeably slow down extraction on some sites, so it's off by default.",
                checked = noPlaylist,
                onCheckedChange = {
                    noPlaylist = it
                    GalleryDlPreferences.setNoPlaylist(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = FeatherIcons.Image,
                title = "Embed thumbnail",
                subtitle = "Save the video's thumbnail as cover art inside the file.",
                checked = embedThumbnail,
                onCheckedChange = {
                    embedThumbnail = it
                    GalleryDlPreferences.setEmbedThumbnail(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = FeatherIcons.Tag,
                title = "Embed metadata",
                subtitle = "Tag the file with its title, uploader, and other details.",
                checked = embedMetadata,
                onCheckedChange = {
                    embedMetadata = it
                    GalleryDlPreferences.setEmbedMetadata(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = FeatherIcons.MessageSquare,
                title = "Download subtitles",
                subtitle = "Fetch and embed subtitles when they're available.",
                checked = downloadSubtitles,
                onCheckedChange = {
                    downloadSubtitles = it
                    GalleryDlPreferences.setDownloadSubtitles(context, it)
                },
            )
            if (downloadSubtitles) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = subtitleLanguages,
                    onValueChange = {
                        subtitleLanguages = it
                        GalleryDlPreferences.setSubtitleLanguages(context, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Subtitle languages") },
                    placeholder = { Text("en") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Comma-separated language codes, e.g. \"en,es\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection(title = "Sharing", icon = FeatherIcons.Share2) {
            IconToggleRow(
                icon = FeatherIcons.Zap,
                title = "Instant download",
                subtitle = "Sharing a link downloads it right away in the background. Off shows a picker to choose which images to download.",
                checked = instantShare,
                onCheckedChange = {
                    instantShare = it
                    GalleryDlPreferences.setInstantShareEnabled(context, it)
                },
            )
        }

        SettingsSection(title = "Concurrent downloads", icon = FeatherIcons.Layers) {
            Text(
                "How many downloads gallery-dl runs at the same time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (count in 1..GalleryDlPreferences.MAX_CONCURRENT_DOWNLOADS) {
                    val selected = concurrentDownloads == count
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = {
                            concurrentDownloads = count
                            sharedPreferences.edit().putInt(GalleryDlPreferences.KEY_CONCURRENT_DOWNLOADS, count).apply()
                        },
                    ) {
                        Box(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "$count",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        SettingsSection(title = "Network", icon = FeatherIcons.Wifi) {
            IconToggleRow(
                icon = FeatherIcons.Wifi,
                title = "Wi-Fi only",
                subtitle = "Queued downloads wait for a Wi-Fi connection instead of using mobile data.",
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    sharedPreferences.edit().putBoolean(GalleryDlPreferences.KEY_WIFI_ONLY, it).apply()
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            Text("Speed limit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Caps download bandwidth, e.g. \"500k\" or \"2M\". Leave blank for unlimited.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = speedLimit,
                onValueChange = {
                    speedLimit = it
                    GalleryDlPreferences.setSpeedLimit(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Speed limit") },
                placeholder = { Text("Unlimited") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }

        SettingsSection(title = "Schedule", icon = FeatherIcons.Clock) {
            IconToggleRow(
                icon = FeatherIcons.Clock,
                title = "Restrict to time window",
                subtitle = "New downloads wait in the queue until the window opens.",
                checked = scheduleEnabled,
                onCheckedChange = {
                    scheduleEnabled = it
                    sharedPreferences.edit().putBoolean(GalleryDlPreferences.KEY_SCHEDULE_ENABLED, it).apply()
                    // Otherwise a download already queued under the old setting just sits
                    // there until its stale delay elapses — see rescheduleQueuedDownloads().
                    scope.launch { com.example.gallerydl.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                },
            )

            if (scheduleEnabled) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = "Start",
                        minutesSinceMidnight = scheduleStartMin,
                        onPicked = { minutes ->
                            scheduleStartMin = minutes
                            sharedPreferences.edit().putInt(GalleryDlPreferences.KEY_SCHEDULE_START_MIN, minutes).apply()
                            scope.launch { com.example.gallerydl.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                        },
                    )
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = "End",
                        minutesSinceMidnight = scheduleEndMin,
                        onPicked = { minutes ->
                            scheduleEndMin = minutes
                            sharedPreferences.edit().putInt(GalleryDlPreferences.KEY_SCHEDULE_END_MIN, minutes).apply()
                            scope.launch { com.example.gallerydl.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimePickerButton(
    modifier: Modifier = Modifier,
    label: String,
    minutesSinceMidnight: Int,
    onPicked: (Int) -> Unit,
) {
    val hour = minutesSinceMidnight / 60
    val minute = minutesSinceMidnight % 60
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        onClick = { showPicker = true },
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%d:%02d %s".format(displayHour, minute, amPm), style = MaterialTheme.typography.titleSmall)
        }
    }

    if (showPicker) {
        // Seeded once from the committed value when the dialog opens, then only ever written by
        // the wheel's own onSnappedTime — re-deriving it from minutesSinceMidnight on every
        // recomposition would fight the wheel's scroll position every time it snaps.
        var pendingMinutes by remember { mutableStateOf(minutesSinceMidnight) }
        val initialTime = remember { LocalTime(hour = hour, minute = minute) }
        Dialog(onDismissRequest = { showPicker = false }) {
            Card(shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    WheelTimePicker(
                        modifier = Modifier.size(280.dp, 160.dp),
                        startTime = initialTime,
                        timeFormatter = timeFormatter(timeFormat = TimeFormat.AM_PM),
                        textStyle = MaterialTheme.typography.headlineSmall,
                        selectedTextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        onSnappedTime = { snapped -> pendingMinutes = snapped.hour * 60 + snapped.minute },
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPicker = false }) { Text("Cancel") }
                        TextButton(onClick = { onPicked(pendingMinutes); showPicker = false }) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var extraArgs by remember { mutableStateOf(GalleryDlPreferences.getExtraArgs(context)) }
    var saved by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = "Advanced", onBack = onBack) {
        SettingsSection(title = "Extra arguments", icon = FeatherIcons.Terminal) {
            Text(
                "Extra command-line arguments passed to gallery-dl on every download. For advanced users.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = extraArgs,
                onValueChange = { extraArgs = it; saved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("e.g. --write-metadata --no-mtime") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    sharedPreferences.edit().putString(GalleryDlPreferences.KEY_EXTRA_ARGS, extraArgs).apply()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(FeatherIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save arguments")
            }

            if (saved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = FeatherIcons.CheckCircle, text = "Extra arguments saved", tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun CookiesSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }

    var showBrowser by remember { mutableStateOf(false) }
    var extractedCookies by remember { mutableStateOf("") }
    var pastedCookies by remember { mutableStateOf(sharedPreferences.getString(GalleryDlPreferences.KEY_COOKIES, "") ?: "") }
    var savedConfirmation by remember { mutableStateOf(false) }

    if (showBrowser) {
        CookieLoginDialog(
            loginUrl = "https://www.instagram.com/accounts/login/",
            onDismiss = { showBrowser = false },
            onCookiesSaved = { merged ->
                extractedCookies = merged
                pastedCookies = merged
                showBrowser = false
            },
        )
    }

    SettingsSubScaffold(title = "Cookies & Login", onBack = onBack) {
        SettingsSection(title = "Cookies", icon = FeatherIcons.Lock) {
            Text(
                "Sign in through the built-in browser to unlock private/age-restricted content, or paste a cookies.txt below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { showBrowser = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(FeatherIcons.Globe, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log in via built-in browser")
            }

            if (extractedCookies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = FeatherIcons.CheckCircle, text = "Cookies extracted successfully", tint = MaterialTheme.colorScheme.secondary)
            }

            Spacer(Modifier.height(20.dp))
            Text("Or paste raw cookies manually", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = pastedCookies,
                onValueChange = { pastedCookies = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                label = { Text("cookies.txt contents") },
                maxLines = 10,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    sharedPreferences.edit().putString(GalleryDlPreferences.KEY_COOKIES, pastedCookies).apply()
                    java.io.File(context.filesDir, "cookies.txt").writeText(pastedCookies)
                    savedConfirmation = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(FeatherIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save cookies")
            }

            if (savedConfirmation) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = FeatherIcons.CheckCircle, text = "Cookies saved and applied", tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/** Shared by the Cookies & Login settings screen and the Queue's per-download "Add cookies"
 * error-card action — a real navigable browser (URL bar, back/forward/refresh) starting at
 * [loginUrl] so the user can sign in normally, or browse anywhere else the site sends them
 * (an OAuth redirect, a "verify it's you" subdomain, ...) without getting stuck on one fixed
 * page. The single FAB extracts whatever CookieManager captured for the page currently on screen
 * and merges it into the saved cookies.txt (replacing only that site's prior lines, not the whole
 * file), then closes. */
@Composable
fun CookieLoginDialog(
    loginUrl: String,
    onDismiss: () -> Unit,
    onCookiesSaved: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var addressBarText by remember { mutableStateOf(loginUrl) }
    // The single source of truth for what's actually loaded — addressBarText tracks the user's
    // in-progress edits separately so typing a new URL doesn't fight with the WebView's own
    // onPageStarted/onPageFinished updates overwriting the field mid-edit.
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        // Dialog's default width policy caps the window well short of the screen (platform
        // "dialog" sizing), which is what was leaving the WebView inset with visible margins —
        // this makes the window itself the full screen instead of just the content inside it.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(FeatherIcons.X, contentDescription = "Close")
                    }
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                        Icon(FeatherIcons.ArrowRight, contentDescription = "Forward")
                    }
                    OutlinedTextField(
                        value = addressBarText,
                        onValueChange = { addressBarText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                val target = normalizeBrowserAddress(addressBarText)
                                currentUrl = target
                                webView?.loadUrl(target)
                            }) {
                                Icon(FeatherIcons.ArrowRightCircle, contentDescription = "Go")
                            }
                        },
                    )
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(FeatherIcons.RefreshCw, contentDescription = "Reload")
                    }
                }
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(4.dp))
                }

                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Strip WebView identifiers to avoid anti-bot blocks, but keep the mobile UA 
                                // so the site renders properly for phones instead of tiny desktop mode.
                                settings.userAgentString = settings.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        // Block Android app intents (intent://) which crash the WebView into a blank screen
                                        if (url.startsWith("intent://") || url.startsWith("android-app://")) {
                                            return true
                                        }
                                        return super.shouldOverrideUrlLoading(view, request)
                                    }

                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
                                        if (url != null) {
                                            currentUrl = url
                                            addressBarText = url
                                        }
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        isLoading = false
                                        if (url != null) {
                                            currentUrl = url
                                            addressBarText = url
                                        }
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }
                                }
                                loadUrl(loginUrl)
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    ExtendedFloatingActionButton(
                        onClick = {
                            val host = runCatching { java.net.URI(currentUrl).host }.getOrNull()
                            val cookieHeader = CookieManager.getInstance().getCookie(currentUrl)
                            if (host != null && !cookieHeader.isNullOrBlank()) {
                                val sharedPreferences = context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                                val existing = sharedPreferences.getString(GalleryDlPreferences.KEY_COOKIES, "") ?: ""
                                val merged = mergeNetscapeCookies(existing, host, cookieHeader)
                                sharedPreferences.edit().putString(GalleryDlPreferences.KEY_COOKIES, merged).apply()
                                java.io.File(context.filesDir, "cookies.txt").writeText(merged)
                                onCookiesSaved(merged)
                            } else {
                                onDismiss()
                            }
                        },
                        icon = { Icon(FeatherIcons.Lock, contentDescription = null) },
                        text = { Text("Extract cookies") },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Turns whatever's typed in the address bar into a real URL to load — a bare host/domain gets
 * "https://" prefixed, anything else (no dot, contains a space, ...) is treated as a search query
 * instead of a broken navigation attempt. */
private fun normalizeBrowserAddress(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isBlank() -> "https://www.google.com"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        !trimmed.contains(' ') && trimmed.contains('.') && !trimmed.contains("://") ->
            "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}

/** CookieManager.getCookie() returns an HTTP-header-style string ("name1=value1; name2=value2"),
 * not the Netscape cookies.txt format gallery-dl/yt-dlp's --cookies flag actually parses (tab-
 * separated: domain, includeSubdomains, path, secure, expiry, name, value). Converts and merges
 * into [existing], dropping any prior lines for [host] first so re-extracting replaces rather than
 * duplicates/conflicts with them. */
// Not private — BrowserScreen's own "Extract cookies" action reuses this same conversion.
fun mergeNetscapeCookies(existing: String, host: String, cookieHeader: String): String {
    val domain = if (host.startsWith(".")) host else ".$host"
    val bareDomain = domain.removePrefix(".")
    // Five years out — CookieManager doesn't expose each cookie's real expiry, and a long-lived
    // session cookie being treated as farther in the future than it really is just means it stops
    // working when the site itself expires it, same as any other stale-cookie failure.
    val expiry = (System.currentTimeMillis() / 1000L) + 60L * 60 * 24 * 365 * 5
    val newLines = cookieHeader.split(";").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val name = pair.substring(0, idx).trim()
        val value = pair.substring(idx + 1).trim()
        if (name.isEmpty()) return@mapNotNull null
        "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
    }
    val keptExisting = existing.lineSequence()
        .filter { line -> line.isBlank() || line.startsWith("#") || !(line.startsWith(domain) || line.startsWith(bareDomain)) }
        .toList()
    val header = if (keptExisting.any { it.startsWith("# Netscape") }) emptyList() else listOf("# Netscape HTTP Cookie File")
    return (header + keptExisting + newLines).joinToString("\n").trim()
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    SettingsSubScaffold(title = "About", onBack = onBack) {
        SettingsSection(title = "App", icon = FeatherIcons.Info) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    // painterResource() can't load mipmap-anydpi-v26/ic_launcher.xml (an
                    // AdaptiveIconDrawable, not a plain vector/raster) — this dedicated drawable
                    // copy is what actually renders here instead of crashing.
                    painter = androidx.compose.ui.res.painterResource(com.example.gallerydl.R.drawable.ic_app_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("gallery-dl", style = MaterialTheme.typography.titleSmall)
                    Text("Version $versionName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SettingsSection(title = "Links", icon = FeatherIcons.Link) {
            LinkRow(
                icon = FeatherIcons.Code,
                title = "gallery-dl source code",
                url = "https://github.com/mikf/gallery-dl",
            )
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

// A toggle row with a leading icon-in-a-circle, matching SettingsListRow's top-level style —
// used for every individual switch setting within a section so the per-row icon convention holds
// at both levels, not just the top-level Appearance/Downloads/Advanced/... list.
@Composable
private fun IconToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // Default unchecked thumb color reads as near-invisible against the unchecked track
            // in this theme — an off toggle looked like a flat, dead pill rather than a working
            // control resting in its off position. onSurfaceVariant/surfaceVariant is M3's own
            // "always contrasts against its matching surface" pairing, so the thumb stays clearly
            // visible against the track regardless of light/dark theme — a plain alpha-dimmed
            // color wasn't enough contrast in this app's dark theme specifically.
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun StatusRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodySmall)
    }
}
