package com.comfort.app.ui.main

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import com.comfort.app.viewmodel.DownloadsViewModel
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
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
    val deleteScope = rememberCoroutineScope()
    // Every delete on this screen (bulk, a single card's "Remove", swipe-to-dismiss) routes through
    // here instead of calling DownloadDispatcher.deleteDownload directly — code review flagged the
    // old direct-delete-on-tap behavior as a HIGH finding (a destructive, irreversible action with
    // no confirmation or undo anywhere). hideForDeletion() only ever hides the ids (reversible);
    // the real, irreversible delete is confirmDelete(), which only runs once this Snackbar's own
    // Undo window has passed without the user tapping it.
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
    // Lets a QUEUED item's card explain *why* it's stuck (no usable network right now) instead of
    // just "Waiting to start…" forever with no visible reason — reproduced live: a WorkManager job
    // sitting on an unsatisfied CONNECTIVITY constraint because the current Wi-Fi network was
    // connected but never validated by the OS (some hotspot/captive-portal setups never do).
    val isNetworkAvailable = rememberIsNetworkAvailable()
    // Hoisted here, not inside each card's own `remember(item.id)` below — a LazyColumn destroys
    // and recreates an item's composable as it scrolls off-screen and back on, which reset a
    // purely-local remember back to its initial "not yet animated" state every time. Reproduced
    // live: the whole visible list kept re-playing its slide-up entrance animation on every scroll
    // up/down, not just once when a card was genuinely new. This map, owned by the screen instead
    // of the item, remembers which ids have already played their entrance once and never resets —
    // Modifier.animateItem() below already handles the smooth reflow/removal animation on its own,
    // so this only ever needs to gate the one-time entrance.
    val alreadyAnimatedIds = remember { mutableStateMapOf<String, Boolean>() }
    // better-interface review: the entrance slide below (and the wavy progress indicator's wave
    // motion in QueueItemCard) animated unconditionally, with nothing checking the OS-level
    // reduce-motion setting.
    val reducedMotion = rememberIsReducedMotionEnabled()
    var selectedFilter by remember { mutableStateOf("Running") }
    val filters = listOf("Running", "In Queue", "Scheduled", "Paused", "Errored", "Cancelled")

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

    // The "Add cookies" error-card action opens this for the failing item's own site, then
    // retries that same download once cookies are extracted.
    var cookieLoginTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // (loginUrl, downloadId)
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
    val filteredItems = queueItems.filter { item ->
        when (selectedFilter) {
            "Running" -> item.status == DownloadStatus.RUNNING
            "In Queue" -> item.status == DownloadStatus.QUEUED
            "Scheduled" -> item.status == DownloadStatus.SCHEDULED
            "Paused" -> item.status == DownloadStatus.PAUSED
            "Errored" -> item.status == DownloadStatus.ERRORED
            "Cancelled" -> item.status == DownloadStatus.CANCELLED
            else -> false
        }
    }
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
        floatingActionButton = {
            if (retryAllStatus != null && filteredItems.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.retryAll(retryAllStatus) },
                    icon = { Icon(FeatherIcons.RefreshCw, contentDescription = null) },
                    text = { Text("Retry All") },
                )
            } else if (hasActiveDownload || hasPausedDownload) {
                ExtendedFloatingActionButton(
                    onClick = { if (showResumeAction) viewModel.resumeAll() else viewModel.pauseAll() },
                    icon = {
                        Icon(
                            if (showResumeAction) FeatherIcons.Play else FeatherIcons.Pause,
                            contentDescription = null,
                        )
                    },
                    text = { Text(if (showResumeAction) "Resume" else "Pause") },
                )
            }
        },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(FeatherIcons.X, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        val allSelected = filteredItems.isNotEmpty() && filteredItems.all { it.id in selectedIds }
                        // A real labeled control instead of a bare icon whose meaning (select vs.
                        // deselect *all*, as opposed to "select all" always adding to a partial
                        // selection) isn't obvious from a glyph alone.
                        TextButton(onClick = {
                            selectedIds = if (allSelected) emptySet() else filteredItems.map { it.id }.toSet()
                        }) {
                            Icon(
                                if (allSelected) FeatherIcons.XSquare else FeatherIcons.CheckSquare,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (allSelected) "Deselect all" else "Select all")
                        }
                        Spacer(Modifier.width(4.dp))
                        // Only ever appears once something's actually selected — the placeholder
                        // "Clear Queue" trash icon this replaces used to sit here unconditionally
                        // and did nothing (a dead TODO), so this is a real, scoped action instead.
                        IconButton(onClick = {
                            requestDelete(selectedIds)
                            selectedIds = emptySet()
                        }) {
                            Icon(FeatherIcons.Trash2, contentDescription = "Delete selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Download Queue", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Only the top inset from Scaffold — same reasoning as Library's own Scaffold
                // (DownloadsHistoryScreen.kt), which this used to differ from. Applying the whole
                // paddingValues here (including its own bottom inset, auto-reserved to clear the
                // floatingActionButton) and then ALSO adding navBarClearance() as the LazyColumn's
                // own bottom contentPadding below double-reserved bottom space — reproduced live:
                // the last card ended up sitting much further from the Retry All FAB than Library's
                // own last row sits from its floating nav bar, for no visual reason. The
                // LazyColumn's own bottom contentPadding is the sole source of bottom clearance now.
                .padding(top = paddingValues.calculateTopPadding())
        ) {
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
            LaunchedEffect(Unit) {
                delay(500)
                if (!reducedMotion && filterListState.canScrollForward) {
                    filterListState.animateScrollBy(filterNudgePx, animationSpec = tween(450))
                    delay(250)
                    filterListState.animateScrollBy(-filterNudgePx, animationSpec = tween(450))
                }
            }
            LazyRow(
                state = filterListState,
                // contentPadding (not an outer Modifier.padding) so the scrollable viewport spans
                // the full screen width — chips scroll flush to the true edge instead of getting
                // clipped mid-chip right at an inset boundary, which read as "cut off."
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { filter ->
                    val count = when (filter) {
                        "Running" -> queueItems.count { it.status == DownloadStatus.RUNNING }
                        "In Queue" -> queueItems.count { it.status == DownloadStatus.QUEUED }
                        "Scheduled" -> queueItems.count { it.status == DownloadStatus.SCHEDULED }
                        "Paused" -> queueItems.count { it.status == DownloadStatus.PAUSED }
                        "Errored" -> queueItems.count { it.status == DownloadStatus.ERRORED }
                        "Cancelled" -> queueItems.count { it.status == DownloadStatus.CANCELLED }
                        else -> 0
                    }
                    BadgedBox(
                        badge = {
                            if (count > 0) {
                                Badge(
                                    containerColor = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                ) {
                                    Text(count.toString())
                                }
                            }
                        }
                    ) {
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            shape = com.comfort.app.theme.PillShape
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                EmptyState(
                    icon = FeatherIcons.Inbox,
                    title = "No downloads in queue",
                    subtitle = "Paste a link on Home to start one.",
                    // weight(1f): without it, this Column child requests the Column's full height
                    // rather than just what's left below the filter row, so its centered content
                    // sat well below true middle. Bottom padding excludes the floating nav pill.
                    modifier = Modifier.weight(1f).padding(bottom = navBarClearance()),
                )
            } else {
                LazyColumn(
                    // Extra bottom inset beyond the normal 16dp: the Pause/Resume/Retry All FAB
                    // floats over the content rather than reserving space for itself, so without
                    // this the last card(s) end up scrolled underneath it, partly unreadable and
                    // with their own action buttons unreachable. navBarClearance() (not a flat
                    // 100dp guess) so this also clears the real system nav bar inset on devices
                    // where it's taller than this app's own FAB assumed — see its doc comment
                    // (MainScreen.kt) for the full story.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navBarClearance()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        // Slide-up + fade-in on first appearance (a newly queued download, or one
                        // scrolling into view for the first time) — targetState flips true right
                        // after this item enters composition, MutableTransitionState's own initial
                        // "false" giving Compose something to animate *from*. Never flips back to
                        // false again from here — a finished/removed item just leaves
                        // filteredItems and this composable is disposed outright, so the "crossfade
                        // out and slide the rest up to fill the gap" half of this is entirely
                        // animateItem()'s own built-in fade-out + placement animation below, not
                        // this AnimatedVisibility's exit (deliberately ExitTransition.None).
                        val visibleState = remember(item.id) {
                            MutableTransitionState(alreadyAnimatedIds.containsKey(item.id)).apply { targetState = true }
                        }
                        SideEffect { alreadyAnimatedIds[item.id] = true }
                        val isSelected = item.id in selectedIds
                        val row = @Composable {
                            if (item.status == DownloadStatus.CANCELLED || item.status == DownloadStatus.PAUSED) {
                                StoppedRow(
                                    item = item,
                                    onResume = { viewModel.retryDownload(item.id) },
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
                                    onAddCookies = {
                                        val host = runCatching { URI(item.url).host }.getOrNull()
                                        if (host != null) cookieLoginTarget = "https://$host" to item.id
                                    },
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
                            modifier = Modifier.animateItem(),
                        ) {
                        // Swipe-to-delete is only offered for downloads that are already stopped
                        // for good (errored or cancelled) — everything still active or resumable
                        // (running, queued, scheduled, paused) should require a deliberate tap
                        // instead of a stray swipe wiping it out. Also suppressed for the whole
                        // list while multi-select is active — a swipe gesture competing with
                        // tap-to-toggle-selection on the same cards would be janky either way.
                        if (!selectionMode && (item.status == DownloadStatus.CANCELLED || item.status == DownloadStatus.ERRORED)) {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value != SwipeToDismissBoxValue.Settled) {
                                        requestDelete(setOf(item.id))
                                    }
                                    true
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
                                    ) {
                                        Icon(FeatherIcons.Trash2, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                },
                            ) {
                                row()
                            }
                        } else {
                            row()
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
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(item.thumbnailPath)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .addHeader("Referer", item.url)
            .build(),
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
                    FeatherIcons.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(26.dp),
                )
            }
        },
    )
}

/** Renders a card's thumbnail with the selection-mode dimming/checkmark badge. Purely visual —
 * the actual long-press/tap-to-toggle gesture lives on the *whole* card (see StoppedRow's/
 * QueueItemCard's own outer combinedClickable), not just this thumbnail, so long-pressing
 * anywhere on a card works, not only its corner. */
@Composable
private fun SelectableThumbnail(
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        content()
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = if (selected) 0.15f else 0.35f)),
            )
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        FeatherIcons.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoppedRow(
    item: DownloadEntity,
    onResume: () -> Unit,
    onDelete: () -> Unit,
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
                .background(MaterialTheme.colorScheme.surfaceContainer)
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectableThumbnail(
                selectionMode = selectionMode,
                selected = selected,
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (hasThumbnail) {
                    QueueThumbnail(item = item, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            FeatherIcons.Image,
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
            val domain = remember(item.url) { runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "Unknown" }
            Text(
                text = domain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))

            val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPaused) FeatherIcons.Pause else FeatherIcons.X,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "${if (isPaused) "Paused" else "Cancelled"} • ${item.downloadedItems} saved • ${sdf.format(Date(item.effectiveDate))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

            if (!selectionMode) {
                IconButton(onClick = onResume) {
                    Icon(FeatherIcons.Play, contentDescription = "Resume")
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(FeatherIcons.MoreVertical, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy link") },
                            leadingIcon = { Icon(FeatherIcons.Copy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("Download link", item.url)))
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = { Icon(FeatherIcons.Trash2, contentDescription = null) },
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
    onAddCookies: () -> Unit = {},
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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
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
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectableThumbnail(
                    selectionMode = selectionMode,
                    selected = selected,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
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
                                DownloadStatus.ERRORED -> FeatherIcons.AlertTriangle
                                DownloadStatus.QUEUED -> FeatherIcons.Clock
                                DownloadStatus.SCHEDULED -> FeatherIcons.Calendar
                                else -> FeatherIcons.DownloadCloud
                            }
                            val tint = if (item.status == DownloadStatus.ERRORED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            Icon(icon, contentDescription = null, tint = tint)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

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
                            if (item.expectedBytes > 0 || item.totalBytes > 0 || item.liveBytes > 0 || item.totalItems > 0) {
                                "Downloading"
                            } else {
                                "Fetching info..."
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

            Spacer(modifier = Modifier.height(12.dp))

            if (item.status == DownloadStatus.RUNNING) {
                if (!isNetworkAvailable) {
                    // The subprocess itself is still running and will eventually time out and error
                    // on its own once the network is actually gone — this just gives an immediate,
                    // visible reason for a running download that's stopped making progress, instead
                    // of leaving the user watching a stalled progress bar with no explanation.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(
                            FeatherIcons.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "No internet connection",
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
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                        item.totalItems > 1 -> "${item.downloadedItems} of ${item.totalItems}"
                        item.expectedBytes > 0 ->
                            "${formatFileSize(item.totalBytes + item.liveBytes)} of ${formatFileSize(item.expectedBytes)}"
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
                    val speedStr = when {
                        !isNetworkAvailable -> "0.00 MB/s"
                        item.speedMbs == 0f -> "0.00 MB/s"
                        item.speedMbs < 1f -> String.format(Locale.getDefault(), "%.2f KB/s", item.speedMbs * 1024)
                        else -> String.format(Locale.getDefault(), "%.2f MB/s", item.speedMbs)
                    }
                    Text(
                        text = "Speed: " + speedStr,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (item.status == DownloadStatus.QUEUED) {
                if (isNetworkAvailable) {
                    Text(
                        text = "Waiting to start…",
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
                            FeatherIcons.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Waiting for a network connection…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else if (item.status == DownloadStatus.SCHEDULED) {
                Text(
                    text = "Waiting for the scheduled time window…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        text = "Temporarily rate-limited by the site — this isn't fixed by retrying immediately. Toggling airplane mode or restarting your router usually gets a fresh IP and clears it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (!selectionMode) {
                Spacer(modifier = Modifier.height(8.dp))

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
                            IconButton(onClick = onPauseResume) {
                                Icon(FeatherIcons.Pause, contentDescription = "Pause")
                            }
                            IconButton(onClick = onCancel) {
                                Icon(FeatherIcons.X, contentDescription = "Cancel")
                            }
                        }
                        DownloadStatus.ERRORED -> {
                            if (isCookieRelatedError(item.errorMessage)) {
                                TextButton(onClick = onAddCookies) {
                                    Icon(FeatherIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add cookies")
                                }
                            }
                            TextButton(onClick = onRetry) {
                                Text("Retry")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("Download link", item.url)))
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(FeatherIcons.Copy, contentDescription = "Copy link")
                            }
                            IconButton(onClick = onDelete) {
                                Icon(FeatherIcons.Trash2, contentDescription = "Remove")
                            }
                        }
                        DownloadStatus.QUEUED, DownloadStatus.SCHEDULED -> {
                            TextButton(onClick = onStartNow) {
                                Icon(FeatherIcons.Zap, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Start now")
                            }
                            IconButton(onClick = onCancel) {
                                Icon(FeatherIcons.X, contentDescription = "Cancel")
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
