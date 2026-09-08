package com.comfort.app.ui.main

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.theme.LocalThemeState
import com.comfort.app.theme.ThemeMode
import com.comfort.app.util.EngineUpdater
import dev.darkokoa.datetimewheelpicker.WheelTimePicker
import dev.darkokoa.datetimewheelpicker.core.format.TimeFormat
import dev.darkokoa.datetimewheelpicker.core.format.timeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import compose.icons.FeatherIcons
import compose.icons.feathericons.*

enum class SettingsRoute { ROOT, APPEARANCE, DOWNLOADS, ADVANCED, COOKIES, ABOUT }

/** [route]/[onNavigate] are hoisted up to MainScreen rather than owned here — this composable
 * itself gets torn down and rebuilt every time the Settings tab is switched away from and back
 * (MainScreen's `when(selectedTab)` only composes the selected tab's screen at all), so a plain
 * local `remember` here used to reset to ROOT on every tab switch instead of staying wherever the
 * user actually was (reported live: drill into Downloads, tap Library, tap Settings again — lands
 * back on the root list instead of Downloads). Hoisting to MainScreen (which stays composed for
 * the app's whole lifetime) is what actually survives a tab switch. */
@Composable
fun MoreScreen(route: SettingsRoute, onNavigate: (SettingsRoute) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        // The root settings list is what a sub-screen's back gesture reveals — kept composed
        // underneath whenever we're not already on it, same reasoning as MainScreen's Home-behind-
        // a-tab treatment, purely so there's something real to peek at mid-swipe.
        if (route != SettingsRoute.ROOT) {
            SettingsRootScreen(onNavigate = onNavigate)
        }

        val backProgress = rememberPredictiveBackProgress(enabled = route != SettingsRoute.ROOT) {
            onNavigate(SettingsRoute.ROOT)
        }
        Box(modifier = Modifier.fillMaxSize().predictiveBackReveal(backProgress)) {
            when (route) {
                SettingsRoute.ROOT -> SettingsRootScreen(onNavigate = onNavigate)
                SettingsRoute.APPEARANCE -> AppearanceScreen(onBack = { onNavigate(SettingsRoute.ROOT) })
                SettingsRoute.DOWNLOADS -> DownloadsSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT) })
                SettingsRoute.ADVANCED -> AdvancedSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT) })
                SettingsRoute.COOKIES -> CookiesSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT) })
                SettingsRoute.ABOUT -> AboutScreen(onBack = { onNavigate(SettingsRoute.ROOT) })
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

    var searchQuery by remember { mutableStateOf("") }
    val mainItems = remember(themeSummary, filenameFormat, hasCookies) {
        listOf(
            SettingsItemSpec(FeatherIcons.Sun, "Appearance", themeSummary, SettingsItemColor.TERTIARY) { onNavigate(SettingsRoute.APPEARANCE) },
            SettingsItemSpec(FeatherIcons.Download, "Downloads", filenameFormat, SettingsItemColor.PRIMARY) { onNavigate(SettingsRoute.DOWNLOADS) },
            SettingsItemSpec(FeatherIcons.Terminal, "Advanced", "Extra gallery-dl arguments", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.ADVANCED) },
            SettingsItemSpec(FeatherIcons.Lock, "Cookies & Login", if (hasCookies) "Configured" else "Not set", SettingsItemColor.PRIMARY) { onNavigate(SettingsRoute.COOKIES) },
        )
    }
    val aboutItems = remember {
        listOf(SettingsItemSpec(FeatherIcons.Info, "About", "Version, credits & source", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.ABOUT) })
    }
    val filteredMainItems = mainItems.filter { it.matches(searchQuery) }
    val filteredAboutItems = aboutItems.filter { it.matches(searchQuery) }
    val isSearching = searchQuery.isNotBlank()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    // Bottom-aligned within an explicit, bounded slot (rather than the default
                    // vertical centering) so the label sits low in the bar, close to the search
                    // bar just below it, without touching that search bar or anything else on the
                    // page. fillMaxHeight() here instead of a fixed height measured against
                    // whatever unbounded height the Scaffold's topBar slot actually passes down —
                    // reproduced live, the label ended up pushed almost entirely off the bottom of
                    // the screen. This slot's own height becomes the whole TopAppBar's height (the
                    // bar wraps to its tallest child), so whatever's reserved above the
                    // bottom-aligned text here shows up as empty space between the status bar and
                    // the label — 96dp left a visibly larger gap there than necessary; 72dp still
                    // comfortably clears this 40sp custom-font text with a little room to spare.
                    // A few dp start padding to line the label's own left edge up with the search
                    // bar/list below (the content Column's own 20dp horizontal padding) — the
                    // TopAppBar's default title inset falls a little short of that on its own.
                    Box(modifier = Modifier.height(72.dp).padding(start = 4.dp), contentAlignment = Alignment.BottomStart) {
                        Text("Settings",
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.comfort.app.R.font.crystal_radio_kit)),
                            fontSize = 40.sp
                        )
                    }
                },
                // Same top-of-screen gradient as Home/Library (primary fading into background)
                // instead of a flat bar, so Settings matches the rest of the app's header treatment.
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.background,
                        )
                    )
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                // Horizontal/bottom keep the original uniform 20dp; top is its own smaller value
                // so the search bar sits close under the "Settings" label — which stays exactly
                // where it is, in the TopAppBar above — instead of leaving the same 20dp gap
                // below it that the rest of the page's margins use.
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsSearchBar(query = searchQuery, onQueryChange = { searchQuery = it })

            ExpressiveSettingsList(items = filteredMainItems, emptyMessage = "No settings match \"$searchQuery\"".takeIf { isSearching && filteredMainItems.isEmpty() && filteredAboutItems.isEmpty() })

            if (!isSearching) {
                QuickEngineUpdateSection()
            }

            if (filteredAboutItems.isNotEmpty()) {
                ExpressiveSettingsList(items = filteredAboutItems)
            }

            // Clears the floating nav pill overlaying this screen (see MainScreen's own comment
            // on why it overlays instead of reserving Scaffold space) so this list can scroll
            // fully clear of it instead of ending up hidden behind.
            Spacer(Modifier.height(navBarClearance()))
        }
    }
}

// A pill-shaped 56dp search bar filtering the settings rows below it in real time.
@Composable
private fun SettingsSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FeatherIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private enum class SettingsItemColor { PRIMARY, TERTIARY, SURFACE_HIGH }

private class SettingsItemSpec(
    val icon: ImageVector,
    val title: String,
    val summary: String,
    val color: SettingsItemColor,
    val onClick: () -> Unit,
) {
    fun matches(query: String): Boolean =
        query.isBlank() || title.contains(query, ignoreCase = true) || summary.contains(query, ignoreCase = true)
}

// The M3 Expressive "list group" shape treatment: 3dp gaps between items, 28dp on the group's
// outer top/bottom corners, 8dp on the corners items share with their neighbor.
private fun expressiveListItemShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 28.dp
    val inner = 8.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun ExpressiveSettingsList(items: List<SettingsItemSpec>, emptyMessage: String? = null) {
    if (items.isEmpty()) {
        if (emptyMessage != null) {
            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { index, item ->
            val (containerColor, onContainerColor) = when (item.color) {
                SettingsItemColor.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                SettingsItemColor.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                SettingsItemColor.SURFACE_HIGH -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            }
            SettingsListRow(
                icon = item.icon,
                title = item.title,
                summary = item.summary,
                containerColor = containerColor,
                onContainerColor = onContainerColor,
                shape = expressiveListItemShape(index, items.size),
                onClick = item.onClick,
            )
        }
    }
}

@Composable
private fun SettingsListRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    containerColor: Color,
    onContainerColor: Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = shape,
        color = containerColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    // A hardcoded primaryContainer background here disappeared entirely on a
                    // PRIMARY row (Downloads, Cookies & Login) — its own Surface color IS
                    // primaryContainer, so the circle drew in the same color as what's behind it.
                    // A tint of the row's own onContainerColor instead stays visibly distinct no
                    // matter which of the three row colors this is.
                    .background(onContainerColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = onContainerColor, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = onContainerColor, fontWeight = FontWeight.SemiBold)
                if (summary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainerColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(FeatherIcons.ChevronRight, contentDescription = null, tint = onContainerColor, modifier = Modifier.size(18.dp))
        }
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
                // Same top-of-screen gradient as the Settings root (and Home/Library) instead of a
                // flat bar, so every sub-page shares the same header treatment as the rest of the app.
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.background,
                        )
                    )
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                // Extra bottom inset beyond the normal 20dp: the floating nav bar overlays the
                // bottom of the screen without reserving space, so without this the last section
                // (e.g. Schedule's Start/End time buttons) scrolls to right underneath it and is
                // unreachable/unreadable. navBarClearance() (not a flat guess) so this also clears
                // the real system nav bar inset on devices where it's taller than this app's own
                // pill assumed — see its own doc comment (MainScreen.kt) for the full story. Used
                // by every settings sub-page through this one shared scaffold.
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + navBarClearance()),
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
    var proxyUrl by remember { mutableStateOf(GalleryDlPreferences.getProxyUrl(context)) }
    var maxFilesizeEnabled by remember { mutableStateOf(GalleryDlPreferences.isMaxFilesizeEnabled(context)) }
    var maxFilesize by remember { mutableStateOf(GalleryDlPreferences.getMaxFilesize(context)) }
    var instantShare by remember { mutableStateOf(GalleryDlPreferences.isInstantShareEnabled(context)) }
    var videoQuality by remember { mutableStateOf(GalleryDlPreferences.getVideoQuality(context)) }
    var noPlaylist by remember { mutableStateOf(GalleryDlPreferences.isNoPlaylist(context)) }
    var liveFromStart by remember { mutableStateOf(GalleryDlPreferences.isLiveFromStart(context)) }
    var downloadSubtitles by remember { mutableStateOf(GalleryDlPreferences.isDownloadSubtitles(context)) }
    var subtitleLanguages by remember { mutableStateOf(GalleryDlPreferences.getSubtitleLanguages(context)) }
    var embedThumbnail by remember { mutableStateOf(GalleryDlPreferences.isEmbedThumbnail(context)) }
    var embedMetadata by remember { mutableStateOf(GalleryDlPreferences.isEmbedMetadata(context)) }
    var writeInfoFiles by remember { mutableStateOf(GalleryDlPreferences.isWriteInfoFiles(context)) }
    var outputFormat by remember { mutableStateOf(GalleryDlPreferences.getOutputFormat(context)) }
    var networkRetries by remember { mutableStateOf(GalleryDlPreferences.getNetworkRetries(context)) }
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

    // A download forced past the schedule window (Start Now) requests setExpedited(), but Android
    // grants each app only a limited expedited-job quota — once a burst of Start Now taps burns
    // through it, the next one silently falls back to a plain background job instead of erroring.
    // A plain background job is exactly the kind of work Battery Saver + the app being backgrounded
    // is allowed to hold indefinitely (reproduced live: one sat QUEUED with its own real
    // workRequestId assigned, but DownloadWorker.doWork() was never actually invoked for it).
    // Whitelisting the app from battery optimization exempts it from that background-network block
    // entirely, the same fix standard Android download managers point users to for this exact class
    // of stall. Re-read (not just set once) via the launcher's callback below, since the user grants
    // or denies this from a system dialog this screen has no other way to observe returning from.
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryUnrestricted = isIgnoringBatteryOptimizations() }

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
                if (locationName != null) "Saving to \"$locationName\"." else "Saving to the default Pictures/Comfort folder.",
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

            Text("Output format", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Only applies when video and audio need merging (most yt-dlp sources). A single already-muxed file, or anything gallery-dl fetches directly, keeps its own format regardless. If MP4 is picked but a source's video can't actually go in an MP4 (Instagram Reels are usually like this), that one download saves as MKV instead rather than an unplayable MP4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutputFormat.entries.forEach { format ->
                    val selected = outputFormat == format
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = {
                            outputFormat = format
                            GalleryDlPreferences.setOutputFormat(context, format)
                        },
                    ) {
                        Box(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                format.label,
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
                icon = FeatherIcons.Rewind,
                title = "Live streams from the start",
                subtitle = "Download an in-progress live stream from its beginning instead of starting at the current moment. Has no effect on a video that isn't currently live.",
                checked = liveFromStart,
                onCheckedChange = {
                    liveFromStart = it
                    GalleryDlPreferences.setLiveFromStart(context, it)
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
                icon = FeatherIcons.FileText,
                title = "Write description / info.json files",
                subtitle = "Save a separate JSON metadata file alongside each download.",
                checked = writeInfoFiles,
                onCheckedChange = {
                    writeInfoFiles = it
                    GalleryDlPreferences.setWriteInfoFiles(context, it)
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
                            // Without this, everything already queued stays chained in whatever
                            // round-robin lane(s) it was originally assigned to (e.g. all in
                            // gallery_dl_queue_0 from when the setting was 1) and keeps running
                            // exactly that concurrently regardless of the new setting — it only
                            // ever applied to downloads added *after* this tap. Redistributes the
                            // existing backlog across the new lane count immediately instead of
                            // leaving the user's current queue stuck on the old concurrency.
                            scope.launch { DownloadDispatcher.rescheduleQueuedDownloads(context) }
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
                "Caps download bandwidth for all future downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SizeSheetField(
                currentValue = speedLimit,
                label = "Speed limit",
                units = SPEED_UNITS,
                onValueChange = {
                    speedLimit = it
                    GalleryDlPreferences.setSpeedLimit(context, it)
                    // A speed limit is only ever read fresh when a new subprocess is spawned — no
                    // IPC channel reaches an already-running one, so changing it here used to do
                    // nothing for whatever's downloading right now, only the next thing queued.
                    scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            Text("Retries", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "How many times a failed request is retried before the download actually fails.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 10 is yt-dlp's own built-in default (see GalleryDlPreferences.DEFAULT_NETWORK_RETRIES) —
                // included as a preset rather than only reachable by not touching this setting at all.
                listOf(3, 5, 10, 20).forEach { count ->
                    val selected = networkRetries == count
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = {
                            networkRetries = count
                            GalleryDlPreferences.setNetworkRetries(context, count)
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

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            Text("Proxy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Routes all future downloads through this proxy. Supports http://, https:// and socks5://.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = {
                    proxyUrl = it
                    GalleryDlPreferences.setProxyUrl(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Proxy") },
                placeholder = { Text("None") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            // Same reasoning as speedLimitSettled elsewhere in this file — a proxy is only ever
            // read fresh when a new subprocess is spawned, so changing it here otherwise does
            // nothing for whatever's downloading right now. Debounced so typing a new URL doesn't
            // restart every currently running download on every keystroke.
            var proxyUrlSettled by remember { mutableStateOf(proxyUrl) }
            LaunchedEffect(proxyUrl) {
                delay(800)
                if (proxyUrl != proxyUrlSettled) {
                    proxyUrlSettled = proxyUrl
                    DownloadDispatcher.restartRunningDownloads(context)
                }
            }
        }

        SettingsSection(title = "Reliability", icon = FeatherIcons.Zap) {
            if (batteryUnrestricted) {
                StatusRow(FeatherIcons.CheckCircle, "Unrestricted — downloads can keep running in the background.", MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    "Unrestricted background activity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Without this, Android's Battery Saver can silently freeze a download that's " +
                        "waiting to start once the app is backgrounded — especially after \"Start now\" " +
                        "is tapped several times in a row. Recommended if downloads seem to get stuck.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        // Some OEM skins ship no activity for this action at all despite declaring
                        // the permission — falls back to the general battery-settings screen rather
                        // than crashing on startActivity() with no handler.
                        runCatching { batteryOptimizationLauncher.launch(intent) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(FeatherIcons.Zap, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Allow unrestricted background activity")
                }
            }
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
                    scope.launch { com.comfort.app.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
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
                            scope.launch { com.comfort.app.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                        },
                    )
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = "End",
                        minutesSinceMidnight = scheduleEndMin,
                        onPicked = { minutes ->
                            scheduleEndMin = minutes
                            sharedPreferences.edit().putInt(GalleryDlPreferences.KEY_SCHEDULE_END_MIN, minutes).apply()
                            scope.launch { com.comfort.app.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                        },
                    )
                }
            }
        }

        SettingsSection(title = "Max file size", icon = FeatherIcons.HardDrive) {
            IconToggleRow(
                icon = FeatherIcons.HardDrive,
                title = "Limit max file size",
                subtitle = "Files larger than this are skipped instead of downloaded.",
                checked = maxFilesizeEnabled,
                onCheckedChange = {
                    maxFilesizeEnabled = it
                    GalleryDlPreferences.setMaxFilesizeEnabled(context, it)
                    scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                },
            )

            if (maxFilesizeEnabled) {
                Spacer(Modifier.height(16.dp))
                SizeSheetField(
                    currentValue = maxFilesize,
                    label = "Max file size",
                    units = FILESIZE_UNITS,
                    onValueChange = {
                        maxFilesize = it
                        GalleryDlPreferences.setMaxFilesize(context, it)
                        scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                    },
                )
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
    var extractorArgs by remember { mutableStateOf(GalleryDlPreferences.getExtractorArgs(context)) }
    var extractorArgsSaved by remember { mutableStateOf(false) }

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

        SettingsSection(title = "yt-dlp extractor arguments", icon = FeatherIcons.Terminal) {
            Text(
                "Site-specific yt-dlp tuning (throttling workarounds, player client selection, etc). " +
                    "CLI syntax, e.g. \"youtube:player_client=android,web\". Separate multiple " +
                    "extractors with whitespace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = extractorArgs,
                onValueChange = { extractorArgs = it; extractorArgsSaved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("e.g. youtube:player_client=android") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    GalleryDlPreferences.setExtractorArgs(context, extractorArgs)
                    extractorArgsSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(FeatherIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save extractor arguments")
            }

            if (extractorArgsSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = FeatherIcons.CheckCircle, text = "Extractor arguments saved", tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun CookiesSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val cookiesFile = remember { java.io.File(context.filesDir, "cookies.txt") }

    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showBrowser by remember { mutableStateOf(false) }
    var extractedCookies by remember { mutableStateOf("") }
    // Deliberately empty, not seeded from whatever's already saved — this box is for pasting a
    // *new* cookies.txt in, not for displaying/re-editing what's already saved (that's what the
    // per-site table below is for). Reported live: pre-filling it with the current save meant this
    // one field alone could end up showing every cookie for every site as one giant wall of raw
    // text, duplicating what the table already shows more usefully.
    var pastedCookies by remember { mutableStateOf("") }
    var savedConfirmation by remember { mutableStateOf(false) }
    // Distinct from savedConfirmation, not just its negation — persist()/clearing pastedCookies
    // only happens on an actual successful parse now (see the Save cookies button below), so this
    // and savedConfirmation are never both true from the same click.
    var pasteError by remember { mutableStateOf<String?>(null) }
    // The real, on-disk cookies.txt is the one source of truth gallery-dl/yt-dlp actually read
    // (see GalleryDlListing.kt/DownloadWorker.kt) — SharedPreferences' own KEY_COOKIES is only ever
    // a same-content mirror written alongside it, kept for the raw-paste textbox's own persistence.
    // Reading the file fresh (not the mirror) for the parsed table below means it can never drift
    // out of sync with what a download actually uses, the way two independently-updated copies of
    // the same data always eventually can.
    var savedCookiesContent by remember {
        mutableStateOf(runCatching { cookiesFile.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty())
    }
    val parsedCookies = remember(savedCookiesContent) { parseCookiesFile(savedCookiesContent) }

    fun persist(content: String) {
        sharedPreferences.edit().putString(GalleryDlPreferences.KEY_COOKIES, content).apply()
        cookiesFile.writeText(content)
        savedCookiesContent = content
    }

    if (showBrowser) {
        CookieLoginDialog(
            loginUrl = "https://instagram.com",
            onDismiss = { showBrowser = false },
            onCookiesSaved = { merged ->
                extractedCookies = merged
                savedCookiesContent = merged
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
                onValueChange = { pastedCookies = it; pasteError = null },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                label = { Text("cookies.txt contents") },
                maxLines = 10,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    // Merges into whatever's already saved rather than replacing it wholesale — a
                    // manual paste is almost always meant to *add* a site's cookies (or refresh
                    // one), not wipe out every other site's already-saved login in the process.
                    // Works on the already-parsed rawLine form (not mergeNetscapeCookies, which
                    // rebuilds each line from scratch with a fixed 5-year expiry and TRUE/TRUE
                    // flags — fine for a browser-extracted cookie header with no real field values
                    // of its own, but would silently overwrite a *pasted* file's own genuine
                    // expiry/secure/subdomain flags) — only the touched registrable domains get
                    // replaced, everything else already saved is left exactly as it was.
                    val pastedParsed = parseCookiesFile(pastedCookies)
                    // parseCookiesFile() silently drops any line that isn't real tab-separated
                    // Netscape format (fewer than 7 fields) rather than throwing — the right call
                    // for skipping a comment/header line, but it means a paste in the wrong format
                    // entirely (an HTTP-header-style "name=value; name2=value2" string, a browser
                    // cookie-editor's own space-aligned export, ...) used to parse to an empty list
                    // and this button would still merge that into (no-op) the existing file and
                    // claim "Cookies saved and applied" regardless — reproduced live: pasted
                    // Instagram cookies that weren't real tab-separated Netscape rows disappeared
                    // with no error, and the very next thing the user saw was a false success
                    // message. Refusing to persist or clear the textbox (so the original paste is
                    // still there to fix/copy elsewhere) when nothing actually parsed turns that
                    // silent no-op into a real, actionable error instead.
                    if (pastedCookies.isNotBlank() && pastedParsed.isEmpty()) {
                        pasteError = "Couldn't find any valid cookies in that text — it needs to be real tab-separated Netscape format (domain, includeSubdomains, path, secure, expiry, name, value per line), not just \"name=value\" pairs."
                        savedConfirmation = false
                    } else {
                        val touchedDomains = pastedParsed.map { it.domain.removePrefix(".") }.toSet()
                        val keptExisting = parseCookiesFile(savedCookiesContent)
                            .filterNot { it.domain.removePrefix(".") in touchedDomains }
                        // parseCookiesFile() deliberately drops comment/header lines when parsing (so
                        // they don't get double-counted as fake cookies) — rebuilding purely from
                        // rawLine values without adding this back means the result can never carry the
                        // "# Netscape HTTP Cookie File" header gallery-dl/yt-dlp's own cookie-jar parser
                        // requires as the file's literal first line. Reproduced live: a save through
                        // this exact path produced a header-less file that both engines flatly rejected
                        // as "does not look like a Netscape format cookies file", failing every
                        // download outright regardless of whether that site even needed cookies.
                        val mergedText = (listOf("# Netscape HTTP Cookie File") + (keptExisting + pastedParsed).map { it.rawLine })
                            .joinToString("\n")
                        persist(mergedText)
                        pastedCookies = ""
                        pasteError = null
                        savedConfirmation = true
                    }
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
            pasteError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        FeatherIcons.AlertTriangle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // One row per *site*, not per individual cookie — a real login legitimately saves a dozen-
        // plus individual cookies together (sessionid, csrftoken, a device id, ...; see
        // groupCookiesBySite's own doc comment for why), and showing every single one as its own
        // row read as "the app is duplicating my cookies" rather than "this is what one login
        // actually consists of" (reported live). Grouped by registrable domain instead — Instagram
        // shows as one row regardless of how many individual cookies back that session, same for
        // Reddit, etc. — with a per-site delete that removes that whole group's cookies at once.
        // Still sourced from the real cookies.txt (see savedCookiesContent's own comment), so it
        // always reflects exactly what a download would actually send.
        val cookieSites = remember(parsedCookies) { groupCookiesBySite(parsedCookies) }
        SettingsSection(title = "Saved cookies (${cookieSites.size})", icon = FeatherIcons.List) {
            if (cookieSites.isEmpty()) {
                Text(
                    "No cookies saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                cookieSites.forEachIndexed { index, site ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                site.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${site.cookies.size} cookie${if (site.cookies.size == 1) "" else "s"} · ${formatCookieExpiry(site.soonestExpiryEpochSeconds)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            val text = site.cookies.joinToString("\n") { it.rawLine }
                            scope.launch {
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("${site.label} cookies", text)))
                            }
                        }) {
                            Icon(FeatherIcons.Copy, contentDescription = "Copy ${site.label}'s cookies", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = {
                            val toRemove = site.cookies.toSet()
                            // Same header requirement as the Save button's own merge logic above —
                            // parseCookiesFile() strips comment/header lines when parsing, so
                            // rebuilding purely from the surviving cookies' rawLine values needs the
                            // "# Netscape HTTP Cookie File" header added back explicitly, or the
                            // result fails gallery-dl/yt-dlp's strict format check the same way.
                            val remaining = parsedCookies.filter { it !in toRemove }
                            val updated = (listOf("# Netscape HTTP Cookie File") + remaining.map { it.rawLine })
                                .joinToString("\n")
                            persist(updated)
                        }) {
                            Icon(FeatherIcons.Trash2, contentDescription = "Remove ${site.label}'s cookies", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (index != cookieSites.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("cookies.txt", savedCookiesContent)))
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(FeatherIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy all")
                    }
                    OutlinedButton(
                        onClick = { persist("") },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(FeatherIcons.Trash2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear all")
                    }
                }
            }
        }
    }
}

/** One row of a Netscape-format cookies.txt (the format gallery-dl/yt-dlp's --cookies flag reads —
 * see mergeNetscapeCookies' own doc comment for the field layout). [rawLine] is kept verbatim
 * (rather than reconstructed from the parsed fields) so deleting a cookie can just drop its exact
 * original line instead of risking a lossy round-trip through re-serialization. */
private data class ParsedCookie(
    val domain: String,
    val name: String,
    val expiryEpochSeconds: Long,
    val rawLine: String,
)

/** Real comment lines start with "#" and nothing else meaningful follows on that line; a
 * "#HttpOnly_"-prefixed line (a real convention plenty of cookies.txt exports — Chrome's own
 * cookie-export extensions among them — actually use) is NOT a comment despite the leading "#": the
 * rest of the line past that exact prefix is a genuine tab-separated cookie row, just one flagged
 * httpOnly. Reproduced live in this app's own "paste raw cookies" flow: without stripping that
 * prefix first, every httpOnly cookie in a real exported file silently vanished from the parsed
 * list, showing as if the file were mostly empty.
 *
 * Falls back to splitting on any run of whitespace (not just a literal tab) when a line doesn't
 * split into 7 real tab-separated fields — reproduced live with a genuine Cookie-Editor export
 * whose tabs had been silently collapsed to plain spaces somewhere in the copy/paste chain (a
 * viewer or app re-rendering the text, not something gallery-dl's own export ever did). Visually
 * indistinguishable from a real tab-separated file, and previously failed this parser entirely —
 * `limit = 7` keeps the 7th field (the cookie's value) intact as everything remaining, in case it
 * legitimately contains its own internal whitespace, rather than truncating it. The corresponding
 * ParsedCookie.rawLine is rebuilt with real tabs in this fallback branch — a big difference from
 * gallery-dl/yt-dlp's own `--cookies` parser (Python's http.cookiejar), which is strict and only
 * ever splits on literal tabs: persisting the original, space-only line as-is would have let this
 * *display* correctly in the app's own cookie list while still silently failing to actually
 * authenticate a single real download — worse than never fixing it, since it would look fixed. */
private fun parseCookiesFile(content: String): List<ParsedCookie> {
    return content.lineSequence().mapNotNull { rawLine ->
        val trimmed = rawLine.trimEnd('\r')
        if (trimmed.isBlank()) return@mapNotNull null
        val hadHttpOnlyPrefix = trimmed.startsWith("#HttpOnly_")
        val dataLine = trimmed.removePrefix("#HttpOnly_")
        if (dataLine.startsWith("#")) return@mapNotNull null
        var fields = dataLine.split("\t")
        var normalizedLine = rawLine
        if (fields.size < 7) {
            val whitespaceFields = dataLine.trim().split(Regex("\\s+"), limit = 7)
            if (whitespaceFields.size == 7) {
                fields = whitespaceFields
                normalizedLine = (if (hadHttpOnlyPrefix) "#HttpOnly_" else "") + whitespaceFields.joinToString("\t")
            }
        }
        if (fields.size < 7) return@mapNotNull null
        ParsedCookie(
            domain = fields[0],
            expiryEpochSeconds = fields[4].toLongOrNull() ?: 0L,
            name = fields[5],
            rawLine = normalizedLine,
        )
    }.toList()
}

/** One "site" worth of cookies for the grouped summary row — [cookies] keeps every individual
 * [ParsedCookie] that belongs to it (needed to actually delete them, and to compute
 * [soonestExpiryEpochSeconds]), while the row itself only ever shows [label] and a count. */
private data class SiteCookies(
    val label: String,
    val cookies: List<ParsedCookie>,
) {
    // The soonest of the group's own expiries is what actually determines when this login first
    // needs refreshing — showing the *latest* one instead would understate how soon a session
    // might already be partly stale (a real login session's individual cookies don't all share one
    // expiry; some non-essential ones (display prefs, A/B-test bucketing) are often set to expire
    // far sooner than the actual session token itself, without meaning the session as a whole
    // isn't still good).
    val soonestExpiryEpochSeconds: Long = cookies
        .map { it.expiryEpochSeconds }
        .filter { it > 0L }
        .minOrNull() ?: 0L
}

/** Groups by *registrable* domain (the last two dot-separated labels — "www.reddit.com" and
 * ".reddit.com" both collapse to the same "reddit.com" group, same simplification VideoSiteRouter
 * itself already makes) rather than by the exact domain string each cookie's own line happens to
 * carry, since a single real login often spans a mix of exact-domain and subdomain-inclusive
 * ("TRUE" in the Netscape format's own includeSubdomains column) cookies for what's really one
 * site as far as a user setting up a login is concerned. Sorted by site label for a stable,
 * predictable display order rather than whatever order cookies.txt's own lines happen to be in. */
private fun groupCookiesBySite(cookies: List<ParsedCookie>): List<SiteCookies> {
    return cookies
        .groupBy { cookie ->
            val bare = cookie.domain.removePrefix(".")
            val labelParts = bare.split(".")
            if (labelParts.size <= 2) bare else labelParts.takeLast(2).joinToString(".")
        }
        .map { (rootDomain, group) -> SiteCookies(label = VideoSiteRouter.siteName("https://$rootDomain"), cookies = group) }
        .sortedBy { it.label.lowercase() }
}

private fun formatCookieExpiry(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Session"
    val millis = epochSeconds * 1000
    if (millis < System.currentTimeMillis()) return "Expired"
    return java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
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
                                // Force a Desktop Chrome User-Agent. Instagram's mobile site often sends intent:// redirects 
                                // to force opening their native app, which causes WebViews to go completely blank.
                                // The desktop site works flawlessly and doesn't try to deep-link you away.
                                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
                // painterResource() can't load mipmap-anydpi-v26/ic_launcher.xml directly (an
                // AdaptiveIconDrawable, not a plain vector/raster) — composed by hand here from
                // the same two layers instead: ic_launcher_background.xml (a plain white rect,
                // approximated directly rather than parsed) behind ic_launcher_foreground.xml,
                // the actual wordmark. This used to be a separate static ic_app_logo.png export
                // that silently drifted out of sync with the real launcher icon once it changed
                // (reported live: "still using the old app icon") — rendering the *same* vector
                // the launcher itself uses guarantees they can't drift again. Fixed black/white
                // rather than theme-reactive, matching ic_launcher_foreground.xml's own reasoning:
                // this is a copy of the real launcher icon, which never follows the in-app theme.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        imageVector = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Comfort", style = MaterialTheme.typography.titleSmall)
                    Text("Version $versionName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        EnginesSection()

        // gallery-dl was the only credit here before — a real gap for an app that's really built on
        // five separately-licensed open-source projects, not one: yt-dlp does just as much of the
        // actual downloading (see VideoSiteRouter), and PythonRuntime's own doc comment describes
        // running YTDLnis's published interpreter/curl_cffi build as a subprocess, FFmpeg
        // (FfmpegRuntime) merging video/audio, and QuickJS (QuickJsRuntime) solving yt-dlp's JS
        // challenges — none of them previously credited or linked anywhere in the app.
        SettingsSection(title = "Credits", icon = FeatherIcons.Link) {
            LinkRow(
                icon = FeatherIcons.Code,
                title = "gallery-dl",
                url = "https://github.com/mikf/gallery-dl",
            )
            LinkRow(
                icon = FeatherIcons.Terminal,
                title = "yt-dlp",
                url = "https://github.com/yt-dlp/yt-dlp",
            )
            LinkRow(
                icon = FeatherIcons.Film,
                title = "FFmpeg",
                url = "https://ffmpeg.org",
            )
            LinkRow(
                icon = FeatherIcons.Cpu,
                title = "QuickJS",
                url = "https://bellard.org/quickjs/",
            )
            LinkRow(
                icon = FeatherIcons.Package,
                title = "YTDLnis Python runtime (curl_cffi build)",
                url = "https://github.com/deniscerri/ytdlnis-packages",
            )
        }
    }
}

/** Compact teaser on the Settings root list, so an available engine update is both visible and
 * actionable without drilling into About > Engines first — mirrors that section's own check/update
 * logic (see EnginesSection() below) but renders nothing at all when everything's already current,
 * rather than always showing a "Checking…"/"Up to date" panel the way the About page's version
 * does (appropriate there as a persistent utility panel; here it should only ever appear as news,
 * not a permanent fixture). */
@Composable
private fun QuickEngineUpdateSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<EngineUpdater.VersionStatus>?>(null) }
    var updatingEngine by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        var result = EngineUpdater.checkAll(context)
        // Same auto-update behavior as MainScreen's own periodic check (Settings > About > Engines
        // > Auto-update, on by default) — opening Settings shouldn't need a manual tap either when
        // it's enabled.
        if (GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) {
            result = result.map { status ->
                val wheelUrl = status.wheelUrl
                if (status.updateAvailable && wheelUrl != null) {
                    val update = EngineUpdater.update(context, status.engine, wheelUrl, status.sha256)
                    update.getOrNull()?.let { newVersion -> status.copy(installedVersion = newVersion) } ?: status
                } else {
                    status
                }
            }
        }
        statuses = result
        val available = result.any { it.updateAvailable }
        GalleryDlPreferences.setEngineUpdateAvailable(context, available)
        GalleryDlPreferences.setEngineUpdateLastCheckMs(context, System.currentTimeMillis())
        EngineUpdateSignal.hasUpdate = available
    }

    val outdated = statuses?.filter { it.updateAvailable } ?: return
    if (outdated.isEmpty()) return

    SettingsSection(title = "Updates available", icon = FeatherIcons.RefreshCw) {
        outdated.forEachIndexed { index, status ->
            EngineUpdateRow(
                status = status,
                updating = updatingEngine == status.engine.packageDirName,
                onUpdate = {
                    val wheelUrl = status.wheelUrl ?: return@EngineUpdateRow
                    updatingEngine = status.engine.packageDirName
                    errorText = null
                    scope.launch {
                        val result = EngineUpdater.update(context, status.engine, wheelUrl, status.sha256)
                        updatingEngine = null
                        result.onSuccess { newVersion ->
                            val updated = statuses.orEmpty().map {
                                if (it.engine == status.engine) it.copy(installedVersion = newVersion) else it
                            }
                            statuses = updated
                            val available = updated.any { it.updateAvailable }
                            GalleryDlPreferences.setEngineUpdateAvailable(context, available)
                            EngineUpdateSignal.hasUpdate = available
                        }
                        result.onFailure { e ->
                            errorText = "Couldn't update ${status.engine.displayName}: ${e.message ?: "unknown error"}"
                        }
                    }
                },
            )
            if (index != outdated.lastIndex) Spacer(Modifier.height(12.dp))
        }
        if (errorText != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** yt-dlp and gallery-dl are bundled as static wheels (see PythonRuntime's own doc comment) that
 * only get refreshed when this app itself ships a new APK — but site extractors break against the
 * live site far more often than that. This lets either engine be updated independently, straight
 * from PyPI, without waiting on an app release — the in-app equivalent of yt-dlp's own `-U` flag. */
@Composable
private fun EnginesSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<EngineUpdater.VersionStatus>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var updatingEngine by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var autoUpdate by remember { mutableStateOf(GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) }

    fun runCheck() {
        checking = true
        errorText = null
        scope.launch {
            val result = EngineUpdater.checkAll(context)
            statuses = result
            checking = false
            // Refreshes both the persisted flag (survives process restart) and the live in-session
            // signal MainScreen's nav-bar badge reads directly — opening this screen and checking
            // here is itself a fresh signal, no reason to wait for the next auto-check interval, or
            // a tab switch, to clear/set it.
            val available = result.any { it.updateAvailable }
            GalleryDlPreferences.setEngineUpdateAvailable(context, available)
            GalleryDlPreferences.setEngineUpdateLastCheckMs(context, System.currentTimeMillis())
            EngineUpdateSignal.hasUpdate = available
        }
    }

    LaunchedEffect(Unit) { runCheck() }

    SettingsSection(title = "Engines", icon = FeatherIcons.RefreshCw) {
        IconToggleRow(
            icon = FeatherIcons.Zap,
            title = "Auto-update",
            subtitle = "Install a newer yt-dlp/gallery-dl release automatically when one's found, instead of just flagging it.",
            checked = autoUpdate,
            onCheckedChange = {
                autoUpdate = it
                GalleryDlPreferences.setAutoUpdateEnginesEnabled(context, it)
            },
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))

        val currentStatuses = statuses
        if (currentStatuses == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking for updates…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            currentStatuses.forEachIndexed { index, status ->
                EngineUpdateRow(
                    status = status,
                    updating = updatingEngine == status.engine.packageDirName,
                    onUpdate = {
                        val wheelUrl = status.wheelUrl ?: return@EngineUpdateRow
                        updatingEngine = status.engine.packageDirName
                        errorText = null
                        scope.launch {
                            val result = EngineUpdater.update(context, status.engine, wheelUrl, status.sha256)
                            updatingEngine = null
                            result.onSuccess { newVersion ->
                                val updated = currentStatuses.map {
                                    if (it.engine == status.engine) it.copy(installedVersion = newVersion) else it
                                }
                                statuses = updated
                                val available = updated.any { it.updateAvailable }
                                GalleryDlPreferences.setEngineUpdateAvailable(context, available)
                                EngineUpdateSignal.hasUpdate = available
                            }
                            result.onFailure { e ->
                                errorText = "Couldn't update ${status.engine.displayName}: ${e.message ?: "unknown error"}"
                            }
                        }
                    },
                )
                if (index != currentStatuses.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        if (errorText != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { runCheck() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check for updates")
            }
        }
    }
}

@Composable
private fun EngineUpdateRow(status: EngineUpdater.VersionStatus, updating: Boolean, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(status.engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                status.installedVersion?.let { "v$it" } ?: "Version unknown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            updating -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            status.updateAvailable -> Button(
                onClick = onUpdate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Update to ${status.latestVersion}", style = MaterialTheme.typography.labelMedium) }
            status.latestVersion != null -> Text(
                "Up to date",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Text(
                "Couldn't check",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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


// (label, suffix) pairs, e.g. "KB/s" -> "k". Order determines the trailing toggle's cycle order.
private val SPEED_UNITS = listOf("KB/s" to "k", "MB/s" to "m")
private val FILESIZE_UNITS = listOf("KB" to "k", "MB" to "m", "GB" to "g")

/** A field that opens a bottom sheet to edit a "number + suffix letter" value (e.g. "500k",
 * "2M") — the string format both Speed limit and Max file size share, and that both engines'
 * own config parsers accept directly. Blank/zero numeric input means unlimited, matching both
 * preferences' own convention. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizeSheetField(
    modifier: Modifier = Modifier,
    label: String,
    currentValue: String,
    units: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    val displayValue = if (currentValue.isBlank()) "Unlimited" else {
        val num = currentValue.filter { it.isDigit() || it == '.' }
        val suffix = currentValue.filter { it.isLetter() }.lowercase()
        val unitLabel = units.firstOrNull { it.second == suffix }?.first ?: units[0].first
        "$num $unitLabel"
    }

    OutlinedButton(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = { showSheet = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayValue, style = MaterialTheme.typography.titleMedium)
            }
            Icon(FeatherIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showSheet) {
        // Seeded once from the committed value when the sheet opens, then only ever written by
        // the fields below — re-deriving from currentValue on every recomposition would fight
        // whatever the user is mid-typing.
        var numberText by remember { mutableStateOf(currentValue.filter { it.isDigit() || it == '.' }) }
        var unitIndex by remember {
            mutableStateOf(
                units.indexOfFirst { (_, suffix) -> currentValue.trim().endsWith(suffix, ignoreCase = true) }
                    .takeIf { it >= 0 } ?: 0
            )
        }
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = numberText,
                    onValueChange = { numberText = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Unlimited") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(12.dp))
                // A plain chip row instead of a DropdownMenu — a popup nested inside a
                // ModalBottomSheet's own popup dismisses BOTH on tap (reproduced live: tapping
                // the unit dropdown closed the whole sheet instead of opening the menu), so this
                // sidesteps that Compose nested-popup bug entirely rather than working around it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    units.forEachIndexed { index, (unitLabel, _) ->
                        val selected = unitIndex == index
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            onClick = { unitIndex = index },
                        ) {
                            Box(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(unitLabel)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onValueChange(if (numberText.isBlank()) "" else "$numberText${units[unitIndex].second}")
                        showSheet = false
                    },
                ) {
                    Text("Done")
                }
            }
        }
    }
}


