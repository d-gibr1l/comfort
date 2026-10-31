package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.comfort.app.R
import androidx.compose.foundation.gestures.animateScrollBy

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import com.comfort.app.theme.HeaderFontFamily
import com.comfort.app.util.rememberIsReducedMotionEnabled
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.comfort.app.util.thumbnailModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import com.comfort.app.viewmodel.DownloadsViewModel
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class LibrarySort(@androidx.annotation.StringRes val label: Int) {
    DATE_NEWEST(R.string.sort_newest_first),
    DATE_OLDEST(R.string.sort_oldest_first),
    NAME(R.string.sort_name),
    SIZE(R.string.sort_size),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsHistoryScreen(
    viewModel: DownloadsViewModel,
    onOpenQueue: () -> Unit,
    isQueueOpen: Boolean = false,
    // Bumped by the "Search Library" shortcut: opens the search, keyboard up.
    openSearchSignal: Int = 0,
    // Hoisted up to MainScreen's own outer Box instead of a plain remember { SnackbarHostState() }
    // here — this screen's own Scaffold draws *before* FloatingNavBar in that Box (it's one of the
    // three tab contents, composed ahead of the persistent floating pill), so a SnackbarHost
    // rendered through this screen's own Scaffold silently drew underneath the pill instead of
    // over it. Reported live: the "Download removed" Undo snackbar peeked out from behind the nav
    // bar's rounded ends instead of sitting above it. MainScreen now owns this SnackbarHost and
    // renders it after FloatingNavBar, so this screen only needs the state to trigger it with.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    // False while MainScreen keeps this screen built but hidden (so opening it has nothing to
    // build). Hidden, it's put back the way a fresh visit starts — no filter, selection or search,
    // newest first, at the top — and its Back handlers stay off, so Back on Home is Home's.
    isVisible: Boolean = true,
    // Held by MainScreen, so the place it was scrolled to survives the screen being rebuilt
    // (it isn't kept between visits while something's downloading).
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
) {
    // Library's own topic icon in its header — same role Icons.Outlined.SettingsApplications
    // plays in the Settings root header (see SettingsScreen.kt's SettingsRootScreen).
    val libraryTopicIcon = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_gallery_thumbnail)
    val historyItems by viewModel.historyFlow.collectAsStateWithLifecycle()
    val deletedItems by viewModel.deletedFlow.collectAsStateWithLifecycle()
    val hasActiveDownloads by viewModel.hasActiveDownloads.collectAsStateWithLifecycle()
    val activeDownloadsCount by viewModel.activeDownloadsCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Hoisted here, not inside each card's own `remember(item.id)` below (shared by both the grid
    // and list layouts — same ids either way) — a Lazy layout destroys and recreates an item's
    // composable as it scrolls off-screen and back on, which reset a purely-local remember back to
    // its initial "not yet animated" state every time. Reproduced live: the whole visible list kept
    // re-playing its slide-up entrance animation on every scroll up/down, not just once when an
    // item was genuinely new. This map, owned by the screen instead of the item, remembers which
    // ids have already played their entrance once and never resets — Modifier.animateItem() below
    // already handles the smooth reflow/removal animation on its own, so this only ever needs to
    // gate the one-time entrance.
    val alreadyAnimatedIds = remember { mutableSetOf<String>() }
    MarkShownAfterFirstFrame(alreadyAnimatedIds, historyItems, deletedItems) { it.id }
    // better-interface review: the entrance slide below animated unconditionally, with nothing
    // checking the OS-level reduce-motion setting — same finding already fixed on QueueScreen.kt.
    val reducedMotion = rememberIsReducedMotionEnabled()
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var showDeletedOnly by remember { mutableStateOf(false) }
    var showDuplicatesOnly by remember { mutableStateOf(false) }
    var audioOnly by remember { mutableStateOf(false) }
    val duplicateAttempts by viewModel.duplicateAttemptsFlow.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    // Toggles the header's title row into the search field, same crossfade-in-place behavior as
    // the Settings root header's own search icon (see SettingsScreen.kt's SettingsRootScreen) — this
    // replaces the old always-visible PillSearchBar that used to sit permanently below the title.
    var searchExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(openSearchSignal) { if (openSearchSignal > 0) searchExpanded = true }
    var sortOption by remember { mutableStateOf(LibrarySort.DATE_NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(GalleryDlPreferences.isLibraryGridView(context)) }
    // Tile size, changed by pinching the grid.
    var gridSize by remember { mutableStateOf(GalleryDlPreferences.getLibraryGridSize(context)) }
    val selectionMode = selectedIds.isNotEmpty()

    // Every delete on this screen (bulk, one item's "Remove", swipe-to-dismiss) is undoable —
    // see rememberUndoableDelete.
    val requestDelete = rememberUndoableDelete(viewModel, snackbarHostState)

    // MainScreen keeps this screen composed underneath the Download Queue overlay now (needed for
    // the predictive-back reveal animation), where it used to fully unmount and remount — which
    // reset scroll position back to the top as a side effect. A freshly-finished download lands at
    // the top of the newest-first list, so without this, coming back from Queue silently leaves
    // you scrolled wherever you were before, with the new item off-screen above.
    val scrollTopScope = rememberCoroutineScope()
    // Only when the Queue actually closes, not each time this screen is built (it ran then too,
    // so coming back to the Library always landed at the top).
    var queueWasOpen by remember { mutableStateOf(isQueueOpen) }
    LaunchedEffect(isQueueOpen) {
        if (queueWasOpen && !isQueueOpen) {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
        queueWasOpen = isQueueOpen
    }

    // Every earlier version here — an alpha-crossfaded pair, a single continuously-morphing
    // overlay whose sub-elements (subtitle, chip row) shrank away via a scroll-derived
    // collapseFraction, first driven by list index/offset math and then ported to match Settings'
    // own ScrollState-based rememberCollapsingHeaderState — was reported live, repeatedly, as
    // still not "one component": the header and the content beneath it always remained two
    // separate things reacting to scroll position, no matter how precisely that reaction was
    // tuned, because a *collapse* (sub-elements independently shrinking to 0 height at their own
    // rate) is not the same motion as *scrolling* (the whole block translating together, rigidly,
    // at the exact same rate as every row below it). The fix is the header is now genuine list
    // content — the very first item in the LazyColumn/LazyVerticalGrid below, at its own fixed
    // natural size, nothing shrinking, nothing separately pinned or crossfaded on top of it. It
    // moves exactly like any other row, because from the list's own point of view it is one.
    //
    // A separate compact bar (icon + title + the 3 action icons, no subtitle/search/chip row)
    // pins to the top once the real header has scrolled completely out of view — gated on
    // `firstVisibleItemIndex > 0` alone, deliberately NOT also on `firstVisibleItemScrollOffset`
    // while still at index 0: that offset-based version (an earlier attempt this session) showed
    // the compact bar fully opaque while the header — a fixed, non-shrinking block now, not
    // something that fades or shrinks away — was still partially on screen underneath it,
    // reproduced live as a literal double "Library" title. Index alone is exact and binary: index
    // is 0 for as long as ANY part of the header is still visible (so the compact bar stays
    // unmounted the entire time), and only becomes 1 once the header has fully scrolled past —
    // the exact instant nothing of it remains on screen for the compact bar to ever overlap.
    val density = LocalDensity.current
    val defaultSelectionBarHeightPx = remember(density) { with(density) { 64.dp.toPx() } }
    var selectionBarHeightPx by remember { mutableFloatStateOf(defaultSelectionBarHeightPx) }
    // NOT `firstVisibleItemIndex > 0` alone, despite that being what actually eliminated the
    // double-title ghost above — live-testing that version surfaced a different bug: the header's
    // own natural height (icon+title+subtitle+chip row, plus its own statusBarsPadding) is much
    // taller than the point at which its visible content (the title) has scrolled away, so index
    // stayed 0 — and the compact bar stayed unmounted — for a long stretch where only the header's
    // trailing chip row remained, scrolled up flush against the status bar with nothing left to
    // protect it (the statusBarsPadding was on the header's own top edge, already scrolled off).
    // Reported live as exactly that: chip labels jammed against the status bar icons. Below this
    // fixed pixel threshold — comfortably past where the title row itself (statusBarsPadding + the
    // 76dp top padding + the title/subtitle row's own height) has scrolled fully out of view, so
    // there's never a moment the compact bar's opaque background would need to cover a still-
    // visible title — the compact bar takes over, which simply paints over that remaining chip-row
    // sliver instead of leaving it exposed against the status bar.
    val compactBarThresholdPx = remember(density) { with(density) { 170.dp.toPx() } }
    // A LazyListState/LazyGridState has no single running "total scrolled px" the way
    // ScrollState.value does (what Settings' own rememberCollapsingHeaderState reads directly) —
    // firstVisibleItemScrollOffset resets to 0 every time the index advances, so it alone can't
    // drive a direction comparison across that boundary. Combining index and offset into one large
    // monotonic value sidesteps that, same trick Settings' own port of this used before the header
    // became real list content: compactBarThresholdPx is far smaller than a single row's height,
    // so by the time the index has advanced past 0 we're already well past it regardless of the
    // exact offset, and multiplying the index by a value much larger than the threshold keeps that
    // true.
    val scrollValuePx by remember {
        derivedStateOf {
            val (index, offset) = if (gridView) {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            } else {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
            index * 1_000_000f + offset
        }
    }
    // Same scroll-linked reveal as Settings' own sub-page headers (see CompactHeaderReveal): within
    // compactBarThresholdPx of the top, the compact bar stays hidden entirely (the real header is
    // on screen there, same reasoning as before); past it, the bar slides in and out pixel-for-pixel
    // with the scroll — up pulls it down into view at the finger's own pace, down pushes it back
    // out — instead of flipping between shown and hidden. Starts hidden: scrolling down from rest
    // must never show it. Pixel deltas come from nested scroll (libraryHeaderScrollConnection,
    // attached to this screen's root Box below), since scrollValuePx jumps by ~1,000,000 whenever
    // the first visible index changes and can't supply them.
    val compactBarReveal = remember { CompactHeaderReveal().apply { hide() } }
    // Whether a genuine upward scroll has "earned" the near-top transform below — NOT just
    // `offset < compactBarThresholdPx`. Reported live: scrolling straight DOWN from rest showed
    // the compact bar's title translating into view and overlapping the real header's own
    // still-visible title, because offset alone can't distinguish "heading toward the threshold
    // for the first time" from "coming back from beyond it" — both pass through the same offset
    // values. Only armed while the bar is at least partly revealed and moving up from beyond the
    // threshold; a reversal back into downward movement near the top disarms it immediately, same
    // as scrolling down should never show the compact bar there at all, transformed or not.
    var nearTopTransformActive by remember { mutableStateOf(false) }
    val libraryHeaderScrollConnection = remember(compactBarThresholdPx, selectionMode) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (selectionMode) return androidx.compose.ui.geometry.Offset.Zero
                // consumed.y < 0 means the content moved up, i.e. scrolled toward its end.
                val delta = -consumed.y
                if (delta == 0f) return androidx.compose.ui.geometry.Offset.Zero
                val current = scrollValuePx
                if (current <= compactBarThresholdPx) {
                    if (delta > 0f || current <= 0f) {
                        nearTopTransformActive = false
                        compactBarReveal.hide()
                    } else if (nearTopTransformActive) {
                        // Still on the way up from a reveal that started deeper down: keep pulling
                        // it the rest of the way into view as it transforms into the real header.
                        compactBarReveal.scrollBy(delta)
                    } else {
                        compactBarReveal.hide()
                    }
                } else {
                    compactBarReveal.scrollBy(delta)
                    if (compactBarReveal.fraction <= 0f) nearTopTransformActive = false
                    else if (delta < 0f) nearTopTransformActive = true
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    // Programmatic jumps back to the very top (scroll-to-top, reopening the tab) never pass through
    // nested scroll, so reset from the position itself there.
    LaunchedEffect(Unit) {
        snapshotFlow { scrollValuePx <= 0f }.collect { atTop ->
            if (atTop) { nearTopTransformActive = false; compactBarReveal.hide() }
        }
    }
    // The header's own real scroll offset, for LibraryCompactBar to translate its title against —
    // NOT scaled against compactBarThresholdPx. An earlier version returned a 0..1 fraction of
    // *that* distance, which reproduced live as the compact bar's title and the real header's own
    // title visibly overlapping at two different heights during the transform: threshold (170dp)
    // has nothing to do with the actual pixel gap between the compact bar's own resting position
    // and where the real title sits, so interpolating against it moved the compact bar's title at
    // the wrong rate to ever coincide with the real one except at the very end. The real title's
    // own screen position is (76dp top padding − offset) below the status bar, and the compact
    // bar's own resting position is 4dp below it (see LibraryCompactBar's own comment for both
    // values) — so translationY needs to close exactly a (76dp − 4dp − offset) gap, not some
    // fraction of an unrelated distance, which LibraryCompactBar computes for itself from this raw
    // offset. Returns +infinity when there's nothing to track (not armed, or already past index
    // 0), which coerces to zero translation there. A pure function, not a `derivedStateOf`, and
    // read directly inside graphicsLayer's own draw-phase lambda (see this composable's own
    // comment on the shared history), so it's never a frame behind the list's own scroll position.
    fun currentHeaderScrollOffsetPx(): Float {
        if (!nearTopTransformActive) return Float.POSITIVE_INFINITY
        val (index, offset) = if (gridView) {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        } else {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
        return if (index > 0) Float.POSITIVE_INFINITY else offset.toFloat()
    }
    // Whether LibraryCompactBar composes at all — safe to lag a frame behind (unlike its own
    // transform fraction), since mount/unmount is coarse either way. Stays composed (slid fully
    // out of view while hidden) everywhere past the threshold, so its height is already measured
    // by the time a reveal starts, plus during an active, still-in-progress near-top transform.
    val compactBarMounted by remember {
        derivedStateOf {
            val (index, offset) = if (gridView) {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            } else {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
            scrollValuePx > compactBarThresholdPx || (index == 0 && nearTopTransformActive && offset > 0)
        }
    }

    // Back turns an active filter chip off before it leaves the Library. Registered before the
    // selection handler, so with both active Back clears the selection first.
    val chipActive = favoritesOnly || showDeletedOnly || showDuplicatesOnly || audioOnly
    BackHandler(enabled = isVisible && chipActive && !isQueueOpen) {
        favoritesOnly = false; showDeletedOnly = false; showDuplicatesOnly = false; audioOnly = false
    }
    BackHandler(enabled = isVisible && selectionMode) { selectedIds = emptySet() }
    // While selecting, sideways swipes stay on the cards instead of turning the page.
    val pageSwipe = LocalPageSwipe.current
    DisposableEffect(pageSwipe, selectionMode, isVisible) {
        pageSwipe?.blocked = selectionMode && isVisible
        onDispose { pageSwipe?.blocked = false }
    }

    // Checks each finished download's thumbnail against the real MediaStore once per Library
    // visit — cheap enough for typical history sizes, and it's the only way to notice a file the
    // user deleted from their gallery outside the app.
    LaunchedEffect(isVisible) {
        if (isVisible) {
            viewModel.scanForDeletedMedia()
        } else {
            // Back to the top only when the list itself is about to change (a filter, search or
            // sort put back); otherwise the next visit picks up where this one was scrolled to.
            val listChanges = favoritesOnly || showDeletedOnly || showDuplicatesOnly || audioOnly ||
                searchQuery.isNotEmpty() || sortOption != LibrarySort.DATE_NEWEST
            selectedIds = emptySet()
            favoritesOnly = false; showDeletedOnly = false; showDuplicatesOnly = false; audioOnly = false
            searchQuery = ""
            searchExpanded = false
            sortOption = LibrarySort.DATE_NEWEST
            sortMenuExpanded = false
            if (listChanges) {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
            }
        }
    }

    // Each item's shape (grid tile widths) and length (the list's duration badge) — see
    // DownloadsViewModel.mediaShapes.
    val shapes by viewModel.mediaShapes.collectAsStateWithLifecycle()

    val openViewer = com.comfort.app.ui.viewer.LocalOpenMediaViewer.current
    val visibleItems = remember(
        showDeletedOnly,
        deletedItems,
        historyItems,
        favoritesOnly,
        audioOnly,
        searchQuery,
        sortOption,
    ) {
        (if (showDeletedOnly) deletedItems else historyItems)
            .let { if (favoritesOnly) it.filter { item -> item.isFavorite } else it }
            .let { if (audioOnly) it.filter { item -> item.isAudio } else it }
            .let { list ->
                if (searchQuery.isBlank()) list
                else list.filter {
                    it.title.contains(searchQuery, ignoreCase = true) || it.url.contains(searchQuery, ignoreCase = true)
                }
            }
            .let { list ->
                when (sortOption) {
                    LibrarySort.DATE_NEWEST -> list.sortedByDescending { it.effectiveDate }
                    LibrarySort.DATE_OLDEST -> list.sortedBy { it.effectiveDate }
                    LibrarySort.NAME -> list.sortedBy { it.title.ifBlank { it.url }.lowercase() }
                    LibrarySort.SIZE -> list.sortedByDescending { it.totalBytes }
                }
            }
    }
    // Both the "duplicates, but none right now" and "nothing matches the current filter" branches
    // below render fullHeaderContent() unconditionally at the top of a plain Column — not as a
    // real list item behind a scroll-position-driven compact bar. Toggling a filter (e.g. tapping
    // Favorites while scrolled down, with the compact bar already mounted from before) can land
    // here directly, and the compact bar's own mount state — tracking scroll position on the
    // LazyColumn/LazyVerticalGrid these two branches don't even use — has no way to know the
    // content underneath just changed shape. Reported live as exactly that: the compact bar still
    // showing, stacked directly on top of this branch's own always-visible full header.
    val usingStaticFullHeaderBranch = if (showDuplicatesOnly) duplicateAttempts.isEmpty() else visibleItems.isEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(libraryHeaderScrollConnection),
    ) {
        val subtitle = if (showDuplicatesOnly) {
            pluralStringResource(R.plurals.duplicate_count, duplicateAttempts.size, duplicateAttempts.size)
        } else {
            pluralStringResource(R.plurals.item_count, visibleItems.size, visibleItems.size)
        }

        // Wraps the whole branch below (previously each ending in its own early return) so the
        // bottom gradient scrim further down can sit as one unconditional sibling instead of
        // needing to be duplicated into every branch — see that scrim's own comment for why it
        // exists at all.
        Box(modifier = Modifier.fillMaxSize()) {
        // The header, as one rigid, non-shrinking block — see this screen's own state-setup
        // comment for why this (real list content, not a synced or collapsing overlay) is what
        // makes it move as one component with the content scrolling beneath it. In selection mode
        // it's skipped entirely (TopAppBar takes over up top instead, see the bottom of this
        // function) rather than kept as a list item nobody can see space for correctly.
        val fullHeaderContent = @Composable {
            if (!selectionMode) {
                LibraryHeader(
                    libraryTopicIcon = libraryTopicIcon,
                    searchExpanded = searchExpanded,
                    onSearchExpandedChange = { searchExpanded = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    subtitle = subtitle,
                    favoritesOnly = favoritesOnly,
                    onFavoritesOnlyChange = {
                        favoritesOnly = it
                        if (favoritesOnly) { showDeletedOnly = false; showDuplicatesOnly = false; audioOnly = false }
                    },
                    gridView = gridView,
                    onToggleGridView = {
                        gridView = !gridView
                        GalleryDlPreferences.setLibraryGridView(context, gridView)
                    },
                    hasActiveDownloads = hasActiveDownloads,
                    activeDownloadsCount = activeDownloadsCount,
                    onOpenQueue = onOpenQueue,
                    reducedMotion = reducedMotion,
                    sortMenuExpanded = sortMenuExpanded,
                    onSortMenuExpandedChange = { sortMenuExpanded = it },
                    sortOption = sortOption,
                    onSortOptionChange = { sortOption = it },
                    showDeletedOnly = showDeletedOnly,
                    onToggleShowDeletedOnly = {
                        showDeletedOnly = !showDeletedOnly
                        if (showDeletedOnly) { favoritesOnly = false; showDuplicatesOnly = false; audioOnly = false }
                    },
                    showDuplicatesOnly = showDuplicatesOnly,
                    onToggleShowDuplicatesOnly = {
                        showDuplicatesOnly = !showDuplicatesOnly
                        if (showDuplicatesOnly) { favoritesOnly = false; showDeletedOnly = false; audioOnly = false }
                    },
                    duplicateAttemptsCount = duplicateAttempts.size,
                    deletedCount = deletedItems.size,
                    onClearDeleted = { requestDelete(deletedItems.map { it.id }.toSet()) },
                    audioOnly = audioOnly,
                    onToggleAudioOnly = {
                        audioOnly = !audioOnly
                        if (audioOnly) { favoritesOnly = false; showDeletedOnly = false; showDuplicatesOnly = false }
                    },
                )
                // The Audio filter's songs, played in the music player: all in order, or shuffled.
                val songs = visibleItems.filter { it.isAudio && it.status != DownloadStatus.DELETED }
                if (audioOnly && songs.isNotEmpty()) {
                    val playSongs = com.comfort.app.ui.music.LocalPlaySongs.current
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = CONTENT_MAX_WIDTH)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            androidx.compose.material3.Button(onClick = { playSongs(songs, false) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.play_all))
                            }
                            androidx.compose.material3.FilledTonalButton(onClick = { playSongs(songs, true) }, modifier = Modifier.weight(1f)) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.shuffle))
                            }
                        }
                    }
                }
            }
        }
        val topPaddingWhileSelecting = with(LocalDensity.current) {
            if (selectionMode) selectionBarHeightPx.toDp() else 0.dp
        }
        if (showDuplicatesOnly) {
            if (duplicateAttempts.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(top = topPaddingWhileSelecting)) {
                    fullHeaderContent()
                    EmptyState(
                        icon = Icons.Outlined.ContentCopy,
                        title = stringResource(R.string.no_duplicates),
                        subtitle = stringResource(R.string.a_link_you_share_in_that),
                        modifier = Modifier.padding(bottom = navBarClearance()),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(top = topPaddingWhileSelecting)) {
                    fullHeaderContent()
                    DuplicatesList(
                        attempts = duplicateAttempts,
                        contentPadding = PaddingValues(bottom = navBarClearance()),
                        onRedownload = { viewModel.redownloadDuplicate(it) },
                        onDismiss = { viewModel.dismissDuplicateAttempt(it) },
                    )
                }
            }
        } else if (visibleItems.isEmpty()) {
            val searching = searchQuery.isNotBlank()
            Column(modifier = Modifier.fillMaxSize().padding(top = topPaddingWhileSelecting)) {
                fullHeaderContent()
                EmptyState(
                    icon = if (searching) Icons.Outlined.Search else if (showDeletedOnly) Icons.Outlined.Delete else if (favoritesOnly) Icons.Outlined.Star else Icons.Outlined.Image,
                    title = if (searching) stringResource(R.string.no_matches) else if (showDeletedOnly) stringResource(R.string.nothing_deleted) else if (favoritesOnly) stringResource(R.string.no_favorites_yet) else stringResource(R.string.nothing_here_yet),
                    subtitle = if (searching) stringResource(R.string.try_a_different_search) else if (showDeletedOnly) stringResource(R.string.pictures_you_remove_from_your_device) else if (favoritesOnly) stringResource(R.string.star_a_download_to_pin_it) else stringResource(R.string.downloaded_pictures_will_show_up_in),
                    modifier = Modifier.padding(bottom = navBarClearance()),
                )
            }
        } else if (gridView) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // Gallery-style: edge to edge, hairline gaps, and rows where every tile is as wide as
            // its media's shape needs (justifiedCells) rather than identical squares. A file whose
            // shape isn't known (yet) counts as square.
            // The grid's own width (not the screen's: a tablet's nav rail takes some). Tile sizes
            // come from a phone's width at most, so a wide screen fits more tiles to a row instead
            // of making them huge (one wide picture used to fill a whole tablet screen).
            val gridWidth = maxWidth.value
            val sizeRef = minOf(gridWidth, GRID_SIZE_REF_DP)
            val (targetFraction, minTileFraction, maxHeightFraction) = LIBRARY_GRID_SIZES[gridSize.coerceIn(0, LIBRARY_GRID_SIZES.lastIndex)]
            val cells = remember(visibleItems, shapes, gridWidth, gridSize) {
                justifiedCells(
                    aspects = visibleItems.map { shapes[it.id]?.aspect ?: 1f },
                    width = gridWidth,
                    maxPerRow = maxOf(4, (4 * gridWidth / GRID_SIZE_REF_DP).toInt()),
                    targetHeight = sizeRef * targetFraction,
                    gap = GRID_GAP_DP,
                    // Portraits cropped only down to 0.6 wide-to-tall: a row of three is taller (bigger
                    // tiles) and shows more of each video.
                    minAspect = 0.6f,
                    // No tile under 30% of the width: a wide video used to squeeze the portraits
                    // sharing its row to a fifth of the screen. Rows that end early for that are
                    // capped at 0.6x the width (tiles crop top and bottom), like Samsung Gallery.
                    maxAspect = 1.78f,
                    minTileWidth = sizeRef * minTileFraction,
                    maxHeight = sizeRef * maxHeightFraction,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(JUSTIFIED_COLUMNS),
                state = gridState,
                contentPadding = PaddingValues(top = topPaddingWhileSelecting, bottom = navBarClearance()),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
                verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
                // Pinch out for bigger tiles, in for smaller — one step per pinch, remembered.
                modifier = Modifier.fillMaxSize().pinchSteps(onStep = { step ->
                    val next = (gridSize + step).coerceIn(0, LIBRARY_GRID_SIZES.lastIndex)
                    if (next != gridSize) {
                        gridSize = next
                        GalleryDlPreferences.setLibraryGridSize(context, next)
                    }
                }),
            ) {
                // Full-width span so the header isn't squeezed into a single grid cell's own 1/3
                // column — same instance, same fullHeaderContent(), as the list branch below. The
                // Modifier.layout bleeds it 8dp past each edge to cancel out this LazyVerticalGrid's
                // own 8dp start/end contentPadding (needed for the real photo cells' own outer
                // margin, but not by the header) — without it, the header's icon row sits 8dp
                // further in from each edge here than it does in list view, reported live as the
                // header's own icons visibly shifting position when switching between grid and
                // list. Modifier.padding can't do this (it throws on a negative value); measuring
                // wider than the slot and placing the result shifted left is the same trick as
                // making an image bleed past a padded container's own edges.
                item(span = { GridItemSpan(maxLineSpan) }) { fullHeaderContent() }
                gridItemsIndexed(
                    visibleItems,
                    key = { _, item -> item.id },
                    span = { index, _ -> GridItemSpan(cells.getOrNull(index)?.span ?: JUSTIFIED_COLUMNS / 3) },
                ) { index, item ->
                    // Same slide-up + fade-in entrance / crossfade-out + reflow-on-removal pattern
                    // as the Download Queue's own cards (see QueueScreen.kt's own comment on this)
                    // — visibleState never flips back to false from here, so the removal half is
                    // entirely animateItem()'s own built-in fade-out + placement animation below,
                    // not this AnimatedVisibility's exit (deliberately ExitTransition.None).
                    val visibleState = remember(item.id) {
                        MutableTransitionState(alreadyAnimatedIds.contains(item.id)).apply { targetState = true }
                    }
                    SideEffect { alreadyAnimatedIds.add(item.id) }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        // Reduced motion drops the slide (a vestibular-trigger-shaped movement)
                        // but keeps the fade — brief functional feedback that a new item just
                        // appeared, same as QueueScreen.kt's own reduced-motion fix.
                        enter = if (reducedMotion) fadeIn(tween(350)) else fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 },
                        exit = ExitTransition.None,
                        modifier = Modifier.animateItem(),
                    ) {
                        HistoryGridItem(
                            item = item,
                            height = (cells.getOrNull(index)?.height ?: (gridWidth / 3)).dp,
                            shape = shapes[item.id],
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onTap = {
                                if (selectionMode) {
                                    selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                                }
                            },
                            onLongPress = { selectedIds = selectedIds + item.id },
                            onOpen = { openViewer(com.comfort.app.ui.viewer.MediaViewerRequest(visibleItems, item.id)) },
                            onDelete = { requestDelete(setOf(item.id)) },
                            onToggleFavorite = { viewModel.setFavorite(item.id, !item.isFavorite) },
                            onRename = { newTitle -> viewModel.renameDownload(item.id, newTitle) },
                            onRedownload = { viewModel.redownloadDeleted(item.id) },
                            onDownloadMissing = { viewModel.retryDownload(item.id) },
                        )
                    }
                }
            }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = topPaddingWhileSelecting, bottom = navBarClearance()),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { fullHeaderContent() }
                items(visibleItems, key = { it.id }) { item ->
                    val row = @Composable {
                        HistoryRow(
                            item = item,
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onTap = {
                                if (selectionMode) {
                                    selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                                }
                            },
                            onLongPress = { selectedIds = selectedIds + item.id },
                            onOpen = { openViewer(com.comfort.app.ui.viewer.MediaViewerRequest(visibleItems, item.id)) },
                            durationMs = shapes[item.id]?.durationMs,
                            onDelete = { requestDelete(setOf(item.id)) },
                            onToggleFavorite = { viewModel.setFavorite(item.id, !item.isFavorite) },
                            onRename = { newTitle -> viewModel.renameDownload(item.id, newTitle) },
                            onRedownload = { viewModel.redownloadDeleted(item.id) },
                            onDownloadMissing = { viewModel.retryDownload(item.id) },
                        )
                    }
                    // Same entrance/removal pattern as the grid above and the Download Queue's own
                    // cards — see either's own comment on why exit is deliberately ExitTransition.None.
                    val visibleState = remember(item.id) {
                        MutableTransitionState(alreadyAnimatedIds.contains(item.id)).apply { targetState = true }
                    }
                    SideEffect { alreadyAnimatedIds.add(item.id) }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        // Reduced motion drops the slide (a vestibular-trigger-shaped movement)
                        // but keeps the fade — brief functional feedback that a new item just
                        // appeared, same as QueueScreen.kt's own reduced-motion fix.
                        enter = if (reducedMotion) fadeIn(tween(350)) else fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 },
                        exit = ExitTransition.None,
                        // Inset rounded cards (same 16dp side margin and 20dp corners as the
                        // Queue's cards) rather than edge-to-edge rows on one continuous band.
                        // At most the other pages' reading width, centred (a tablet's rows ran edge
                        // to edge, the ⋮ a screen away from the title).
                        modifier = Modifier.animateItem()
                            .fillMaxWidth()
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        if (selectionMode) {
                            row()
                        } else {
                            SwipeToDeleteCard(onDelete = { requestDelete(setOf(item.id)) }, shape = LIBRARY_CARD_SHAPE) {
                                row()
                            }
                        }
                    }
                }
            }
        }

        // The fade behind the floating nav pill is MainScreen's, shared by every page under it.

        val showScrollToTopFab by remember {
            derivedStateOf {
                // Index 0 is the header itself now, so a real row's Nth position is index N+1 —
                // the +1 keeps the FAB appearing after the same number of real rows as before.
                !selectionMode && !showDuplicatesOnly &&
                    (if (gridView) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex) >= 7
            }
        }
        AnimatedVisibility(
            visible = showScrollToTopFab,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = navBarClearance(), end = 24.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    scrollTopScope.launch {
                        // From far down, animateScrollToItem() alone teleported most of the way and
                        // then snapped (recorded: two jumps, barely any motion). Jump silently to a
                        // few rows from the top first, then glide only that last stretch.
                        if (gridView) {
                            if (gridState.firstVisibleItemIndex > SCROLL_TOP_GLIDE_ITEMS) gridState.scrollToItem(SCROLL_TOP_GLIDE_ITEMS)
                            gridState.animateScrollToItem(0)
                        } else {
                            listState.glideToTop()
                        }
                    }
                },
                shape = androidx.compose.material3.MaterialShapes.Arch.toShape(),
            ) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.scroll_to_top))
            }
        }
        }

        // Only once the real header has scrolled away (same threshold the compact bar uses), and
        // never in the static-header branches, whose header can't scroll away at all — the list's
        // scroll state is left over from before switching into them and doesn't mean anything there.
        // derivedStateOf: scrollValuePx changes on every scrolled pixel, and reading it directly
        // here made the whole Library screen recompose on every scroll frame (a real source of the
        // scroll jank reported live). Only the crossing of the threshold matters.
        val pastHeader by remember { derivedStateOf { scrollValuePx >= compactBarThresholdPx } }
        val statusBarScrimVisible = !selectionMode && !usingStaticFullHeaderBranch && pastHeader
        val statusBarScrimAlpha by animateFloatAsState(
            targetValue = if (statusBarScrimVisible) 1f else 0f,
            animationSpec = tween(200),
            label = "library-status-bar-scrim",
        )
        StatusBarScrim(alpha = { statusBarScrimAlpha }, modifier = Modifier.align(Alignment.TopStart))

        if (selectionMode) {
            TopAppBar(
                title = { Text(stringResource(R.string.x_selected, selectedIds.size), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel_selection))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val candidateItems = (historyItems + deletedItems).filter { it.id in selectedIds && it.status != DownloadStatus.DELETED }
                        val uris = candidateItems.mapNotNull {
                            (it.mediaUri ?: it.thumbnailPath)?.takeIf { p -> p.isNotBlank() }?.let { p -> Uri.parse(p) }
                        }
                        if (uris.isNotEmpty()) {
                            val mimeTypes = uris.mapNotNull { context.contentResolver.getType(it) }.toSet()
                            val resolvedType = when {
                                mimeTypes.size == 1 -> mimeTypes.single()
                                mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("image/") } -> "image/*"
                                mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("video/") } -> "video/*"
                                mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("audio/") } -> "audio/*"
                                else -> "*/*"
                            }
                            val intent = if (uris.size == 1) {
                                Intent(Intent.ACTION_SEND).apply {
                                    type = resolvedType
                                    putExtra(Intent.EXTRA_STREAM, uris.first())
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = resolvedType
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, if (uris.size == 1) context.getString(R.string.share) else context.getString(R.string.share_files))) }
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share_selected))
                    }
                    IconButton(onClick = {
                        selectedIds.forEach { viewModel.setFavorite(it, true) }
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Outlined.Star, contentDescription = stringResource(R.string.add_selected_to_favorites))
                    }
                    IconButton(onClick = {
                        requestDelete(selectedIds)
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove_selected))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { selectionBarHeightPx = it.height.toFloat() },
            )
        } else if (compactBarMounted && !usingStaticFullHeaderBranch) {
            // The real header is list content (see fullHeaderContent() above); this is only the
            // compact replacement. Mounted whenever there's anything to show — either the
            // deep-scroll direction-based boolean, or an active, still-in-progress near-top
            // transform — with the real header's offset itself read fresh each frame inside
            // LibraryCompactBar's own translation modifier, not read here at composition time.
            // Suppressed outright while usingStaticFullHeaderBranch — see that val's own comment
            // for why: those branches always show their own full header regardless of scroll
            // position, so a compact bar left mounted from before switching into one of them (e.g.
            // tapping Favorites while scrolled down, landing on 0 results) would stack directly on
            // top of it.
            LibraryCompactBar(
                subtitle = subtitle,
                favoritesOnly = favoritesOnly,
                onFavoritesOnlyChange = {
                    favoritesOnly = it
                    if (favoritesOnly) { showDeletedOnly = false; showDuplicatesOnly = false; audioOnly = false }
                },
                gridView = gridView,
                onToggleGridView = {
                    gridView = !gridView
                    GalleryDlPreferences.setLibraryGridView(context, gridView)
                },
                hasActiveDownloads = hasActiveDownloads,
                activeDownloadsCount = activeDownloadsCount,
                onOpenQueue = onOpenQueue,
                headerScrollOffsetPx = ::currentHeaderScrollOffsetPx,
                modifier = Modifier.align(Alignment.TopStart).compactHeaderReveal(compactBarReveal),
            )
        }
    }
}

/** Marks every item of [lists] as already shown ([shown], the lists' "played its entrance" set)
 * once a frame has gone by since they changed. The entrance is for an item that just arrived, so
 * only the ones on screen in that frame play it. It used to play for any item the list hadn't drawn
 * yet this session: after the app started, a fast fling brought in a whole screen of never-drawn
 * cards, every one starting invisible, and the Library went blank mid-scroll (seen live). The
 * Queue's cards use it too. */
@Composable
internal fun <T> MarkShownAfterFirstFrame(shown: MutableSet<String>, vararg lists: List<T>, id: (T) -> String) {
    LaunchedEffect(*lists) {
        // Two frames: the lazy list composes the items on screen in the frame after the change.
        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        lists.forEach { list -> list.forEach { shown.add(id(it)) } }
    }
}

/** Gap between grid tiles, horizontally and vertically (dp). */
private const val GRID_GAP_DP = 2f

/** How many rows from the top the Library's scroll-to-top button glides over (it jumps to here
 * first when further down). */
private const val SCROLL_TOP_GLIDE_ITEMS = 6

/** The list view's scroll-to-top: jump to [SCROLL_TOP_GLIDE_ITEMS] rows from the top, then one
 * even 450ms glide the rest of the way. animateScrollToItem(0) did the last stretch with the top
 * item off screen, and recorded frame by frame it sat still for ~7 frames and then covered it all
 * in ~4. The distance is estimated from the visible rows' real heights (near-uniform here) and
 * padded, since the list can't scroll past its top — the excess is absorbed in the slow end of the
 * ease — and a final scrollToItem(0) settles any shortfall. */
private suspend fun androidx.compose.foundation.lazy.LazyListState.glideToTop() {
    if (firstVisibleItemIndex > SCROLL_TOP_GLIDE_ITEMS) scrollToItem(SCROLL_TOP_GLIDE_ITEMS)
    val rowHeights = layoutInfo.visibleItemsInfo.filter { it.index > 0 }.map { it.size }
    if (rowHeights.isNotEmpty() && firstVisibleItemIndex > 0) {
        val distance = (firstVisibleItemIndex * rowHeights.average() + firstVisibleItemScrollOffset) * 1.15
        animateScrollBy(
            -distance.toFloat(),
            androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }
    scrollToItem(0)
}

/** The width (dp) the grid's tile sizes are measured against at most — about a big phone's. */
private const val GRID_SIZE_REF_DP = 480f
