package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.comfort.app.viewmodel.startedMessage
import com.comfort.app.viewmodel.LinkRouterViewModel
import com.comfort.app.viewmodel.LinkRoute
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearWavyProgressIndicator
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
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import java.util.Locale
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

private data class NavTab(@androidx.annotation.StringRes val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val label: String @Composable get() = stringResource(labelRes)
}

private val tabs = listOf(
    NavTab(R.string.nav_home, Icons.Outlined.Home),
    NavTab(R.string.nav_library, Icons.Outlined.Image),
    NavTab(R.string.nav_settings, Icons.Outlined.Settings),
)


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
fun navBarClearance(): Dp = navBarOnlyClearance() + LocalMiniPlayerInset.current

/** [navBarClearance] without the mini player: where the mini player itself sits. */
@Composable
private fun navBarOnlyClearance(): Dp {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // No floating pill on wide windows (a side rail replaces it), so only a small margin is needed.
    return if (LocalUseNavRail.current) 16.dp + inset else NAV_BAR_RESERVED_HEIGHT + inset
}

/** The mini player's height while it shows (else 0), so every page's bottom clearance
 * ([navBarClearance]) keeps its last row and buttons above it too. */
val LocalMiniPlayerInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** True when the window is wide enough (tablet, or a phone in landscape) that navigation is a side
 * rail instead of the floating bottom pill. Set once in [MainScreen]. */
val LocalUseNavRail = androidx.compose.runtime.compositionLocalOf { false }

/** Window width (dp) from which navigation becomes a side rail — Material's "medium" breakpoint. */
private const val NAV_RAIL_MIN_WIDTH_DP = 600

/** Widest a text/form-style screen (Home, Settings, Queue) gets before it's centered instead of
 * stretching edge to edge across a tablet. */
val CONTENT_MAX_WIDTH = 720.dp

/** Centers [content] at most [CONTENT_MAX_WIDTH] wide; a no-op on phone-width windows. */
@Composable
fun ConstrainedWidth(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxHeight()) { content() }
    }
}


/** A launcher shortcut to act on ([action], MainActivity.SHORTCUT_*); [seq] makes a repeat a new value. */
data class ShortcutRequest(val action: String, val seq: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: DownloadsViewModel = viewModel(), openQueueSignal: Int = 0, shortcut: ShortcutRequest? = null) {
    var selectedTab by remember { mutableStateOf(0) }
    var showQueueScreen by remember { mutableStateOf(false) }
    // Owned here, not inside DownloadsHistoryScreen's own Scaffold — see that screen's own
    // snackbarHostState parameter doc comment for why (it drew behind FloatingNavBar otherwise).
    val librarySnackbarHostState = remember { SnackbarHostState() }
    // Which sheet a link opened from Home is showing (listing it / preview / item picker), and the
    // download it starts — LinkRouterViewModel, shared with the share sheet's own flow.
    val router: LinkRouterViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val route by router.route.collectAsState()
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

    // Engine and app update checks, once per launch and rate-limited — see UpdatesViewModel.
    val updates = updatesViewModel()
    val anyUpdateAvailable by updates.anyUpdateAvailable.collectAsState()
    LaunchedEffect(Unit) { updates.runLaunchChecks() }

    // Deliberately not Scaffold's own bottomBar slot: Scaffold reserves that whole slot's
    // measured region — pill height plus FloatingNavBar's own 24dp/16dp padding — and paints
    // containerColor behind all of it, not just the pill itself. That turned the padding around
    // the floating pill into a solid opaque block sitting on top of (and cutting off) whatever
    // list content would otherwise be visible there. Overlaying it on a plain Box instead lets
    // content scroll underneath the pill's transparent padding for real, which is what "floating"
    // is supposed to look like.
    // Hoisted out of the content Box below so the side rail (wide windows) and the floating pill
    // (narrow ones) can share the same tab-switching logic.
    // Home is always composed under everything, as the one and only Home: it's what a
    // non-Home tab's back reveals, and it's simply the visible page on tab 0. There used to be
    // a second Home inside the animated box below for tab 0 — so every back to Home threw this
    // copy away and built a brand-new one mid-transition, which showed as a flicker at the end
    // of the back animation (reported live). Now back just removes the page on top.
    // Back from a non-Home tab returns to Home first, matching standard bottom-nav behavior,
    // instead of immediately falling through to the empty nav backstack and quitting the app.
    // Disabled while QueueScreen is up — its own predictive-back handler takes over.
    val tabBack = rememberBackRevealState(enabled = !showQueueScreen && selectedTab != 0) {
        selectedTab = 0
    }
    val queueBack = rememberBackRevealState(enabled = showQueueScreen) {
        showQueueScreen = false
    }
    // Opening the Queue plays the push, the mirror of its back (see animateEnter).
    val openQueue = { if (!showQueueScreen) queueBack.animateEnter { showQueueScreen = true } }

    // A download-in-progress notification tap bumps this (MainActivity.openQueueSignal) — jump
    // straight to Queue regardless of which tab was showing. Keyed on the signal itself (not
    // Unit) so a second tap while already on Queue still re-triggers this instead of being a no-op
    // LaunchedEffect restart.
    LaunchedEffect(openQueueSignal) {
        if (openQueueSignal > 0) openQueue()
    }
    // The tab being left during a switch between two tabs (Library <-> Settings): kept on
    // screen as the page behind, so it slides out like any page does. Without it the old tab
    // vanished at once and the screen was empty for ~80ms before the new one came in (Home,
    // the usual page behind, is hidden while another tab is up). Cleared when the switch ends.
    var outgoingTab by remember { mutableStateOf<Int?>(null) }
    // Settings stays composed once built, hidden when not shown, instead of being thrown away
    // on every tab switch. Measured on-device: every open cost one ~87ms frame (~47ms
    // composing the page, ~38ms layout+draw) — rebuilt from scratch each time, which also
    // re-ran its "Updates available" engine check. It's nearly static, so keeping it costs
    // little. Built ~2s after launch while idle, so even the first open only has to draw it.
    // Library is kept the same way, but only while nothing is downloading: it recomposes on
    // every progress tick, which a hidden copy would pay for too. Kept, it opens with nothing
    // to build; otherwise enterTab builds it the frame before its slide (measured on the
    // benchmark build: a ~75ms frame inside the slide without either).
    var keepSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_000)
        keepSettings = true
    }
    // A tab that isn't kept composed (Library) is built first, hidden, and only then slides in:
    // built inside the push it cost the animation its first frames (the page's ~35-50ms
    // composition landed in them — the lag opening Library). Now that cost is one frame before
    // the slide starts, and the slide itself only has to draw a page that's already there.
    var preparingTab by remember { mutableStateOf<Int?>(null) }
    val keepLibrary = keepSettings && activeDownloadsCount == 0
    // The Library's scroll position, held here: the Library itself is rebuilt each visit while
    // something's downloading (above), and its place in the list went with it.
    val libraryListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val libraryGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val tabScope = rememberCoroutineScope()
    fun enterTab(index: Int, enter: () -> Unit) {
        if (preparingTab != null) return
        if ((index == 2 && keepSettings) || (index == 1 && keepLibrary)) { enter(); return }
        preparingTab = index
        tabScope.launch {
            // The next frame composes and measures it (hidden); the one after starts the slide.
            withFrameNanos { }
            withFrameNanos { }
            enter()
            preparingTab = null
        }
    }
    // Swiping sideways between the pages (PageSwipe.kt). Not while the Queue covers them or a
    // tap's own transition is still running.
    val pageSwipe = rememberPageSwipeState(
        current = selectedTab,
        // Nor while a back or an open moves the pages: a swipe took them over mid-way (a jump),
        // and the back's selectedTab = 0 then landed under it.
        allowed = !showQueueScreen && outgoingTab == null && preparingTab == null && !tabBack.busy && !queueBack.busy,
        onSettled = { selectedTab = it },
    )
    val onTabSelect: (Int) -> Unit = { index ->
        when {
            // A swipe still settling would set its own page when it lands, over this tap's.
            pageSwipe.neighbor != null -> Unit
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
            // Another tab opens with the push, the page it covers sliding away behind it:
            // Home, or when switching between two tabs, the tab being left (outgoingTab).
            selectedTab == 0 -> enterTab(index) { tabBack.animateEnter { selectedTab = index } }
            else -> {
                val leaving = selectedTab
                enterTab(index) {
                    tabBack.animateEnter(onFinished = { outgoingTab = null }) {
                        outgoingTab = leaving
                        selectedTab = index
                    }
                }
            }
        }
    }

    // The launcher icon's shortcuts (MainActivity.SHORTCUT_*): straight to their page. Paste &
    // download and Search hand over to Home and Library, which act on their signal.
    var pasteDownloadSignal by remember { mutableIntStateOf(0) }
    // The music player's full screen (ui/music), over everything; the mini player opens it.
    var showNowPlaying by remember { mutableStateOf(false) }
    val music by com.comfort.app.music.MusicPlayer.state.collectAsState()
    // Shows music already playing when the app is reopened.
    LaunchedEffect(Unit) { com.comfort.app.music.MusicPlayer.connect(context) }
    LaunchedEffect(music.song == null) { if (music.song == null) showNowPlaying = false }
    var librarySearchSignal by remember { mutableIntStateOf(0) }
    LaunchedEffect(shortcut) {
        val request = shortcut ?: return@LaunchedEffect
        if (request.action == com.comfort.app.MainActivity.SHORTCUT_OPEN_QUEUE) {
            openQueue()
        } else {
            showQueueScreen = false
            when (request.action) {
                com.comfort.app.MainActivity.SHORTCUT_PASTE_DOWNLOAD -> { selectedTab = 0; pasteDownloadSignal++ }
                com.comfort.app.MainActivity.SHORTCUT_OPEN_LIBRARY -> selectedTab = 1
                com.comfort.app.MainActivity.SHORTCUT_SEARCH_LIBRARY -> { selectedTab = 1; librarySearchSignal++ }
                // The music notification's tap.
                com.comfort.app.MainActivity.ACTION_OPEN_NOW_PLAYING -> showNowPlaying = true
            }
        }
    }

    val useNavRail = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= NAV_RAIL_MIN_WIDTH_DP
    // The media viewer (Photon Gallery's, ui/viewer), opened from a Library item over everything.
    var mediaViewer by remember { mutableStateOf<com.comfort.app.ui.viewer.MediaViewerRequest?>(null) }
    val showMiniPlayer = music.song != null && !showNowPlaying && mediaViewer == null
    CompositionLocalProvider(
        LocalUseNavRail provides useNavRail,
        // A song plays in the music player (its neighbours in the list as the queue); anything
        // else opens the media viewer, which pages through the pictures and videos only.
        com.comfort.app.ui.viewer.LocalOpenMediaViewer provides { request ->
            val tapped = request.downloads.firstOrNull { it.id == request.startId }
            if (tapped?.isAudio == true) {
                com.comfort.app.music.MusicPlayer.play(context, request.downloads, tapped.id)
                showNowPlaying = true
            } else {
                mediaViewer = request.copy(downloads = request.downloads.filter { !it.isAudio })
            }
        },
        com.comfort.app.ui.music.LocalPlaySongs provides { songs, shuffle ->
            com.comfort.app.music.MusicPlayer.play(context, songs, null, shuffle)
            showNowPlaying = true
        },
        // Animated with the bar's own slide: switched at once, pages' bottom space jumped.
        LocalMiniPlayerInset provides androidx.compose.animation.core.animateDpAsState(
            if (showMiniPlayer) com.comfort.app.ui.music.MINI_PLAYER_HEIGHT else 0.dp,
            label = "miniPlayerInset",
        ).value,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    if (useNavRail) {
        SideNavRail(
            selectedTab = selectedTab,
            activeDownloadsCount = activeDownloadsCount,
            hasEngineUpdate = anyUpdateAvailable,
            onSelect = onTabSelect,
        )
    }
    // clipToBounds: pages parked behind the front one are shifted left (predictiveBackBehind) and
    // are invisible but still hit-testable — unclipped, on a wide window they landed on top of the
    // rail and swallowed its taps (Home's text field opened the keyboard instead of switching tab).
    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
        // Everything under the Queue (Home + the current tab) — it's what closing the Queue
        // reveals, so it trails/fades in as one layer (see predictiveBackBehind).
        Box(modifier = Modifier.fillMaxSize().predictiveBackBehind(queueBack, active = showQueueScreen).pageSwipeArea(pageSwipe)) {
        CompositionLocalProvider(LocalPageSwipe provides pageSwipe) {
        Box(
            modifier = Modifier.fillMaxSize().then(
                when {
                    // A swipe to or from Home: beside the other page, following the finger.
                    pageSwipe.involves(0) -> Modifier.pageSwipeLayer(pageSwipe, 0, selectedTab)
                    // Between two tabs Home stays hidden; the outgoing tab is the page behind instead.
                    outgoingTab != null -> Modifier.graphicsLayer { alpha = 0f }
                    else -> Modifier.predictiveBackBehind(tabBack, active = selectedTab != 0)
                }
            )
        ) {
            ConstrainedWidth {
            HomeScreen(
                onConfigure = { url -> router.open(url) },
                viewModel = viewModel,
                onOpenLibrary = { selectedTab = 1 },
                onOpenQueue = openQueue,
                pasteDownloadSignal = pasteDownloadSignal,
                // A lambda, read only by the strip's drift: as a plain value, leaving Home
                // recomposed all of it (6.4 ms of the Library's opening frame, traced).
                active = { (selectedTab == 0 || pageSwipe.involves(0)) && !showQueueScreen && mediaViewer == null && !showNowPlaying },
            )
            }
        }

        // Only the non-Home tabs, on top of the Home above (see its comment) — plus, during a
        // tab-to-tab switch, the tab being left, underneath the new one. Keyed per tab so the
        // outgoing tab keeps its composed instance (rebuilding it would stall the frame).
        val shownTabs = listOfNotNull(outgoingTab, selectedTab.takeIf { it != 0 }).distinct()
        // The page a swipe is bringing in is built as the swipe starts, if it wasn't already.
        val keptTabs = (
            shownTabs + listOfNotNull(2.takeIf { keepSettings }, 1.takeIf { keepLibrary }, preparingTab, pageSwipe.neighbor?.takeIf { it != 0 })
        ).distinct()
        for (tab in keptTabs) key(tab) {
            Box(
                // Opaque so a centered, width-capped page (Home/Settings on a wide window) doesn't
                // show whatever's underneath in the gutters either side of it.
                modifier = if (pageSwipe.involves(tab)) {
                    // Moved by a swipe: its background moves with it (inside the layer), or it
                    // would stay put and cover the page coming in beside it.
                    Modifier.fillMaxSize().pageSwipeLayer(pageSwipe, tab, selectedTab).background(MaterialTheme.colorScheme.background)
                } else if (tab == outgoingTab) {
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).predictiveBackBehind(tabBack, active = true)
                } else {
                    // Kept but not shown: laid out and drawn, invisible and underneath Home, so
                    // showing it is only a change of its layer's alpha. Left unplaced (as it was),
                    // the opening frame still had to lay out and draw the whole page from scratch:
                    // 46 ms, about five dropped frames at 120 Hz (traced on the benchmark build:
                    // 14 ms layout, 12 ms drawing). The same modifiers either way, so the switch
                    // doesn't rebuild them; the hidden copy can't be touched or read out.
                    val hidden = tab != selectedTab
                    Modifier.fillMaxSize()
                        .zIndex(if (hidden) -1f else 0f)
                        .graphicsLayer { alpha = if (hidden) 0f else 1f }
                        .pointerInput(hidden) {
                            if (!hidden) return@pointerInput
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                        .background(MaterialTheme.colorScheme.background)
                        .predictiveBackReveal(tabBack)
                        .then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
                }
            ) {
                when (tab) {
                    1 -> DownloadsHistoryScreen(
                        listState = libraryListState,
                        gridState = libraryGridState,
                        viewModel = viewModel,
                        onOpenQueue = openQueue,
                        isQueueOpen = showQueueScreen,
                        snackbarHostState = librarySnackbarHostState,
                        openSearchSignal = librarySearchSignal,
                        isVisible = tab == selectedTab || tab == outgoingTab,
                    )
                    2 -> ConstrainedWidth {
                    MoreScreen(
                        route = settingsRoute,
                        highlightKey = settingsHighlightKey,
                        onNavigate = { route, key -> settingsRoute = route; settingsHighlightKey = key },
                        isVisible = selectedTab == 2,
                    )
                    }
                }
            }
        }
        }
        }

        // A soft floor behind the floating nav pill on every page it floats over (Home, Library,
        // Settings): content fades into the page colour instead of meeting the pill at a hard edge.
        // It also takes every touch in that strip, so what's faded out under the pill (a grid
        // tile, a settings row) can't be tapped or dragged by a touch that missed the pill.
        if (!useNavRail) Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBarClearance())
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
        )
        if (!useNavRail) FloatingNavBar(
            selectedTab = selectedTab,
            activeDownloadsCount = activeDownloadsCount,
            hasEngineUpdate = anyUpdateAvailable,
            onSelect = onTabSelect,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // Just above the navigation pill (or the bottom edge, beside a rail), on every page.
        androidx.compose.animation.AnimatedVisibility(
            visible = showMiniPlayer,
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBarOnlyClearance() - if (useNavRail) 0.dp else 8.dp)
                .padding(horizontal = 16.dp)
                .widthIn(max = CONTENT_MAX_WIDTH),
        ) {
            com.comfort.app.ui.music.MiniPlayer(onOpen = { showNowPlaying = true })
        }

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
            Box(modifier = Modifier.fillMaxSize().predictiveBackReveal(queueBack).background(MaterialTheme.colorScheme.background)) {
                ConstrainedWidth {
                QueueScreen(
                    viewModel = viewModel,
                    // Same peel-away as the back gesture, not an instant jump back.
                    onBack = { queueBack.animateBack() },
                )
                }
            }
        }

        // Neither engine knows the site: the in-app browser, to find the media on the page.
        (route as? LinkRoute.Browse)?.let { browse ->
            WebBrowser(startUrl = browse.url, onDismiss = { router.close() })
        }

        (route as? LinkRoute.Preview)?.let { preview ->
            DownloadPreviewSheet(
                url = preview.url,
                preloadedPreview = preview.preloaded,
                onDismiss = { router.close() },
                onDownload = { options ->
                    router.download(preview.url, options)
                    router.close()
                },
            )
        }

        // Brief — the listing pass above usually resolves in well under a second — but real
        // enough on a slow/rate-limited site that a bare frozen Download button would otherwise
        // look broken with no feedback at all.
        if (route is LinkRoute.Listing) {
            ModalBottomSheet(onDismissRequest = { router.close() }) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularWavyProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.looking_at_what_s_there),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The router's listing decided this is a real multi-item gallery rather than a
        // single/multi-video case — same SharePickerScreen the share-sheet flow already uses,
        // fed the same already-fetched result so it isn't listed a second time.
        (route as? LinkRoute.Picker)?.let { picker ->
            var sheetHeight by remember { mutableStateOf(400.dp) }
            val animatedHeight by androidx.compose.animation.core.animateDpAsState(targetValue = sheetHeight, label = "sharePickerSheetHeight")
            ModalBottomSheet(
                onDismissRequest = { router.close() },
                dragHandle = null,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(animatedHeight)) {
                    SharePickerScreen(
                        url = picker.url,
                        onDismiss = { router.close() },
                        onDownload = { downloadUrl, itemFilter, totalItems, videoQuality, forceDuplicate ->
                            router.download(downloadUrl, itemFilter, totalItems, videoQuality, forceDuplicate)
                            router.close()
                        },
                        onHeightChange = { sheetHeight = it },
                        preloadedResult = picker.listing,
                        onRetry = { router.open(picker.url) },
                    )
                }
            }
        }
    }
    }
    // The media viewer over everything, the nav rail included (on a tablet it opened beside it).
    mediaViewer?.let { request ->
        com.comfort.app.ui.viewer.MediaViewerHost(request, viewModel, onClose = {
            mediaViewer = null
            // A video with sound paused the music: it plays on once the viewer is gone.
            com.comfort.app.music.MusicPlayer.resumeAfterInterruption()
        })
    }
    // The music player's full screen, over everything.
    androidx.compose.animation.AnimatedVisibility(
        visible = showNowPlaying && music.song != null,
        enter = androidx.compose.animation.slideInVertically { it },
        exit = androidx.compose.animation.slideOutVertically { it },
    ) {
        com.comfort.app.ui.music.NowPlayingScreen(onClose = { showNowPlaying = false })
    }
    }
    }
}

/** Side navigation for wide windows (tablets, phones in landscape) — same three destinations,
 * badges and tap behavior as [FloatingNavBar], which only suits a narrow portrait window. */
@Composable
private fun SideNavRail(
    selectedTab: Int,
    activeDownloadsCount: Int,
    hasEngineUpdate: Boolean,
    onSelect: (Int) -> Unit,
) {
    val activeDownloadsDescription = pluralStringResource(R.plurals.active_downloads_count, activeDownloadsCount, activeDownloadsCount)
    val updatesAvailableDescription = stringResource(R.string.updates_available)
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.weight(1f))
        tabs.forEachIndexed { index, tab ->
            NavigationRailItem(
                selected = selectedTab == index,
                onClick = { onSelect(index) },
                icon = {
                    when {
                        index == 1 && activeDownloadsCount > 0 -> BadgedBox(badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics {
                                    contentDescription = activeDownloadsDescription
                                },
                            ) { Text(badgeCountText(activeDownloadsCount)) }
                        }) { Icon(tab.icon, contentDescription = tab.label) }
                        index == 2 && hasEngineUpdate -> BadgedBox(badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics {
                                    contentDescription = updatesAvailableDescription
                                },
                            )
                        }) { Icon(tab.icon, contentDescription = tab.label) }
                        else -> Icon(tab.icon, contentDescription = tab.label)
                    }
                },
                label = { Text(tab.label) },
            )
        }
        Spacer(Modifier.weight(1f))
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
    val activeDownloadsDescription = pluralStringResource(R.plurals.active_downloads_count, activeDownloadsCount, activeDownloadsCount)
    val updatesAvailableDescription = stringResource(R.string.updates_available)
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
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                            }
                            .clickable(
                                role = Role.Tab,
                                onClickLabel = if (selected) {
                                    if (index == 1) stringResource(R.string.open_queue) else if (index == 2) stringResource(R.string.reset_settings) else null
                                } else stringResource(R.string.switch_to_x, tab.label),
                            ) { onSelect(index) }
                            .padding(horizontal = if (selected) 20.dp else 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val iconModifier = Modifier.size(22.dp)
                        if (index == 1 && activeDownloadsCount > 0) {
                            BadgedBox(badge = {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics {
                                        contentDescription = activeDownloadsDescription
                                    },
                                ) {
                                    Text(badgeCountText(activeDownloadsCount))
                                }
                            }) {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = iconModifier)
                            }
                        } else if (index == 2 && hasEngineUpdate) {
                            BadgedBox(badge = {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics {
                                        contentDescription = updatesAvailableDescription
                                    },
                                )
                            }) {
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
    // Bumped by the "Paste & download" shortcut.
    pasteDownloadSignal: Int = 0,
    // Whether Home is the page on screen. It stays composed under the other pages (they reveal it
    // on back), and its strip's auto-scroll laid it out every frame there too: a page switch ran
    // on top of that the whole time (choppy, measured: ~70 frames a second with nothing moving).
    active: () -> Boolean = { true },
) {
    var url by remember { mutableStateOf("") }
    // The in-app browser (WebBrowser), open at this address; null when closed.
    var browserUrl by remember { mutableStateOf<String?>(null) }
    // YouTube search (YouTubeSearchScreen), open for this query; null when closed.
    var youtubeSearch by remember { mutableStateOf<String?>(null) }
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val router: LinkRouterViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    // Download: starts right away with the default settings — the same thing sharing a link into
    // the "Instant" Sharesheet entry does (ShareActivity's InstantShareHandler), duplicate handling
    // and message included. Configure is the other button.
    fun downloadNow(link: String) {
        router.download(link) { result ->
            android.widget.Toast.makeText(homeContext, startedMessage(homeContext, result), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val homeScope = rememberCoroutineScope()
    suspend fun readClipboardText(): String? {
        val clip = clipboard.getClipEntry() ?: return null
        return if (clip.clipData.itemCount > 0) clip.clipData.getItemAt(0)?.text?.toString()?.trim() else null
    }
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    val queueItems by viewModel.queueFlow.collectAsStateWithLifecycle()
    val historyItems by viewModel.historyFlow.collectAsStateWithLifecycle()
    // Most-recent RUNNING item — a summary screen only ever needs to surface one at a time; the
    // full Queue is one tap away (onOpenQueue) for anything more than that.
    val activeDownload = remember(queueItems) { queueItems.firstOrNull { it.status == DownloadStatus.RUNNING } }
    val recentDownloads = remember(historyItems) { historyItems.filter { !it.thumbnailPath.isNullOrBlank() }.take(10) }

    fun normalizeInputUrl(input: String): String {
        val trimmed = input.trim()
        return if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            trimmed.contains('.') &&
            !trimmed.contains(' ')
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    // "Paste & download" shortcut: download the copied link straight away. Android only lets an
    // app read the clipboard while its window has focus, so this waits for that first.
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(pasteDownloadSignal) {
        if (pasteDownloadSignal == 0) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        val clipText = readClipboardText()
        if (!clipText.isNullOrBlank() && android.util.Patterns.WEB_URL.matcher(clipText).matches()) {
            val target = normalizeInputUrl(clipText)
            ClipboardSuggestionState.lastHandled = target
            clipboardSuggestion = null
            downloadNow(target)
        } else {
            android.widget.Toast.makeText(homeContext, homeContext.getString(R.string.no_link_copied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        val clipText = readClipboardText()
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
            val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            var wordmarkFontSize by remember(screenWidth, fontScale) { mutableStateOf(88.sp) }
            var wordmarkMeasured by remember(screenWidth, fontScale) { mutableStateOf(false) }
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
                        val next = wordmarkFontSize * 0.94f
                        wordmarkFontSize = if (next > 12.sp) next else 12.sp
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
                    // Text that isn't a link is a YouTube search: the buttons below become one
                    // "Search YouTube", and the keyboard's action searches.
                    val isSearch = url.isNotBlank() && !looksLikeLink(url)
                    // The field lets go of focus when a sheet opens over it: still focused under
                    // the sheet, it brought the keyboard back up as the sheet closed.
                    val fieldFocus = androidx.compose.ui.platform.LocalFocusManager.current
                    fun openSearch() {
                        if (!isSearch) return
                        youtubeSearch = url.trim()
                        url = ""
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        // A plain placeholder (no floating label) so the field reads as one
                        // resting pill with "Paste a link" sitting centered inside it, rather than
                        // a caption perched above the field's own border.
                        placeholder = { Text(androidx.compose.ui.res.stringResource(com.comfort.app.R.string.home_link_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                        shape = MaterialTheme.shapes.extraLarge,
                        leadingIcon = {
                            androidx.compose.animation.Crossfade(targetState = isSearch, label = "fieldIcon") { search ->
                                Icon(if (search) Icons.Outlined.Search else Icons.Outlined.Link, contentDescription = null)
                            }
                        },
                        // One action whatever's typed. It used to switch from Done to Search as the
                        // first letter made the text a search, and changing it restarts the
                        // keyboard's connection to the field mid-word: Samsung's keyboard kept
                        // typing into the old one, so after "h" nothing more arrived (reproduced).
                        // Its key searches a search; for a link it just closes the keyboard.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            if (isSearch) openSearch() else defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Done)
                        }),
                        trailingIcon = {
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { url = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_link))
                                }
                            }
                        },
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Turning into a search, Configure slides away while Download widens into
                    // "Search YouTube" (its icon and label crossfading), and back again.
                    val configureShare by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isSearch) 0f else 1f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        ),
                        label = "configureShare",
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (configureShare > 0.001f) {
                            OutlinedButton(
                                onClick = {
                                    if (url.isNotBlank() && !isSearch) {
                                        val target = normalizeInputUrl(url)
                                        fieldFocus.clearFocus()
                                        onConfigure(target)
                                        url = ""
                                    }
                                },
                                modifier = Modifier
                                    .weight(configureShare)
                                    .height(52.dp)
                                    .graphicsLayer { alpha = configureShare },
                                shape = MaterialTheme.shapes.extraLarge,
                                // Not switched off for a search: it greyed out at once, then faded. Its
                                // tap does nothing then (onClick), and it's gone a moment later.
                                enabled = url.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.configure), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                            }
                            Spacer(Modifier.width(12.dp * configureShare))
                        }

                        Button(
                            onClick = {
                                if (isSearch) {
                                    openSearch()
                                } else if (url.isNotBlank()) {
                                    val target = normalizeInputUrl(url)
                                    fieldFocus.clearFocus()
                                    downloadNow(target)
                                    url = ""
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            enabled = url.isNotBlank(),
                        ) {
                            androidx.compose.animation.AnimatedContent(
                                targetState = isSearch,
                                transitionSpec = {
                                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) +
                                        androidx.compose.animation.scaleIn(initialScale = 0.9f, animationSpec = androidx.compose.animation.core.tween(220, delayMillis = 60))) togetherWith
                                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) using
                                        androidx.compose.animation.SizeTransform(clip = false)
                                },
                                label = "primaryAction",
                            ) { search ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (search) Icons.Outlined.Search else Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (search) androidx.compose.ui.res.stringResource(com.comfort.app.R.string.home_search_youtube) else stringResource(R.string.search_download),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Browse: for sites the engines don't know — the browser finds the media a page loads
            // and downloads it from there. A link in the box above opens in it; otherwise search.
            Surface(
                onClick = {
                    val raw = url.trim()
                    browserUrl = if (raw.isBlank()) "https://www.google.com" else normalizeInputUrl(raw)
                    url = ""
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.TravelExplore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (url.isNotBlank() && looksLikeLink(url)) stringResource(R.string.open_this_link_in_the_browser) else stringResource(R.string.browse_the_web),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.find_videos_and_pictures_on_any),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            browserUrl?.let { start ->
                WebBrowser(startUrl = start, onDismiss = { browserUrl = null })
            }
            youtubeSearch?.let { query ->
                YouTubeSearchScreen(
                    initialQuery = query,
                    onDismiss = { youtubeSearch = null },
                    onConfigure = onConfigure,
                    onDownload = { downloadNow(it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.supported_sources),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // All four in primary: alternating primary/secondary looked mismatched in themes whose
            // secondary is a near-grey (reported live: Pixiv and "+ hundreds more" went grey).
            val sources = listOf(
                Triple("Twitter / X", FeatherIcons.Twitter, MaterialTheme.colorScheme.primary),
                Triple("Pixiv", Icons.Outlined.Image, MaterialTheme.colorScheme.primary),
                Triple("Instagram", FeatherIcons.Instagram, MaterialTheme.colorScheme.primary),
                Triple(stringResource(R.string.hundreds_more), Icons.Outlined.Public, MaterialTheme.colorScheme.primary),
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

            // Downloads held for a connection: WorkManager won't start them without one, and with
            // nothing running Home otherwise looked idle while the Queue had work in it.
            val networkOk = com.comfort.app.util.rememberIsNetworkAvailable()
            val waitingCount = remember(queueItems) { queueItems.count { it.status == DownloadStatus.QUEUED } }
            if (activeDownload == null && waitingCount > 0 && !networkOk) {
                Spacer(Modifier.height(24.dp))
                Surface(
                    onClick = onOpenQueue,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.waiting_for_a_network_connection),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                networkWaitSubtitle(homeContext, waitingCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            if (recentDownloads.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.recently_downloaded),
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
                val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
                val homeWindowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                // One whole pixel per step at ~25 dp a second (the old 1.1 px every 16 ms on a
                // typical screen): a fraction of a pixel moved nothing on screen but still cost a
                // frame.
                val driftStepMs = with(androidx.compose.ui.platform.LocalDensity.current) { (1000f / 25.dp.toPx()).toLong().coerceAtLeast(8L) }
                LaunchedEffect(recentDownloads.size, reducedMotion, homeWindowFocused) {
                    if (recentDownloads.size <= 1 || reducedMotion || !homeWindowFocused) return@LaunchedEffect
                    // Only while Home is on screen and the app is in front (see `active`).
                    androidx.compose.runtime.snapshotFlow { active() }.collectLatest { onScreen ->
                    if (!onScreen) return@collectLatest
                    kotlinx.coroutines.delay(3500)
                    // One pass to the end and back, then it stays still: every step is a frame, and
                    // drifting for as long as Home was open kept the phone from ever idling there
                    // (~100 redraws a second, traced). Coming back to Home, or a new download,
                    // starts it again.
                    while (true) {
                        if (isRecentListDragged) {
                            kotlinx.coroutines.delay(200)
                            continue
                        }
                        if (!recentListState.canScrollForward) break
                        recentListState.scrollBy(1f)
                        kotlinx.coroutines.delay(driftStepMs)
                    }
                    kotlinx.coroutines.delay(1800)
                    recentListState.animateScrollToItem(0)
                    }
                }
                // Comfort: a tap opens the item in the media viewer, paging through the strip.
                val openViewer = com.comfort.app.ui.viewer.LocalOpenMediaViewer.current
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
                        RecentDownloadThumbnail(
                            item = item,
                            onClick = {
                                val viewable = recentDownloads.filter { it.status != com.comfort.app.data.DownloadStatus.DELETED }
                                if (item in viewable) {
                                    openViewer(com.comfort.app.ui.viewer.MediaViewerRequest(viewable, item.id))
                                } else {
                                    onOpenLibrary()
                                }
                            },
                        )
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
                val suggested = clipboardSuggestion
                if (!suggested.isNullOrBlank()) {
                    url = suggested
                    ClipboardSuggestionState.lastHandled = suggested
                    clipboardSuggestion = null
                } else {
                    homeScope.launch {
                        val clipText = readClipboardText()
                        if (clipText.isNullOrBlank()) {
                            android.widget.Toast.makeText(homeContext, homeContext.getString(R.string.nothing_to_paste), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            url = clipText
                            ClipboardSuggestionState.lastHandled = clipText
                            clipboardSuggestion = null
                        }
                    }
                }
            },
            icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
            text = { Text(if (clipboardSuggestion != null) stringResource(R.string.paste_copied_link) else stringResource(R.string.paste)) },
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
    val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
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
                    // Same wavy bar as the Queue card (QueueItemCard: tween-smoothed level, explicit
                    // waveSpeed so it keeps moving while the level sits still, flat near 100%).
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(durationMillis = 450),
                        label = "homeDownloadProgress",
                    )
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        // surfaceContainerHighest, not the older surfaceVariant token — same
                        // finding as the Queue screen's own progress track.
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        amplitude = { p -> if (reducedMotion || p > 0.9f) 0f else 0.4f },
                        wavelength = 32.dp,
                        waveSpeed = 8.dp,
                    )
                } else {
                    Text(
                        stringResource(R.string.downloading_2),
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
                    val note = com.comfort.app.data.DownloadNotes.notes.collectAsState().value[item.id]
                    if (note != null) {
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    } else if (item.speedMbs > 0f) {
                        Text(
                            com.comfort.app.util.LocalFormat.measure(item.speedMbs.toDouble(), android.icu.util.MeasureUnit.MEGABYTE, 2, perSecond = true),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.open),
                onClick = onClick,
            ),
    ) {
        // A finished download's file (a song's cover): the Library's own thumbnail.
        LibraryThumbnail(item, Modifier.fillMaxSize())
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
    R.string.long_press_a_card_in_queue to Icons.Outlined.CheckBox,
    R.string.yt_dlp_gallery_dl_and_instaloader to Icons.Outlined.Refresh,
    R.string.add_cookies_from_settings_to_unlock to Icons.Outlined.Lock,
    R.string.tap_a_queued_download_s_up to Icons.Outlined.Bolt,
    R.string.choose_mp4_or_mkv_output_and to Icons.Outlined.Settings,
)

@Composable
private fun HomeTipsCarousel() {
    var index by remember { mutableIntStateOf(0) }
    val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(reducedMotion, windowInfo.isWindowFocused) {
        if (reducedMotion || !windowInfo.isWindowFocused) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(6000)
            index = (index + 1) % HOME_TIPS.size
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.next_tip),
                role = androidx.compose.ui.semantics.Role.Button,
            ) {
                index = (index + 1) % HOME_TIPS.size
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                if (reducedMotion) {
                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                } else {
                    (slideInVertically { it / 3 } + androidx.compose.animation.fadeIn(tween(300))) togetherWith
                        (slideOutVertically { -it / 3 } + androidx.compose.animation.fadeOut(tween(300)))
                }
            },
            label = "homeTip",
        ) { i ->
            val (tipRes, icon) = HOME_TIPS[i]
            val tip = stringResource(tipRes)
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

/** Whether Home's field holds a link (opened as a download) rather than words to search YouTube
 * for: a web address, with or without its https://. */
private fun looksLikeLink(text: String): Boolean {
    val t = text.trim()
    if (t.contains("://")) return true
    if (t.contains(' ')) return false
    return android.util.Patterns.WEB_URL.matcher(t).matches() && t.contains('.')
}
