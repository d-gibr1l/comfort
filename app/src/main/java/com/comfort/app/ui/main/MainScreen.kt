package com.comfort.app.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.comfort.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.theme.PillShape
import com.comfort.app.util.AppUpdater
import com.comfort.app.util.EngineUpdater
import com.comfort.app.util.shouldUsePreviewSheet
import compose.icons.FeatherIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.launch
import compose.icons.feathericons.*
import com.comfort.app.viewmodel.DownloadsViewModel

// The Home wordmark's display face — a purchased/downloaded font, not one of Google Fonts'
// downloadable-at-runtime families, so it ships as a bundled resource like any other static asset.
private val CrystalRadioKit = FontFamily(Font(R.font.crystal_radio_kit))

private data class NavTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    NavTab("Home", Icons.Outlined.Home),
    NavTab("Library", Icons.Outlined.Image),
    NavTab("Settings", Icons.Outlined.Settings),
)

/** Live, in-session mirror of GalleryDlPreferences.isEngineUpdateAvailable() — the persisted flag
 * stays the source of truth across process restarts, but reading it back only on a tab switch
 * (the previous approach) meant updating an engine from Settings' own quick-update section or the
 * About page, *without* ever leaving the Settings tab, left the nav-bar dot showing stale
 * (reproduced live: updated an engine, dot stayed lit until switching tabs and back). Every writer
 * — MainScreen's own rate-limited auto-check, and every place in MoreScreen.kt that finishes an
 * update — sets this directly, so the badge (which just reads it, no LaunchedEffect polling needed)
 * updates the instant any of them do, same-session, regardless of which screen did it. */
object EngineUpdateSignal {
    var hasUpdate by mutableStateOf(false)
}

/** Same shape as [EngineUpdateSignal], for AppUpdater's own GitHub Releases check on the app
 * itself instead of PyPI on yt-dlp/gallery-dl — kept as a separate signal (not folded into the one
 * above) since Updates > App update and Updates > Engines are separate sections a user acts on
 * independently; the nav-bar dot itself still just ORs the two together (see FloatingNavBar's call
 * site) since it means "something in Settings needs attention," not specifically which. */
object AppUpdateSignal {
    var hasUpdate by mutableStateOf(false)
}

// FloatingNavBar's own footprint: 16dp padding + 68dp pill + 16dp padding. Screens that now
// overlay it (instead of Scaffold reserving space for it) use this so their own scrollable
// content and floating buttons can still clear the pill instead of sitting behind it.
//
// This is only the pill's own visual size, deliberately not the real system navigation bar's
// height on top of it — enableEdgeToEdge() (MainActivity.onCreate) makes the system bars
// transparent and lets this app's content draw underneath them, which is only *half* of a real
// edge-to-edge setup. The other half is making sure nothing interactive (this pill itself, or the
// last item in a list using this constant) ends up sitting *behind* the real system nav bar
// instead of just behind its own transparent space — reproduced live: hardcoding a flat 100dp
// everywhere happened to clear this device's own nav bar by luck, but nothing here was actually
// reading its real height (WindowInsets.navigationBars), so a taller one (3-button nav, or a
// gesture nav with a taller inset on a different OEM skin) had no guarantee of being cleared at
// all. [navBarClearance] is the fix — this raw constant is kept only for the one place that isn't
// a screen-content clearance value (FloatingNavBar's own internal pill height math, further down).
val NAV_BAR_RESERVED_HEIGHT = 100.dp

/** [NAV_BAR_RESERVED_HEIGHT] plus the real system navigation bar inset — use this (not the bare
 * constant) for any scrollable content's bottom clearance or a floating button's own bottom
 * padding, so the reserved space actually adapts to the device's real nav bar height (3-button vs
 * gesture, and however tall a given OEM skin makes either) instead of assuming a fixed guess
 * happens to already cover it. See [NAV_BAR_RESERVED_HEIGHT]'s own doc comment for the full story. */
@Composable
fun navBarClearance(): Dp = NAV_BAR_RESERVED_HEIGHT + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: DownloadsViewModel = viewModel(), openQueueSignal: Int = 0) {
    var selectedTab by remember { mutableStateOf(0) }
    var showQueueScreen by remember { mutableStateOf(false) }
    // Owned here, not inside DownloadsHistoryScreen's own Scaffold — see that screen's own
    // snackbarHostState parameter doc comment for why (it drew behind FloatingNavBar otherwise).
    val librarySnackbarHostState = remember { SnackbarHostState() }
    // A download-in-progress notification tap bumps this (MainActivity.openQueueSignal) — jump
    // straight to Queue regardless of which tab was showing. Keyed on the signal itself (not
    // Unit) so a second tap while already on Queue still re-triggers this instead of being a no-op
    // LaunchedEffect restart.
    LaunchedEffect(openQueueSignal) {
        if (openQueueSignal > 0) showQueueScreen = true
    }
    // Non-null while the download preview sheet is up for that URL — the Home screen's Download
    // button opens the sheet instead of enqueueing straight away, so per-download quality/format/
    // trim/commands/filename can be set before anything starts.
    var previewUrl by remember { mutableStateOf<String?>(null) }
    // Non-null while a just-pasted/downloaded URL is being listed to decide which of the two
    // sheets above it actually deserves — see routingUrl's own LaunchedEffect further down.
    var routingUrl by remember { mutableStateOf<String?>(null) }
    // Non-null once that listing decides this link is a real multi-item gallery rather than a
    // single/multi-video case: url paired with the already-fetched result, fed straight into
    // SharePickerScreen as its own preloadedResult so it isn't listed a second time — same
    // optimization ShareActivity's own share-sheet flow already does for a shared link.
    var pickerState by remember { mutableStateOf<Pair<String, com.comfort.app.util.ListingResult>?>(null) }
    // Hoisted here (not owned inside MoreScreen) specifically so it survives switching away from
    // and back to the Settings tab — see MoreScreen's own doc comment for why a local remember
    // there wasn't enough.
    var settingsRoute by remember { mutableStateOf(SettingsRoute.ROOT) }
    // Set alongside settingsRoute only when navigating in from a Settings-search result (see
    // SettingsRootScreen's subpage results list) — tells that sub-screen which one row to scroll
    // to and flash. Hoisted for the same reason settingsRoute is: MoreScreen itself is torn down
    // and rebuilt on every Settings-tab revisit, so anything that needs to survive that has to live
    // up here instead.
    var settingsHighlightKey by remember { mutableStateOf<String?>(null) }
    // Same RUNNING+QUEUED count already shown inside the Library screen's own queue-icon badge
    // (DownloadsHistoryScreen) — kept consistent with that existing definition of "active" rather
    // than introducing a second, differently-scoped count just for this badge.
    val activeDownloadsCount by viewModel.activeDownloadsCount.collectAsStateWithLifecycle()

    // Rate-limited auto-check for a newer yt-dlp/gallery-dl release — seeded from the cached
    // result of the last check (so the badge shows immediately without waiting on a fresh network
    // round trip), then only actually re-hits PyPI if ENGINE_UPDATE_CHECK_INTERVAL_MS has actually
    // elapsed since the last one, so relaunching the app repeatedly doesn't spam it. The Engines
    // section in Settings > About always does its own fresh check regardless of this cache.
    val context = androidx.compose.ui.platform.LocalContext.current

    // Runs the same listing pass ShareActivity's own share-sheet flow already does, so a link
    // pasted directly on Home gets the same "look at what's there first" treatment a shared link
    // does instead of always assuming it's a single video — reproduced live: pasting a plain
    // multi-image gallery link went straight to DownloadPreviewSheet's video-styled card with
    // nothing to actually pick between, no way to exclude any of the images.
    LaunchedEffect(routingUrl) {
        val url = routingUrl ?: return@LaunchedEffect
        val result = com.comfort.app.util.GalleryDlListing.listItems(context, url)
        if (result.shouldUsePreviewSheet()) previewUrl = url else pickerState = url to result
        routingUrl = null
    }

    LaunchedEffect(Unit) {
        // Seeded from the persisted flag immediately (so the badge shows right away without
        // waiting on a fresh network round trip), then only actually re-checks PyPI if
        // ENGINE_UPDATE_CHECK_INTERVAL_MS has elapsed since the last check, so relaunching the app
        // repeatedly doesn't spam it.
        EngineUpdateSignal.hasUpdate = GalleryDlPreferences.isEngineUpdateAvailable(context)
        val lastCheck = GalleryDlPreferences.getEngineUpdateLastCheckMs(context)
        if (System.currentTimeMillis() - lastCheck < GalleryDlPreferences.ENGINE_UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        var statuses = EngineUpdater.checkAll(context)
        // On by default (Settings > Updates > Engines): install whatever this check found instead
        // of only flagging it for the user to apply by hand later. Best-effort per engine — a
        // failed download/verify (network hiccup, PyPI momentarily unreachable) just leaves that
        // one engine's own outdated status in place, still surfaced normally via the dot/quick
        // Settings section/About page, rather than silently swallowing the failure.
        if (GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) {
            statuses = statuses.map { status ->
                if (status.updateAvailable && status.artifactUrl != null) {
                    val result = EngineUpdater.update(context, status)
                    result.getOrNull()?.let { newVersion -> status.copy(installedVersion = newVersion) } ?: status
                } else {
                    status
                }
            }
        }
        val available = statuses.any { it.updateAvailable }
        EngineUpdateSignal.hasUpdate = available
        GalleryDlPreferences.setEngineUpdateAvailable(context, available)
        GalleryDlPreferences.setEngineUpdateLastCheckMs(context, System.currentTimeMillis())
    }

    // Same rate-limited-auto-check shape as the engine one just above, for AppUpdater's own GitHub
    // Releases check instead — a separate LaunchedEffect (not folded into that one) since it's a
    // fully independent check against a different service on its own interval
    // (APP_UPDATE_CHECK_INTERVAL_MS), not a step of the engine-update flow.
    LaunchedEffect(Unit) {
        AppUpdateSignal.hasUpdate = GalleryDlPreferences.isAppUpdateAvailable(context)
        val lastCheck = GalleryDlPreferences.getAppUpdateLastCheckMs(context)
        if (System.currentTimeMillis() - lastCheck < GalleryDlPreferences.APP_UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        val status = AppUpdater.check(context)
        AppUpdateSignal.hasUpdate = status.updateAvailable
        GalleryDlPreferences.setAppUpdateAvailable(context, status.updateAvailable)
        GalleryDlPreferences.setAppUpdateLastCheckMs(context, System.currentTimeMillis())
    }

    // Deliberately not Scaffold's own bottomBar slot: Scaffold reserves that whole slot's
    // measured region — pill height plus FloatingNavBar's own 24dp/16dp padding — and paints
    // containerColor behind all of it, not just the pill itself. That turned the padding around
    // the floating pill into a solid opaque block sitting on top of (and cutting off) whatever
    // list content would otherwise be visible there. Overlaying it on a plain Box instead lets
    // content scroll underneath the pill's transparent padding for real, which is what "floating"
    // is supposed to look like.
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Home is always composed here, underneath, as the one and only Home: it's what a
        // non-Home tab's back reveals, and it's simply the visible page on tab 0. There used to be
        // a second Home inside the animated box below for tab 0 — so every back to Home threw this
        // copy away and built a brand-new one mid-transition, which showed as a flicker at the end
        // of the back animation (reported live). Now back just removes the page on top.
        // Back from a non-Home tab returns to Home first, matching standard bottom-nav behavior,
        // instead of immediately falling through to the empty nav backstack and quitting the app.
        // Disabled while QueueScreen is up — its own predictive-back handler below takes over.
        val tabBack = rememberBackRevealState(enabled = !showQueueScreen && selectedTab != 0) {
            selectedTab = 0
        }
        val queueBack = rememberBackRevealState(enabled = showQueueScreen) {
            showQueueScreen = false
        }
        // Opening the Queue plays the push, the mirror of its back (see animateEnter).
        val openQueue = { if (!showQueueScreen) queueBack.animateEnter { showQueueScreen = true } }
        // Everything under the Queue (Home + the current tab) — it's what closing the Queue
        // reveals, so it trails/fades in as one layer (see predictiveBackBehind).
        Box(modifier = Modifier.fillMaxSize().predictiveBackBehind(queueBack, active = showQueueScreen)) {
        Box(modifier = Modifier.fillMaxSize().predictiveBackBehind(tabBack, active = selectedTab != 0)) {
            HomeScreen(
                onConfigure = { url -> routingUrl = url },
                viewModel = viewModel,
                onOpenLibrary = { selectedTab = 1 },
                onOpenQueue = openQueue,
            )
        }

        // Only the non-Home tabs, on top of the Home above (see its comment).
        if (selectedTab != 0) Box(modifier = Modifier.fillMaxSize().predictiveBackReveal(tabBack)) {
            when (selectedTab) {
                1 -> DownloadsHistoryScreen(
                    viewModel = viewModel,
                    onOpenQueue = openQueue,
                    isQueueOpen = showQueueScreen,
                    snackbarHostState = librarySnackbarHostState,
                )
                2 -> MoreScreen(
                    route = settingsRoute,
                    highlightKey = settingsHighlightKey,
                    onNavigate = { route, key -> settingsRoute = route; settingsHighlightKey = key },
                )
            }
        }
        }

        FloatingNavBar(
            selectedTab = selectedTab,
            activeDownloadsCount = activeDownloadsCount,
            hasEngineUpdate = EngineUpdateSignal.hasUpdate || AppUpdateSignal.hasUpdate,
            onSelect = { index ->
                when {
                    // Tapping the already-selected Library tab again jumps to the Queue, matching
                    // the "tap again for more" pattern used elsewhere in the app.
                    index == 1 && selectedTab == 1 -> openQueue()
                    // Tapping the already-selected Settings tab again backs all the way out to the
                    // main Settings list, instead of leaving whatever subpage was open in place —
                    // selectedTab is already 2 here, so a bare `selectedTab = index` wouldn't have
                    // changed anything.
                    index == 2 && selectedTab == 2 -> {
                        settingsRoute = SettingsRoute.ROOT
                        settingsHighlightKey = null
                    }
                    index == selectedTab -> Unit
                    // Home is the page every other tab sits on: going there is a back.
                    index == 0 -> tabBack.animateBack()
                    // Another tab opens with the push. Coming from Home, Home slides away behind
                    // it; between two tabs Home is already hidden, so only the new tab moves.
                    else -> tabBack.animateEnter(behindVisible = selectedTab == 0) { selectedTab = index }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Composed after FloatingNavBar (draws on top of it) and inset by the same
        // navBarClearance() the FAB below already uses to clear the pill — otherwise this
        // renders invisibly empty until Library's "Download removed" Undo snackbar actually
        // fires, same as SnackbarHost always does when it has nothing queued.
        // DownloadEventSnackbarHost (Components.kt), not the plain default SnackbarHost — same
        // themed surfaceContainerHigh card + accent Undo button QueueScreen's own equivalent
        // delete-undo Snackbar already uses, instead of Material's flat default bar this one
        // still had, unthemed against the rest of the app.
        DownloadEventSnackbarHost(
            librarySnackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBarClearance()),
        )

        // Rendered on top of (not instead of) the tab content above, specifically so the tab
        // underneath is actually visible while mid-swipe — an early-return here (the previous
        // approach) would mean there's nothing behind QueueScreen to peek at during the gesture.
        if (showQueueScreen) {
            Box(modifier = Modifier.fillMaxSize().predictiveBackReveal(queueBack)) {
                QueueScreen(
                    viewModel = viewModel,
                    // Same peel-away as the back gesture, not an instant jump back.
                    onBack = { queueBack.animateBack() },
                )
            }
        }

        previewUrl?.let { pendingUrl ->
            DownloadPreviewSheet(
                url = pendingUrl,
                onDismiss = { previewUrl = null },
                onDownload = { options ->
                    // forceDuplicate = true: DownloadPreviewSheet's own Download button already
                    // checked DownloadDispatcher.isDuplicate and would already be reading
                    // "Redownload" here if this url was one — see that button's own doc comment.
                    viewModel.enqueueDownload(
                        url = pendingUrl,
                        title = "Downloading from ${VideoSiteRouter.siteName(pendingUrl)}",
                        itemFilter = options.itemFilter,
                        totalItems = options.totalItems,
                        videoQuality = options.quality,
                        clipRange = options.clipRange,
                        extraCommands = options.extraCommands,
                        outputFormat = options.outputFormat,
                        filenameTemplate = options.filenameTemplate,
                        saveThumbnail = options.saveThumbnail,
                        overrideTitle = options.overrideTitle,
                        overrideArtist = options.overrideArtist,
                        forceDuplicate = true,
                    )
                    previewUrl = null
                },
            )
        }

        // Brief — the listing pass above usually resolves in well under a second — but real
        // enough on a slow/rate-limited site that a bare frozen Download button would otherwise
        // look broken with no feedback at all.
        if (routingUrl != null) {
            ModalBottomSheet(onDismissRequest = { routingUrl = null }) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularWavyProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Looking at what's there…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // routingUrl's own listing decided this is a real multi-item gallery rather than a
        // single/multi-video case — same SharePickerScreen the share-sheet flow already uses,
        // fed the same already-fetched result so it isn't listed a second time.
        pickerState?.let { (pickerUrl, preloadedResult) ->
            var sheetHeight by remember { mutableStateOf(400.dp) }
            val animatedHeight by androidx.compose.animation.core.animateDpAsState(targetValue = sheetHeight, label = "sharePickerSheetHeight")
            ModalBottomSheet(
                onDismissRequest = { pickerState = null },
                dragHandle = null,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(animatedHeight)) {
                    SharePickerScreen(
                        url = pickerUrl,
                        onDismiss = { pickerState = null },
                        onDownload = { downloadUrl, itemFilter, totalItems, videoQuality, forceDuplicate ->
                            viewModel.enqueueDownload(
                                url = downloadUrl,
                                title = "Downloading from ${VideoSiteRouter.siteName(downloadUrl)}",
                                itemFilter = itemFilter,
                                totalItems = totalItems,
                                videoQuality = videoQuality,
                                forceDuplicate = forceDuplicate,
                            )
                            pickerState = null
                        },
                        onHeightChange = { sheetHeight = it },
                        preloadedResult = preloadedResult,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavBar(
    selectedTab: Int,
    activeDownloadsCount: Int,
    hasEngineUpdate: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Real system nav bar inset first (so the pill sits clear of it on every device, not
            // just this one, where the fixed 16dp below happened to already be enough), then this
            // bar's own fixed visual margin on top of that — see NAV_BAR_RESERVED_HEIGHT's doc
            // comment for the full story.
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(68.dp),
            shape = PillShape,
            color = MaterialTheme.colorScheme.primary,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    val tint by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                        animationSpec = tween(200),
                        label = "navTint",
                    )
                    val bg by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        animationSpec = tween(200),
                        label = "navBg",
                    )

                    Row(
                        modifier = Modifier
                            .clip(PillShape)
                            .background(bg)
                            .clickable { onSelect(index) }
                            .padding(horizontal = if (selected) 20.dp else 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val iconModifier = Modifier.size(22.dp)
                        if (index == 1 && activeDownloadsCount > 0) {
                            BadgedBox(badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text(activeDownloadsCount.toString())
                                }
                            }) {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = iconModifier)
                            }
                        } else if (index == 2 && hasEngineUpdate) {
                            BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = iconModifier)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = iconModifier)
                        }
                        AnimatedVisibility(visible = selected) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Text(tab.label, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Remembers the last clipboard link already offered/dismissed, across tab switches, so the
// suggestion banner doesn't keep nagging about the same copied link.
private object ClipboardSuggestionState {
    var lastHandled: String? = null
}

@Composable
fun HomeScreen(
    // Opens the preview/picker sheets (per-download quality, items, trim, ...) for the link.
    onConfigure: (String) -> Unit,
    viewModel: DownloadsViewModel,
    onOpenLibrary: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
) {
    var url by remember { mutableStateOf("") }
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val homeScope = androidx.compose.runtime.rememberCoroutineScope()
    // Download: starts right away with the default settings — the same thing sharing a link into
    // the "Instant" Sharesheet entry does (ShareActivity's InstantShareHandler), duplicate handling
    // and message included. Configure is the other button.
    fun downloadNow(link: String) {
        homeScope.launch {
            val result = com.comfort.app.data.DownloadDispatcher.enqueueDownload(
                homeContext, link, "Downloading from ${com.comfort.app.data.VideoSiteRouter.siteName(link)}",
            )
            android.widget.Toast.makeText(
                homeContext,
                if (result is com.comfort.app.data.EnqueueResult.Duplicate) "Already downloaded — see Library > Duplicates" else "Download started",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    val queueItems by viewModel.queueFlow.collectAsStateWithLifecycle()
    val historyItems by viewModel.historyFlow.collectAsStateWithLifecycle()
    // Most-recent RUNNING item — a summary screen only ever needs to surface one at a time; the
    // full Queue is one tap away (onOpenQueue) for anything more than that.
    val activeDownload = remember(queueItems) { queueItems.firstOrNull { it.status == DownloadStatus.RUNNING } }
    val recentDownloads = remember(historyItems) { historyItems.filter { !it.thumbnailPath.isNullOrBlank() }.take(10) }

    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text?.trim()
        if (!clipText.isNullOrBlank() &&
            clipText != ClipboardSuggestionState.lastHandled &&
            android.util.Patterns.WEB_URL.matcher(clipText).matches()
        ) {
            clipboardSuggestion = clipText
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(top = 12.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
        ) {
            // A big, chunky wordmark instead of a name + tagline pair — no separate app-name
            // caption above it (there's nothing to disambiguate it from anymore) and no subtitle
            // below, just the logo standing on its own the way a launcher icon does. Manually
            // shrinks to fit (this Compose Foundation version doesn't have the newer built-in
            // autoSize) rather than a guessed fixed fontSize — grows/shrinks to actually fill the
            // available width edge-to-edge like the reference image, on any screen width, instead
            // of only being right on one specific device.
            var wordmarkFontSize by remember { mutableStateOf(88.sp) }
            var wordmarkMeasured by remember { mutableStateOf(false) }
            BasicText(
                "COMFORT",
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = CrystalRadioKit,
                    textAlign = TextAlign.Center,
                    fontSize = wordmarkFontSize,
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    // Extra inset beyond the screen's own 24dp margin (see this Column's parent
                    // padding above) so the auto-fit-to-width logic below settles on a slightly
                    // smaller size instead of stretching edge-to-edge — a size request, not a
                    // literal fontSize, so it still scales consistently across screen widths the
                    // same way the un-inset version did.
                    .padding(horizontal = 48.dp)
                    .graphicsLayer(alpha = if (wordmarkMeasured) 1f else 0f),
                onTextLayout = { result ->
                    if (result.didOverflowWidth && wordmarkFontSize > 12.sp) {
                        wordmarkFontSize *= 0.94f
                    } else {
                        wordmarkMeasured = true
                    }
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .offset(y = (-28).dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        // A plain placeholder (no floating label) so the field reads as one
                        // resting pill with "Paste a link" sitting centered inside it, rather than
                        // a caption perched above the field's own border.
                        placeholder = { Text("Paste a link") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        trailingIcon = {
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { url = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (url.isNotBlank()) {
                                    onConfigure(url.trim())
                                    url = ""
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            enabled = url.isNotBlank(),
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Configure")
                        }

                        Button(
                            onClick = {
                                if (url.isNotBlank()) {
                                    downloadNow(url.trim())
                                    url = ""
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            enabled = url.isNotBlank(),
                        ) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Supported sources",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val sources = listOf(
                Triple("Twitter / X", FeatherIcons.Twitter, MaterialTheme.colorScheme.primary),
                Triple("Pixiv", Icons.Outlined.Image, MaterialTheme.colorScheme.secondary),
                Triple("Instagram", FeatherIcons.Instagram, MaterialTheme.colorScheme.primary),
                Triple("+ hundreds more", Icons.Outlined.Public, MaterialTheme.colorScheme.secondary),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sources.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (name, icon, tint) ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(tint.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (activeDownload != null) {
                Spacer(Modifier.height(24.dp))
                ActiveDownloadCard(item = activeDownload, onClick = onOpenQueue)
            }

            if (recentDownloads.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Recently downloaded",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val recentListState = rememberLazyListState()
                // A genuinely continuous, slow drift (not the earlier per-item jump-and-pause)
                // once the user's left it alone for a while — an idle showcase, not something
                // fighting a real swipe. scrollBy() runs at MutatePriority.Default, the same
                // priority auto-scroll conventionally uses; a real drag gesture is handled by the
                // LazyRow's own built-in touch machinery at UserInput priority, which Compose's
                // scroll mutex always lets preempt a lower-priority caller, so a user grabbing the
                // list mid-drift interrupts this loop's current scrollBy call automatically — the
                // isDragged check below just avoids wastefully *starting* a new one while they're
                // still holding it, on top of that.
                val isRecentListDragged by recentListState.interactionSource.collectIsDraggedAsState()
                LaunchedEffect(recentDownloads.size) {
                    if (recentDownloads.size <= 1) return@LaunchedEffect
                    kotlinx.coroutines.delay(3500)
                    while (true) {
                        if (isRecentListDragged) {
                            kotlinx.coroutines.delay(200)
                            continue
                        }
                        if (recentListState.canScrollForward) {
                            recentListState.scrollBy(1.1f)
                        } else {
                            kotlinx.coroutines.delay(1800)
                            recentListState.animateScrollToItem(0)
                            kotlinx.coroutines.delay(1800)
                        }
                        kotlinx.coroutines.delay(16)
                    }
                }
                LazyRow(
                    state = recentListState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    // The earlier Modifier.padding() here was the actual bug: applied after the
                    // bleed widening, it shrinks the LazyRow's own *viewport*, which just moves
                    // where the clipped edge sits — it can never let scrolling actually reach past
                    // it, no matter how much extra width the bleed measures into. contentPadding
                    // is the real mechanism for "inset at rest, reachable by scrolling": it pads
                    // the *scrollable content* within a full-width viewport, not the viewport
                    // itself, so the leading/trailing item can still scroll into that space. The
                    // bleed layout below still does need to stay, though — it's the only thing
                    // giving this row a full-width viewport to begin with, escaping the parent
                    // Column's own 24dp margin that would otherwise cap it regardless.
                    modifier = Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val bleed = 24.dp.roundToPx()
                            val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                            layout(placeable.width - bleed * 2, placeable.height) {
                                placeable.placeRelative(-bleed, 0)
                            }
                        },
                    contentPadding = PaddingValues(horizontal = 24.dp),
                ) {
                    items(recentDownloads, key = { it.id }) { item ->
                        RecentDownloadThumbnail(item = item, onClick = onOpenLibrary)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HomeTipsCarousel()

            // Matches navBarClearance() below — content needs to be able to scroll clear
            // of the floating pill now that it overlays on top instead of reserving its own
            // Scaffold-managed space (see MainScreen's own comment on that change).
            Spacer(Modifier.height(navBarClearance()))
        }
    }

        // Always here now that the card's own Paste button became Configure: reads the clipboard
        // at tap time (so a link copied after Home opened works too), and says "Paste copied link"
        // when a fresh link was already spotted on open.
        ExtendedFloatingActionButton(
            onClick = {
                val clipText = clipboardSuggestion ?: clipboardManager.getText()?.text?.trim()
                if (clipText.isNullOrBlank()) {
                    android.widget.Toast.makeText(homeContext, "Nothing to paste", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    url = clipText
                    ClipboardSuggestionState.lastHandled = clipText
                    clipboardSuggestion = null
                }
            },
            icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
            text = { Text(if (clipboardSuggestion != null) "Paste copied link" else "Paste") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = navBarClearance(), end = 24.dp),
        )
    }
}

/** Compact summary of the single most-recent RUNNING download, filling what used to be dead
 * space below "Supported sources" — tapping it opens the full Queue (onClick) for anything more
 * than the one item this shows. Deliberately much simpler than QueueScreen's own QueueItemCard
 * (no per-item action buttons, no network-stall messaging) — this is a glance/shortcut, not
 * another place to manage the download from. */
@Composable
private fun ActiveDownloadCard(item: com.comfort.app.data.DownloadEntity, onClick: () -> Unit) {
    val progress = when {
        item.totalItems > 1 -> (item.downloadedItems.toFloat() / item.totalItems).coerceIn(0f, 1f)
        item.expectedBytes > 0 -> ((item.totalBytes + item.liveBytes).toFloat() / item.expectedBytes).coerceIn(0f, 1f)
        else -> null
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // shapes.medium (12dp) — the spec's own card token; shapes.large (16dp) is for FABs/nav
        // drawers, same finding as the Queue screen's own running card had.
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            // 16dp, not 14dp — MD3's spacing system is built on an 8dp grid.
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    // shapes.small (8dp) — 10dp matched no real shape token.
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.thumbnailPath.isNullOrBlank()) {
                    HomeCardThumbnail(item.thumbnailPath, item.url, item.title, Modifier.fillMaxSize())
                } else {
                    // onPrimaryContainer, not the bare (unpaired) primary — same tonal-pairing
                    // fix as the Queue screen's own thumbnail icon: this box's background is
                    // primaryContainer, and MD3 only guarantees contrast for a container against
                    // its own matching "on" color, not an arbitrary combination.
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                }
            }
            // 16dp, not 12dp — same 8dp-grid reasoning as the padding above.
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title.ifBlank { item.url },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        // surfaceContainerHighest, not the older surfaceVariant token — same
                        // finding as the Queue screen's own progress track.
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    Text(
                        "Downloading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (progress != null) {
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (item.speedMbs > 0f) {
                        Text(
                            "${String.format("%.2f", item.speedMbs)} MB/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RecentDownloadThumbnail(item: com.comfort.app.data.DownloadEntity, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            // shapes.large (16dp) — same fix, same magic-number/size pairing, as the Library
            // screen's own equivalent thumbnail box (DownloadsHistoryScreen.kt's HistoryRow).
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        HomeCardThumbnail(item.thumbnailPath, item.url, item.title, Modifier.fillMaxSize())
    }
}

// Shared by ActiveDownloadCard/RecentDownloadThumbnail — same spoofed-header pattern QueueScreen's
// own QueueThumbnail uses (many sites reject hotlinked image requests without a browser-like UA
// and a same-site Referer), kept local here rather than exported since it's a small, self-contained
// image request and Home has no other reason to depend on QueueScreen's internals.
//
// SubcomposeAsyncImage with explicit loading/error composables, not a plain AsyncImage — the
// latter renders nothing at all on a failed load, leaving just the parent Box's flat background
// color showing (reproduced live: a finished download's own real content:// thumbnail URI
// intermittently failed to load here and showed as a blank tile in "Recently downloaded", while
// the exact same item's thumbnail rendered fine in Library, which already uses this same
// loading/error-icon pattern QueueScreen's own QueueThumbnail does). Matches both of those instead
// of being the one thumbnail spot in the app with no fallback.
@Composable
private fun HomeCardThumbnail(path: String?, refererUrl: String, title: String, modifier: Modifier = Modifier) {
    coil.compose.SubcomposeAsyncImage(
        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(path)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .addHeader("Referer", refererUrl)
            .build(),
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        },
        error = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(26.dp),
                )
            }
        },
    )
}

// Short, rotating tips highlighting features that are easy to miss otherwise — long-press
// multi-select and auto-updating engines especially, both added the same session this carousel
// was, with no other obvious discovery path on Home.
private val HOME_TIPS = listOf(
    "Long-press a card in Queue to select multiple downloads at once." to Icons.Outlined.CheckBox,
    "gallery-dl and yt-dlp engines auto-update in the background — check Settings > About." to Icons.Outlined.Refresh,
    "Add cookies from Settings to unlock private or age-restricted content." to Icons.Outlined.Lock,
    "Tap a queued download's \"Start now\" to skip the schedule window or its place in line." to Icons.Outlined.Bolt,
    "Choose MP4 or MKV output, and how many times a failed download retries, in Settings > Downloads." to Icons.Outlined.Settings,
)

@Composable
private fun HomeTipsCarousel() {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(6000)
            index = (index + 1) % HOME_TIPS.size
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInVertically { it / 3 } + androidx.compose.animation.fadeIn(tween(300))) togetherWith
                    (slideOutVertically { -it / 3 } + androidx.compose.animation.fadeOut(tween(300)))
            },
            label = "homeTip",
        ) { i ->
            val (tip, icon) = HOME_TIPS[i]
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

