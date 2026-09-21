package com.comfort.app.ui.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import com.comfort.app.theme.FavoriteGold
import com.comfort.app.theme.HeaderFontFamily
import com.comfort.app.theme.SuccessGreen40
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import com.comfort.app.viewmodel.DownloadsViewModel
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



private enum class LibrarySort(val label: String) {
    DATE_NEWEST("Newest first"),
    DATE_OLDEST("Oldest first"),
    NAME("Name"),
    SIZE("Size"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsHistoryScreen(
    viewModel: DownloadsViewModel,
    onOpenQueue: () -> Unit,
    isQueueOpen: Boolean = false,
    // Hoisted up to MainScreen's own outer Box instead of a plain remember { SnackbarHostState() }
    // here — this screen's own Scaffold draws *before* FloatingNavBar in that Box (it's one of the
    // three tab contents, composed ahead of the persistent floating pill), so a SnackbarHost
    // rendered through this screen's own Scaffold silently drew underneath the pill instead of
    // over it. Reported live: the "Download removed" Undo snackbar peeked out from behind the nav
    // bar's rounded ends instead of sitting above it. MainScreen now owns this SnackbarHost and
    // renders it after FloatingNavBar, so this screen only needs the state to trigger it with.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // Library's own topic icon in its header — same role Icons.Outlined.SettingsApplications
    // plays in the Settings root header (see MoreScreen.kt's SettingsRootScreen).
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
    val alreadyAnimatedIds = remember { mutableStateMapOf<String, Boolean>() }
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
    // the Settings root header's own search icon (see MoreScreen.kt's SettingsRootScreen) — this
    // replaces the old always-visible PillSearchBar that used to sit permanently below the title.
    var searchExpanded by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(LibrarySort.DATE_NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(GalleryDlPreferences.isLibraryGridView(context)) }
    val selectionMode = selectedIds.isNotEmpty()

    val deleteScope = rememberCoroutineScope()
    // Every delete on this screen (bulk, a single row's "Remove", swipe-to-dismiss) routes through
    // here instead of calling viewModel.deleteDownload directly — better-interface review flagged
    // the old direct-delete-on-tap behavior as a HIGH finding (a destructive, irreversible action
    // with no confirmation or undo anywhere), the same issue already fixed on the Queue screen.
    // Mirrors QueueScreen.kt's own requestDelete(): hideForDeletion() only ever hides the ids
    // (reversible); the real, irreversible delete is confirmDelete(), which only runs once this
    // Snackbar's own Undo window has passed without the user tapping it.
    fun requestDelete(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModel.hideForDeletion(ids)
        deleteScope.launch {
            val message = if (ids.size == 1) "Download removed" else "${ids.size} downloads removed"
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.cancelDeletion(ids)
            } else {
                viewModel.confirmDelete(ids)
            }
        }
    }

    // MainScreen keeps this screen composed underneath the Download Queue overlay now (needed for
    // the predictive-back reveal animation), where it used to fully unmount and remount — which
    // reset scroll position back to the top as a side effect. A freshly-finished download lands at
    // the top of the newest-first list, so without this, coming back from Queue silently leaves
    // you scrolled wherever you were before, with the new item off-screen above.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scrollTopScope = rememberCoroutineScope()
    LaunchedEffect(isQueueOpen) {
        if (!isQueueOpen) {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
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
    var selectionBarHeightPx by remember { mutableFloatStateOf(0f) }
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
    val density = LocalDensity.current
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
    // Same three-way behavior as Settings' own sub-page headers: within compactBarThresholdPx of
    // the top, the compact bar stays hidden entirely (the real header is on screen there, same
    // reasoning as before); past it, showing it chases scroll direction — down hides it, up
    // reveals it — rather than simply staying visible for the rest of the scroll. Hysteresis (net
    // movement has to clear 8dp in one direction before flipping, not any nonzero amount) damps
    // the natural per-frame tremor of a real slow drag that a flip-per-frame reaction would
    // otherwise chase.
    val hysteresisPx = remember(density) { with(density) { 8.dp.toPx() } }
    var showCompactBar by remember { mutableStateOf(false) }
    // Whether a genuine upward flick has "earned" the near-top transform below — NOT just
    // `offset < compactBarThresholdPx`. Reported live: scrolling straight DOWN from rest showed
    // the compact bar's title translating into view and overlapping the real header's own
    // still-visible title, because offset alone can't distinguish "heading toward the threshold
    // for the first time" from "coming back from beyond it" — both pass through the same offset
    // values. Only set true at the exact moment showCompactBar itself flips true (a decisive
    // upward flick from beyond the threshold), and only while that upward motion continues; a
    // reversal back into downward movement disarms it immediately, same as scrolling down should
    // never show the compact bar at all, transformed or not.
    var nearTopTransformActive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var previous = scrollValuePx
        var accumulated = 0f
        snapshotFlow { scrollValuePx }.collect { current ->
            val delta = current - previous
            when {
                current <= compactBarThresholdPx -> {
                    showCompactBar = false
                    accumulated = 0f
                    if (delta > 0f) nearTopTransformActive = false
                    if (current <= 0f) nearTopTransformActive = false
                }
                else -> {
                    accumulated = if (accumulated == 0f || (accumulated > 0f) == (delta > 0f)) accumulated + delta else delta
                    if (accumulated > hysteresisPx) { showCompactBar = false; accumulated = 0f }
                    else if (accumulated < -hysteresisPx) { showCompactBar = true; nearTopTransformActive = true; accumulated = 0f }
                }
            }
            previous = current
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
    // transform fraction), since mount/unmount is coarse either way. True whenever there's
    // anything to show: the deep-scroll boolean, or an active, still-in-progress near-top reveal.
    val compactBarMounted by remember {
        derivedStateOf {
            val (index, offset) = if (gridView) {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            } else {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
            showCompactBar || (index == 0 && nearTopTransformActive && offset > 0)
        }
    }

    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    // Checks each finished download's thumbnail against the real MediaStore once per Library
    // visit — cheap enough for typical history sizes, and it's the only way to notice a file the
    // user deleted from their gallery outside the app.
    LaunchedEffect(Unit) { viewModel.scanForDeletedMedia() }

    val visibleItems = (if (showDeletedOnly) deletedItems else historyItems)
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val subtitle = if (showDuplicatesOnly) {
            "${duplicateAttempts.size} ${if (duplicateAttempts.size == 1) "duplicate" else "duplicates"}"
        } else {
            "${visibleItems.size} ${if (visibleItems.size == 1) "item" else "items"}"
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
                    audioOnly = audioOnly,
                    onToggleAudioOnly = {
                        audioOnly = !audioOnly
                        if (audioOnly) { favoritesOnly = false; showDeletedOnly = false; showDuplicatesOnly = false }
                    },
                )
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
                        title = "No duplicates",
                        subtitle = "A link you share in that's already queued, running, or finished lands here instead of starting a second copy.",
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
                    title = if (searching) "No matches" else if (showDeletedOnly) "Nothing deleted" else if (favoritesOnly) "No favorites yet" else "Nothing here yet",
                    subtitle = if (searching) "Try a different search." else if (showDeletedOnly) "Pictures you remove from your device gallery will show up here." else if (favoritesOnly) "Star a download to pin it here." else "Downloaded pictures will show up in this gallery.",
                    modifier = Modifier.padding(bottom = navBarClearance()),
                )
            }
        } else if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(top = topPaddingWhileSelecting, start = 8.dp, end = 8.dp, bottom = navBarClearance()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.layout { measurable, constraints ->
                            val bleedPx = 8.dp.roundToPx()
                            val widerConstraints = Constraints(
                                minWidth = (constraints.minWidth + bleedPx * 2).coerceAtLeast(0),
                                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + bleedPx * 2 else constraints.maxWidth,
                                minHeight = constraints.minHeight,
                                maxHeight = constraints.maxHeight,
                            )
                            val placeable = measurable.measure(widerConstraints)
                            layout(placeable.width - bleedPx * 2, placeable.height) {
                                placeable.placeRelative(-bleedPx, 0)
                            }
                        },
                    ) { fullHeaderContent() }
                }
                gridItems(visibleItems, key = { it.id }) { item ->
                    // Same slide-up + fade-in entrance / crossfade-out + reflow-on-removal pattern
                    // as the Download Queue's own cards (see QueueScreen.kt's own comment on this)
                    // — visibleState never flips back to false from here, so the removal half is
                    // entirely animateItem()'s own built-in fade-out + placement animation below,
                    // not this AnimatedVisibility's exit (deliberately ExitTransition.None).
                    val visibleState = remember(item.id) {
                        MutableTransitionState(alreadyAnimatedIds.containsKey(item.id)).apply { targetState = true }
                    }
                    SideEffect { alreadyAnimatedIds[item.id] = true }
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
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onTap = {
                                if (selectionMode) {
                                    selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                                }
                            },
                            onLongPress = { selectedIds = selectedIds + item.id },
                        )
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
                            onDelete = { requestDelete(setOf(item.id)) },
                            onToggleFavorite = { viewModel.setFavorite(item.id, !item.isFavorite) },
                            onRename = { newTitle -> viewModel.renameDownload(item.id, newTitle) },
                        )
                    }
                    // Same entrance/removal pattern as the grid above and the Download Queue's own
                    // cards — see either's own comment on why exit is deliberately ExitTransition.None.
                    val visibleState = remember(item.id) {
                        MutableTransitionState(alreadyAnimatedIds.containsKey(item.id)).apply { targetState = true }
                    }
                    SideEffect { alreadyAnimatedIds[item.id] = true }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        // Reduced motion drops the slide (a vestibular-trigger-shaped movement)
                        // but keeps the fade — brief functional feedback that a new item just
                        // appeared, same as QueueScreen.kt's own reduced-motion fix.
                        enter = if (reducedMotion) fadeIn(tween(350)) else fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 },
                        exit = ExitTransition.None,
                        modifier = Modifier.animateItem(),
                    ) {
                        if (selectionMode) {
                            row()
                        } else {
                            SwipeToDeleteCard(onDelete = { requestDelete(setOf(item.id)) }) {
                                row()
                            }
                        }
                    }
                }
            }
        }

        // A visual "floor" at the bottom, same idea as before: once the header (now list content)
        // has scrolled away and real content reaches edge-to-edge, nothing softens where it meets
        // the floating nav pill, reading as an abrupt edge rather than an intentional one. Faded
        // in once scrolled at all — a plain threshold, not a collapse fraction, since there's no
        // collapse any more. FloatingNavBar itself (MainScreen.kt) composes after — on top of —
        // this whole screen, so this scrim sits correctly behind the pill without this screen
        // needing to know anything about it directly.
        val scrolledPastHeader by remember {
            derivedStateOf {
                if (gridView) {
                    gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
                } else {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                }
            }
        }
        AnimatedVisibility(
            visible = scrolledPastHeader,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(navBarClearance())
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
            )
        }

        val showScrollToTopFab by remember {
            derivedStateOf {
                // Index 0 is the header itself now, so a real row's Nth position is index N+1 —
                // the +1 keeps the FAB appearing after the same number of real rows as before.
                !showDuplicatesOnly &&
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
                        if (gridView) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0)
                    }
                },
            ) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = "Scroll to top")
            }
        }
        }

        if (selectionMode) {
            TopAppBar(
                title = { Text("${selectedIds.size} selected", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel selection")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val selectedItems = historyItems.filter { it.id in selectedIds }
                        val uris = selectedItems.mapNotNull { it.thumbnailPath?.let { p -> Uri.parse(p) } }
                        if (uris.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, "Share images")) }
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share selected")
                    }
                    IconButton(onClick = {
                        selectedIds.forEach { viewModel.setFavorite(it, true) }
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Outlined.Star, contentDescription = "Add selected to favorites")
                    }
                    IconButton(onClick = {
                        requestDelete(selectedIds)
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove selected")
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
        } else if (compactBarMounted) {
            // The real header is list content (see fullHeaderContent() above); this is only the
            // compact replacement. Mounted whenever there's anything to show — either the
            // deep-scroll direction-based boolean, or an active, still-in-progress near-top
            // transform — with the real header's offset itself read fresh each frame inside
            // LibraryCompactBar's own translation modifier, not read here at composition time.
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
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/** The Library header — real list content now (its caller composes it via `item { }` /
 * `item(span = { GridItemSpan(maxLineSpan) }) { }`), so it's a fixed, static block like any other
 * row: nothing here shrinks or fades on its own any more. It moves exactly as far and as fast as
 * the finger drags the list, because from the list's own point of view it IS the list — there's
 * nothing left to keep "in sync" with scroll position, which is the whole point. */
@Composable
private fun LibraryHeader(
    libraryTopicIcon: ImageVector,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    subtitle: String,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    gridView: Boolean,
    onToggleGridView: () -> Unit,
    hasActiveDownloads: Boolean,
    activeDownloadsCount: Int,
    onOpenQueue: () -> Unit,
    reducedMotion: Boolean,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    sortOption: LibrarySort,
    onSortOptionChange: (LibrarySort) -> Unit,
    showDeletedOnly: Boolean,
    onToggleShowDeletedOnly: () -> Unit,
    showDuplicatesOnly: Boolean,
    onToggleShowDuplicatesOnly: () -> Unit,
    duplicateAttemptsCount: Int,
    audioOnly: Boolean,
    onToggleAudioOnly: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // Favorites/Grid-List/Queue, pinned at a fixed top=12dp position independent of the
        // icon+title/search row below, same long-standing spot near the status bar as before.
        // Hidden while searching — floating above an otherwise-empty search field read as
        // leftover clutter, not part of the search UI.
        androidx.compose.animation.AnimatedVisibility(
            visible = !searchExpanded,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(modifier = Modifier.padding(end = 12.dp, top = 12.dp)) {
                LibraryHeaderActions(
                    favoritesOnly = favoritesOnly,
                    onFavoritesOnlyChange = onFavoritesOnlyChange,
                    gridView = gridView,
                    onToggleGridView = onToggleGridView,
                    hasActiveDownloads = hasActiveDownloads,
                    activeDownloadsCount = activeDownloadsCount,
                    onOpenQueue = onOpenQueue,
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
        run {
            val expanded = searchExpanded
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 76.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (expanded) {
                    PillSearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        placeholder = "Search downloads",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            onSearchExpandedChange(false)
                            onSearchQueryChange("")
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Close search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 40dp box around the (non-clickable) leading icon, not just the bare 32dp
                    // icon — matches the 40dp touch target every Settings sub-page's own topic
                    // icon sits in.
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            libraryTopicIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Library",
                            // Same style declaration as the Settings root header's own "Settings"
                            // title (see MoreScreen.kt's SettingsRootScreen) — displayMedium (30sp)
                            // plus the Google Sans HeaderFontFamily.
                            style = MaterialTheme.typography.displayMedium,
                            fontFamily = HeaderFontFamily,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onSearchExpandedChange(true) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            // better-interface review: this row silently overflowed past the last couple of chips
            // ("Deleted", Grid/List) on typical phone widths with no visible cue that anything
            // more was scrollable. Two earlier attempts (an edge fade, then a scrollbar-style
            // track/thumb strip) both worked but the user didn't want either look — this is a
            // one-time "nudge" instead: a brief auto-scroll-and-back on first appearance, the
            // physical equivalent of someone tapping the row and pointing right. Scrolls all the
            // way to maxValue (the real end), not a small hinting bump — a fixed small nudge
            // (reported live, and true of the analogous Queue-screen fix too) stopped short of
            // actually revealing the last chip.
            val toolbarScrollState = rememberScrollState()
            LaunchedEffect(Unit) {
                // Give the static state a beat to register before moving anything — also lets the
                // real maxValue (only known post-layout) settle so a screen where every chip
                // already fits doesn't nudge toward nothing.
                delay(500)
                // An automatic scroll the user didn't ask for — same reduce-motion gate as the
                // entrance animations elsewhere in this screen, not just decorative here.
                if (!reducedMotion && toolbarScrollState.maxValue > 0) {
                    toolbarScrollState.animateScrollTo(toolbarScrollState.maxValue, animationSpec = tween(450))
                    delay(250)
                    toolbarScrollState.animateScrollTo(0, animationSpec = tween(450))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(toolbarScrollState)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            ) {
                Box {
                    LibraryToolbarChip(
                        icon = Icons.Outlined.Tune,
                        label = "Sort",
                        onClick = { onSortMenuExpandedChange(true) },
                        groupIndex = 0, groupSize = 4,
                    )
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { onSortMenuExpandedChange(false) }) {
                        LibrarySort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = if (option == sortOption) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                                } else null,
                                onClick = { onSortOptionChange(option); onSortMenuExpandedChange(false) },
                            )
                        }
                    }
                }
                LibraryToolbarChip(
                    icon = Icons.Outlined.Delete,
                    label = "Deleted",
                    active = showDeletedOnly,
                    onClick = onToggleShowDeletedOnly,
                    groupIndex = 1, groupSize = 4,
                )
                LibraryToolbarChip(
                    icon = Icons.Outlined.ContentCopy,
                    label = "Duplicates",
                    active = showDuplicatesOnly,
                    count = duplicateAttemptsCount,
                    onClick = onToggleShowDuplicatesOnly,
                    groupIndex = 2, groupSize = 4,
                )
                LibraryToolbarChip(
                    icon = Icons.Outlined.MusicNote,
                    label = "Audio",
                    active = audioOnly,
                    onClick = onToggleAudioOnly,
                    groupIndex = 3, groupSize = 4,
                )
            }
        }
        }
    }
}

/** The pinned bar that replaces [LibraryHeader] once it has scrolled fully out of view as real
 * list content — see the caller's own `showCompactBar` doc comment for the exact index-based
 * threshold that keeps this from ever being visible at the same time as any part of the real
 * header. Icon + title + Favorites/Grid/Queue, all in one row (so they share the exact same
 * `verticalAlignment` and can't drift out of line with each other) — no search, no chip row.
 *
 * [headerScrollOffsetPx] drives a position-matched transform: the whole row translates downward
 * by exactly (76dp − 4dp − offset), landing on top of where the real header's own title sits by
 * the time they'd otherwise coincide. An earlier version of this same idea reproduced live as the
 * two titles visibly overlapping at slightly different heights — not because the translation math
 * was wrong, but because this bar's own title sat at a different position *within its own row*
 * than the real header's title does within *its* row: the real header wraps its title in a
 * Column above a subtitle line, so [CenterVertically][Alignment.CenterVertically] centers that
 * whole two-line block and the title itself ends up above the row's true center, while this bar's
 * title — with no subtitle beneath it — was centered directly at its row's true center instead.
 * The invisible placeholder [Text] below reserves exactly the subtitle's own line height so the
 * same Column/CenterVertically math lands the title identically in both places, without needing
 * to hand-tune a second offset on top of the real one. */
@Composable
private fun LibraryCompactBar(
    subtitle: String,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    gridView: Boolean,
    onToggleGridView: () -> Unit,
    hasActiveDownloads: Boolean,
    activeDownloadsCount: Int,
    onOpenQueue: () -> Unit,
    headerScrollOffsetPx: () -> Float,
    modifier: Modifier = Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val maxTranslatePx = (76.dp - 4.dp).toPx()
                val rawOffset = headerScrollOffsetPx()
                val currentTranslate = if (rawOffset == Float.POSITIVE_INFINITY) 0f else (maxTranslatePx - rawOffset).coerceIn(0f, maxTranslatePx)
                drawRect(
                    color = bgColor,
                    size = size.copy(height = size.height + currentTranslate)
                )
            }
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 2.dp)
                // The one piece of real motion here — see this composable's own doc comment for
                // exactly what distance this covers. A graphicsLayer translation (computed fresh
                // every frame from the caller's own function), not an animated padding value,
                // which would need a full recomposition to update and could lag a frame behind
                // the list's own scroll position the same way an earlier version's alpha did
                // before it was moved into graphicsLayer too.
                .graphicsLayer {
                    val maxTranslatePx = (76.dp - 4.dp).toPx()
                    translationY = (maxTranslatePx - headerScrollOffsetPx()).coerceIn(0f, maxTranslatePx)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_gallery_thumbnail),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Library",
                    // Same displayMedium size as the real header's own title — a smaller
                    // compact-bar title would read as a distinct thing popping in rather than the
                    // same title continuing once it's revealed.
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = HeaderFontFamily,
                    fontWeight = FontWeight.Bold,
                )

                // The subtitle physically shrinks its layout height to 0 as the header collapses,
                // which automatically shifts the "Library" title above it vertically down to the exact
                // true center of the row to align perfectly with the action icons. As it expands,
                // it smoothly pushes the "Library" title back up into its expanded-header position.
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .graphicsLayer {
                            val maxTranslatePx = (76.dp - 4.dp).toPx()
                            val rawOffset = headerScrollOffsetPx()
                            val offset = if (rawOffset == Float.POSITIVE_INFINITY) maxTranslatePx else rawOffset.coerceIn(0f, maxTranslatePx)
                            // Fade in as it expands (offset -> 0)
                            alpha = 1f - (offset / maxTranslatePx)
                        }
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val maxTranslatePx = (76.dp - 4.dp).toPx()
                            val rawOffset = headerScrollOffsetPx()
                            val offset = if (rawOffset == Float.POSITIVE_INFINITY) maxTranslatePx else rawOffset.coerceIn(0f, maxTranslatePx)
                            val fraction = offset / maxTranslatePx
                            
                            val placeable = measurable.measure(constraints)
                            val currentHeight = (placeable.height * (1f - fraction)).toInt()
                            layout(placeable.width, currentHeight) {
                                placeable.place(0, 0)
                            }
                        }
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 12.dp)
                .graphicsLayer {
                    val maxTranslatePx = (76.dp - 4.dp).toPx()
                    val rawOffset = headerScrollOffsetPx()
                    val offset = if (rawOffset == Float.POSITIVE_INFINITY) maxTranslatePx else rawOffset.coerceIn(0f, maxTranslatePx)
                    // We want to translate UP by 8.dp exactly as offset goes from 0 to maxTranslatePx.
                    // This smoothly transitions the icons from top=12.dp to a visual top of 4.dp.
                    val fraction = offset / maxTranslatePx
                    val endTranslateY = (4.dp - 12.dp).toPx()
                    translationY = endTranslateY * fraction
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryHeaderActions(
                favoritesOnly = favoritesOnly,
                onFavoritesOnlyChange = onFavoritesOnlyChange,
                gridView = gridView,
                onToggleGridView = onToggleGridView,
                hasActiveDownloads = hasActiveDownloads,
                activeDownloadsCount = activeDownloadsCount,
                onOpenQueue = onOpenQueue,
            )
        }
    }
}

/** The Favorites toggle, Grid/List view toggle, and Queue button (with its active-downloads
 * badge) — shared between the full header's own title row and the compact header's title row so
 * the two stay pixel-identical and can't drift out of sync with each other. */
@Composable
private fun LibraryHeaderActions(
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    gridView: Boolean,
    onToggleGridView: () -> Unit,
    hasActiveDownloads: Boolean,
    activeDownloadsCount: Int,
    onOpenQueue: () -> Unit,
) {
    IconToggleButton(checked = favoritesOnly, onCheckedChange = onFavoritesOnlyChange) {
        Icon(
            Icons.Outlined.Star,
            contentDescription = "Favorites only",
            tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    IconButton(onClick = onToggleGridView) {
        Icon(
            if (gridView) Icons.Outlined.List else Icons.Outlined.GridView,
            contentDescription = if (gridView) "Switch to list view" else "Switch to grid view",
        )
    }
    val queueIcon = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_video_frame_save)
    IconButton(onClick = onOpenQueue) {
        if (hasActiveDownloads) {
            BadgedBox(
                badge = {
                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                        Text(activeDownloadsCount.toString())
                    }
                }
            ) {
                Icon(queueIcon, contentDescription = "Active downloads")
            }
        } else {
            Icon(queueIcon, contentDescription = "Active downloads")
        }
    }
}

/** Shared swipe-to-delete wrapper for both HistoryRow's own list and DuplicatesList below —
 * [modifier] and [shape] describe the card's own footprint (edge-to-edge for HistoryRow's plain
 * rows, a margined rounded pill for Duplicates' cards) and are applied to the reveal background
 * exactly once, from the same values the caller gives [content] itself, rather than each call site
 * hand-copying its card's own margin/shape into a second, separate background Box. That hand-copy
 * was exactly what caused Duplicates' cards to show a permanent colored halo at rest: its
 * background didn't match the rounded, margined Surface it sat behind. */
@Composable
private fun SwipeToDeleteCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onDelete()
            }
            true
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        content()
    }
}

/** Library's "Duplicates" filter content — a plain audit log, not the rich gallery grid/list the
 * rest of this screen renders, since a duplicate attempt was never actually downloaded (no
 * progress, no size, no favorite/delete state — just "you tried this link and already had it").
 * Each row can redownload anyway (bypassing the duplicate check for just that one link) or be
 * dismissed on its own, independent of the real download it matched. */
@Composable
private fun DuplicatesList(
    attempts: List<com.comfort.app.data.DuplicateAttempt>,
    contentPadding: PaddingValues,
    onRedownload: (com.comfort.app.data.DuplicateAttempt) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val sdf = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    // Same entrance/removal treatment as the real download rows further up (HistoryRow/
    // HistoryGridItem) — a plain instant pop-out on Dismiss read as jarring/inconsistent next to
    // every other list in this app already animating removals, once actually compared side by
    // side. Own id-keyed "already played its entrance" map (own id-space — DuplicateAttempt ids,
    // never collide with a real download's own id) rather than sharing the outer screen's
    // alreadyAnimatedIds: a Lazy layout recycling this composable in and out of view shouldn't
    // replay the slide-in every time, same reasoning as that other map's own doc comment.
    val alreadyAnimatedIds = remember { mutableStateMapOf<String, Boolean>() }
    val reducedMotion = rememberIsReducedMotionEnabled()
    LazyColumn(contentPadding = contentPadding.let { PaddingValues(top = it.calculateTopPadding(), bottom = it.calculateBottomPadding(), start = 16.dp, end = 16.dp) }) {
        items(attempts, key = { it.id }) { attempt ->
            val visibleState = remember(attempt.id) {
                MutableTransitionState(alreadyAnimatedIds.containsKey(attempt.id)).apply { targetState = true }
            }
            SideEffect { alreadyAnimatedIds[attempt.id] = true }
            AnimatedVisibility(
                visibleState = visibleState,
                enter = if (reducedMotion) fadeIn(tween(350)) else fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 },
                // Exit is deliberately ExitTransition.None — the item's removal from `attempts`
                // itself (once onDismiss's caller drops it from the flow) is what actually removes
                // this row; animateItem() below handles that fade-out + the rest of the list
                // smoothly reflowing into the gap, same split as every other list in this file.
                exit = ExitTransition.None,
                modifier = Modifier.animateItem(),
            ) {
            // Same shared swipe-to-delete wrapper as the real download rows above (HistoryRow) —
            // no confirmation, matching the existing Dismiss (X) button below, which already
            // removes an attempt with no confirmation either. modifier/shape here are the single
            // source both the reveal background and this card's own Surface derive from, so they
            // can't drift out of sync the way two hand-copied literals could.
            SwipeToDeleteCard(
                onDelete = { onDismiss(attempt.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (attempt.thumbnailPath != null) {
                        AsyncImage(
                            model = attempt.thumbnailPath,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            attempt.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Already downloaded • tried again ${sdf.format(Date(attempt.dateAdded))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onRedownload(attempt) }) {
                        Icon(Icons.Outlined.Restore, contentDescription = "Redownload")
                    }
                    IconButton(onClick = { onDismiss(attempt.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Dismiss")
                    }
                }
            }
            }
            }
        }
    }
}

// A labeled, pill-shaped filter/action button for the Library toolbar — echoes the floating nav
// bar's own chip treatment (icon + label, accent-colored when active) so the two feel like one
// design system instead of the nav bar being the only polished piece of chrome on screen.
@Composable
private fun LibraryToolbarChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    // Null (the default) renders a plain icon — only Duplicates passes this today. Same
    // BadgedBox+Badge treatment as this screen's own "Active downloads" queue button above
    // (and FloatingNavBar's own Settings-tab dot), not a "(N)" suffix on the label text, so a
    // count on any icon in this app always looks like the same one thing.
    count: Int? = null,
    // Same connected-group treatment as the Queue card's Pause/Cancel chips: the caller passes
    // this chip's own position/row size so rememberMorphingChipShape below can compute the
    // row's outer ends fully rounded and the chips facing each other the tighter shared corner
    // — now owned internally (not a plain passed-in Shape) since the morph needs this chip's own
    // interactionSource, which only this composable creates.
    groupIndex: Int = 0,
    groupSize: Int = 1,
) {
    // Badge sits on the whole chip's own top-right corner (not the icon inside it — tried that
    // first, reported live as reading like it belonged to the icon rather than as a count on the
    // chip itself) — an outer Box wrapping the real FilterChip plus the badge as its own sibling,
    // so [modifier] (whatever a caller passes — e.g. the Sort chip's own wrapping Box for its
    // DropdownMenu) still sizes/positions the *whole* chip+badge unit as one thing.
    //
    // Positioned via a measured pixel width, not .align(Alignment.TopEnd) — tried that on the
    // (differently-scoped) icon-only version of this earlier and it landed top-left instead of
    // top-right despite it, for reasons that didn't trace back to anything in this file (no RTL/
    // LayoutDirection override anywhere here). A plain top-left-anchored offset (Box's own default
    // child placement, no alignment modifier) sidesteps needing to trust that alignment resolution
    // at all — and doing it off this Row's own real onSizeChanged width, rather than a fixed dp
    // guess, keeps the badge correctly at the corner regardless of the label text's own length.
    var chipWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier) {
        // A real FilterChip instead of a hand-rolled Row+clip+background+clickable — same tokens,
        // same animated color transition (FilterChip animates its own colors on selection change
        // internally), but now with the chip API's own accessibility semantics, minimum touch
        // target, and selected-state contract for free instead of reimplementing them.
        FilterChip(
            selected = active,
            onClick = onClick,
            modifier = Modifier.height(40.dp).onSizeChanged { chipWidthPx = it.width },
            interactionSource = interactionSource,
            shape = rememberMorphingChipShape(groupIndex, groupSize, selected = active, interactionSource = interactionSource, height = 40.dp),
            leadingIcon = {
                // better-interface review: this icon's contentDescription duplicated the visible
                // Text right next to it inside one clickable (merged-semantics) row — decorative
                // next to real text, so null here, not a repeat of the same name TalkBack already
                // gets from the label.
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            label = { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            border = null,
        )
        if (count != null && count > 0 && chipWidthPx > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                // -10dp so roughly half the badge overlaps the chip's own corner (the usual
                // notification-badge look) instead of sitting fully outside it.
                modifier = Modifier.offset(x = with(density) { chipWidthPx.toDp() } - 10.dp, y = (-6).dp),
            ) { Text(count.toString()) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryGridItem(
    item: DownloadEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    // DELETED means the underlying file is confirmed gone — don't bother attempting a load that
    // can only fail, and don't offer open/share actions that would just error out.
    val hasThumbnail = !item.thumbnailPath.isNullOrBlank() && item.status != DownloadStatus.DELETED

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            // shapes.medium (12dp token) — same value as before, now tied to the theme's own
            // shape scale instead of a magic number that would silently drift out of sync with
            // it if the app's shape theme is ever customized.
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onTap()
                    } else if (hasThumbnail) {
                        // mediaUri is only ever set when thumbnailPath got pointed at a separate
                        // extracted image instead of the real file (audio downloads — see
                        // DownloadEntity.mediaUri's own doc comment); everything else still opens
                        // thumbnailPath itself, same as before mediaUri existed.
                        val uri = Uri.parse(item.mediaUri ?: item.thumbnailPath)
                        // thumbnailPath is the real saved file's own MediaStore URI (see
                        // DownloadWorker.kt's setThumbnail call) — for a video download that's a
                        // video/* file, not an image, so a hardcoded "image/*" here sent every
                        // video open request under the wrong type. Android's "Always" default-app
                        // preference is stored per resolved type, so a video's "Always open with
                        // Google Photos" choice (recorded against video/*) never matched this
                        // screen's requests and the chooser reappeared on every tap (reproduced
                        // live on a Samsung A03 Core). Querying the real type from the content
                        // resolver fixes it for images and videos alike.
                        val realType = context.contentResolver.getType(uri) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, realType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                },
                onLongClick = onLongPress,
            ),
    ) {
        if (hasThumbnail) {
            AsyncImage(
                model = item.thumbnailPath,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val icon = when (item.status) {
                    DownloadStatus.ERRORED -> Icons.Outlined.Warning
                    DownloadStatus.DELETED -> Icons.Outlined.Delete
                    else -> Icons.Outlined.Image
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
            }
        }

        if (selected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }

        if (item.downloadedItems > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${item.downloadedItems}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (item.isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Star, contentDescription = "Favorite", tint = FavoriteGold, modifier = Modifier.size(11.dp))
            }
        }

        // A quick, scannable outcome badge in a grid full of thumbnails — a checkmark or an
        // alert, so you don't have to open each item (or squint at its icon-vs-photo state) to
        // tell success from failure. DELETED/CANCELLED already read clearly enough from their own
        // placeholder icon above (trash can, greyed out) not to need one.
        StatusBadge(status = item.status, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp))

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/** Small circular success/failure badge shared by both the grid and list history rows —
 * FINISHED gets a green check, ERRORED a red alert; every other status (DELETED, CANCELLED)
 * already reads clearly enough from its own placeholder icon not to need one. */
@Composable
private fun StatusBadge(status: DownloadStatus, modifier: Modifier = Modifier) {
    val (icon, tint, description) = when (status) {
        DownloadStatus.FINISHED -> Triple(Icons.Outlined.CheckCircle, SuccessGreen40, "Succeeded")
        DownloadStatus.ERRORED -> Triple(Icons.Outlined.Error, MaterialTheme.colorScheme.error, "Failed")
        else -> return
    }
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    item: DownloadEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // DELETED means the underlying file is confirmed gone — don't bother attempting a load that
    // can only fail, and don't offer open/share actions that would just error out.
    val hasThumbnail = !item.thumbnailPath.isNullOrBlank() && item.status != DownloadStatus.DELETED
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            currentTitle = item.title.ifBlank { item.url },
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle -> onRename(newTitle); showRenameDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // "background" is the top-level scaffold/page role, not a role for a row sitting on
            // top of it as its own distinct surface — this row now reads as a real MD3 surface
            // (surfaceContainer, same tone the Queue screen's own cards use) instead of blending
            // into the page. The selected state used an alpha-blended primaryContainer, which
            // breaks the tonal-pairing contract other content on this row (onSurface title text,
            // not onPrimaryContainer) assumes a fully-opaque container guarantees — full-opacity
            // primaryContainer instead.
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onTap()
                    } else if (hasThumbnail) {
                        // mediaUri is only ever set when thumbnailPath got pointed at a separate
                        // extracted image instead of the real file (audio downloads — see
                        // DownloadEntity.mediaUri's own doc comment); everything else still opens
                        // thumbnailPath itself, same as before mediaUri existed.
                        val uri = Uri.parse(item.mediaUri ?: item.thumbnailPath)
                        // thumbnailPath is the real saved file's own MediaStore URI (see
                        // DownloadWorker.kt's setThumbnail call) — for a video download that's a
                        // video/* file, not an image, so a hardcoded "image/*" here sent every
                        // video open request under the wrong type. Android's "Always" default-app
                        // preference is stored per resolved type, so a video's "Always open with
                        // Google Photos" choice (recorded against video/*) never matched this
                        // screen's requests and the chooser reappeared on every tap (reproduced
                        // live on a Samsung A03 Core). Querying the real type from the content
                        // resolver fixes it for images and videos alike.
                        val realType = context.contentResolver.getType(uri) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, realType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                },
                onLongClick = onLongPress,
            )
            // 8dp, not 10dp — MD3's spacing system is built on an 8dp grid.
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(76.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // shapes.large (16dp) — 14dp was a magic number matching no real shape token.
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (hasThumbnail) {
                    AsyncImage(
                        model = item.thumbnailPath,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val icon = when (item.status) {
                    DownloadStatus.ERRORED -> Icons.Outlined.Warning
                    DownloadStatus.DELETED -> Icons.Outlined.Delete
                    else -> Icons.Outlined.Image
                }
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
                    }
                }

                if (item.downloadedItems > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${item.downloadedItems}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (item.isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Star, contentDescription = "Favorite", tint = FavoriteGold, modifier = Modifier.size(11.dp))
                    }
                }

                StatusBadge(status = item.status, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp))
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                }
            }
        }

        // 16dp, not 14dp — same 8dp-grid reasoning as the padding above.
        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { item.url },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val domain = remember(item.url) { runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "Unknown" }
            // Real artist/album beats the domain for an audio download whenever it's known —
            // that's the actual point of capturing this metadata at all (see DownloadEntity's
            // own doc comments on artist/album). Falls back to the domain exactly like before
            // for anything else, including an audio download whose source never reported one.
            // audioSubtitle is shared with DownloadPreviewSheet's own song preview card (see its
            // own doc comment) so the two formats can't drift apart.
            val subtitleText = if (item.isAudio) audioSubtitle(item.artist, item.album) ?: domain else domain
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))

            val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
            MetaRow(icon = Icons.Outlined.Layers, text = "${item.downloadedItems} • ${sdf.format(Date(item.effectiveDate))}")
            Spacer(Modifier.height(2.dp))
            MetaRow(icon = Icons.Outlined.SdStorage, text = "Size • ${formatFileSize(item.totalBytes)}")
        }

        // Bulk actions in the selection-mode top bar replace the per-item menu while selecting.
        if (!selectionMode) Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (item.isFavorite) "Remove from Favorites" else "Add to Favorites") },
                    leadingIcon = { Icon(Icons.Outlined.Star, contentDescription = null) },
                    onClick = { menuExpanded = false; onToggleFavorite() },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; showRenameDialog = true },
                )
                DropdownMenuItem(
                    text = { Text("Share Image") },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    enabled = hasThumbnail,
                    onClick = {
                        menuExpanded = false
                        val uri = Uri.parse(item.thumbnailPath)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(intent, "Share image")) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("View Original Source") },
                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy Post Link") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        scope.launch {
                            clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("Post link", item.url)))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun MetaRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


