package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import com.comfort.app.viewmodel.DownloadsViewModel
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.rememberIsNetworkAvailable
import com.comfort.app.util.rememberIsReducedMotionEnabled
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit
) {
    val queueItems by viewModel.queueFlow.collectAsStateWithLifecycle()
    val isGloballyPaused by viewModel.isGloballyPaused.collectAsStateWithLifecycle()
    // Only watched here for the finished-download toast below — the Queue's own list is
    // everything NOT finished/saved/deleted (see DownloadDao.getQueueFlow), so a finished item is
    // only ever visible via historyFlow, never queueItems itself.
    val historyItems by viewModel.historyFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    DownloadEventSnackbars(historyItems = historyItems, queueItems = queueItems, snackbarHostState = snackbarHostState)
    // Every delete on this screen (bulk, one item's "Remove", swipe-to-dismiss) is undoable —
    // see rememberUndoableDelete.
    val requestDelete = rememberUndoableDelete(viewModel, snackbarHostState)

    // Lets a QUEUED item's card explain *why* it's stuck (no usable network right now) instead of
    // just "Waiting to start…" forever with no visible reason — reproduced live: a WorkManager job
    // sitting on an unsatisfied CONNECTIVITY constraint because the current Wi-Fi network was
    // connected but never validated by the OS (some hotspot/captive-portal setups never do).
    val isNetworkAvailable = rememberIsNetworkAvailable()
    // Downloads held only for a connection (see the Running tab's empty state).
    val waitingForNetwork = if (isNetworkAvailable) 0 else queueItems.count { it.status == DownloadStatus.QUEUED }
    val queueContext = LocalContext.current
    // Hoisted here, not inside each card's own `remember(item.id)` below — a LazyColumn destroys
    // and recreates an item's composable as it scrolls off-screen and back on, which reset a
    // purely-local remember back to its initial "not yet animated" state every time. Reproduced
    // live: the whole visible list kept re-playing its slide-up entrance animation on every scroll
    // up/down, not just once when a card was genuinely new. This map, owned by the screen instead
    // of the item, remembers which ids have already played their entrance once and never resets —
    // Modifier.animateItem() below already handles the smooth reflow/removal animation on its own,
    // so this only ever needs to gate the one-time entrance.
    val alreadyAnimatedIds = remember { mutableSetOf<String>() }
    MarkShownAfterFirstFrame(alreadyAnimatedIds, queueItems) { it.id }
    // better-interface review: the entrance slide below (and the wavy progress indicator's wave
    // motion in QueueItemCard) animated unconditionally, with nothing checking the OS-level
    // reduce-motion setting.
    val reducedMotion = rememberIsReducedMotionEnabled()
    var selectedFilter by remember { mutableStateOf("Running") }
    val filters = listOf("Running", "In Queue", "Paused", "Scheduled", "Errored", "Cancelled")

    // Multi-select: entered via a long-press on any card's thumbnail (see QueueItemCard/StoppedRow),
    // not a dedicated mode toggle — matches how the request was framed ("make cards selectable by
    // long pressing"). selectedIds is cleared (which also drops back out of selection mode, see
    // toggleSelected below) whenever the filter tab changes, since a selection tied to one status
    // list stops making sense once a different list is showing.
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedIds.isNotEmpty()
    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    // Takes priority over MainScreen's own predictive-back handling for this whole screen (a
    // system back press/gesture while selecting should clear the selection, not leave Queue
    // entirely) — same pattern already proven in DownloadsHistoryScreen's own multi-select.
    // Reproduced live before this: back while selecting jumped straight out to Library instead.
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }
    // Swiping sideways moves between the filters, in their chips' order (PageSwipe.kt). Not while
    // selecting: a selection belongs to one filter.
    val filterSwipe = rememberPageSwipeState(
        current = filters.indexOf(selectedFilter),
        allowed = !selectionMode,
        pageCount = filters.size,
        onSettled = { selectedFilter = filters[it] },
    )

    // The "Add cookies" error-card action opens this for the failing item's own site, then
    // retries that same download once cookies are extracted.
    var cookieLoginTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // (loginUrl, downloadId)
    // Holds just the id, not a captured DownloadEntity snapshot — resolving it live against
    // queueItems below means the open sheet always reflects this download's current error instead
    // of freezing whatever errorMessage/erroredAt it had at the moment the sheet was opened (e.g.
    // an auto-retry re-erroring with different text while the sheet is still up). Also means the
    // sheet auto-dismisses if the row disappears entirely (deleted, or moved out of ERRORED).
    var errorSheetItemId by remember { mutableStateOf<String?>(null) }
    val errorSheetItem = errorSheetItemId?.let { id -> queueItems.find { it.id == id } }
    errorSheetItem?.let { item ->
        ErrorDetailsSheet(
            item = item,
            onDismiss = { errorSheetItemId = null }
        )
    }

    // "Start at…" on a waiting or paused card. By id, like the error sheet: it closes by itself if
    // the download starts or goes meanwhile.
    var startAtItemId by remember { mutableStateOf<String?>(null) }
    val startAtItem = startAtItemId?.let { id ->
        queueItems.find { it.id == id && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED, DownloadStatus.PAUSED, DownloadStatus.RUNNING) }
    }
    startAtItem?.let { item ->
        val running = item.status == DownloadStatus.RUNNING
        StartAtDialog(
            initial = item.scheduledAt.takeIf { !running },
            onDismiss = { startAtItemId = null },
            onPicked = { at -> startAtItemId = null; viewModel.scheduleAt(item.id, at) },
            // A running one has no time of its own to take off.
            onRemove = if (item.scheduledAt != null && !running) ({ startAtItemId = null; viewModel.scheduleAt(item.id, null) }) else null,
            note = if (running) stringResource(R.string.start_at_running_note) else null,
        )
    }
    
    cookieLoginTarget?.let { (loginUrl, downloadId) ->
        CookieLoginDialog(
            loginUrl = loginUrl,
            onDismiss = { cookieLoginTarget = null },
            onCookiesSaved = {
                cookieLoginTarget = null
                viewModel.retryDownload(downloadId)
            },
        )
    }

    // Reflects reality even if every download ended up PAUSED one at a time (not via this FAB) —
    // the button should still offer "Resume" rather than staying stuck on "Pause" against a queue
    // that has nothing left to pause.
    val hasActiveDownload = queueItems.any {
        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED
    }
    val hasPausedDownload = queueItems.any { it.status == DownloadStatus.PAUSED }
    val showResumeAction = isGloballyPaused || (!hasActiveDownload && hasPausedDownload)

    // Hoisted above the Scaffold so the FAB can react to both the current filter tab (Errored/
    // Cancelled get "Retry All" instead of Pause/Resume) and what's actually in it. No more
    // catch-all "All" tab — every status has its own explicit chip now, so this is exhaustive.
    fun itemsFor(filter: String) =
        queueItems.filter { item ->
            when (filter) {
                "Running" -> item.status == DownloadStatus.RUNNING
                "In Queue" -> item.status == DownloadStatus.QUEUED
                "Scheduled" -> item.status == DownloadStatus.SCHEDULED
                "Paused" -> item.status == DownloadStatus.PAUSED
                "Errored" -> item.status == DownloadStatus.ERRORED
                "Cancelled" -> item.status == DownloadStatus.CANCELLED
                else -> false
            }
        }
    val filteredItems = remember(queueItems, selectedFilter) { itemsFor(selectedFilter) }
    val retryAllStatus = when (selectedFilter) {
        "Errored" -> DownloadStatus.ERRORED
        "Cancelled" -> DownloadStatus.CANCELLED
        else -> null
    }

    // A selection tied to one status list stops making sense once a different list is showing.
    LaunchedEffect(selectedFilter) { selectedIds = emptySet() }

    // queueItems updates live from a background StateFlow, so a selected item can finish, error
    // out into a different tab's status, or get deleted while it's still selected — e.g. it
    // auto-retries and moves out of the Errored tab, or a bulk action elsewhere removes it. Drop
    // any selected id the instant it's no longer in filteredItems, so the selection count/actions
    // never operate on a phantom id that isn't even on screen any more.
    LaunchedEffect(filteredItems) {
        val visibleIds = filteredItems.mapTo(HashSet()) { it.id }
        if (selectedIds.any { it !in visibleIds }) {
            selectedIds = selectedIds intersect visibleIds
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // No floating bottom nav bar overlaying this screen (it's a full-screen overlay on top of
        // MainScreen, not one of its tabs — see MainScreen's own showQueueScreen handling), so
        // unlike Library's own snackbarHost this needs no extra bottom padding to clear one.
        snackbarHost = { DownloadEventSnackbarHost(snackbarHostState) },
        floatingActionButtonPosition = if (selectionMode) FabPosition.Center else FabPosition.End,
        floatingActionButton = {
            if (selectionMode) {
                val selection = selectedIds
                SelectionActionBar(
                    filter = selectedFilter,
                    // One bulk action each, not one per selected download: those ran all at once, in
                    // no particular order, and the cards moved a few at a time.
                    onPause = { viewModel.pauseDownloads(selection); selectedIds = emptySet() },
                    onCancel = { viewModel.cancelDownloads(selection); selectedIds = emptySet() },
                    onResume = { viewModel.retryDownloads(selection); selectedIds = emptySet() },
                    onRetry = { viewModel.retryDownloads(selection); selectedIds = emptySet() },
                    // Not cancelled first: the delete that runs once the Undo snackbar passes
                    // (DownloadDispatcher.deleteDownload) stops each one's job itself. Cancelling
                    // up front meant Undo brought running downloads back cancelled.
                    onDelete = { requestDelete(selection); selectedIds = emptySet() },
                )
            }
            if (!selectionMode) {
                if (retryAllStatus != null && filteredItems.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.retryAll(retryAllStatus) },
                        icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        text = { Text(stringResource(R.string.retry_all)) },
                    )
                } else if (hasActiveDownload || hasPausedDownload || isGloballyPaused) {
                    // isGloballyPaused on its own (queue otherwise empty) still needs this FAB shown —
                    // it's the only surface anywhere in the app for isGloballyPaused/setGloballyPaused.
                    // Without it, pausing everything and then clearing the queue (cancel/delete every
                    // item) hid the only control that could flip it back off, leaving every download
                    // added afterward silently stuck PAUSED until the queue happened to gain a
                    // RUNNING/PAUSED item again on its own.
                    ExtendedFloatingActionButton(
                        onClick = { if (showResumeAction) viewModel.resumeAll() else viewModel.pauseAll() },
                        icon = {
                            Icon(
                                if (showResumeAction) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                contentDescription = null,
                            )
                        },
                        text = { Text(if (showResumeAction) stringResource(R.string.resume) else stringResource(R.string.widget_music_pause)) },
                    )
                }
            }
        },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    // The count rolls up or down as cards are picked, rather than the text just
                    // swapping.
                    title = {
                        androidx.compose.animation.AnimatedContent(
                            targetState = selectedIds.size,
                            transitionSpec = {
                                val up = targetState > initialState
                                (androidx.compose.animation.slideInVertically(tween(180)) { if (up) it else -it } + fadeIn(tween(180)))
                                    .togetherWith(androidx.compose.animation.slideOutVertically(tween(180)) { if (up) -it else it } + fadeOut(tween(120)))
                            },
                            label = "selected count",
                        ) { count ->
                            Text(stringResource(R.string.x_selected, count), fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = {
                        FilledTonalIconButton(
                            onClick = { selectedIds = emptySet() },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel_selection))
                        }
                    },
                    actions = {
                        val allSelected = filteredItems.isNotEmpty() && filteredItems.all { it.id in selectedIds }
                        // A labelled pill with a three-state box (none, some, all) rather than a bare
                        // glyph: what a tap does (select all, or clear) is in its words.
                        val boxState = when {
                            allSelected -> androidx.compose.ui.state.ToggleableState.On
                            selectedIds.isEmpty() -> androidx.compose.ui.state.ToggleableState.Off
                            else -> androidx.compose.ui.state.ToggleableState.Indeterminate
                        }
                        Surface(
                            onClick = { selectedIds = if (allSelected) emptySet() else filteredItems.map { it.id }.toSet() },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 4.dp, end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TriStateCheckbox(state = boxState, onClick = null, modifier = Modifier.padding(8.dp).size(20.dp))
                                Text(
                                    if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        // The actions are on the floating bar at the bottom (SelectionActionBar).
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
            // Outside selection mode the header is the Settings-style collapsing overlay drawn
            // inside the content below, not a Scaffold topBar.
        }
    ) { paddingValues ->
        // Same header concept as the Settings sub-pages (see SettingsSubScaffold): one pinned
        // overlay — back button above a large icon + title — that collapses into a compact bar
        // over the first stretch of scroll, hides while scrolling down, reappears on the way back
        // up, with the content reserving exactly its fully-expanded height. The filter chips ride
        // inside that overlay, right under the title, so they stay reachable in the compact bar
        // too — hence the small bottom padding (the chip row brings its own spacing).
        // One list state per filter: the list a swipe brings in carries on as the current one,
        // where it already is, with the header following it.
        val listStates = remember { HashMap<String, androidx.compose.foundation.lazy.LazyListState>() }
        fun listStateFor(filter: String) = listStates.getOrPut(filter) { androidx.compose.foundation.lazy.LazyListState() }
        val listState = listStateFor(selectedFilter)
        val headerState = rememberLazyCollapsingHeaderState(
            listState,
            expandedTopPadding = 76.dp,
            expandedBottomPadding = 0.dp,
            collapsedBottomPadding = 0.dp,
        )
        val density = LocalDensity.current
        val defaultHeaderHeightPx = remember(density) { with(density) { 180.dp.roundToPx() } }
        val defaultSelectionHeaderHeightPx = remember(density) { with(density) { 104.dp.roundToPx() } }
        var maxHeaderHeightPx by remember { mutableStateOf(defaultHeaderHeightPx) }
        var selectionHeaderHeightPx by remember { mutableStateOf(defaultSelectionHeaderHeightPx) }
        // Only the top inset is taken from Scaffold (in selection mode, its TopAppBar, measured
        // together with the chips under it); bottom clearance comes solely from the LazyColumn's
        // own contentPadding, since reserving Scaffold's FAB-driven bottom inset as well
        // double-counted it.
        val topReserve = with(density) {
            (if (selectionMode) selectionHeaderHeightPx else maxHeaderHeightPx).toDp()
        }
        // A new tab starts from the top, with the header fully expanded again, rather than
        // inheriting the previous tab's scroll position (possibly past the new tab's end).
        LaunchedEffect(selectedFilter) { listState.scrollToItem(0) }
        val scrollConnection = remember(selectionMode, headerState.nestedScrollConnection) {
            if (selectionMode) object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {}
            else headerState.nestedScrollConnection!!
        }
        Box(modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection).pageSwipeArea(filterSwipe)) {
            // better-interface review: this filter row (6 chips: Running/In Queue/Scheduled/
            // Paused/Errored/Cancelled) has the same undiscoverable-overflow problem the Library
            // toolbar row had — nothing on screen hints that "Cancelled" sits off past the visible
            // edge on a typical phone width. Same fix applied here: a one-time nudge (auto-scroll
            // right, then back) on first appearance, gated behind reduced motion. Scrolls by a
            // deliberately oversized distance rather than a small hinting bump — animateScrollBy
            // clamps at the real end on its own, so this reliably reveals "Cancelled" regardless
            // of how many chips are hidden, instead of a small nudge that (reported live) stopped
            // short of it.
            val filterListState = rememberLazyListState()
            val filterNudgePx = with(LocalDensity.current) { 1000.dp.toPx() }
            // Keyed on reducedMotion (not Unit) so toggling it mid-delay restarts this effect with
            // the current value instead of running out the rest of the delay against whatever
            // reducedMotion happened to read when the effect first started — that stale captured
            // value, not a live re-read, is what the check below sees since it's a plain Boolean,
            // not a State, by the time it's used here.
            // Only the first few times the page opens (GalleryDlPreferences.takeQueueChipsHint):
            // on every visit it was more distraction than hint.
            val hintContext = LocalContext.current
            LaunchedEffect(reducedMotion) {
                delay(500)
                if (!reducedMotion && filterListState.canScrollForward &&
                    com.comfort.app.data.GalleryDlPreferences.takeQueueChipsHint(hintContext)) {
                    filterListState.animateScrollBy(filterNudgePx, animationSpec = tween(450))
                    delay(250)
                    filterListState.animateScrollBy(-filterNudgePx, animationSpec = tween(450))
                }
            }
            LaunchedEffect(selectedFilter) {
                val index = filters.indexOf(selectedFilter)
                val info = filterListState.layoutInfo
                val chip = info.visibleItemsInfo.firstOrNull { it.index == index }
                if (chip == null || chip.offset < info.viewportStartOffset || chip.offset + chip.size > info.viewportEndOffset) {
                    filterListState.animateScrollToItem(index)
                }
            }
            // The pinned overlay: the Settings-style header plus the filter chips under it, one
            // unit that collapses and hides/reappears together. In selection mode the header part
            // gives way to Scaffold's selection TopAppBar, but the chips stay. zIndex keeps it (and
            // the scrim) drawn above the list declared after it.
            if (!selectionMode) {
                StatusBarScrim(
                    alpha = { 1f - headerState.reveal.fraction },
                    modifier = Modifier.align(Alignment.TopStart).zIndex(2f),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .fillMaxWidth()
                    .then(if (selectionMode) Modifier else Modifier.compactHeaderReveal(headerState.reveal))
                    .background(MaterialTheme.colorScheme.background)
                    .then(
                        if (selectionMode) Modifier
                            .onSizeChanged { selectionHeaderHeightPx = it.height }
                            .padding(top = paddingValues.calculateTopPadding())
                        // Measured outside the status bar inset (same as SettingsSubScaffold) so
                        // the reserved space includes it.
                        else Modifier
                            .onSizeChanged { maxHeaderHeightPx = maxOf(maxHeaderHeightPx, it.height) }
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ),
            ) {
                if (!selectionMode) {
                    SettingsSubPageHeader(
                        title = stringResource(R.string.queue),
                        topicIcon = Icons.Outlined.DownloadForOffline,
                        onBack = onBack,
                        topPadding = headerState.topPadding,
                        bottomPadding = headerState.bottomPadding,
                        collapseFraction = headerState.collapseFraction,
                        includeHorizontalPadding = true,
                    )
                }
            val countsByStatus = remember(queueItems) { queueItems.groupingBy { it.status }.eachCount() }
            LazyRow(
                state = filterListState,
                // contentPadding (not an outer Modifier.padding) so the scrollable viewport spans
                // the full screen width — chips scroll flush to the true edge instead of getting
                // clipped mid-chip right at an inset boundary, which read as "cut off."
                modifier = Modifier
                    .fillMaxWidth()
                    // No top padding under the title: the count badges poking above the chips
                    // still show, since a horizontal row only clips left/right. Under the
                    // selection TopAppBar they need room, or the bar (drawn over this) cuts them.
                    .padding(top = if (selectionMode) 8.dp else 0.dp, bottom = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters, key = { it }) { filter ->
                    val count = when (filter) {
                        "Running" -> countsByStatus[DownloadStatus.RUNNING] ?: 0
                        "In Queue" -> countsByStatus[DownloadStatus.QUEUED] ?: 0
                        "Scheduled" -> countsByStatus[DownloadStatus.SCHEDULED] ?: 0
                        "Paused" -> countsByStatus[DownloadStatus.PAUSED] ?: 0
                        "Errored" -> countsByStatus[DownloadStatus.ERRORED] ?: 0
                        "Cancelled" -> countsByStatus[DownloadStatus.CANCELLED] ?: 0
                        else -> 0
                    }
                    BadgedBox(
                        badge = {
                            if (count > 0) {
                                Badge(
                                    containerColor = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                    // A long count grows back over the chip, not past its
                                    // end: "1,234" on the last chip ran off the screen's edge.
                                    // placeRelative: rightward in a right-to-left language, where
                                    // the badge sits on the chip's left corner.
                                    modifier = Modifier.layout { measurable, constraints ->
                                        val badge = measurable.measure(constraints)
                                        val grow = (badge.width - 16.dp.roundToPx()).coerceAtLeast(0)
                                        layout(badge.width, badge.height) { badge.placeRelative(-grow, 0) }
                                    },
                                ) {
                                    // The full count, not badgeCountText's "99+": a chip has room
                                    // for it, unlike the icon badges that cap is for.
                                    Text(java.text.NumberFormat.getIntegerInstance().format(count))
                                }
                            }
                        }
                    ) {
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(queueFilterLabel(filter)) },
                            shape = com.comfort.app.theme.PillShape
                        )
                    }
                }
            }
            }

            // The filter's list (or its empty state) — and while a sideways swipe moves between
            // filters, the neighbouring filter's beside it (PageSwipe.kt). Keyed per filter, so the
            // list a swipe brings in stays the same instance once it's the current one.
            val currentIndex = filters.indexOf(selectedFilter)
            // The tabs either side are kept built (measured, not placed: not drawn, not touchable),
            // so a swipe doesn't have to build the page it brings in. Building it as the swipe
            // started stalled a frame (~80ms for the Errored tab's cards), and the swipe's speed
            // read as 0 across the stall, so a quick flick snapped back.
            val shownFilters = (
                listOf(selectedFilter) +
                    listOfNotNull(filters.getOrNull(currentIndex - 1), filters.getOrNull(currentIndex + 1), filterSwipe.neighbor?.let { filters[it] })
            ).distinct()
            CompositionLocalProvider(LocalPageSwipe provides filterSwipe) {
            for (filter in shownFilters) key(filter) {
                val pageIndex = filters.indexOf(filter)
                val pageItems = remember(queueItems, filter) { itemsFor(filter) }
                val pageListState = listStateFor(filter)
                // Brought in by a swipe: from its top, as a tap on its chip shows it.
                LaunchedEffect(Unit) { if (filter != selectedFilter) pageListState.scrollToItem(0) }
                Box(
                    modifier = Modifier.fillMaxSize().then(
                        when {
                            filterSwipe.involves(pageIndex) -> Modifier.pageSwipeLayer(filterSwipe, pageIndex, currentIndex)
                            filter == selectedFilter -> Modifier
                            else -> Modifier.layout { measurable, constraints ->
                                measurable.measure(constraints)
                                layout(0, 0) {}
                            }
                        }
                    )
                ) {
                if (pageItems.isEmpty()) {
                    // Outside the list, centered in the space actually left below the header (and
                    // above the bottom nav), so it moves down along with the taller header.
                    // Tab-specific copy — "Paste a link on Home to start one" only actually helps on
                    // the tabs that describe genuinely-no-work-at-all (Running/In Queue/Scheduled);
                    // Paused/Errored/Cancelled describe downloads that already went through some other
                    // state, so the same generic line there read as a non sequitur (reported live:
                    // the Paused tab said "No downloads in queue" while Errored/Cancelled next to it
                    // had real counts, which read as those two other tabs error being wrong).
                    val (emptyTitle, emptySubtitle) = when (filter) {
                        "Paused" -> stringResource(R.string.no_paused_downloads) to stringResource(R.string.downloads_you_pause_will_show_up)
                        "Errored" -> stringResource(R.string.no_errored_downloads) to stringResource(R.string.failed_downloads_will_show_up_here)
                        "Cancelled" -> stringResource(R.string.no_cancelled_downloads) to stringResource(R.string.downloads_you_cancel_will_show_up)
                        "Scheduled" -> stringResource(R.string.no_scheduled_downloads) to stringResource(R.string.downloads_waiting_for_their_schedule_window)
                        // Nothing running only because there's no usable connection: say so, rather than
                        // "No downloads in queue" with the In Queue chip showing a count beside it.
                        else -> if (filter == "Running" && waitingForNetwork > 0) {
                            stringResource(R.string.waiting_for_a_network_connection) to networkWaitSubtitle(queueContext, waitingForNetwork)
                        } else {
                            stringResource(R.string.no_downloads_in_queue) to stringResource(R.string.paste_a_link_on_home_to)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topReserve, bottom = navBarClearance()),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = if (filter == "Running" && waitingForNetwork > 0) Icons.Outlined.WifiOff else Icons.Outlined.Inbox,
                            title = emptyTitle,
                            subtitle = emptySubtitle,
                        )
                    }
                }
                LazyColumn(
                    state = pageListState,
                    // Extra bottom inset beyond the normal 16dp: the Pause/Resume/Retry All FAB floats
                    // over the content rather than reserving space for itself, so without this the
                    // last card(s) end up scrolled underneath it, partly unreadable and with their own
                    // action buttons unreachable. navBarClearance() (not a flat 100dp guess) so this
                    // also clears the real system nav bar inset on devices where it's taller than this
                    // app's own FAB assumed — see its doc comment (MainScreen.kt) for the full story.
                    // Cards get their 16dp horizontal inset individually (see animateItem below).
                    contentPadding = PaddingValues(bottom = navBarClearance()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // The header's reserved space as a real item 0, not top contentPadding: the
                    // header's collapse is driven by item 0's own scroll offset (see
                    // rememberLazyCollapsingHeaderState), which is only exact while item 0 is still on
                    // screen. With the first *card* as item 0, a card shorter than the 120dp collapse
                    // range scrolled off part-way through, the index ticked to 1, and collapseFraction
                    // jumped straight to 1 — reported live as the header snapping into its compact form.
                    // This spacer is always taller than that range. Minus the 12dp item spacing that
                    // follows it, so the first card still starts exactly topReserve + 8dp down.
                    item(key = "queue-header-space") {
                        // Clamped: topReserve is 0 until the header's first measure.
                        Spacer(Modifier.height((topReserve + 8.dp - 12.dp).coerceAtLeast(0.dp)))
                    }
                        items(pageItems, key = { it.id }) { item ->
                            // Slide-up + fade-in on first appearance (a newly queued download, or one
                            // scrolling into view for the first time) — targetState flips true right
                            // after this item enters composition, MutableTransitionState's own initial
                            // "false" giving Compose something to animate *from*. Never flips back to
                            // false again from here — a finished/removed item just leaves
                            // pageItems and this composable is disposed outright, so the "crossfade
                            // out and slide the rest up to fill the gap" half of this is entirely
                            // animateItem()'s own built-in fade-out + placement animation below, not
                            // this AnimatedVisibility's exit (deliberately ExitTransition.None).
                            val visibleState = remember(item.id) {
                                MutableTransitionState(alreadyAnimatedIds.contains(item.id)).apply { targetState = true }
                            }
                            SideEffect { alreadyAnimatedIds.add(item.id) }
                            val isSelected = item.id in selectedIds
                            val row = @Composable {
                                if (item.status == DownloadStatus.CANCELLED || item.status == DownloadStatus.PAUSED) {
                                    StoppedRow(
                                        item = item,
                                        onResume = { viewModel.retryDownload(item.id) },
                                        onStartNow = { viewModel.startNow(item.id) },
                                        onStartAt = { startAtItemId = item.id },
                                        onDelete = { requestDelete(setOf(item.id)) },
                                        selectionMode = selectionMode,
                                        selected = isSelected,
                                        onToggleSelect = { toggleSelected(item.id) },
                                    )
                                } else {
                                    QueueItemCard(
                                        item = item,
                                        isNetworkAvailable = isNetworkAvailable,
                                        onCancel = { viewModel.cancelDownload(item.id) },
                                        onDelete = { requestDelete(setOf(item.id)) },
                                        onPauseResume = { viewModel.pauseDownload(item.id) },
                                        onRetry = { viewModel.retryDownload(item.id) },
                                        onStartNow = { viewModel.startNow(item.id) },
                                        onStartAt = { startAtItemId = item.id },
                                        onAddCookies = {
                                            val host = runCatching { android.net.Uri.parse(item.url).host }.getOrNull()
                                            val targetUrl = if (!host.isNullOrBlank()) "https://$host" else item.url
                                            cookieLoginTarget = targetUrl to item.id
                                        },
                                        onShowError = { errorSheetItemId = item.id },
                                        selectionMode = selectionMode,
                                        selected = isSelected,
                                        onToggleSelect = { toggleSelected(item.id) },
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visibleState = visibleState,
                                // Reduced motion drops the slide (a vestibular-trigger-shaped movement)
                                // but keeps the fade — brief functional feedback that a new item just
                                // appeared, same as better-accessibility's own guidance distinguishes.
                                enter = if (reducedMotion) fadeIn(tween(350)) else fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 },
                                exit = ExitTransition.None,
                                modifier = Modifier.animateItem().padding(horizontal = 16.dp),
                            ) {
                            // No swipe-to-remove on Queue cards: a sideways swipe anywhere here moves
                            // between the filters (PageSwipe.kt). On a card it went to the card's own
                            // swipe, and the card's page-turning edge strips sit in Android's
                            // back-gesture zone, so fast swipes almost never turned the page. Every
                            // card has its own delete/Cancel button.
                            row()
                            }
                        }
                }
                }
            }
            }
        }
    }
}

/** While selecting: the actions that fit the tab, on a bar floating at the bottom. */
@Composable
private fun SelectionActionBar(
    filter: String,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
                TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = color)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label)
                }
            }
            val normal = MaterialTheme.colorScheme.onSurface
            when (filter) {
                "Running", "In Queue", "Scheduled" -> {
                    action(Icons.Outlined.Pause, stringResource(R.string.widget_music_pause), normal, onPause)
                    action(Icons.Outlined.Close, stringResource(R.string.action_cancel), normal, onCancel)
                }
                "Paused", "Cancelled" -> action(Icons.Outlined.PlayArrow, stringResource(R.string.resume), normal, onResume)
                "Errored" -> action(Icons.Outlined.Refresh, stringResource(R.string.retry), normal, onRetry)
            }
            action(Icons.Outlined.Delete, stringResource(R.string.delete), MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

/** A filter's name as shown; the filters themselves are keyed by their English names. */
@Composable
private fun queueFilterLabel(filter: String): String = stringResource(
    when (filter) {
        "Running" -> R.string.queue_filter_running
        "In Queue" -> R.string.queue_filter_in_queue
        "Paused" -> R.string.queue_filter_paused
        "Scheduled" -> R.string.queue_filter_scheduled
        "Errored" -> R.string.queue_filter_errored
        else -> R.string.queue_filter_cancelled
    }
)
