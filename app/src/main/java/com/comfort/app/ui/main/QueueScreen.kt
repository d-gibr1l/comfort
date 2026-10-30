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

// Matches the phrasing yt-dlp/gallery-dl actually use when a download failed because the site
// wants an authenticated session — e.g. "The web client only works when logged-in. Use --cookies,
// --cookies-from-browser, ..." (see DownloadWorker's real-error-surfacing fix). Deliberately
// keyword-based rather than parsing engine-specific error codes, since both engines phrase this
// in plain English rather than a structured error type.
private val COOKIE_ERROR_KEYWORDS = listOf(
    "cookie", "logged-in", "log in", "sign in", "login", "private", "authentication", "credentials", "netrc",
)

private fun isCookieRelatedError(message: String?): Boolean =
    message != null && COOKIE_ERROR_KEYWORDS.any { message.contains(it, ignoreCase = true) }

// HTTP 429 ("Too Many Requests") is a real, temporary IP-level block from the site, not something
// retrying the same download fixes on its own — most mobile connections and home routers sit
// behind a dynamic IP (often Carrier-Grade NAT), so toggling airplane mode or power-cycling the
// router usually gets a fresh one and clears it. Keyword-based for the same reason
// COOKIE_ERROR_KEYWORDS is: both engines phrase this in plain English, not a structured error type.
private val RATE_LIMIT_ERROR_KEYWORDS = listOf("too many requests", "rate-limit", "rate limit", "rate limited")

// A plain substring check for "429" (like the phrase keywords above use) false-positives on any
// error message that happens to contain those digits for an unrelated reason — a URL/video id, a
// byte count, anything numeric. \b429\b requires it to stand alone as its own token instead.
private val RATE_LIMIT_429_RE = Regex("""\b429\b""")

private fun isRateLimitError(message: String?): Boolean =
    message != null &&
        (RATE_LIMIT_ERROR_KEYWORDS.any { message.contains(it, ignoreCase = true) } || RATE_LIMIT_429_RE.containsMatchIn(message))

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
        queueItems.find { it.id == id && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED, DownloadStatus.PAUSED) }
    }
    startAtItem?.let { item ->
        StartAtDialog(
            initial = item.scheduledAt,
            onDismiss = { startAtItemId = null },
            onPicked = { at -> startAtItemId = null; viewModel.scheduleAt(item.id, at) },
            onRemove = if (item.scheduledAt != null) ({ startAtItemId = null; viewModel.scheduleAt(item.id, null) }) else null,
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

// Shared by both the running-item card and the stopped-item row — item.thumbnailPath can be
// either a local content:// URI (once a file has actually landed) or a remote http(s) preview URL
// (set early from yt-dlp's own extracted thumbnail, before any bytes have downloaded; see
// DownloadWorker's [thumbnail] handling). The spoofed User-Agent/Referer only matter for the
// remote case — many sites (Instagram included) reject hotlinked image requests without them —
// but Coil simply ignores headers for a local URI, so there's no need to branch on which kind it is.
@Composable
private fun QueueThumbnail(item: DownloadEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageRequest = remember(item.thumbnailPath, item.url, context) {
        val safeReferer = runCatching { android.net.Uri.encode(item.url, ":/?#[]@!$&'()*+,;=") }.getOrNull() ?: item.url
        ImageRequest.Builder(context)
            .data(item.thumbnailPath)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .addHeader("Referer", safeReferer)
            .build()
    }
    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = item.title,
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

/** A card's thumbnail, which turns into a round checkbox while selecting (and back), like a mail
 * app's avatar. Purely visual: the long-press/tap that selects lives on the whole card (StoppedRow's
 * and QueueItemCard's own combinedClickable), not just here. It used to stay the thumbnail, dimmed,
 * with a small check badge over its corner that muddied the status icon underneath. */
@Composable
private fun SelectableThumbnail(
    selectionMode: Boolean,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    androidx.compose.animation.AnimatedContent(
        targetState = selectionMode,
        transitionSpec = {
            (fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(160), initialScale = 0.6f))
                .togetherWith(fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(120), targetScale = 0.6f))
        },
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
        label = "selection checkbox",
    ) { selecting ->
        if (!selecting) {
            Box(modifier = modifier, content = content)
        } else {
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                SelectionCheck(selected)
            }
        }
    }
}

/** The round checkbox: an outline, filled with a check once selected. */
@Composable
private fun SelectionCheck(selected: Boolean) {
    val fill by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "check fill",
    )
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoppedRow(
    item: DownloadEntity,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onStartNow: () -> Unit = {},
    onStartAt: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val isPaused = item.status == DownloadStatus.PAUSED
    var menuExpanded by remember { mutableStateOf(false) }
    val hasThumbnail = !item.thumbnailPath.isNullOrBlank()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                // Selected: tinted and outlined, so the selection reads from the whole card.
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier)
                // Always present, not just once selectionMode is already true — this is what
                // actually *enters* selection mode via a long-press anywhere on the row (not just
                // the thumbnail; reported live that only the thumbnail worked before this). Short
                // taps stay a no-op outside selection mode, so the Resume/More buttons further
                // right keep working normally: Compose routes a tap to the deepest element under
                // it first, so a direct tap on one of those buttons is consumed there and never
                // reaches this outer handler at all — only taps that land on otherwise-inert areas
                // of the row (thumbnail, title, blank space) ever hit this onClick/onLongClick.
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = onToggleSelect,
                )
                .semantics {
                    if (selectionMode) {
                        this.selected = selected
                        this.role = Role.Checkbox
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectableThumbnail(
                selectionMode = selectionMode,
                selected = selected,
                size = 76.dp,
                modifier = Modifier.size(76.dp).clip(thumbnailShape()).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (hasThumbnail) {
                    QueueThumbnail(item = item, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { item.url },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val domain = remember(item.url) {
                runCatching { android.net.Uri.parse(item.url).host?.removePrefix("www.") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: context.getString(R.string.unknown_site)
            }
            // When, beside the site: on the status line below it was cut off ("Paused · nothing
            // saved yet · a…").
            val whenText = remember(item.downloadStartTime, item.dateAdded) {
                context.getString(if (item.downloadStartTime > 0) R.string.started_when else R.string.added_when, shortWhen(context, item.effectiveDate))
            }
            Text(
                text = "$domain · $whenText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))

            // How far it got, measured the way the running card measures it (item count for a
            // gallery, bytes for a single file): a paused download keeps a thin bar where it stopped.
            val savedBytes = item.totalBytes + item.liveBytes
            val stoppedProgress = when {
                item.totalItems > 1 -> (item.downloadedItems.toFloat() / item.totalItems).coerceIn(0f, 1f)
                item.expectedBytes > 0 -> (savedBytes.toFloat() / item.expectedBytes).coerceIn(0f, 1f)
                else -> null
            }
            if (isPaused && stoppedProgress != null) {
                LinearProgressIndicator(
                    progress = { stoppedProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(6.dp))
            }
            val progressText = when {
                item.totalItems > 1 -> stringResource(R.string.x_of_x, item.downloadedItems, item.totalItems)
                item.expectedBytes > 0 && savedBytes > 0 -> stringResource(R.string.x_of_x, formatFileSize(savedBytes), formatFileSize(item.expectedBytes))
                item.downloadedItems > 0 -> stringResource(R.string.x_saved, item.downloadedItems)
                else -> stringResource(R.string.nothing_saved_yet)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPaused) Icons.Outlined.Pause else Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(if (isPaused) R.string.paused_progress else R.string.cancelled_progress, progressText),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QueueLink(item.url, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), onSelectionTap = if (selectionMode) onToggleSelect else null)
        }

            if (!selectionMode) {
                IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.resume))
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // Resume waits for a free slot and a closed schedule window; Start now, like
                        // on a waiting card, starts it right away (making room if every slot is busy).
                        if (isPaused) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.up_next)) },
                                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                                onClick = { menuExpanded = false; onStartNow() },
                            )
                            // Or later, at a time picked for it (StartAtDialog).
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.start_at)) },
                                leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                                onClick = { menuExpanded = false; onStartAt() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_link)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText(context.getString(R.string.download_link), item.url)))
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sponsorblock_remove)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun QueueItemCard(
    item: DownloadEntity,
    isNetworkAvailable: Boolean = true,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPauseResume: () -> Unit,
    onRetry: () -> Unit,
    onStartNow: () -> Unit = {},
    onStartAt: () -> Unit = {},
    onAddCookies: () -> Unit = {},
    onShowError: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // better-interface review: the wavy progress indicator below animated its wave motion
    // unconditionally. Flattening amplitude to 0 under reduced motion keeps the progress *level*
    // itself moving (functional feedback, not decorative) while dropping the continuous undulation.
    val reducedMotion = rememberIsReducedMotionEnabled()
    // Plain Card, not ElevatedCard-with-elevation-zeroed-out — MD3 communicates elevation
    // through tonal surface color (surfaceContainer below), not shadow; choosing the Elevated
    // variant only to strip its own elevation back to 0dp was self-contradictory. shapes.medium
    // (12dp) is the spec's own card token — shapes.large (16dp) is for FABs/nav drawers.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        // Selected: tinted and outlined, so the selection reads from the whole card.
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Same reasoning as StoppedRow's own combinedClickable — always present so a
                // long-press anywhere on the card (not just the thumbnail) enters/extends
                // selection, while a direct tap on one of the buttons further down is still
                // consumed there first and never reaches this outer handler.
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = onToggleSelect,
                )
                .semantics {
                    if (selectionMode) {
                        this.selected = selected
                        this.role = Role.Checkbox
                    }
                }
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // shapes.small (8dp token) instead of a hardcoded RoundedCornerShape(8.dp) — same
                // radius, but tied to the theme's own shape scale instead of a magic number.
                // Container/icon now pair correctly per MD3's tonal-pairing rule (errorContainer +
                // onErrorContainer for ERRORED, same as the Cancel button below; primaryContainer +
                // onPrimaryContainer otherwise) — previously the background stayed primaryContainer
                // unconditionally while only the icon switched to the bare (unpaired) error color,
                // an arbitrary combination the spec explicitly calls out as breaking contrast
                // guarantees in dynamic color and high-contrast modes.
                val isErrored = item.status == DownloadStatus.ERRORED
                SelectableThumbnail(
                    selectionMode = selectionMode,
                    selected = selected,
                    size = 40.dp,
                    modifier = Modifier.size(40.dp).clip(thumbnailShape()).background(
                        if (isErrored) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    ),
                ) {
                    // A RUNNING download gets a thumbnail as soon as yt-dlp's extractor picks one
                    // (a remote preview URL, well before any bytes land — see DownloadWorker's
                    // [thumbnail] handling) or, for gallery-dl / once the real file lands, from
                    // setThumbnail(IfAbsent). Falls back to the status icon until either happens,
                    // same as QUEUED/ERRORED which never have one.
                    if (!item.thumbnailPath.isNullOrBlank()) {
                        QueueThumbnail(item = item, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val icon = when (item.status) {
                                DownloadStatus.ERRORED -> Icons.Outlined.Warning
                                DownloadStatus.QUEUED -> Icons.Outlined.Schedule
                                DownloadStatus.SCHEDULED -> Icons.Outlined.CalendarMonth
                                else -> Icons.Outlined.CloudDownload
                            }
                            val tint = if (isErrored) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                            Icon(icon, contentDescription = null, tint = tint)
                        }
                    }
                }

                // 8dp, not 12dp — MD3's own spacing system is built on an 8dp grid so margins/
                // padding/gaps stay consistent and can adapt programmatically across densities.
                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { item.url },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusText = when (item.status) {
                        DownloadStatus.RUNNING -> {
                            if (item.downloadingAudioTrack) {
                                // A video+audio merge's own separate audio sub-file looks, from
                                // the queue card alone, like a second download starting out of
                                // nowhere with a much smaller size — this is what actually
                                // distinguishes it from a stuck/wrong progress bar.
                                stringResource(R.string.extracting_audio)
                            } else if (item.expectedBytes > 0 || item.totalBytes > 0 || item.liveBytes > 0 || item.totalItems > 0) {
                                stringResource(R.string.downloading)
                            } else {
                                stringResource(R.string.fetching_info)
                            }
                        }
                        else -> item.status.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            DownloadStatus.ERRORED -> MaterialTheme.colorScheme.error
                            DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Same 8dp-grid reasoning as the thumbnail gap above.
            Spacer(modifier = Modifier.height(8.dp))

            if (item.status == DownloadStatus.RUNNING) {
                if (!isNetworkAvailable) {
                    // The subprocess itself is still running and will eventually time out and error
                    // on its own once the network is actually gone — this just gives an immediate,
                    // visible reason for a running download that's stopped making progress, instead
                    // of leaving the user watching a stalled progress bar with no explanation.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(
                            Icons.Outlined.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.no_internet_connection),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // Item count wins whenever there's more than one item, even once expectedBytes is
                // also known — the case that matters is a gallery-dl gallery (several pictures,
                // item-count progress climbing normally) that yt-dlp then supplements with the
                // same post's video (byte-accurate progress, but only for that one file): letting
                // expectedBytes take over there would snap the bar from, say, "6 of 8" straight
                // back down to a fresh 0% for just the video — a visible regression, since the
                // gallery's own completed items aren't reflected in a byte count that only ever
                // tracked the one file. Byte-accurate progress is reserved for genuinely
                // single-item downloads, which is what it's normally available for anyway.
                val itemProgress = if (item.totalItems > 1) {
                    (item.downloadedItems.toFloat() / item.totalItems).coerceIn(0f, 1f)
                } else null
                val byteProgress = if (itemProgress == null && item.expectedBytes > 0) {
                    ((item.totalBytes + item.liveBytes).toFloat() / item.expectedBytes).coerceIn(0f, 1f)
                } else null
                val rawProgress = itemProgress ?: byteProgress

                if (rawProgress != null) {
                    // tween-smoothed so a jump from (say) one file finishing to the next eases
                    // into place over a few frames instead of snapping straight to the new value
                    // — the wavy indicator's own animation only smooths the wave motion, not the
                    // progress level itself.
                    val animatedProgress by animateFloatAsState(
                        targetValue = rawProgress,
                        animationSpec = tween(durationMillis = 450),
                        label = "downloadProgress",
                    )
                    // The wave amplitude tapers to flat as progress nears 100% in the determinate
                    // overload — that's M3 Expressive's own spec, not something tuned here.
                    // waveSpeed/amplitude/wavelength must be passed explicitly (mirroring the
                    // indeterminate branch below) — the determinate overload's own defaults render
                    // a static, non-scrolling wave shape that only redraws when the progress level
                    // itself changes, which reads as "frozen" on any card whose progress happens to
                    // sit still for a beat.
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        // surfaceContainerHighest, not the older surfaceVariant token this app's
                        // current MD3 color-role set doesn't otherwise use anywhere else.
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        amplitude = { progress -> if (reducedMotion || progress > 0.9f) 0f else 0.4f },
                        wavelength = 40.dp,
                        waveSpeed = 8.dp,
                    )
                } else {
                    // Nothing to measure a real fraction against — either a single item with no
                    // known size (gallery-dl doesn't report one upfront the way yt-dlp does), or
                    // still extracting the link's metadata entirely (nothing downloaded yet at
                    // all). Indeterminate keeps animating either way rather than sitting frozen or
                    // jumping straight from empty to full; extraction runs at half the wave speed
                    // of an active transfer so the two are visually distinguishable.
                    val isExtracting = item.totalBytes <= 0 && item.liveBytes <= 0 && item.downloadedItems <= 0
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        amplitude = if (reducedMotion) 0f else 0.4f,
                        wavelength = 40.dp,
                        waveSpeed = if (isExtracting) 4.dp else 8.dp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val leadingText = when {
                        item.totalItems > 1 -> stringResource(R.string.x_of_x, item.downloadedItems, item.totalItems)
                        item.expectedBytes > 0 ->
                            stringResource(R.string.x_of_x, formatFileSize(item.totalBytes + item.liveBytes), formatFileSize(item.expectedBytes))
                        // A single item with no known size — an item count of "0 of 1"/"1 of 1"
                        // says nothing useful, so this space is left blank instead.
                        else -> ""
                    }
                    Text(
                        text = leadingText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Only for the genuine single-item byte-progress case (see byteProgress's own
                    // comment above for why item-count downloads don't get one here) — SpaceBetween
                    // naturally lands this between the size and speed text either side of it.
                    if (byteProgress != null) {
                        Text(
                            text = "${(byteProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // A live note (an engine waiting out a site's rate limit) replaces the speed,
                    // which would otherwise sit at 0 KB/s with no explanation — see DownloadNotes.
                    val note = com.comfort.app.data.DownloadNotes.notes.collectAsStateWithLifecycle().value[item.id]
                    val speedStr = when {
                        !isNetworkAvailable -> com.comfort.app.util.LocalFormat.speed(0f, 2)
                        else -> com.comfort.app.util.LocalFormat.speed(item.speedMbs, 2)
                    }
                    Text(
                        text = note ?: stringResource(R.string.speed_x, speedStr),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Fills what used to be dead space below the size/speed row on a running
                // download's card — a site badge (titles are frequently in the source post's own
                // language/script, giving no hint where a download actually came from) and a
                // rough ETA, only ever shown for the same genuine single-item byte-progress case
                // the "X%" text above is already gated on, since remaining-bytes÷speed means
                // nothing for an item-count-only gallery download. A tinted pill rather than bare
                // icon+text — a plain label read as an afterthought floating in a lot of empty
                // card, not a deliberate piece of the layout.
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Format tags, if any, then the site badge — grouped as a single connected
                    // pill row (same rounded-outer/tight-inner shape as the Pause/Cancel chips
                    // below): the site badge is always index 0, so this list's own size decides
                    // where each pill's "outer end" actually falls.
                    val formatTagList = item.formatTags?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
                    val pillCount = 1 + formatTagList.size
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoPill(shape = groupedChipShape(0, pillCount, height = 24.dp)) {
                            Icon(
                                Icons.Outlined.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = VideoSiteRouter.siteName(item.url, stringResource(R.string.this_link)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Exactly what got picked for the file currently downloading — resolution/
                        // fps/container for video, bitrate+codec for audio (see
                        // yt_dlp_wrapper.py's own "[format]" signal) — at a glance, no need to
                        // open anything to find out. Gallery-dl-routed downloads never populate
                        // this at all, so the row is just the site badge for those, same as before.
                        formatTagList.forEachIndexed { i, tag ->
                            InfoPill(shape = groupedChipShape(i + 1, pillCount, height = 24.dp)) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // The link this download came from, in the space the pills leave free (QueueLink).
                    QueueLink(item.url, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), onSelectionTap = if (selectionMode) onToggleSelect else null)
                    if (byteProgress != null && item.speedMbs > 0f) {
                        val remainingBytes = item.expectedBytes - (item.totalBytes + item.liveBytes)
                        val remainingSeconds = (remainingBytes / (item.speedMbs * 1024f * 1024f)).toInt()
                        if (remainingSeconds > 0) {
                            Text(
                                text = formatEta(remainingSeconds),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (item.status == DownloadStatus.QUEUED) {
                if (isNetworkAvailable) {
                    Text(
                        text = stringResource(R.string.waiting_to_start),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // WorkManager's own CONNECTIVITY constraint (see DownloadDispatcher.enqueueWork)
                    // never resolves without this, so a QUEUED item just sits at the generic
                    // "Waiting to start…" forever with no visible reason otherwise — reproduced live
                    // against a Wi-Fi network that was connected but never validated by the OS.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.waiting_for_a_network_connection_2),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else if (item.status == DownloadStatus.SCHEDULED) {
                // When it starts, counted down live, and the window from Settings › Downloads.
                val context = LocalContext.current
                val now by produceState(System.currentTimeMillis()) {
                    while (true) {
                        kotlinx.coroutines.delay(30_000)
                        value = System.currentTimeMillis()
                    }
                }
                // Its own start time ("Start at…"), or the window's.
                val (startsText, windowText) = remember(now, item.scheduledAt) {
                    item.scheduledAt?.let { ownStartText(context, it) to context.getString(R.string.your_start_time) }
                        ?: scheduleTexts(context)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = startsText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(R.string.x_up_next_starts_it_now, windowText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.status == DownloadStatus.ERRORED && item.errorMessage != null) {
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (isRateLimitError(item.errorMessage)) {
                    Text(
                        text = stringResource(R.string.temporarily_rate_limited_by_the_site),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                QueueLink(item.url, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), onSelectionTap = if (selectionMode) onToggleSelect else null)
            }

            if (!selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // Explicit gap rather than relying on each TextButton/IconButton's own default
                    // touch-target padding for spacing — better-interface review: on the 4-action
                    // ERRORED row (Add cookies / Retry / Copy / Remove) that default padding was the
                    // only thing separating adjacent actions, tighter and less deliberate than a
                    // real gap.
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item.status) {
                        DownloadStatus.RUNNING -> {
                            // Real AssistChips (8dp corners, 32dp tall — M3's own chip spec) with
                            // the connected-group shape treatment: fully rounded on the pair's own
                            // outer ends (Pause's left, Cancel's right), a tighter corner where
                            // they face each other — instead of the previous icon-only
                            // FilledTonalIconButtons, whose bare glyph made Pause and Cancel hard
                            // to tell apart at a glance.
                            // Cancel first, Pause last (swapped on request) — Pause now sits on the
                            // right edge, where the Queued cards' own primary action also is.
                            AssistChip(
                                onClick = onCancel,
                                shape = groupedChipShape(0, 2),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = MaterialTheme.colorScheme.error,
                                ),
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = MaterialTheme.colorScheme.error,
                                    leadingIconContentColor = MaterialTheme.colorScheme.error,
                                ),
                                leadingIcon = { Icon(Icons.Outlined.CancelPresentation, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge) },
                            )
                            AssistChip(
                                onClick = onPauseResume,
                                shape = groupedChipShape(1, 2),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = MaterialTheme.colorScheme.outline,
                                ),
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                leadingIcon = { Icon(Icons.Outlined.PausePresentation, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text(stringResource(R.string.widget_music_pause), style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                        DownloadStatus.ERRORED -> {
                            IconButton(onClick = onShowError) {
                                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.error_details))
                            }
                            if (isCookieRelatedError(item.errorMessage)) {
                                TextButton(onClick = onAddCookies) {
                                    Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.add_cookies))
                                }
                            }
                            // Retry and delete as one split button, like "Start now" / cancel on a
                            // queued card. No copy button: a long-press on the card's link copies it
                            // (QueueLink).
                            ConfirmCancelSplitButton(
                                label = stringResource(R.string.retry),
                                icon = Icons.Outlined.Refresh,
                                onConfirm = onRetry,
                                onCancel = onDelete,
                                cancelDescription = stringResource(R.string.sponsorblock_remove),
                                cancelIcon = Icons.Outlined.Delete,
                            )
                        }
                        DownloadStatus.QUEUED, DownloadStatus.SCHEDULED -> {
                            // Same split button as Settings' edit sheets: "Start now" starts this
                            // download right away, past any schedule wait — when every slot is busy
                            // the newest running download steps aside for it (see
                            // DownloadDispatcher.startNow); cancel on the trailing half.
                            // Tapped, it turns into "Starting…" with a spinner until the card moves
                            // to Running: making room can take a few seconds (see startNow).
                            var starting by remember(item.id) { mutableStateOf(false) }
                            LaunchedEffect(starting) {
                                // Back to the button if it never started (it stays in the queue).
                                if (starting) { kotlinx.coroutines.delay(20_000L); starting = false }
                            }
                            // Set or change when it starts (StartAtDialog).
                            IconButton(onClick = onStartAt) {
                                Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.start_at))
                            }
                            ConfirmCancelSplitButton(
                                label = stringResource(R.string.up_next),
                                icon = Icons.Outlined.PlayArrow,
                                // Taps while starting are ignored rather than the button disabled:
                                // disabled, it turned a dim grey that hid the spinner.
                                onConfirm = { if (!starting) { starting = true; onStartNow() } },
                                onCancel = onCancel,
                                cancelDescription = stringResource(R.string.cancel_download),
                                leadingContent = { StartNowLabel(starting) },
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** A waiting card's Start now: the play icon slides away and "Starting…" with a spinner comes up
 * from below once tapped, and back if it never starts. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
private fun StartNowLabel(starting: Boolean) {
    androidx.compose.animation.AnimatedContent(
        targetState = starting,
        transitionSpec = {
            if (targetState) {
                (slideInVertically(tween(260)) { it } + fadeIn(tween(260))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(tween(220)) { it / 2 } + fadeOut(tween(180)))
            } else {
                fadeIn(tween(220)) togetherWith fadeOut(tween(180))
            } using androidx.compose.animation.SizeTransform(clip = false)
        },
        label = "startNow",
    ) { isStarting ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isStarting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (isStarting) R.string.starting else R.string.up_next))
        }
    }
}

/** DownloadEntity.errorDetails as (engine, message) pairs; empty when absent or unreadable. */
private fun parseEngineErrors(json: String?): List<Pair<String, String>> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = org.json.JSONArray(json)
        (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            entry.optString("engine") to entry.optString("message")
        }
    }.getOrDefault(emptyList())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ErrorDetailsSheet(
    item: com.comfort.app.data.DownloadEntity,
    onDismiss: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            // The download's name under the title, so it's clear which one failed.
            SheetHeader(
                icon = Icons.Outlined.Warning,
                title = stringResource(R.string.error_details),
                subtitle = item.title,
                tileColor = MaterialTheme.colorScheme.errorContainer,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            // Each engine's own error, in the order they ran (DownloadEntity.errorDetails) — the
            // card only shows one summary line, which could be a fallback engine's less relevant
            // message. Older failures have none: the summary is shown instead.
            val engineErrors = remember(item.errorDetails) { parseEngineErrors(item.errorDetails) }
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (engineErrors.isEmpty()) {
                    Text(
                        item.errorMessage ?: stringResource(R.string.unknown_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    engineErrors.forEach { (engine, message) ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(engine, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(4.dp))
                                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = engineErrors.takeIf { it.isNotEmpty() }
                                ?.joinToString("\n\n") { (engine, message) -> "$engine: $message" }
                                ?: item.errorMessage.orEmpty()
                            val clipData = android.content.ClipData.newPlainText(context.getString(R.string.error_message), text)
                            clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(clipData))
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                android.widget.Toast.makeText(context, context.getString(R.string.error_copied), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.copy)) }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/** "Waiting for a network connection" copy, shared by Home's notice and the Queue's Running tab:
 * how many downloads are held, and that Wi-Fi only is why when it's on. */
internal fun networkWaitSubtitle(context: android.content.Context, count: Int): String {
    val res = context.resources
    return if (com.comfort.app.data.GalleryDlPreferences.isWifiOnly(context)) res.getQuantityString(R.plurals.downloads_start_on_wifi, count, count)
    else res.getQuantityString(R.plurals.downloads_start_when_online, count, count)
}

/** A time today ("10:42"), or a date ("Oct 9") for anything older — in the phone's 12/24-hour style. */
internal fun shortWhen(context: android.content.Context, millis: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val today = java.util.Calendar.getInstance()
    val sameDay = then.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
        then.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
    else shortDateFormat().format(Date(millis))
}

// One "Oct 9" formatter for every queue card rather than a new one per card, rebuilt only if the
// language changes. SimpleDateFormat isn't thread-safe; it's shared only because shortWhen is
// called while composing, on the main thread.
private var shortDateFormatCache: Pair<Locale, SimpleDateFormat>? = null

private fun shortDateFormat(): SimpleDateFormat {
    val locale = Locale.getDefault()
    shortDateFormatCache?.let { (cachedLocale, format) -> if (cachedLocale == locale) return format }
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).also { shortDateFormatCache = locale to it }
}

/** A scheduled download's two lines: "Starts at 1:00 AM · in 3 h 20 min" (or "Starting soon"
 * once the window is open), and "Downloads run 1:00 AM–6:00 AM". Uses the same delay the
 * dispatcher waited on (DownloadDispatcher.scheduleDelayMillis). */
internal fun scheduleTexts(context: android.content.Context): Pair<String, String> {
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    fun clock(minutesOfDay: Int): String = timeFormat.format(
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            set(java.util.Calendar.MINUTE, minutesOfDay % 60)
        }.time,
    )
    val start = com.comfort.app.data.GalleryDlPreferences.getScheduleStartMinutes(context)
    val end = com.comfort.app.data.GalleryDlPreferences.getScheduleEndMinutes(context)
    val window = context.getString(R.string.downloads_run_x_x, clock(start), clock(end))
    val delay = com.comfort.app.data.DownloadDispatcher.scheduleDelayMillis(context)
    if (delay <= 0) return context.getString(R.string.starting_soon_the_window_is_open) to window
    val startsAt = timeFormat.format(Date(System.currentTimeMillis() + delay))
    return context.getString(R.string.starts_at_x_in_x, startsAt, inText(context, delay)) to window
}

/** "3 h 20 min" until something [delayMillis] away, rounded up to the minute. */
private fun inText(context: android.content.Context, delayMillis: Long): String {
    val minutes = (delayMillis + 59_999) / 60_000
    return when {
        minutes < 60 -> context.getString(R.string.x_min, minutes)
        minutes % 60 == 0L -> context.getString(R.string.x_h, minutes / 60)
        else -> context.getString(R.string.x_h_x_min, minutes / 60, minutes % 60)
    }
}

/** [scheduleTexts]' first line for a download with its own start time [at] ("Start at…"):
 * "Starts at 7:00 AM · in 9 h 10 min", or "Starting soon" once it's come. */
internal fun ownStartText(context: android.content.Context, at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= now) return context.getString(R.string.starting_soon)
    val startsAt = android.text.format.DateFormat.getTimeFormat(context).format(Date(at))
    return context.getString(R.string.starts_at_x_in_x, startsAt, inText(context, at - now))
}

/** The next time the clock shows [minutesOfDay] after [now]: later today, or tomorrow once it's
 * passed. Seconds at 0, so it starts on the minute picked. */
internal fun nextOccurrence(minutesOfDay: Int, now: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = now
    set(java.util.Calendar.HOUR_OF_DAY, minutesOfDay / 60)
    set(java.util.Calendar.MINUTE, minutesOfDay % 60)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
    if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

/** The Queue's "Start at…" on a waiting or paused download: a time of day on the same wheel as the
 * schedule window's (Settings › Downloads), for the next time it comes round — later today, or
 * tomorrow once it's passed — said underneath as it turns ("Starts at 7:00 AM · in 9 h 10 min").
 * [initial]: the time already set, if any (else the next full hour). [onRemove], when a time is
 * set: back to waiting for the window like the rest. */
@Composable
private fun StartAtDialog(
    initial: Long?,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val context = LocalContext.current
    // Seeded once, then only written by the wheel (re-seeding it would fight its scrolling).
    val startMinutes = remember {
        val c = java.util.Calendar.getInstance()
        if (initial != null && initial > System.currentTimeMillis()) {
            c.timeInMillis = initial
        } else {
            c.add(java.util.Calendar.HOUR_OF_DAY, 1)
            c.set(java.util.Calendar.MINUTE, 0)
        }
        c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }
    var minutes by remember { mutableStateOf(startMinutes) }
    val startTime = remember { kotlinx.datetime.LocalTime(hour = startMinutes / 60, minute = startMinutes % 60) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val summary = remember(minutes, now) { ownStartText(context, nextOccurrence(minutes, now), now) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.start_at), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                dev.darkokoa.datetimewheelpicker.WheelTimePicker(
                    modifier = Modifier.size(280.dp, 160.dp),
                    startTime = startTime,
                    // The phone's own 12/24-hour setting.
                    timeFormatter = dev.darkokoa.datetimewheelpicker.core.format.timeFormatter(
                        timeFormat = if (android.text.format.DateFormat.is24HourFormat(context)) {
                            dev.darkokoa.datetimewheelpicker.core.format.TimeFormat.HOUR_24
                        } else {
                            dev.darkokoa.datetimewheelpicker.core.format.TimeFormat.AM_PM
                        },
                    ),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    selectedTextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    onSnappedTime = { snapped -> minutes = snapped.hour * 60 + snapped.minute },
                )
                Spacer(Modifier.height(12.dp))
                Text(summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onRemove != null) {
                        TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_start_time)) }
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { onPicked(nextOccurrence(minutes, System.currentTimeMillis())) }) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

/** A queue card's link, without the scheme/"www." and cut off with an ellipsis so it never pushes
 * anything around: a tap opens it, a long-press copies it (with a buzz and "Link copied"). Its
 * own handler, so a long-press here copies instead of starting the card's selection. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueueLink(
    url: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // While its card is in selection mode: a tap or long-press here selects/deselects the card.
    // The link used to be merely disabled then, which still swallowed the tap, so this strip was
    // the one part of a card that didn't respond.
    onSelectionTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shortLink = remember(url) { shortQueueLink(url) }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                // While selecting, only this: the link's own handler, even disabled, still took the
                // tap before it could reach the card.
                if (onSelectionTap != null) Modifier.combinedClickable(onClick = onSelectionTap, onLongClick = onSelectionTap)
                else Modifier.combinedClickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.open_link),
                onLongClickLabel = stringResource(R.string.copy_link),
                onClick = { runCatching { uriHandler.openUri(url) } },
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.download_link), url))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                    }
                },
            ))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = shortLink,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [url] without the scheme, "www." and the share-tracking parameters ("?si=", "?igsh=", "utm_..."),
 * but keeping the ones that say what it is: a YouTube link's video ID lives in "?v=", and cutting
 * the whole query showed every one of them as just "youtube.com/watch". */
internal fun shortQueueLink(url: String): String {
    val base = url.substringAfter("://").removePrefix("www.").substringBefore('#')
    val path = base.substringBefore('?').trimEnd('/')
    val kept = base.substringAfter('?', "").split('&').filter { param ->
        val name = param.substringBefore('=').lowercase()
        param.isNotBlank() && !com.comfort.app.data.UrlKey.isTracking(name)
    }
    return if (kept.isEmpty()) path else path + "?" + kept.joinToString("&")
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
