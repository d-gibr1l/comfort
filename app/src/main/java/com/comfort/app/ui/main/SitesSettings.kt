package com.comfort.app.ui.main

// Settings › Updates & engines › Sites: which engine each site uses.

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.comfort.app.R
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import compose.icons.feathericons.Instagram
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

/** Which engine each site's links go to. On the page it's one row; tapping it opens a sheet with
 * an add form and the three lists — sites only gallery-dl supports, only yt-dlp, and both — read
 * from the engines themselves (EngineSitesRepository), with the user's changes on top
 * (VideoSiteRouter.siteLists). Each list opens as its own searchable sheet where any site can be
 * moved to another list, or reset. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SitesSection() {
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
            onBlock = { hosts, block ->
                com.comfort.app.data.VideoSiteRouter.setBlocked(context, hosts, block)
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
    onBlock: (Set<String>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Which of them are blocked: re-read whenever a block changes.
    var blockRevision by remember { mutableStateOf(0) }
    val blockedSites = remember(sites, blockRevision) { sites.filter { com.comfort.app.data.VideoSiteRouter.isBlocked(it) }.toSet() }
    val block: (Set<String>, Boolean) -> Unit = { hosts, on -> onBlock(hosts, on); blockRevision++ }
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
                                // All of them blocked: Unblock; otherwise Block (the rest too).
                                val allBlocked = selected.all { it in blockedSites }
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(if (allBlocked) R.string.unblock else R.string.block)) },
                                    leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        batchMenu = false
                                        block(selected, !allBlocked)
                                    },
                                )
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
                    val isBlocked = site in blockedSites
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
                            if (isBlocked) Text(stringResource(R.string.blocked_nothing_downloaded), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
                                // Its links refused before any preview or download.
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(if (isBlocked) R.string.unblock else R.string.block)) },
                                    leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        menu = false
                                        block(setOf(site), !isBlocked)
                                    },
                                )
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
