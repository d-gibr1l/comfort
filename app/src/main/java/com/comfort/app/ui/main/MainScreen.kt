package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.viewmodel.LinkRouterViewModel
import com.comfort.app.viewmodel.LinkRoute
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.comfort.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.launch
import compose.icons.feathericons.*
import com.comfort.app.viewmodel.DownloadsViewModel

// The Home wordmark's display face — a purchased/downloaded font, not one of Google Fonts'
// downloadable-at-runtime families, so it ships as a bundled resource like any other static asset.
internal val CrystalRadioKit = FontFamily(Font(R.font.crystal_radio_kit))

internal data class NavTab(
    @androidx.annotation.StringRes val labelRes: Int,
    @androidx.annotation.DrawableRes val iconRes: Int,
    @androidx.annotation.DrawableRes val selectedIconRes: Int,
) {
    val label: String @Composable get() = stringResource(labelRes)
    val icon: androidx.compose.ui.graphics.vector.ImageVector @Composable get() = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = iconRes)
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector @Composable get() = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = selectedIconRes)
}

// Material Symbols (res/drawable/ic_nav_*): Home, Photo Library and Settings Heart, outlined, and
// filled for the open tab.
internal val tabs = listOf(
    NavTab(R.string.nav_home, R.drawable.ic_nav_home, R.drawable.ic_nav_home_filled),
    NavTab(R.string.nav_library, R.drawable.ic_nav_photo_library, R.drawable.ic_nav_photo_library_filled),
    NavTab(R.string.nav_settings, R.drawable.ic_nav_settings_heart, R.drawable.ic_nav_settings_heart_filled),
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

/** A notification or intent request to open a downloaded item in Comfort; [seq] makes repeats re-trigger. */
data class OpenDownloadRequest(val downloadId: String, val seq: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DownloadsViewModel = viewModel(),
    openQueueSignal: Int = 0,
    shortcut: ShortcutRequest? = null,
    openDownload: OpenDownloadRequest? = null,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showQueueScreen by remember { mutableStateOf(false) }
    // The app's one snackbar host and its rules (AppSnackbars), with this screen's scope so a
    // delete's Undo outlives the screen it came from. Drawn in exactly one place at a time (below).
    val snackbarScope = rememberCoroutineScope()
    val appSnackbars = remember { AppSnackbars(SnackbarHostState(), snackbarScope) }
    val librarySnackbarHostState = appSnackbars.host
    // The media viewer's deletes (see MediaViewerHost).
    val viewerDelete = rememberUndoableDelete(viewModel, snackbars = appSnackbars)
    val viewerDeleteFromPhone = rememberUndoableDelete(viewModel, fromPhone = true, snackbars = appSnackbars)
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
    // Bumped by tapping the Settings tab on a sub-page: MoreScreen plays its back animation to
    // the main list (the same as the page's back button) rather than jumping there.
    var settingsBackSignal by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        updates.runLaunchChecks()
    }
    // What those checks found: a new Comfort, or engine updates waiting with auto-update off.
    UpdatePromptDialog(updates)
    // "Delete from phone" on gallery files Android won't let the app delete on its own (made
    // before a reinstall, say): the system's own dialog asks the user (DownloadsViewModel.deleteConsent).
    val deleteConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) {}
    LaunchedEffect(viewModel) {
        viewModel.deleteConsent.collect { sender ->
            runCatching { deleteConsentLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
        }
    }

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
    var keepLibrary by remember { mutableStateOf(false) }
    val shouldKeepLibrary = keepLibrary && activeDownloadsCount == 0
    LaunchedEffect(Unit) {
        // Cold start displays Home instantly; then, while idle, Settings at 1s and Library at
        // 1.2s. Settings was built at 4s, so a tap on it right after launch built it on the spot
        // (55-70 ms frames), and its sub-pages then found none of the rows and cards they share
        // with it ready either.
        kotlinx.coroutines.delay(1_000)
        keepSettings = true
        kotlinx.coroutines.delay(200)
        keepLibrary = true
    }
    // The Library's scroll position, held here: the Library itself is rebuilt each visit while
    // something's downloading (above), and its place in the list went with it.
    val libraryListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val libraryGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var preparingTab by remember { mutableStateOf<Int?>(null) }
    val tabScope = rememberCoroutineScope()
    fun enterTab(index: Int, enter: () -> Unit) {
        if (preparingTab != null) return
        if ((index == 2 && keepSettings) || (index == 1 && shouldKeepLibrary)) { enter(); return }
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
    // Settings › Look & feel › Swipe between pages.
    val swipePages by com.comfort.app.data.GestureSettings.swipeBetweenPages.collectAsState()
    val pageSwipe = rememberPageSwipeState(
        current = selectedTab,
        // Nor while a back or an open moves the pages: a swipe took them over mid-way (a jump),
        // and the back's selectedTab = 0 then landed under it.
        allowed = swipePages && !showQueueScreen && outgoingTab == null && preparingTab == null && !tabBack.busy && !queueBack.busy,
        onSettled = { selectedTab = it },
    )
    // Home as the nav bar's open tab from the moment it's tapped: selectedTab only becomes 0 once
    // the page before it has faded out (~150 ms), and the bar waited for it, showing only the
    // tap's ripple. The other tabs open in two frames (enterTab) — moved at the tap, the highlight
    // ran through those frames' page building and jumped.
    var navTab by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedTab) { navTab = null }
    val onTabSelect: (Int) -> Unit = { index ->
        if (index == 0 && pageSwipe.neighbor == null && selectedTab != 0) navTab = 0
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
                if (settingsRoute != SettingsRoute.ROOT) settingsBackSignal++
                settingsHighlightKey = null
            }
            index == selectedTab -> Unit
            // Home is the page every other tab sits on: going there is a back.
            // A plain crossfade between tabs, no slide.
            index == 0 -> tabBack.animateBack(slide = false)
            // Another tab opens with the push, the page it covers sliding away behind it:
            // Home, or when switching between two tabs, the tab being left (outgoingTab).
            selectedTab == 0 -> enterTab(index) { tabBack.animateEnter(slide = false) { selectedTab = index } }
            else -> {
                val leaving = selectedTab
                enterTab(index) {
                    tabBack.animateEnter(slide = false, onFinished = { outgoingTab = null }) {
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
    // The player fully up, settled and not being dragged: it covers everything, so what's under it
    // isn't drawn. It was, every frame, the Library's list and all (~a third of each frame's
    // drawing time on a Galaxy A22).
    var playerSettled by remember { mutableStateOf(false) }
    var playerDragged by remember { mutableStateOf(false) }
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
    // Reopened after the app was stopped, the last song comes back paused: the mini player waits on
    // the Library page (where the music is) rather than sitting on every page, until something plays.
    val playedThisRun by com.comfort.app.music.MusicPlayer.playedThisRun.collectAsState()
    val showMiniPlayer = music.song != null && !showNowPlaying && mediaViewer == null &&
        (playedThisRun || ((selectedTab == 1 || outgoingTab == 1) && !showQueueScreen))
    // Shown or hidden by a change of page (the Library-only case above): it fades with the
    // Library page itself, and only the Library keeps room for it. Sliding it down over the navigation pill while the
    // space under every page shrank frame by frame, during the page change itself, stuttered.
    val miniPlayerByPage = !playedThisRun

    // Completed download notification tap or "Open" action button: open in Comfort.
    LaunchedEffect(openDownload) {
        val request = openDownload ?: return@LaunchedEffect
        val targetId = request.downloadId
        showQueueScreen = false

        val history = viewModel.historyFlow.value
        val entity = history.firstOrNull { it.id == targetId }
            ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.comfort.app.data.AppDatabase.getDatabase(context).downloadDao().getById(targetId)
            }

        if (entity != null) {
            selectedTab = 1
            if (com.comfort.app.data.PlayerChoice.openExternallyIfChosen(context, entity)) {
                // Settings › Look & feel › Players: opened in the phone's player.
            } else if (entity.isAudio) {
                mediaViewer = null
                val audioList = if (history.any { it.id == entity.id } && !com.comfort.app.music.MusicSettings.playTappedOnly.current) {
                    history.filter { it.isAudio }
                } else {
                    listOf(entity)
                }
                com.comfort.app.music.MusicPlayer.play(context, audioList, entity.id)
                showNowPlaying = true
            } else {
                showNowPlaying = false
                val viewableList = if (history.any { it.id == entity.id }) {
                    history.filter { !it.isAudio }
                } else {
                    listOf(entity)
                }
                mediaViewer = com.comfort.app.ui.viewer.MediaViewerRequest(
                    downloads = viewableList,
                    startId = entity.id,
                )
            }
        } else {
            selectedTab = 1
        }
    }
    CompositionLocalProvider(
        LocalUseNavRail provides useNavRail,
        LocalAppSnackbars provides appSnackbars,
        // A song plays in the music player (its neighbours in the list as the queue); anything
        // else opens the media viewer, which pages through the pictures and videos only.
        com.comfort.app.ui.viewer.LocalOpenMediaViewer provides { request ->
            // startId can be one file of a download ("id#2", from its items sheet): that download.
            val tapped = request.downloads.firstOrNull { it.id == request.startId }
                ?: request.downloads.firstOrNull { request.startId.startsWith(it.id + "#") }
            // A comic (a chapter saved as .cbz): the comic reader.
            val comicUri = tapped?.mediaUri?.let(android.net.Uri::parse)?.takeIf { uri ->
                com.comfort.app.data.ComicSites.isComicFile(uri.lastPathSegment, runCatching { context.contentResolver.getType(uri) }.getOrNull())
            }
            if (tapped != null && comicUri != null) {
                com.comfort.app.ui.comic.ComicReaderRequest.request = com.comfort.app.ui.comic.ComicRequest(comicUri, tapped.title.ifBlank { context.getString(R.string.comic) })
            } else if (tapped != null && com.comfort.app.data.PlayerChoice.openExternallyIfChosen(context, tapped)) {
                // Opened there.
            } else if (tapped?.isAudio == true) {
                // Music settings: Play only the tapped song.
                val queue = if (com.comfort.app.music.MusicSettings.playTappedOnly.current) listOf(tapped) else request.downloads
                // From the song's own id when it's one song of a playlist (MusicPlayer's songsFor).
                com.comfort.app.music.MusicPlayer.play(context, queue, request.startId)
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
            animationSpec = if (miniPlayerByPage) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(visibilityThreshold = 0.5.dp),
            label = "miniPlayerInset",
        ).value,
    ) {
    // "Finished" and "failed" for downloads, on whichever page is open (they were only seen with
    // the Queue open), but not over the media viewer: held until it closes.
    run {
        val eventQueue by viewModel.queueFlow.collectAsStateWithLifecycle()
        val eventHistory by viewModel.historyFlow.collectAsStateWithLifecycle()
        DownloadEventSnackbars(historyItems = eventHistory, queueItems = eventQueue, holdWhile = { mediaViewer != null })
    }
    Box(modifier = Modifier.fillMaxSize()) {
    // No background of its own: MainActivity's Surface paints it, and every page paints its own
    // over that — a third full-screen fill under each frame for nothing.
    Row(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (playerSettled && !playerDragged) 0f else 1f }) {
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
            // Remembered, so Home doesn't see new lambdas on every MainScreen recomposition.
            val onConfigureUrl = remember(router) { { url: String -> router.open(url) } }
            val onOpenLibraryTab = remember { { selectedTab = 1 } }
            // Library-only mini player: Home keeps no room for it (its Paste button sat raised
            // until a page change from the Library had ended, then dropped).
            CompositionLocalProvider(LocalMiniPlayerInset provides if (miniPlayerByPage) 0.dp else LocalMiniPlayerInset.current) {
            ConstrainedWidth {
            HomeScreen(
                onConfigure = onConfigureUrl,
                viewModel = viewModel,
                onOpenLibrary = onOpenLibraryTab,
                onOpenQueue = openQueue,
                pasteDownloadSignal = pasteDownloadSignal,
                // A lambda, read only by the strip's drift: as a plain value, leaving Home
                // recomposed all of it (6.4 ms of the Library's opening frame, traced).
                active = { (selectedTab == 0 || pageSwipe.involves(0)) && !showQueueScreen && mediaViewer == null && !showNowPlaying },
            )
            }
            }
        }

        // Only the non-Home tabs, on top of the Home above (see its comment) — plus, during a
        // tab-to-tab switch, the tab being left, underneath the new one. Keyed per tab so the
        // outgoing tab keeps its composed instance (rebuilding it would stall the frame).
        val shownTabs = listOfNotNull(outgoingTab, selectedTab.takeIf { it != 0 }).distinct()
        // The page a swipe is bringing in is built as the swipe starts, if it wasn't already.
        val keptTabs = (
            shownTabs + listOfNotNull(2.takeIf { keepSettings }, 1.takeIf { shouldKeepLibrary }, preparingTab, pageSwipe.neighbor?.takeIf { it != 0 })
        ).distinct()
        val pageColor = MaterialTheme.colorScheme.background
        for (tab in keptTabs) key(tab) {
            Box(
                // Opaque so a centered, width-capped page (Home/Settings on a wide window) doesn't
                // show whatever's underneath in the gutters either side of it.
                modifier = if (pageSwipe.involves(tab)) {
                    // Moved by a swipe: its background moves with it (inside the layer), or it
                    // would stay put and cover the page coming in beside it.
                    // Side by side with the other page, never over it: only the activity's Surface
                    // is under it, the same colour, unless a tab switch is still underneath.
                    Modifier.fillMaxSize().pageSwipeLayer(pageSwipe, tab, selectedTab).pageBackground(pageColor) { outgoingTab != null }
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
                        // Only while something's under it — the tab being left during a switch, or
                        // Home during a back gesture. At rest, under it is the activity's Surface in
                        // the same colour (Home's alpha is 0), and painting it again cost every
                        // frame a full-screen fill (TODO: Library opening, the rest).
                        // Inside predictiveBackReveal's layer, so it fades and slides with the
                        // page: outside it, the fill was opaque from the first frame of an opening
                        // while the page faded in, hiding Home under a blank screen for ~9 frames
                        // (a flicker on every tap of Library or Settings, recorded on the phone).
                        .predictiveBackReveal(tabBack)
                        .pageBackground(pageColor) { outgoingTab != null || tabBack.revealing }
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
                        openSearchSignal = librarySearchSignal,
                        isVisible = tab == selectedTab || tab == outgoingTab,
                    )
                    2 -> CompositionLocalProvider(LocalMiniPlayerInset provides if (miniPlayerByPage) 0.dp else LocalMiniPlayerInset.current) { ConstrainedWidth {
                    val onNavigateSettings = remember { { route: SettingsRoute, key: String? -> settingsRoute = route; settingsHighlightKey = key } }
                    MoreScreen(
                        route = settingsRoute,
                        highlightKey = settingsHighlightKey,
                        onNavigate = onNavigateSettings,
                        isVisible = selectedTab == 2,
                        backSignal = settingsBackSignal,
                    )
                    } }
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
            selectedTab = navTab ?: selectedTab,
            activeDownloadsCount = activeDownloadsCount,
            hasEngineUpdate = anyUpdateAvailable,
            onSelect = onTabSelect,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // Just above the navigation pill (or the bottom edge, beside a rail), on every page.
        androidx.compose.animation.AnimatedVisibility(
            visible = showMiniPlayer,
            // Shown by the page: it fades in with the page itself (the alpha below), nothing of its own.
            enter = if (miniPlayerByPage) androidx.compose.animation.EnterTransition.None
                else androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            // Gone at once: it has already faded with the page (the alpha below). A fade of its own,
            // started as the page change ended, brought it back at full for a moment first.
            exit = if (miniPlayerByPage) androidx.compose.animation.ExitTransition.None
                else androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBarOnlyClearance() - if (useNavRail) 0.dp else 8.dp)
                .padding(horizontal = 16.dp)
                .widthIn(max = CONTENT_MAX_WIDTH)
                // Library-only (miniPlayerByPage): it fades with the Library page, frame for frame, as
                // Home is opened or a back swipe drags it — it stayed up over Home for ~200 ms before,
                // since the page counts as left only once that transition is over.
                // Read as it's drawn: in the frame the page change lands, it's no longer the Library
                // and draws nothing, though it's only removed in the next frame (it flashed back).
                // Leaving the Library for another tab (Settings): that page fades in over it, so this
                // fades out as it does, rather than going before the Library has.
                .graphicsLayer {
                    if (miniPlayerByPage) alpha = when {
                        selectedTab == 1 -> tabBack.frontAlpha.value
                        outgoingTab == 1 -> 1f - tabBack.frontAlpha.value
                        else -> 0f
                    }
                },
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
        // Only here when nothing covers it: the Queue draws this same host in its own Scaffold, and
        // the media viewer over itself — two live copies showed every snackbar twice (TalkBack
        // read each one twice).
        if (!showQueueScreen && mediaViewer == null) {
            DownloadEventSnackbarHost(
                librarySnackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBarClearance()),
            )
        }
        // Engine updates (tapped in Settings, or automatic at launch), on any tab: "Updating yt-dlp
        // and gallery-dl…" while they run, then one summary in its place once the last has ended —
        // two engines updating at once gave two messages, one replacing the other.
        val updatingNames by updates.updatingEngineNames.collectAsState()
        val updatingText = if (updatingNames.isEmpty()) null else stringResource(R.string.updating_engines_x, joinNames(updatingNames))
        LaunchedEffect(updatingText) {
            val text = updatingText ?: return@LaunchedEffect
            appSnackbars.showEvent("engines") {
                librarySnackbarHostState.showSnackbar(DownloadEventVisuals(isSuccess = true, message = text, inProgress = true))
            }
        }
        val engineUpdatedOne = stringResource(R.string.engine_updated_to)
        val enginesUpdatedMany = stringResource(R.string.engines_updated_x)
        val enginesFailed = stringResource(R.string.couldn_t_update_engines_x)
        LaunchedEffect(updates) {
            updates.engineUpdated.collect { summary ->
                val lines = buildList {
                    when (summary.updated.size) {
                        0 -> {}
                        1 -> add(engineUpdatedOne.format(summary.updated[0].first, summary.updated[0].second))
                        else -> add(enginesUpdatedMany.format(joinNames(summary.updated.map { it.first })))
                    }
                    if (summary.failed.isNotEmpty()) add(enginesFailed.format(joinNames(summary.failed)))
                }
                appSnackbars.showEvent("engines") {
                    librarySnackbarHostState.showSnackbar(DownloadEventVisuals(isSuccess = summary.failed.isEmpty(), message = lines.joinToString("\n")))
                }
            }
        }

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

        // A multi-item card's items (its stack button, in the Library or the Queue), over both.
        BatchViewHost(viewModel.historyFlow.collectAsStateWithLifecycle().value)

        // A blocked site's link: said so, and nothing opens.
        (route as? LinkRoute.Blocked)?.let { blocked ->
            val message = stringResource(R.string.site_x_is_blocked, blocked.site)
            LaunchedEffect(blocked) {
                router.close()
                appSnackbars.showEvent("blocked") {
                    librarySnackbarHostState.showSnackbar(DownloadEventVisuals(isSuccess = false, message = message))
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
            // As sturdy as the download sheet: a short or slow pull springs back.
            ModalBottomSheet(onDismissRequest = { router.close() }, sheetState = rememberSturdySheetState()) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Material 3 Expressive's shape-morphing indicator.
                    androidx.compose.material3.LoadingIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
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
                // As hard to swipe away as the download sheet (the default closed on a short pull).
                sheetState = rememberSturdySheetState(),
                dragHandle = null,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(animatedHeight)) {
                    SharePickerScreen(
                        url = picker.url,
                        onDismiss = { router.close() },
                        onDownload = { downloadUrl, itemFilter, totalItems, videoQuality, forceDuplicate, startAt ->
                            router.download(downloadUrl, itemFilter, totalItems, videoQuality, forceDuplicate, scheduledAt = startAt)
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
        val viewerBottomBar = remember { mutableStateOf(0.dp) }
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(com.comfort.app.ui.viewer.LocalViewerBottomBarHeight provides viewerBottomBar) {
            // A new request (a download's notification tapped while the viewer was already up)
            // builds it afresh: kept, its pager stayed on the item it had, and the video playing
            // before went on playing (reported).
            androidx.compose.runtime.key(request) {
            com.comfort.app.ui.viewer.MediaViewerHost(
                request, viewModel,
                requestDelete = viewerDelete,
                requestDeleteFromPhone = viewerDeleteFromPhone,
                onClose = {
                    mediaViewer = null
                    // A video with sound paused the music: it plays on once the viewer is gone.
                    com.comfort.app.music.MusicPlayer.resumeAfterInterruption()
                },
                // Re-download: the viewer closes and the link's configure sheet opens.
                onRedownload = { url ->
                    mediaViewer = null
                    router.open(url)
                },
            )
            }
            }
            // The app's snackbars over the viewer, just above its bottom controls (the viewer
            // measures them: the toolbar, plus the filmstrip when it shows). The host under the pages
            // isn't drawn meanwhile.
            DownloadEventSnackbarHost(
                librarySnackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = viewerBottomBar.value.coerceAtLeast(0.dp))
                    .then(if (viewerBottomBar.value == 0.dp) Modifier.navigationBarsPadding() else Modifier),
            )
        }
    }
    // The music player's full screen, over everything.
    androidx.compose.animation.AnimatedVisibility(
        visible = showNowPlaying && music.song != null,
        // Held off two frames: the first builds the whole player (~15-30 ms on the phone), and a
        // slide already running then jumped ahead by that much — the stutter on opening. Started
        // after it, the slide runs from rest. Material's emphasized-decelerate curve, near the spring
        // it replaces.
        enter = androidx.compose.animation.slideInVertically(
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 400,
                delayMillis = 32,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),
            ),
        ) { it },
        exit = androidx.compose.animation.slideOutVertically { it },
    ) {
        val settled = transition.currentState == androidx.compose.animation.EnterExitState.Visible &&
            transition.targetState == androidx.compose.animation.EnterExitState.Visible
        SideEffect { playerSettled = settled }
        DisposableEffect(Unit) { onDispose { playerSettled = false; playerDragged = false } }
        com.comfort.app.ui.music.NowPlayingScreen(
            onClose = { showNowPlaying = false },
            showing = transition.targetState == androidx.compose.animation.EnterExitState.Visible,
            onDragged = { playerDragged = it },
        )
    }
    }
    }
}

/** A page's background, painted only while [needed] says something else is under it. Read in the
 * draw phase, so the modifier chain stays the same (nothing recomposes) as it turns on and off. */
private fun Modifier.pageBackground(color: Color, needed: () -> Boolean): Modifier =
    drawBehind { if (needed()) drawRect(color) }

/** "yt-dlp", "yt-dlp and gallery-dl", "yt-dlp, gallery-dl and Instaloader", in the phone's
 * language where Android can say it (8.0+). */
internal fun joinNames(names: List<String>): String =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) android.icu.text.ListFormatter.getInstance().format(names)
    else names.joinToString(", ")
