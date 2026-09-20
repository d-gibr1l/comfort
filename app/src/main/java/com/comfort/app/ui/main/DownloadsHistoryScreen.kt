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

// How far the status bar's own text-protection scrim (see its own comment further down) extends
// past the real status bar height before fading to fully transparent — matches Samsung Gallery's
// own version of this same scrim, which doesn't stop exactly at the status bar's own edge either,
// so the fade reads as a soft falloff into the content behind it rather than a hard-edged strip.
private val STATUS_BAR_SCRIM_EXTRA_HEIGHT = 24.dp

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

    // Compact-header behavior ported from the Settings pages' own CollapsingHeaderState (see
    // MoreScreen.kt's rememberCollapsingHeaderState) rather than Material3's TopAppBarState +
    // enterAlwaysScrollBehavior — that mechanism re-expands to FULL size on any upward scroll
    // delta, not only once already back at the list's true top. Reported live against the first
    // version of this feature: "the header is supposed to completely vanish when I scroll down,
    // and scrolling up should bring back the COMPACT version, only expanding to the full header
    // once scrolled all the way back to the top" — three distinct states (hidden / compact /
    // full) enterAlways can't express on its own, since it only interpolates continuously between
    // one fully-open and one fully-closed size with no separate "compact, but not at the very top
    // yet" resting state. This reimplements the same scroll-direction/threshold logic Settings
    // uses, adapted from a plain ScrollState's single scrollState.value (Settings' pages are all
    // a fixed verticalScroll Column) to LazyListState/LazyGridState, since Library's content is a
    // real lazy list.
    // Ported properly this time: ONE header composable that continuously morphs, exactly like
    // Settings' own SettingsSubScaffold/SettingsSubPageHeader/rememberCollapsingHeaderState (see
    // MoreScreen.kt) — not two separate composables (a "full" and a "compact") crossfaded against
    // each other by alpha. That two-composable version is explicitly what Settings' own code
    // comment warns against: "that two-composable version was tried first and reliably showed
    // both at once for a moment (reproduced live as a double title ghost) because one was fading
    // out on its own timer while the other was simultaneously scrolling into view underneath it."
    // Reported live here as the exact same symptom — a persistent double-image ghost during a
    // slow, gradual scroll — for the exact same reason. The fix is architectural, not a tuning
    // knob: collapse by shrinking sub-elements of ONE instance (the subtitle line and the chip
    // row, both down to 0 height) rather than by cross-fading two differently-laid-out instances.
    val density = LocalDensity.current
    // Chip row's own natural height, hoisted up from LibraryHeader (which measures it) so
    // collapseRangePx below can be derived from it — see that val's own comment for why.
    var chipRowHeightPx by remember { mutableFloatStateOf(0f) }
    // NOT a flat 120dp (what Settings' own collapseRangePx is) — collapseRangePx here has to
    // equal the header's own real total shrink amount (title row's 76dp->8dp top-padding delta,
    // the subtitle's 18dp, and the chip row's own height), because topContentPaddingDp below is
    // reserved at the header's fully-EXPANDED height for the entire scroll, never shrinking.
    // Settings tolerates a flat 120dp because its own header happens to shrink by roughly that
    // same amount, so the reserved-but-no-longer-needed space is fully "scrolled through" by the
    // time the header finishes collapsing. Library's shrink amount is larger than 120dp (subtitle
    // + chip row + padding delta together), so a flat 120dp finished the collapse ANIMATION well
    // before scroll had actually consumed that much reserved space — reported live as a large,
    // constant dead-space block between the compact bar and the first real list row, persisting
    // no matter how far past that point you scrolled. Deriving collapseRangePx from the same
    // shrink amount the header itself uses guarantees the two finish together, by construction.
    val collapseRangePx = (with(density) { 68.dp.toPx() + 18.dp.toPx() } + chipRowHeightPx)
        .coerceAtLeast(with(density) { 40.dp.toPx() })
    // A LazyListState/LazyGridState has no single running "total scrolled px" the way
    // ScrollState.value does (what Settings' own version reads directly) — firstVisibleItemScrollOffset
    // resets to 0 every time the first visible item's index advances, so it alone can't drive a
    // threshold/direction comparison the same way. Combining index and offset into one large
    // monotonic value sidesteps that: collapseRangePx is far smaller than a single row's height
    // (list rows run ~180dp tall, confirmed live), so by the time the index has advanced past 0
    // we're already well past collapseRangePx regardless of the exact offset, and multiplying the
    // index by a value much larger than collapseRangePx keeps that true.
    val scrollValuePx by remember {
        derivedStateOf {
            val index = if (gridView) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
            val offset = if (gridView) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset
            index * 1_000_000f + offset
        }
    }
    // Same three-way branch as Settings' own LaunchedEffect: within collapseRangePx of the top,
    // always visible; past it, visible chases scroll direction (down hides, up reveals). Hysteresis
    // (net movement has to clear 8dp in one direction before flipping, not any nonzero amount)
    // kept from the earlier two-composable version — it's still worth having with a single header
    // too, damping the same natural per-frame tremor of a real slow drag that a flip-per-frame
    // reaction would otherwise chase.
    val hysteresisPx = remember(density) { with(density) { 8.dp.toPx() } }
    var headerVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        var previous = scrollValuePx
        var accumulated = 0f
        snapshotFlow { scrollValuePx }.collect { current ->
            val delta = current - previous
            when {
                current <= collapseRangePx -> { headerVisible = true; accumulated = 0f }
                else -> {
                    accumulated = if (accumulated == 0f || (accumulated > 0f) == (delta > 0f)) accumulated + delta else delta
                    if (accumulated > hysteresisPx) { headerVisible = false; accumulated = 0f }
                    else if (accumulated < -hysteresisPx) { headerVisible = true; accumulated = 0f }
                }
            }
            previous = current
        }
    }
    val headerAlpha by animateFloatAsState(if (headerVisible) 1f else 0f, label = "library-header-alpha")
    // 0 at rest (fully expanded), 1 once fully scrolled/collapsed — see Settings' own
    // CollapsingHeaderState doc comment. Continuous, not a discrete swap: the subtitle line and
    // the chip row both shrink away in step with this (see the header composable below), so by
    // the time it's fully collapsed the compact bar is just icon+title+actions with no space
    // above or below it, and there's no separate "collapsed layout" to jump-cut into.
    val collapseFraction = (scrollValuePx / collapseRangePx).coerceIn(0f, 1f)

    // The header's own natural (fully expanded) height in px, tracked maxOf like Settings' own
    // maxHeaderHeightPx.
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    var selectionBarHeightPx by remember { mutableFloatStateOf(0f) }
    // NOT fixed at headerHeightPx for the whole scroll, unlike Settings' own maxHeaderHeightPx
    // padding — Settings gets away with a fixed reservation because its header's own shrink
    // amount is a modest fraction of its full height, so the leftover reserved-but-unneeded space
    // is small enough not to read as a real gap. Library's header sheds a much bigger fraction of
    // its own height (subtitle + the entire chip row, on top of the title row's own padding
    // shrink) — reported live as a large, constant blank block sitting between the compact bar
    // and the first real list row, persisting no matter how far past that point the list was
    // scrolled, because the reservation never gave back the space the collapse animation had
    // already finished reclaiming visually. Lerping the reservation itself from the full expanded
    // height down to (headerHeightPx - collapseRangePx) — the header's own true compact height,
    // computed algebraically from the exact same shrink amount collapseRangePx already represents,
    // not a second, separately-measured value that could drift out of sync with it — means the
    // reserved space and the header's own visible size finish shrinking at exactly the same
    // moment, by construction.
    val topContentPaddingDp = with(density) {
        if (selectionMode) {
            selectionBarHeightPx.toDp()
        } else {
            androidx.compose.ui.unit.lerp(
                headerHeightPx.toDp(),
                (headerHeightPx - collapseRangePx).coerceAtLeast(0f).toDp(),
                collapseFraction,
            )
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
        // Wraps the whole branch below (previously each ending in its own early return) so the
        // bottom gradient scrim further down can sit as one unconditional sibling instead of
        // needing to be duplicated into every branch — see that scrim's own comment for why it
        // exists at all.
        Box(modifier = Modifier.fillMaxSize()) {
        if (showDuplicatesOnly) {
            if (duplicateAttempts.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.ContentCopy,
                    title = "No duplicates",
                    subtitle = "A link you share in that's already queued, running, or finished lands here instead of starting a second copy.",
                    modifier = Modifier.padding(top = topContentPaddingDp, bottom = navBarClearance()),
                )
            } else {
                DuplicatesList(
                    attempts = duplicateAttempts,
                    contentPadding = PaddingValues(top = topContentPaddingDp, bottom = navBarClearance()),
                    onRedownload = { viewModel.redownloadDuplicate(it) },
                    onDismiss = { viewModel.dismissDuplicateAttempt(it) },
                )
            }
        } else if (visibleItems.isEmpty()) {
            val searching = searchQuery.isNotBlank()
            EmptyState(
                icon = if (searching) Icons.Outlined.Search else if (showDeletedOnly) Icons.Outlined.Delete else if (favoritesOnly) Icons.Outlined.Star else Icons.Outlined.Image,
                title = if (searching) "No matches" else if (showDeletedOnly) "Nothing deleted" else if (favoritesOnly) "No favorites yet" else "Nothing here yet",
                subtitle = if (searching) "Try a different search." else if (showDeletedOnly) "Pictures you remove from your device gallery will show up here." else if (favoritesOnly) "Star a download to pin it here." else "Downloaded pictures will show up in this gallery.",
                modifier = Modifier.padding(top = topContentPaddingDp, bottom = navBarClearance()),
            )
        } else if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(top = topContentPaddingDp, start = 8.dp, end = 8.dp, bottom = navBarClearance()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
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
                contentPadding = PaddingValues(top = topContentPaddingDp, bottom = navBarClearance()),
                modifier = Modifier.fillMaxSize(),
            ) {
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

        // A visual "floor" at the bottom mirroring where the header used to fade into the status
        // bar at the top — once the header's own real content (chip row, subtitle) has shrunk
        // away, content reaches edge-to-edge with nothing softening where it meets the floating
        // nav pill either, which read as an abrupt, unfinished edge rather than an intentional
        // one. Tied to collapseFraction (0 fully expanded, 1 fully collapsed) so it fades in
        // exactly as the header finishes collapsing. FloatingNavBar itself (MainScreen.kt)
        // composes after — on top of — this whole screen, so this scrim sits correctly behind the
        // pill without this screen needing to know anything about it directly.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBarClearance())
                .graphicsLayer { alpha = collapseFraction }
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
        )

        // The header is opaque and covers the status bar whenever it's visible at all, same as
        // Settings' own sub-page headers — but once it's faded away entirely (scrolled down far
        // enough, past collapseRangePx, in the "hidden" direction), nothing paints behind the
        // status bar any more. This is a real "status bar text protection" scrim (a Samsung
        // Gallery-style treatment): pure black, alpha-blended, fading to fully transparent —
        // legible white status bar icons against literally any content scrolled underneath, not
        // just this app's own palette. Extends STATUS_BAR_SCRIM_EXTRA_HEIGHT past the real status
        // bar height rather than stopping exactly at it, so the fade reads as a soft falloff into
        // whatever's scrolled there rather than a hard-edged strip — same reasoning Samsung's own
        // implementation uses.
        val statusBarPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
        val statusBarScrimAlpha = 1f - headerAlpha
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(with(density) { statusBarPx.toDp() } + STATUS_BAR_SCRIM_EXTRA_HEIGHT)
                .graphicsLayer { alpha = statusBarScrimAlpha }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent)))
        )

        val showScrollToTopFab by remember {
            derivedStateOf {
                !showDuplicatesOnly &&
                    (if (gridView) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex) >= 6
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
        } else {
            // Backdrop sized to match topContentPaddingDp exactly (same lerp, same collapseFraction
            // — not the earlier version's fixed headerHeightPx), faded by the same headerAlpha as
            // the real header drawn on top of it. NOT derived from measuring the header itself,
            // unlike an earlier attempt that made the header's own Modifier.layout claim this same
            // height directly: that measured the header with the *incoming* constraints
            // unmodified, and something about this Box's position inside a fillMaxSize() parent
            // fed back into a runaway claimed height, reproduced live as a large, constant blank
            // gap at the top of the screen. A separate, non-measuring Box has no such feedback
            // risk. It has to shrink in step with topContentPaddingDp, not stay fixed at the full
            // expanded height: once that reservation itself started shrinking (see its own
            // comment on why a fixed reservation was the real bug), a backdrop still fixed at the
            // old full height would now extend past the (smaller) reserved area and paint over
            // real list rows that have scrolled up into what used to be reserved space.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(topContentPaddingDp)
                    .graphicsLayer { alpha = headerAlpha }
                    .background(MaterialTheme.colorScheme.background),
            )
            LibraryHeader(
                libraryTopicIcon = libraryTopicIcon,
                collapseFraction = collapseFraction,
                onChipRowHeightChange = { chipRowHeightPx = it },
                searchExpanded = searchExpanded,
                onSearchExpandedChange = { searchExpanded = it },
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                subtitle = if (showDuplicatesOnly) {
                    "${duplicateAttempts.size} ${if (duplicateAttempts.size == 1) "duplicate" else "duplicates"}"
                } else {
                    "${visibleItems.size} ${if (visibleItems.size == 1) "item" else "items"}"
                },
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
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer { alpha = headerAlpha }
                    .background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { headerHeightPx = maxOf(headerHeightPx, it.height.toFloat()) }
                    .statusBarsPadding(),
            )
        }
    }
}

/** Library's own single, continuously-morphing header — ported from Settings' own
 * SettingsSubPageHeader/SettingsSubScaffold (see MoreScreen.kt), which uses exactly one header
 * instance whose own sub-elements shrink away as [collapseFraction] goes from 0 (fully expanded,
 * at rest) to 1 (fully collapsed), rather than two separate composables crossfaded by alpha
 * against each other — see this composable's caller for why that first approach caused a visible
 * double-image ghost during a slow scroll. Here, the subtitle line and the chip row both shrink
 * to 0 height (not just alpha) as [collapseFraction] increases; the icon+title+actions row itself
 * never changes shape, just gains or loses the space those two pieces used to take up around it,
 * so the compact bar "grows into" the full header (and back) exactly in step with the finger. */
@Composable
private fun LibraryHeader(
    libraryTopicIcon: ImageVector,
    collapseFraction: Float,
    onChipRowHeightChange: (Float) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Box(modifier = modifier.fillMaxWidth()) {
        // Favorites/Grid-List/Queue, pinned at a fixed top=12dp position independent of the
        // icon+title/search row below — reported live after they briefly shared that row: sharing
        // it meant they inherited the row's own 76dp->8dp top-padding lerp meant for the title,
        // dragging them down to the title's (now lower, Settings-matching) vertical position
        // instead of staying at their own long-standing spot near the status bar. A separate
        // overlay row, independent of collapseFraction entirely, keeps them exactly where they
        // were. Hidden while searching, same as the row below — floating above an otherwise-empty
        // search field read as leftover clutter, not part of the search UI.
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
        AnimatedContent(targetState = searchExpanded, label = "library-header-row") { expanded ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // top lerps 76dp -> 8dp, same values Settings' own header uses (see
                    // rememberCollapsingHeaderState's expandedTopPadding/collapsedTopPadding) —
                    // previously a fixed 12dp, which reproduced live as two separate complaints:
                    // the title/search sitting higher than intended, and scrolling not visibly
                    // "pushing" the header up the way it does on Settings' sub-pages (only the
                    // subtitle/chip row below it were shrinking; the row itself never moved).
                    // Continuous with collapseFraction, not a discrete jump, so this row itself
                    // is what "grows into" the compact bar, exactly like Settings' own title row.
                    .padding(
                        start = 20.dp,
                        end = 12.dp,
                        top = androidx.compose.ui.unit.lerp(76.dp, 8.dp, collapseFraction),
                        bottom = 2.dp,
                    ),
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
                        // Shrinks to 0 height (not just alpha) as collapseFraction -> 1, so the
                        // compact bar reclaims the space entirely instead of leaving it
                        // empty-but-reserved — same technique as Settings' own back-button row
                        // (see SettingsSubPageHeader).
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(density) { androidx.compose.ui.unit.lerp(18.dp, 0.dp, collapseFraction) })
                                .clipToBounds(),
                        ) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.graphicsLayer { alpha = 1f - collapseFraction },
                            )
                        }
                    }
                    // Shrinks away (width, not just alpha) as collapseFraction -> 1, same
                    // reasoning as the subtitle above — the compact bar is title + the 3 icon
                    // buttons in their own fixed overlay row only, no search, per the original
                    // scope this compact bar was asked for.
                    Box(
                        modifier = Modifier
                            .width(with(density) { androidx.compose.ui.unit.lerp(40.dp, 0.dp, collapseFraction) })
                            .clipToBounds(),
                    ) {
                        IconButton(
                            onClick = { onSearchExpandedChange(true) },
                            modifier = Modifier
                                .size(40.dp)
                                .graphicsLayer { alpha = 1f - collapseFraction },
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
        }
        // Same shrink-to-0-height intent as the subtitle above, but NOT the same
        // `.height(lerp(measuredPx.toDp(), 0.dp, fraction))` technique — unlike the subtitle's
        // fixed 18dp target, chipRowHeightPx here is itself derived from measuring this row, and
        // a plain `.height()` modifier is also an incoming constraint on that same child: at
        // collapseFraction=0 with chipRowHeightPx still at its initial 0, that constrains the Row
        // to an exact 0dp, which is also what it then measures and reports back via
        // onSizeChanged, permanently — a circular deadlock. Reproduced live: the chip row never
        // appeared at all, even fully expanded at rest. Modifier.layout sidesteps it exactly like
        // the header's own former collapsing-Surface trick did (see git history): measure the Row
        // with maxHeight = Infinity so it always reports its one true natural size regardless of
        // how much is currently visible, and only the *placement* — this node's own reported
        // height, coerced into [0, natural] by collapseFraction — actually shrinks.
        // Reported up to the caller via onChipRowHeightChange, not just kept local — the caller
        // needs this same value to derive collapseRangePx (see its own comment on why a flat
        // 120dp isn't enough), so the scroll distance needed to finish collapsing and the amount
        // of reserved space that scroll actually consumes stay exactly in sync.
        var chipRowHeightPx by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    if (placeable.height > chipRowHeightPx) {
                        chipRowHeightPx = placeable.height.toFloat()
                        onChipRowHeightChange(chipRowHeightPx)
                    }
                    val visibleHeight = (placeable.height * (1f - collapseFraction)).toInt()
                    layout(placeable.width, visibleHeight) { placeable.placeRelative(0, 0) }
                },
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
                    .padding(top = 8.dp, bottom = 10.dp)
                    .onSizeChanged { chipRowHeightPx = maxOf(chipRowHeightPx, it.height.toFloat()) },
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
    IconButton(onClick = onOpenQueue) {
        if (hasActiveDownloads) {
            BadgedBox(
                badge = {
                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                        Text(activeDownloadsCount.toString())
                    }
                }
            ) {
                Icon(Icons.Outlined.Download, contentDescription = "Active downloads")
            }
        } else {
            Icon(Icons.Outlined.Download, contentDescription = "Active downloads")
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


