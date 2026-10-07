package com.comfort.app.ui.main

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import compose.icons.FeatherIcons
import compose.icons.feathericons.Instagram
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.EngineUpdateChannel
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.AppUpdater
import com.comfort.app.util.EngineUpdater
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Updates and About: app and engine updates, version and credits.

/** The app's own update check plus the download engines' (yt-dlp, gallery-dl, Instaloader) —
 * moved out of About into their own page so updating isn't buried under version info and credits. */
@Composable
internal fun UpdatesSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    SettingsSubScaffold(title = "Updates and engines", topicIcon = Icons.Outlined.Update, onBack = onBack, highlightKey = highlightKey) {
        EngineChoiceSection()
        SitesSection()
        AppUpdateSection()
        EnginesSection()
    }
}

/** Which engines may run, and the order they try a link more than one of them can download
 * (EngineChoice; read by DownloadWorker's plan and the preview listing). Turning off gallery-dl or
 * yt-dlp asks first, since it stops whole kinds of links; at least one engine always stays on. */
@Composable
private fun EngineChoiceSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var choice by remember { mutableStateOf(com.comfort.app.worker.EngineChoice.load(context)) }
    var confirmOff by remember { mutableStateOf<com.comfort.app.data.DownloadEngine?>(null) }

    fun setOn(engine: com.comfort.app.data.DownloadEngine, on: Boolean) {
        GalleryDlPreferences.setEngineEnabled(context, engine, on)
        choice = com.comfort.app.worker.EngineChoice.load(context)
    }

    fun move(index: Int, by: Int) {
        val order = choice.order.toMutableList()
        val target = index + by
        if (target !in order.indices) return
        order[index] = order[target].also { order[target] = order[index] }
        GalleryDlPreferences.setEngineOrder(context, order.map { it.name })
        choice = com.comfort.app.worker.EngineChoice.load(context)
    }

    SettingsSection(title = "Which engines run", icon = Icons.Outlined.Refresh) {
        Text(
            "Turn engines on or off, and choose which one tries first when more than one can download a link. YouTube and other video sites always use yt-dlp, and Spotify needs it too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        choice.order.forEachIndexed { index, engine ->
            val on = choice.isOn(engine)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(engineLabel(engine), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (on) engineRole(engine) else "Off — never runs or updates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move ${engineLabel(engine)} up")
                }
                IconButton(onClick = { move(index, +1) }, enabled = index < choice.order.lastIndex) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move ${engineLabel(engine)} down")
                }
                Switch(
                    checked = on,
                    onCheckedChange = { turnOn ->
                        when {
                            turnOn -> setOn(engine, true)
                            choice.enabled.size <= 1 ->
                                android.widget.Toast.makeText(context, "Keep at least one engine on.", android.widget.Toast.LENGTH_SHORT).show()
                            engine == com.comfort.app.data.DownloadEngine.INSTALOADER -> setOn(engine, false)
                            else -> confirmOff = engine
                        }
                    },
                )
            }
        }
    }

    confirmOff?.let { engine ->
        ConfirmDeleteSheet(
            title = "Turn off ${engineLabel(engine)}?",
            message = if (engine == com.comfort.app.data.DownloadEngine.YT_DLP) {
                "YouTube, Spotify and every video download will stop working until you turn it back on. It also won't be kept updated."
            } else {
                "Image galleries (Pixiv, Twitter/X pictures, Reddit galleries, …) won't download until you turn it back on; links yt-dlp handles still will. It also won't be kept updated."
            },
            confirmLabel = "Turn off",
            onConfirm = {
                setOn(engine, false)
                confirmOff = null
            },
            onDismiss = { confirmOff = null },
        )
    }
}

/** Which engine each site's links go to (VideoSiteRouter's Sites lists). On the page it's one
 * row with the counts; tapping it opens a sheet with both lists — the built-in video sites on
 * yt-dlp plus anything added, and sites moved to gallery-dl — and an add form at the top, which
 * takes a domain or any link from the site. A site's ✕ takes it off its list. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SitesSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ytDlpSites by remember { mutableStateOf(com.comfort.app.data.VideoSiteRouter.ytDlpSites()) }
    var galleryDlSites by remember { mutableStateOf(com.comfort.app.data.VideoSiteRouter.galleryDlSites()) }
    var open by remember { mutableStateOf(false) }
    fun refresh() {
        ytDlpSites = com.comfort.app.data.VideoSiteRouter.ytDlpSites()
        galleryDlSites = com.comfort.app.data.VideoSiteRouter.galleryDlSites()
    }

    SettingsSection(title = "Sites", icon = Icons.Outlined.Public, onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Which engine each site uses", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${ytDlpSites.size} on yt-dlp · ${galleryDlSites.size} on gallery-dl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (!open) return
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        var input by remember { mutableStateOf("") }
        var toYtDlp by remember { mutableStateOf(true) }
        val site = com.comfort.app.data.VideoSiteRouter.siteFromInput(input)
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            Text("Sites", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Which engine a site's links go to. Sites not listed try gallery-dl first, with yt-dlp for their videos. Instagram posts use Instaloader and Spotify its own engine either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Add a site
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Add a site, or paste a link from it") },
                placeholder = { Text("tiktok.com") },
                singleLine = true,
                supportingText = { Text(if (input.isBlank()) " " else site ?: "Not a site") },
                isError = input.isNotBlank() && site == null,
                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = toYtDlp, onClick = { toYtDlp = true }, label = { Text("yt-dlp") })
                androidx.compose.material3.FilterChip(selected = !toYtDlp, onClick = { toYtDlp = false }, label = { Text("gallery-dl") })
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = site != null,
                    onClick = {
                        site?.let {
                            com.comfort.app.data.VideoSiteRouter.addSite(
                                context, it,
                                if (toYtDlp) com.comfort.app.data.DownloadEngine.YT_DLP else com.comfort.app.data.DownloadEngine.GALLERY_DL,
                            )
                        }
                        refresh()
                        input = ""
                    },
                ) { Text("Add") }
            }

            // The two lists
            listOf(
                Triple("yt-dlp", ytDlpSites, "Only yt-dlp"),
                Triple("gallery-dl", galleryDlSites, "gallery-dl first"),
            ).forEach { (name, sites, meaning) ->
                Spacer(Modifier.height(18.dp))
                Text("$name · $meaning", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (sites.isEmpty()) {
                    Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        sites.forEach { s ->
                            androidx.compose.material3.InputChip(
                                selected = false,
                                onClick = {
                                    com.comfort.app.data.VideoSiteRouter.removeSite(context, s)
                                    refresh()
                                },
                                label = { Text(s) },
                                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove $s", modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                com.comfort.app.data.VideoSiteRouter.resetSites(context)
                refresh()
            }) { Text("Restore defaults") }
        }
    }
}

private fun engineLabel(engine: com.comfort.app.data.DownloadEngine) = when (engine) {
    com.comfort.app.data.DownloadEngine.YT_DLP -> "yt-dlp"
    com.comfort.app.data.DownloadEngine.GALLERY_DL -> "gallery-dl"
    com.comfort.app.data.DownloadEngine.INSTALOADER -> "Instaloader"
    com.comfort.app.data.DownloadEngine.SPOTIFY -> "Spotify"
}

private fun engineRole(engine: com.comfort.app.data.DownloadEngine) = when (engine) {
    com.comfort.app.data.DownloadEngine.YT_DLP -> "Video and audio, on YouTube and every other site"
    com.comfort.app.data.DownloadEngine.GALLERY_DL -> "Images and galleries from hundreds of sites"
    com.comfort.app.data.DownloadEngine.INSTALOADER -> "Instagram posts, reels and carousels"
    com.comfort.app.data.DownloadEngine.SPOTIFY -> "Spotify tracks, via yt-dlp"
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    SettingsSubScaffold(title = "About", topicIcon = Icons.Outlined.Info, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(
            title = "App", 
            icon = Icons.Outlined.Info,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/d-gibr1l/comfort"))
                context.startActivity(intent)
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // painterResource() can't load mipmap-anydpi-v26/ic_launcher.xml directly (an
                // AdaptiveIconDrawable, not a plain vector/raster) — composed by hand here from
                // the same two layers instead: ic_launcher_background.xml (a plain white rect,
                // approximated directly rather than parsed) behind ic_launcher_foreground.xml,
                // the actual wordmark. This used to be a separate static ic_app_logo.png export
                // that silently drifted out of sync with the real launcher icon once it changed
                // (reported live: "still using the old app icon") — rendering the *same* vector
                // the launcher itself uses guarantees they can't drift again.
                // Colors come from the in-app theme, not @color/splash_icon (which the foreground
                // vector's own fill references): resources resolve against the *system* night
                // mode, so with the app's own Light/Dark setting overriding it the wordmark could
                // end up black-on-dark or the tile a stray white block. Light keeps the launcher's
                // own white tile and black wordmark; dark inverts to a raised dark tile.
                val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val tileColor = if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
                val logoColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color.Black
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(tileColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        imageVector = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(logoColor),
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

        // gallery-dl was the only credit here before — a real gap for an app that's really built on
        // six separately-licensed open-source projects, not one: yt-dlp does just as much of the
        // actual downloading (see VideoSiteRouter), and PythonRuntime's own doc comment describes
        // running YTDLnis's published interpreter/curl_cffi build as a subprocess, FFmpeg
        // (FfmpegRuntime) merging video/audio, QuickJS (QuickJsRuntime) solving yt-dlp's JS
        // challenges, and aria2 (Aria2Runtime) doing real multi-connection downloads — none of them
        // previously credited or linked anywhere in the app.
        SettingsSection(title = "Credits", icon = Icons.Outlined.Link) {
            // gallery-dl, QuickJS, and aria2 have no usable real-logo asset (gallery-dl and QuickJS
            // ship no logo at all; aria2's only asset is a 16x16 favicon too small to read as
            // anything but a blob at chip size) — those three keep a generic Material icon.
            // FFmpeg's favicon and the Python community logo (credited project is literally
            // CPython, via YTDLnis's published interpreter build — see PythonRuntime's own doc
            // comment) are real, recognizable marks, so those two use their actual artwork instead.
            val credits = remember {
                listOf(
                    CreditEntry(icon = Icons.Outlined.Code, title = "gallery-dl", url = "https://github.com/mikf/gallery-dl"),
                    CreditEntry(icon = Icons.Outlined.Terminal, title = "yt-dlp", url = "https://github.com/yt-dlp/yt-dlp"),
                    CreditEntry(icon = FeatherIcons.Instagram, title = "Instaloader", url = "https://github.com/instaloader/instaloader"),
                    CreditEntry(iconRes = com.comfort.app.R.drawable.ic_credit_ffmpeg, title = "FFmpeg", url = "https://ffmpeg.org"),
                    CreditEntry(icon = Icons.Outlined.Memory, title = "QuickJS", url = "https://bellard.org/quickjs/"),
                    CreditEntry(iconRes = com.comfort.app.R.drawable.ic_credit_python, title = "Python runtime", url = "https://github.com/deniscerri/ytdlnis-packages"),
                    CreditEntry(icon = Icons.Outlined.Bolt, title = "aria2", url = "https://aria2.github.io/"),
                )
            }
            credits.chunked(2).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { entry -> CreditChip(entry, modifier = Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** App-itself equivalent of QuickEngineUpdateSection() just below — same "only ever appears as
 * news, not a permanent fixture" reasoning (renders nothing once there's nothing to report), just
 * checking AppUpdater's GitHub Releases instead of PyPI. Listed first (a whole app release is
 * arguably bigger news than an extractor-fix engine bump) but otherwise fully independent of it —
 * both can be visible at once if both happen to have something new. */
@Composable
internal fun QuickAppUpdateSection() {
    val updates = updatesViewModel()
    val status by updates.appStatus.collectAsState()
    val downloadProgress by updates.appDownloadProgress.collectAsState()
    val downloadError by updates.appDownloadError.collectAsState()

    LaunchedEffect(Unit) { updates.ensureAppChecked() }

    val current = status
    if (current == null || !current.updateAvailable) return

    SettingsSection(title = "App update available", icon = Icons.Outlined.Download) {
        AppUpdateRow(
            status = current,
            downloadProgress = downloadProgress,
            onUpdate = { updates.startAppDownload(current) },
        )
        if (downloadError != null) {
            Spacer(Modifier.height(10.dp))
            Text(downloadError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Persistent panel for Updates > this app's own update check — mirrors EnginesSection() below (a
 * persistent "Checking…"/"Up to date" utility panel, unlike QuickAppUpdateSection above which only
 * ever shows up as news) but for one thing, not a list. Always does its own fresh check regardless
 * of the cached flag/interval MainScreen's periodic one respects, same as EnginesSection. */
@Composable
private fun AppUpdateSection() {
    val updates = updatesViewModel()
    val status by updates.appStatus.collectAsState()
    val checking by updates.checkingApp.collectAsState()
    val downloadProgress by updates.appDownloadProgress.collectAsState()
    val downloadError by updates.appDownloadError.collectAsState()

    LaunchedEffect(Unit) { updates.checkApp() }

    SettingsSection(title = "App Update", icon = Icons.Outlined.Download) {
        val current = status
        if (current == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking for updates…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            AppUpdateRow(
                status = current,
                downloadProgress = downloadProgress,
                onUpdate = { updates.startAppDownload(current) },
            )
        }
        if (downloadError != null) {
            Spacer(Modifier.height(10.dp))
            Text(downloadError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { updates.checkApp() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check for updates")
            }
        }
    }
}

@Composable
private fun AppUpdateRow(status: AppUpdater.UpdateStatus, downloadProgress: Float?, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Comfort", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "v${status.installedVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            downloadProgress != null -> CircularProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            status.updateAvailable -> Button(
                onClick = onUpdate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Update to v${status.latestVersion}", style = MaterialTheme.typography.labelMedium) }
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

/** Compact teaser on the Settings root list, so an available engine update is both visible and
 * actionable without drilling into Updates > Engines first — mirrors that section's own check/update
 * logic (see EnginesSection() below) but renders nothing at all when everything's already current,
 * rather than always showing a "Checking…"/"Up to date" panel the way the About page's version
 * does (appropriate there as a persistent utility panel; here it should only ever appear as news,
 * not a permanent fixture). */
@Composable
internal fun QuickEngineUpdateSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updates = updatesViewModel()
    val statuses by updates.engineStatuses.collectAsState()
    val updatingEngines by updates.updatingEngines.collectAsState()
    val errorText by updates.engineError.collectAsState()

    // Installs what it finds when auto-update is on, so opening Settings needs no manual tap.
    LaunchedEffect(Unit) { updates.ensureEnginesChecked() }

    val outdated = statuses?.filter { it.updateAvailable && EngineUpdater.isOn(context, it.engine) } ?: return
    if (outdated.isEmpty()) return

    SettingsSection(title = "Updates available", icon = Icons.Outlined.Refresh) {
        outdated.forEachIndexed { index, status ->
            EngineUpdateRow(
                status = status,
                updating = status.engine.packageDirName in updatingEngines,
                onUpdate = { updates.updateEngine(status) },
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
    val updates = updatesViewModel()
    val statuses by updates.engineStatuses.collectAsState()
    val checking by updates.checkingEngines.collectAsState()
    val updatingEngines by updates.updatingEngines.collectAsState()
    val errorText by updates.engineError.collectAsState()
    var autoUpdate by remember { mutableStateOf(GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) }
    var ytDlpChannel by remember { mutableStateOf(GalleryDlPreferences.getYtDlpUpdateChannel(context)) }
    var galleryDlChannel by remember { mutableStateOf(GalleryDlPreferences.getGalleryDlUpdateChannel(context)) }
    var instaloaderChannel by remember { mutableStateOf(GalleryDlPreferences.getInstaloaderUpdateChannel(context)) }

    // A fresh check when the page opens, and again whenever a channel flips — the check reads the
    // channel preference itself, so Stable -> Nightly/Master needs one against that new source.
    LaunchedEffect(ytDlpChannel, galleryDlChannel, instaloaderChannel) { updates.checkEngines() }

    SettingsSection(title = "Engines", icon = Icons.Outlined.Refresh) {
        IconToggleRow(
            icon = Icons.Outlined.SystemUpdateAlt,
            title = "Auto-update",
            subtitle = "Automatically installs newer yt-dlp, gallery-dl and Instaloader updates when found.",
            checked = autoUpdate,
            onCheckedChange = {
                autoUpdate = it
                GalleryDlPreferences.setAutoUpdateEnginesEnabled(context, it)
            },
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                EngineCard(
                    status = status,
                    channel = when (status.engine) {
                        EngineUpdater.YT_DLP -> ytDlpChannel
                        EngineUpdater.INSTALOADER -> instaloaderChannel
                        else -> galleryDlChannel
                    },
                    onChannelChange = { newChannel ->
                        when (status.engine) {
                            EngineUpdater.YT_DLP -> {
                                ytDlpChannel = newChannel
                                GalleryDlPreferences.setYtDlpUpdateChannel(context, newChannel)
                            }
                            EngineUpdater.INSTALOADER -> {
                                instaloaderChannel = newChannel
                                GalleryDlPreferences.setInstaloaderUpdateChannel(context, newChannel)
                            }
                            else -> {
                                galleryDlChannel = newChannel
                                GalleryDlPreferences.setGalleryDlUpdateChannel(context, newChannel)
                            }
                        }
                    },
                    updating = status.engine.packageDirName in updatingEngines,
                    onUpdate = { updates.updateEngine(status) },
                )
                if (index != currentStatuses.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        if (errorText != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { updates.checkEngines() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check for updates")
            }
        }
    }
}

/** Plain single-row status, no card/channel-picker — used only by QuickEngineUpdateSection's
 * compact "Updates available" notice elsewhere in Settings, which lists just the outdated engines
 * with nothing else to configure. EngineCard below is the richer version for the About page's own
 * Engines section, which needs the channel picker too. */
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

/** One engine's own card inside the Engines section — previously a single flat row shared by both
 * engines with the channel picker chips stacked loosely above it, easy to misread as one control
 * block rather than two separate engines. Giving each its own icon+name+version header (plus the
 * channel picker underneath, now clearly scoped to *this* card) makes the yt-dlp/gallery-dl split
 * visually obvious at a glance instead of requiring reading the row text to tell them apart. */
@Composable
private fun EngineCard(
    status: EngineUpdater.VersionStatus,
    channel: EngineUpdateChannel,
    onChannelChange: (EngineUpdateChannel) -> Unit,
    updating: Boolean,
    onUpdate: () -> Unit,
) {
    val icon = when (status.engine) {
        EngineUpdater.YT_DLP -> Icons.Outlined.Terminal
        EngineUpdater.INSTALOADER -> FeatherIcons.Instagram
        else -> Icons.Outlined.Image
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
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
                    status.latestVersion != null -> Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ) {
                        Text(
                            "Up to date",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    else -> Text(
                        "Couldn't check",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            EngineChannelPicker(engine = status.engine, channel = channel, onChannelChange = onChannelChange)
        }
    }
}

/** Same 2-option Surface-chip-row picker as ProcessingSettingsScreen's own Output format picker
 * (see that screen's own comment on the pattern) — one per engine, its label for the non-Stable
 * option differing per engine (see EngineUpdater's own doc comment on why gallery-dl's is "Master"
 * rather than "Nightly": it has no nightly build, only its live Master branch source). */
@Composable
private fun EngineChannelPicker(
    engine: EngineUpdater.EngineInfo,
    channel: EngineUpdateChannel,
    onChannelChange: (EngineUpdateChannel) -> Unit,
) {
    val bleedingEdgeLabel = if (engine == EngineUpdater.YT_DLP) "Nightly" else "Master"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            EngineUpdateChannel.STABLE to "Stable",
            EngineUpdateChannel.BLEEDING_EDGE to bleedingEdgeLabel,
        ).forEach { (value, label) ->
            val selected = channel == value
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { onChannelChange(value) },
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Exactly one of icon/iconRes is set per entry — a plain Material icon for projects with no real
// logo asset, or a bundled drawable (see the Credits section's own comment) for the ones that have one.
private data class CreditEntry(
    val title: String,
    val url: String,
    val icon: ImageVector? = null,
    @androidx.annotation.DrawableRes val iconRes: Int? = null,
)

// 2-column chip grid for the About screen's Credits section — replaced a plain vertical list of
// icon+title+raw-URL rows (one credit per line, URL spelled out underneath) with a denser card
// grid: the URL itself was never useful at a glance (nobody reads out a github.com URL), just an
// affordance to tap through, so it's dropped from the visible chip and only used as the tap target.
@Composable
private fun CreditChip(entry: CreditEntry, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = modifier
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.iconRes != null) {
                Image(
                    androidx.compose.ui.res.painterResource(entry.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    entry.icon!!,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The activity's [com.comfort.app.viewmodel.UpdatesViewModel] — one per activity, not per screen,
 * so Home, the Settings root and the Updates page all read and update the same state. */
@Composable
internal fun updatesViewModel(): com.comfort.app.viewmodel.UpdatesViewModel {
    var context = androidx.compose.ui.platform.LocalContext.current
    while (context !is androidx.activity.ComponentActivity && context is android.content.ContextWrapper) {
        context = context.baseContext
    }
    val owner = context as? androidx.lifecycle.ViewModelStoreOwner
        ?: error("updatesViewModel() needs to run inside an activity")
    return androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = owner)
}
