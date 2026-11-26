package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import com.comfort.app.util.AppUpdater
import com.comfort.app.util.EngineUpdater
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Updates and About: app and engine updates, version and credits.

/** The app's own update check plus the download engines' (yt-dlp, gallery-dl, Instaloader) —
 * moved out of About into their own page so updating isn't buried under version info and credits. */
@Composable
internal fun UpdatesSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    SettingsSubScaffold(title = stringResource(R.string.updates_and_engines), topicIcon = Icons.Outlined.Update, onBack = onBack, highlightKey = highlightKey) {
        item {
            EngineChoiceSection()
        }
        item {
            SitesSection()
        }
        item {
            AppUpdateSection()
        }
        item {
            EnginesSection()
        }
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
    // Same toggle feel as every other Settings switch (IconToggleRow); a tick on each reorder.
    val haptics = LocalHapticFeedback.current

    fun setOn(engine: com.comfort.app.data.DownloadEngine, on: Boolean) {
        GalleryDlPreferences.setEngineEnabled(context, engine, on)
        choice = com.comfort.app.worker.EngineChoice.load(context)
    }

    fun move(index: Int, by: Int) {
        val order = choice.order.filter { it in com.comfort.app.worker.EngineChoice.REORDERABLE }.toMutableList()
        val target = index + by
        if (index !in order.indices || target !in order.indices) return
        order[index] = order[target].also { order[target] = order[index] }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        GalleryDlPreferences.setEngineOrder(context, order.map { it.name })
        choice = com.comfort.app.worker.EngineChoice.load(context)
    }

    SettingsSection(title = stringResource(R.string.which_engines_run), icon = Icons.Outlined.Tune) {
        Text(
            stringResource(R.string.turn_engines_on_or_off_and),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val reorderable = choice.order.filter { it in com.comfort.app.worker.EngineChoice.REORDERABLE }
        reorderable.forEachIndexed { index, engine ->
            val on = choice.isOn(engine)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Icon(engineIcon(engine), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(engineLabel(engine), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (on) engineRole(engine) else stringResource(R.string.off_never_runs_or_updates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.move_x_up, engineLabel(engine)))
                }
                IconButton(onClick = { move(index, +1) }, enabled = index < reorderable.lastIndex) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.move_x_down, engineLabel(engine)))
                }
                Switch(
                    checked = on,
                    modifier = Modifier.semantics { contentDescription = context.getString(R.string.x_engine, engineLabel(engine)) },
                    onCheckedChange = { turnOn ->
                        haptics.performHapticFeedback(if (turnOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        when {
                            turnOn -> setOn(engine, true)
                            choice.enabled.size <= 1 -> {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                android.widget.Toast.makeText(context, context.getString(R.string.keep_at_least_one_engine_on), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            else -> confirmOff = engine
                        }
                    },
                )
            }
        }

        // Instaloader isn't in the order: it only takes Instagram links, and always goes first there.
        val instaloader = com.comfort.app.data.DownloadEngine.INSTALOADER
        val instaloaderOn = choice.isOn(instaloader)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(24.dp))
            Icon(engineIcon(instaloader), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(engineLabel(instaloader), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (instaloaderOn) stringResource(R.string.instagram_posts_reels_and_carousels_always) else stringResource(R.string.off_instagram_goes_to_gallery_dl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = instaloaderOn,
                modifier = Modifier.semantics { contentDescription = context.getString(R.string.instaloader_engine) },
                onCheckedChange = { turnOn ->
                    haptics.performHapticFeedback(if (turnOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    if (!turnOn && choice.enabled.size <= 1) {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        android.widget.Toast.makeText(context, context.getString(R.string.keep_at_least_one_engine_on), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        setOn(instaloader, turnOn)
                    }
                },
            )
        }
    }

    confirmOff?.let { engine ->
        ConfirmDeleteSheet(
            title = stringResource(R.string.turn_off_x, engineLabel(engine)),
            message = if (engine == com.comfort.app.data.DownloadEngine.YT_DLP) {
                stringResource(R.string.youtube_spotify_and_every_video_download)
            } else {
                stringResource(R.string.image_galleries_pixiv_twitter_x_pictures)
            },
            confirmLabel = stringResource(R.string.turn_off),
            onConfirm = {
                setOn(engine, false)
                confirmOff = null
            },
            onDismiss = { confirmOff = null },
        )
    }
}

private fun engineLabel(engine: com.comfort.app.data.DownloadEngine) = when (engine) {
    com.comfort.app.data.DownloadEngine.YT_DLP -> "yt-dlp"
    com.comfort.app.data.DownloadEngine.GALLERY_DL -> "gallery-dl"
    com.comfort.app.data.DownloadEngine.INSTALOADER -> "Instaloader"
    com.comfort.app.data.DownloadEngine.SPOTIFY -> "Spotify"
}

@Composable
internal fun SettingsErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** The app's icon as the launcher shows it, drawn from the launcher's own vector — used wherever
 * the app shows its own icon (About, App Update, the update sheet), so none can show an outdated export. */
@Composable
internal fun AppIconTile(size: androidx.compose.ui.unit.Dp) {
    // painterResource() can't load mipmap-anydpi-v26/ic_launcher.xml directly (an
    // AdaptiveIconDrawable, not a plain vector/raster) — composed by hand here from the same two
    // layers instead: ic_launcher_background.xml (a plain white rect, approximated directly rather
    // than parsed) behind ic_launcher_foreground.xml, the actual wordmark, so it's always the
    // launcher's own artwork.
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
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(tileColor),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_launcher_foreground),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(logoColor),
            // The vector is the whole adaptive-icon canvas, with the artwork in its safe zone (about
            // a third of the width): drawn larger than the tile, clipped by it, so the wordmark
            // fills the tile the way it does on the home screen.
            modifier = Modifier.requiredSize(size * 1.8f),
        )
    }
}

/** Each engine's icon, the same as on its Engines card. */
private fun engineIcon(engine: com.comfort.app.data.DownloadEngine): ImageVector = when (engine) {
    com.comfort.app.data.DownloadEngine.YT_DLP -> Icons.Outlined.Terminal
    com.comfort.app.data.DownloadEngine.GALLERY_DL -> Icons.Outlined.Image
    com.comfort.app.data.DownloadEngine.INSTALOADER -> FeatherIcons.Instagram
    com.comfort.app.data.DownloadEngine.SPOTIFY -> Icons.Outlined.MusicNote
}

/** [engineIcon] for an updater entry, which names its engine rather than using DownloadEngine. */
private fun engineIconByName(name: String): ImageVector? = when {
    name.contains("yt-dlp", ignoreCase = true) -> Icons.Outlined.Terminal
    name.contains("gallery-dl", ignoreCase = true) -> Icons.Outlined.Image
    name.contains("instaloader", ignoreCase = true) -> FeatherIcons.Instagram
    else -> null
}

@Composable
private fun engineRole(engine: com.comfort.app.data.DownloadEngine) = when (engine) {
    com.comfort.app.data.DownloadEngine.YT_DLP -> stringResource(R.string.video_and_audio_on_youtube_and)
    com.comfort.app.data.DownloadEngine.GALLERY_DL -> stringResource(R.string.images_and_galleries_from_hundreds_of)
    com.comfort.app.data.DownloadEngine.INSTALOADER -> stringResource(R.string.instagram_posts_reels_and_carousels)
    com.comfort.app.data.DownloadEngine.SPOTIFY -> stringResource(R.string.spotify_tracks_via_yt_dlp)
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

    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2_000); updates.ensureAppChecked() }

    val current = status
    if (current == null || !current.updateAvailable) return

    // A tap on the card shows the update's sheet (what's new, Update now); its button still
    // updates straight away.
    SettingsSection(title = stringResource(R.string.app_update_available), icon = Icons.Outlined.Download, onClick = { updates.showAppPrompt(current) }) {
        AppUpdateRow(
            status = current,
            downloadProgress = downloadProgress,
            onUpdate = { updates.startAppDownload(current) },
        )
        if (downloadError != null) {
            Spacer(Modifier.height(10.dp))
            SettingsErrorBanner(downloadError.orEmpty())
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

    SettingsSection(title = stringResource(R.string.app_update), icon = Icons.Outlined.SystemUpdate) {
        val current = status
        if (current == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.checking_for_updates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SettingsErrorBanner(downloadError.orEmpty())
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { updates.checkApp() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.check_for_updates))
            }
        }
    }
}

@Composable
private fun AppUpdateRow(status: AppUpdater.UpdateStatus, downloadProgress: Float?, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIconTile(size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Comfort",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                shownVersion(status.installedVersion.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            downloadProgress != null -> CircularProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                // Not surfaceVariant: in the light themes that's the card's own colour, and the track vanished.
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            status.updateAvailable -> Button(
                onClick = onUpdate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.update_to_v_x, status.latestVersion.orEmpty()), style = MaterialTheme.typography.labelMedium) }
            status.latestVersion != null -> Text(
                stringResource(R.string.up_to_date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Text(
                stringResource(R.string.couldn_t_check),
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
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2_000); updates.ensureEnginesChecked() }

    val outdated = statuses?.filter { it.updateAvailable && EngineUpdater.isOn(context, it.engine) } ?: return
    if (outdated.isEmpty()) return

    SettingsSection(title = stringResource(R.string.updates_available), icon = Icons.Outlined.Update) {
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
            SettingsErrorBanner(errorText.orEmpty())
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

    SettingsSection(title = stringResource(R.string.engines), icon = Icons.Outlined.Extension) {
        IconToggleRow(
            icon = Icons.Outlined.SystemUpdateAlt,
            title = stringResource(R.string.auto_update),
            subtitle = stringResource(R.string.automatically_installs_newer_yt_dlp_gallery),
            checked = autoUpdate,
            onCheckedChange = {
                autoUpdate = it
                GalleryDlPreferences.setAutoUpdateEnginesEnabled(context, it)
                if (it) com.comfort.app.worker.EngineUpdateWorker.schedule(context) else com.comfort.app.worker.EngineUpdateWorker.cancel(context)
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
                Text(stringResource(R.string.checking_for_updates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SettingsErrorBanner(errorText.orEmpty())
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { updates.checkEngines() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.check_for_updates))
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
    EngineUpdateRowContent(status, updating, onUpdate)
}

/** A version as shown: "v2026.10.26" for a release; a branch build ("master-3f9c2e1d…") as the
 * branch and a short commit, "master · 3f9c2e1", no "v" (it read "vmaster-…", and the long hash
 * squeezed the card). */
internal fun shownVersion(version: String, withV: Boolean = true): String {
    if (version.firstOrNull()?.isDigit() == true) return if (withV) "v$version" else version
    val branch = version.substringBefore('-', version)
    val rest = version.substringAfter('-', "")
    val hash = Regex("[0-9a-fA-F]{7,}").find(rest)?.value
    return when {
        hash != null -> "$branch · ${hash.take(7)}"
        rest.isNotEmpty() -> "$branch · ${rest.take(12)}"
        else -> branch
    }
}

@Composable
private fun EngineUpdateRowContent(status: EngineUpdater.VersionStatus, updating: Boolean, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        engineIconByName(status.engine.displayName)?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                status.engine.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                status.installedVersion?.let { shownVersion(it) } ?: stringResource(R.string.version_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            updating -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            status.updateAvailable -> Button(
                onClick = onUpdate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.widthIn(max = 190.dp),
            ) {
                Text(
                    stringResource(R.string.update_to_x, shownVersion(status.latestVersion.orEmpty(), withV = false)),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            status.latestVersion != null -> Text(
                stringResource(R.string.up_to_date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Text(
                stringResource(R.string.couldn_t_check),
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
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        status.engine.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        status.installedVersion?.let { shownVersion(it) } ?: stringResource(R.string.version_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    updating -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    status.updateAvailable -> Button(
                        onClick = onUpdate,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.update_to_x, shownVersion(status.latestVersion.orEmpty(), withV = false)), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    status.latestVersion != null -> Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ) {
                        Text(
                            stringResource(R.string.up_to_date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    else -> Text(
                        stringResource(R.string.couldn_t_check),
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
    val haptics = LocalHapticFeedback.current
    val bleedingEdgeLabel = if (engine == EngineUpdater.YT_DLP) stringResource(R.string.nightly) else stringResource(R.string.master)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOfNotNull(
            EngineUpdateChannel.STABLE to stringResource(R.string.stable),
            EngineUpdateChannel.BLEEDING_EDGE to bleedingEdgeLabel,
            // yt-dlp's build of every commit to master, newer than Nightly by up to a day. The
            // other engines' second option already is their master branch.
            (EngineUpdateChannel.MASTER to stringResource(R.string.master)).takeIf { engine == EngineUpdater.YT_DLP },
        ).forEach { (value, label) ->
            val selected = channel == value
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = {
                            if (!selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            onChannelChange(value)
                        },
                    ),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
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
