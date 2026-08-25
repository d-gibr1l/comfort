package com.example.gallerydl.ui.main

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.gallerydl.data.GalleryDlPreferences
import com.example.gallerydl.theme.LocalThemeState
import com.example.gallerydl.theme.ThemeMode
import kotlinx.coroutines.launch
import compose.icons.FeatherIcons
import compose.icons.feathericons.*

private enum class SettingsRoute { ROOT, DOWNLOADS, ADVANCED, COOKIES, ABOUT }

@Composable
fun MoreScreen() {
    var route by remember { mutableStateOf(SettingsRoute.ROOT) }

    BackHandler(enabled = route != SettingsRoute.ROOT) { route = SettingsRoute.ROOT }

    AnimatedContent(targetState = route, label = "settingsRoute") { current ->
        when (current) {
            SettingsRoute.ROOT -> SettingsRootScreen(onNavigate = { route = it })
            SettingsRoute.DOWNLOADS -> DownloadsSettingsScreen(onBack = { route = SettingsRoute.ROOT })
            SettingsRoute.ADVANCED -> AdvancedSettingsScreen(onBack = { route = SettingsRoute.ROOT })
            SettingsRoute.COOKIES -> CookiesSettingsScreen(onBack = { route = SettingsRoute.ROOT })
            SettingsRoute.ABOUT -> AboutScreen(onBack = { route = SettingsRoute.ROOT })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRootScreen(onNavigate: (SettingsRoute) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val themeState = LocalThemeState.current
    val themeSummary = when (themeState.mode) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }
    val filenameFormat = remember { GalleryDlPreferences.getFilenameFormat(context) }
    val hasCookies = remember { GalleryDlPreferences.getCookies(context).isNotBlank() }
    var showAppearanceDialog by remember { mutableStateOf(false) }

    if (showAppearanceDialog) {
        AppearanceDialog(onDismiss = { showAppearanceDialog = false })
    }

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
                    onClick = { showAppearanceDialog = true },
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

@Composable
private fun AppearanceDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                ThemeModePicker()
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Done")
                }
            }
        }
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
        SettingsSection(title = "Filename format") {
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

        SettingsSection(title = "Download location") {
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

        SettingsSection(title = "Sharing") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Instant download", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Sharing a link downloads it right away in the background. Off shows a picker to choose which images to download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = instantShare,
                    onCheckedChange = {
                        instantShare = it
                        GalleryDlPreferences.setInstantShareEnabled(context, it)
                    },
                )
            }
        }

        SettingsSection(title = "Concurrent downloads") {
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

        SettingsSection(title = "Network") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Queued downloads wait for a Wi-Fi connection instead of using mobile data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        sharedPreferences.edit().putBoolean(GalleryDlPreferences.KEY_WIFI_ONLY, it).apply()
                    },
                )
            }

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

        SettingsSection(title = "Schedule") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Restrict to time window", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "New downloads wait in the queue until the window opens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = scheduleEnabled,
                    onCheckedChange = {
                        scheduleEnabled = it
                        sharedPreferences.edit().putBoolean(GalleryDlPreferences.KEY_SCHEDULE_ENABLED, it).apply()
                        // Otherwise a download already queued under the old setting just sits
                        // there until its stale delay elapses — see rescheduleQueuedDownloads().
                        scope.launch { com.example.gallerydl.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                    },
                )
            }

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val hour = minutesSinceMidnight / 60
    val minute = minutesSinceMidnight % 60

    OutlinedButton(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        onClick = {
            android.app.TimePickerDialog(
                context,
                { _, pickedHour, pickedMinute -> onPicked(pickedHour * 60 + pickedMinute) },
                hour,
                minute,
                true,
            ).show()
        },
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%02d:%02d".format(hour, minute), style = MaterialTheme.typography.titleSmall)
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
        SettingsSection(title = "Extra arguments") {
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
        Dialog(onDismissRequest = { showBrowser = false }) {
            Surface(modifier = Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.large) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { showBrowser = false }) { Text("Close") }
                        Button(
                            onClick = {
                                val cookies = CookieManager.getInstance().getCookie("https://instagram.com")
                                if (cookies != null) {
                                    extractedCookies = cookies
                                    pastedCookies = cookies
                                    sharedPreferences.edit().putString(GalleryDlPreferences.KEY_COOKIES, pastedCookies).apply()
                                    java.io.File(context.filesDir, "cookies.txt").writeText(pastedCookies)
                                }
                                showBrowser = false
                            },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text("Extract Cookies")
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = true
                                loadUrl("https://instagram.com")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    SettingsSubScaffold(title = "Cookies & Login", onBack = onBack) {
        SettingsSection(title = "Cookies") {
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

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    SettingsSubScaffold(title = "About", onBack = onBack) {
        SettingsSection(title = "App") {
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

        SettingsSection(title = "Links") {
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
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
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

@Composable
private fun StatusRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ThemeModePicker() {
    val themeState = LocalThemeState.current
    val options = listOf(
        Triple(ThemeMode.SYSTEM, "System", FeatherIcons.Smartphone),
        Triple(ThemeMode.LIGHT, "Light", FeatherIcons.Sun),
        Triple(ThemeMode.DARK, "Dark", FeatherIcons.Moon),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (mode, label, icon) ->
            val selected = themeState.mode == mode
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { themeState.setMode(mode) },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
