package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.comfort.app.R
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
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import compose.icons.FeatherIcons
import compose.icons.feathericons.Instagram
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
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

/** Which engine each site's links go to. On the page it's one row; tapping it opens a sheet with
 * an add form and the three lists — sites only gallery-dl supports, only yt-dlp, and both — read
 * from the engines themselves (EngineSitesRepository), with the user's changes on top
 * (VideoSiteRouter.siteLists). Each list opens as its own searchable sheet where any site can be
 * moved to another list, or reset. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SitesSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var engineSites by remember { mutableStateOf<com.comfort.app.util.EngineSites?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    // Bumped after every change, so the lists are recomputed.
    var revision by remember { mutableStateOf(0) }
    var browsing by remember { mutableStateOf<com.comfort.app.data.VideoSiteRouter.SiteRoute?>(null) }
    LaunchedEffect(open) {
        if (open && engineSites == null) {
            engineSites = com.comfort.app.util.EngineSitesRepository.load(context)
            loadFailed = engineSites == null
        }
    }
    val lists = remember(engineSites, revision) {
        engineSites?.let { com.comfort.app.data.VideoSiteRouter.siteLists(it.galleryDlOnly, it.ytDlpOnly, it.both) }
    }
    val changes = remember(revision) { com.comfort.app.data.VideoSiteRouter.siteChangeCount() }
    fun route(host: String, to: com.comfort.app.data.VideoSiteRouter.SiteRoute?) {
        if (to == null) com.comfort.app.data.VideoSiteRouter.resetSite(context, host)
        else com.comfort.app.data.VideoSiteRouter.setSiteRoute(context, host, to)
        revision++
    }

    SettingsSection(title = stringResource(R.string.sites), icon = Icons.Outlined.Public, onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.AltRoute, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.which_engine_each_site_uses), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (changes == 0) stringResource(R.string.every_supported_site_by_engine) else pluralStringResource(R.plurals.sites_changed_by_you, changes, changes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    browsing?.let { which ->
        SiteListSheet(
            route = which,
            sites = lists?.get(which).orEmpty(),
            onMove = { hosts, to ->
                if (to == null) com.comfort.app.data.VideoSiteRouter.resetSites(context, hosts)
                else com.comfort.app.data.VideoSiteRouter.setSitesRoute(context, hosts, to)
                revision++
            },
            onDelete = { hosts ->
                com.comfort.app.data.VideoSiteRouter.deleteSites(context, hosts)
                revision++
            },
            onDismiss = { browsing = null },
        )
    }

    if (!open) return
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // Edge to edge: the content runs under the navigation bar instead of stopping above it.
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        var input by remember { mutableStateOf("") }
        var addTo by remember { mutableStateOf(com.comfort.app.data.VideoSiteRouter.SiteRoute.YT_DLP_ONLY) }
        var justAdded by remember { mutableStateOf<String?>(null) }
        val site = com.comfort.app.data.VideoSiteRouter.siteFromInput(input)
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .imePadding()
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            // Header
            SheetHeader(
                icon = Icons.Outlined.Public,
                title = stringResource(R.string.sites),
                subtitle = stringResource(R.string.choose_which_engine_a_site_s),
                modifier = Modifier.padding(top = 8.dp),
            )

            // Add or move a site
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.add_a_site), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; justAdded = null },
                        placeholder = { Text(stringResource(R.string.tiktok_com_or_paste_a_link)) },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        trailingIcon = {
                            if (input.isNotEmpty()) {
                                IconButton(onClick = { input = "" }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear)) }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        isError = input.isNotBlank() && site == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (site != null) {
                                route(site, addTo)
                                justAdded = site
                                input = ""
                                focusManager.clearFocus()
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    )
                    Text(
                        when {
                            justAdded != null -> stringResource(R.string.added_x, justAdded.orEmpty())
                            input.isBlank() -> stringResource(R.string.a_domain_or_any_link_from)
                            site == null -> stringResource(R.string.that_isn_t_a_site)
                            else -> stringResource(R.string.adds_x, site)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            input.isNotBlank() && site == null -> MaterialTheme.colorScheme.error
                            justAdded != null -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = com.comfort.app.data.VideoSiteRouter.SiteRoute.entries
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = addTo == option,
                                onClick = { addTo = option },
                                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, options.size),
                            ) { Text(siteRouteShortLabel(option), maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = site != null,
                        onClick = {
                            site?.let { route(it, addTo); justAdded = it }
                            input = ""
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add_to_x, siteRouteTitle(addTo)))
                    }
                }
            }

            // The three lists
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.supported_sites),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    when {
                        lists != null -> com.comfort.app.data.VideoSiteRouter.SiteRoute.entries.forEach { which ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { browsing = which }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(siteRouteIcon(which), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(siteRouteTitle(which), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(siteRouteMeaning(which), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("%,d".format(lists[which].orEmpty().size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        loadFailed -> Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsErrorBanner(stringResource(R.string.couldn_t_read_the_engines_site))
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                coroutineScope.launch {
                                    loadFailed = false
                                    engineSites = com.comfort.app.util.EngineSitesRepository.load(context)
                                    loadFailed = engineSites == null
                                }
                            }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.retry))
                            }
                        }
                        else -> Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.reading_the_engines), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.a_site_on_no_list_tries),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (changes > 0) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    com.comfort.app.data.VideoSiteRouter.resetSites(context)
                    justAdded = null
                    revision++
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.restore_defaults))
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(androidx.compose.foundation.layout.WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun siteRouteTitle(route: com.comfort.app.data.VideoSiteRouter.SiteRoute) = when (route) {
    com.comfort.app.data.VideoSiteRouter.SiteRoute.GALLERY_DL_ONLY -> stringResource(R.string.only_gallery_dl)
    com.comfort.app.data.VideoSiteRouter.SiteRoute.YT_DLP_ONLY -> stringResource(R.string.only_yt_dlp)
    com.comfort.app.data.VideoSiteRouter.SiteRoute.BOTH -> stringResource(R.string.both)
}

@Composable
private fun siteRouteShortLabel(route: com.comfort.app.data.VideoSiteRouter.SiteRoute) = when (route) {
    com.comfort.app.data.VideoSiteRouter.SiteRoute.GALLERY_DL_ONLY -> "gallery-dl"
    com.comfort.app.data.VideoSiteRouter.SiteRoute.YT_DLP_ONLY -> "yt-dlp"
    com.comfort.app.data.VideoSiteRouter.SiteRoute.BOTH -> stringResource(R.string.both)
}

@Composable
private fun siteRouteMeaning(route: com.comfort.app.data.VideoSiteRouter.SiteRoute) = when (route) {
    com.comfort.app.data.VideoSiteRouter.SiteRoute.GALLERY_DL_ONLY -> stringResource(R.string.gallery_dl_downloads_these_videos_too)
    com.comfort.app.data.VideoSiteRouter.SiteRoute.YT_DLP_ONLY -> stringResource(R.string.yt_dlp_downloads_these)
    com.comfort.app.data.VideoSiteRouter.SiteRoute.BOTH -> stringResource(R.string.gallery_dl_first_yt_dlp_for)
}

private fun siteRouteIcon(route: com.comfort.app.data.VideoSiteRouter.SiteRoute) = when (route) {
    com.comfort.app.data.VideoSiteRouter.SiteRoute.GALLERY_DL_ONLY -> Icons.Outlined.Image
    com.comfort.app.data.VideoSiteRouter.SiteRoute.YT_DLP_ONLY -> Icons.Outlined.Terminal
    com.comfort.app.data.VideoSiteRouter.SiteRoute.BOTH -> Icons.Outlined.Public
}

/** One Sites list, searchable — hundreds to thousands of sites, so a lazy list. Each site's ⋮ menu
 * moves it to another list, resets a site the user changed back to its default, or deletes it.
 * A long press starts selecting: taps then pick more, and the bar above the list does the same
 * to all of them at once (Select all takes what the search shows). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SiteListSheet(
    route: com.comfort.app.data.VideoSiteRouter.SiteRoute,
    sites: List<String>,
    onMove: (Set<String>, com.comfort.app.data.VideoSiteRouter.SiteRoute?) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    // Sites moved or deleted leave the list: they leave the selection too.
    LaunchedEffect(sites) { selected = selected intersect sites.toSet() }
    // With "Use Instaloader for Instagram" on, Instagram's posts and reels go to Instaloader
    // before the list's engines (VideoSiteRouter.resolveEngine), whichever list it's on.
    val context = androidx.compose.ui.platform.LocalContext.current
    val instaloaderOn = remember {
        com.comfort.app.data.GalleryDlPreferences.isInstaloaderForInstagram(context)
    }
    val shown = remember(query, sites) { if (query.isBlank()) sites else sites.filter { it.contains(query.trim(), ignoreCase = true) } }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // Edge to edge: the content runs under the navigation bar instead of stopping above it.
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        val haptics = LocalHapticFeedback.current
        // Full height whatever the search leaves, so the sheet doesn't jump as results shrink.
        Column(modifier = Modifier.fillMaxHeight(0.92f).padding(horizontal = 20.dp).imePadding()) {
            SheetHeader(
                icon = siteRouteIcon(route),
                title = siteRouteTitle(route),
                subtitle = stringResource(
                    R.string.site_count_and_meaning,
                    if (query.isNotBlank()) pluralStringResource(R.plurals.sites_shown_of, sites.size, "%,d".format(shown.size), "%,d".format(sites.size))
                    else pluralStringResource(R.plurals.site_count_grouped, sites.size, "%,d".format(sites.size)),
                    siteRouteMeaning(route),
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_sites)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
            )
            Spacer(Modifier.height(8.dp))
            androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
            androidx.compose.animation.AnimatedVisibility(visible = selecting) {
                val allShown = shown.isNotEmpty() && selected.containsAll(shown)
                var batchMenu by remember { mutableStateOf(false) }
                val anyChanged = selected.any { com.comfort.app.data.VideoSiteRouter.siteOverride(it) != null }
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.deselect_all))
                        }
                        Text(
                            stringResource(R.string.x_selected, "%,d".format(selected.size)),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { selected = if (allShown) selected - shown.toSet() else selected + shown }) {
                            Icon(
                                if (allShown) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                                contentDescription = stringResource(if (allShown) R.string.deselect_all else R.string.select_all),
                            )
                        }
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onDelete(selected)
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                        }
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { batchMenu = true }, colors = menuButtonColors(batchMenu, MaterialTheme.colorScheme.primary)) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
                            androidx.compose.material3.DropdownMenu(expanded = batchMenu, onDismissRequest = { batchMenu = false }) {
                                com.comfort.app.data.VideoSiteRouter.SiteRoute.entries.filter { it != route }.forEach { to ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.move_to_x, siteRouteTitle(to))) },
                                        leadingIcon = { Icon(siteRouteIcon(to), contentDescription = null) },
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            batchMenu = false
                                            onMove(selected, to)
                                        },
                                    )
                                }
                                if (anyChanged) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.reset_to_default)) },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            batchMenu = false
                                            onMove(selected, null)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Long-press a site and drag: every site from it to the finger is selected (or, from
            // one already selected, unselected), the list scrolling on near its top or bottom —
            // the photo-gallery gesture. A long press without a drag picks just that one.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            var dragSelecting by remember { mutableStateOf(false) }
            var dragPointerY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val dragState = remember { IntArray(2) } // anchor, current
            val dragBase = remember { arrayOf(emptySet<String>()) }
            val dragAdds = remember { BooleanArray(1) }
            val latestShown by androidx.compose.runtime.rememberUpdatedState(shown)
            fun siteIndexAt(y: Float): Int? = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { y >= it.offset && y < it.offset + it.size && it.index < latestShown.size }?.index
            fun applyDrag() {
                val (from, to) = dragState[0] to dragState[1]
                val range = latestShown.subList(minOf(from, to), maxOf(from, to) + 1)
                selected = if (dragAdds[0]) dragBase[0] + range else dragBase[0] - range.toSet()
            }
            val listDensity = androidx.compose.ui.platform.LocalDensity.current
            val edgePx = with(listDensity) { 64.dp.toPx() }
            // A frame, deep in a zone: about what going up ran at (a finger above the list).
            val maxSpeedPx = with(listDensity) { 20.dp.toPx() }
            // The list runs on under the navigation bar, where a finger can't go: the bottom zone
            // sits above it, so it's as reachable as the top one.
            val navBarPx = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(listDensity).toFloat()
            LaunchedEffect(dragSelecting) {
                while (dragSelecting) {
                    val bottom = listState.layoutInfo.viewportSize.height - navBarPx
                    // How deep into an edge zone the finger is, 0 to 1 (a finger past the list's
                    // top counts as 1: unbounded, going up ran faster than down — reported).
                    val up = ((edgePx - dragPointerY) / edgePx).coerceIn(0f, 1f)
                    val down = ((dragPointerY - (bottom - edgePx)) / edgePx).coerceIn(0f, 1f)
                    val speed = (down - up) * maxSpeedPx
                    val height = bottom
                    if (speed != 0f) {
                        listState.scrollBy(speed)
                        // The same tick as a drag over a row: without it they stopped once the list
                        // took over scrolling (reported).
                        siteIndexAt(dragPointerY.coerceIn(0f, height - 1f))?.let {
                            if (it != dragState[1]) {
                                dragState[1] = it
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                applyDrag()
                            }
                        }
                    }
                    androidx.compose.runtime.withFrameNanos { }
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                // Off while drag-selecting, so the drag selects instead of scrolling.
                userScrollEnabled = !dragSelecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val i = siteIndexAt(offset.y) ?: return@detectDragGesturesAfterLongPress
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragBase[0] = selected
                                dragAdds[0] = latestShown[i] !in selected
                                dragState[0] = i
                                dragState[1] = i
                                dragPointerY = offset.y
                                dragSelecting = true
                                applyDrag()
                            },
                            onDrag = { change, _ ->
                                if (!dragSelecting) return@detectDragGesturesAfterLongPress
                                change.consume()
                                dragPointerY = change.position.y
                                siteIndexAt(change.position.y)?.let { i ->
                                    if (i != dragState[1]) {
                                        dragState[1] = i
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        applyDrag()
                                    }
                                }
                            },
                            onDragEnd = { dragSelecting = false },
                            onDragCancel = { dragSelecting = false },
                        )
                    },
            ) {
                items(shown.size, key = { shown.getOrNull(it) ?: it }) { i ->
                    val site = shown.getOrNull(i) ?: return@items
                    val changed = com.comfort.app.data.VideoSiteRouter.siteOverride(site) != null
                    val isSelected = site in selected
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                            // The long press is the list's (it can go on into a drag); this one's
                            // for TalkBack, which long-presses through the semantics.
                            .combinedClickable(
                                onClick = { if (selecting) selected = if (isSelected) selected - site else selected + site },
                            )
                            .semantics {
                                onLongClick {
                                    selected = if (isSelected) selected - site else selected + site
                                    true
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (changed || isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                            } else Text(
                                site.firstOrNull()?.uppercase() ?: "",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (changed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(site, style = MaterialTheme.typography.bodyLarge)
                            if (changed) Text(stringResource(R.string.changed_by_you), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (instaloaderOn && site == "instagram.com") Text(stringResource(R.string.posts_and_reels_go_to_instaloader), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // While selecting, the bar above does it for all of them (its room kept,
                        // so the rows don't shrink and the list jump).
                        if (selecting) Spacer(Modifier.size(48.dp))
                        else androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menu = true }, colors = menuButtonColors(menu)) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.move_x, site)) }
                            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                com.comfort.app.data.VideoSiteRouter.SiteRoute.entries.filter { it != route }.forEach { to ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.move_to_x, siteRouteTitle(to))) },
                                        leadingIcon = { Icon(siteRouteIcon(to), contentDescription = null) },
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            menu = false
                                            onMove(setOf(site), to)
                                        },
                                    )
                                }
                                if (changed) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.reset_to_default)) },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            menu = false
                                            onMove(setOf(site), null)
                                        },
                                    )
                                }
                                // Off every list; its links then go as a site on no list does.
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        menu = false
                                        onDelete(setOf(site))
                                    },
                                )
                            }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (sites.isEmpty()) stringResource(R.string.no_sites_in_this_list) else stringResource(R.string.no_site_matches_x, query.trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Spacer(Modifier.windowInsetsBottomHeight(androidx.compose.foundation.layout.WindowInsets.navigationBars))
                }
            }
        }
    }
}

private fun engineLabel(engine: com.comfort.app.data.DownloadEngine) = when (engine) {
    com.comfort.app.data.DownloadEngine.YT_DLP -> "yt-dlp"
    com.comfort.app.data.DownloadEngine.GALLERY_DL -> "gallery-dl"
    com.comfort.app.data.DownloadEngine.INSTALOADER -> "Instaloader"
    com.comfort.app.data.DownloadEngine.SPOTIFY -> "Spotify"
}

@Composable
private fun SettingsErrorBanner(message: String, modifier: Modifier = Modifier) {
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

@Composable
internal fun AboutScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    SettingsSubScaffold(title = stringResource(R.string.about), topicIcon = Icons.Outlined.Info, onBack = onBack, highlightKey = highlightKey) {
        item {
            SettingsSection(
                title = stringResource(R.string.app), 
                icon = Icons.Outlined.Info,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/d-gibr1l/comfort"))
                    runCatching { context.startActivity(intent) }
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = context.getString(R.string.comfort_version_x_open_project_page, versionName)
                    }
                ) {
                    AppIconTile(size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Comfort", style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.version_x, versionName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
        item {
            SettingsSection(title = stringResource(R.string.credits), icon = Icons.Outlined.Link) {
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
                        CreditEntry(iconRes = com.comfort.app.R.drawable.ic_credit_python, title = context.getString(R.string.python_runtime), url = "https://github.com/deniscerri/ytdlnis-packages"),
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        engineIconByName(status.engine.displayName)?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(status.engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                status.installedVersion?.let { "v$it" } ?: stringResource(R.string.version_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            updating -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            status.updateAvailable -> Button(
                onClick = onUpdate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.update_to_x, status.latestVersion.orEmpty()), style = MaterialTheme.typography.labelMedium) }
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
                    Text(status.engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        status.installedVersion?.let { "v$it" } ?: stringResource(R.string.version_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    updating -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    status.updateAvailable -> Button(
                        onClick = onUpdate,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.update_to_x, status.latestVersion.orEmpty()), style = MaterialTheme.typography.labelMedium) }
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
            .semantics {
                role = Role.Button
                contentDescription = context.getString(R.string.open_x_website, entry.title)
            }
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
