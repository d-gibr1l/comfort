package com.comfort.app.ui.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import com.comfort.app.theme.FavoriteGold
import com.comfort.app.theme.SuccessGreen40
import com.comfort.app.util.rememberIsReducedMotionEnabled
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
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
fun DownloadsHistoryScreen(viewModel: DownloadsViewModel, onOpenQueue: () -> Unit, isQueueOpen: Boolean = false) {
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
    var sortOption by remember { mutableStateOf(LibrarySort.DATE_NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(GalleryDlPreferences.isLibraryGridView(context)) }
    val selectionMode = selectedIds.isNotEmpty()

    val snackbarHostState = remember { SnackbarHostState() }
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

    // Real nested-scroll-driven collapse instead of polling LazyListState/LazyGridState's own
    // position after the fact (the previous approach here) — that only ever sees where the list
    // *ended up* a frame late, coarse and index/offset-based, which is exactly why it needed a
    // slop threshold hacked in to stop flickering on tiny movements and still never actually
    // followed the finger, just snapped fully open/closed. TopAppBarScrollBehavior's own
    // NestedScrollConnection intercepts real scroll deltas as the gesture happens — the same
    // continuous, finger-following collapse Gmail/most apps' own toolbars use, and reused here
    // as-is rather than hand-rolling the drag/fling/overscroll edge cases it already handles.
    // enterAlways (not exitUntilCollapsed): reappears on ANY scroll back up, not only once
    // already at the very top of the list — matches "chases scroll direction" like a feed's
    // toolbar, not a page-detail screen's.
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    // The header's own natural (fully expanded) height in px — TopAppBarState needs this as its
    // heightOffsetLimit (how far *down* heightOffset, a value from 0 to this negative limit, can
    // collapse) to know when it's fully collapsed. Measured off the header's own inner content
    // (see its Modifier.onGloballyPositioned below), not the outer collapsing Box that wraps it —
    // that outer Box's own height IS the animated, currently-collapsing value, so measuring it
    // instead would be measuring its own output, never converging on the header's true full size.
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(headerHeightPx) {
        if (headerHeightPx > 0f) topAppBarState.heightOffsetLimit = -headerHeightPx
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            Icon(FeatherIcons.Share2, contentDescription = "Share selected")
                        }
                        IconButton(onClick = {
                            selectedIds.forEach { viewModel.setFavorite(it, true) }
                            selectedIds = emptySet()
                        }) {
                            Icon(FeatherIcons.Star, contentDescription = "Add selected to favorites")
                        }
                        IconButton(onClick = {
                            requestDelete(selectedIds)
                            selectedIds = emptySet()
                        }) {
                            Icon(FeatherIcons.Trash2, contentDescription = "Remove selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            } else {
                // Modifier.layout, not a plain Box(Modifier.height(...)) wrapping this Surface —
                // that first version measured the Surface WITH the collapsing height as its own
                // incoming constraint (a plain Box passes its own constraints straight through to
                // an unconstrained child), so onGloballyPositioned kept reporting back whatever the
                // *already-collapsed* height currently was instead of the header's true natural
                // size. That fed straight back into heightOffsetLimit, which fed back into the
                // collapsed height itself — a real feedback loop, reproduced live as the header
                // visibly flickering while scrolling rather than collapsing smoothly. Forcing
                // maxHeight = Infinity for measurement (ignoring the incoming constraint entirely)
                // is what breaks that loop: this Surface always measures at its one true natural
                // size regardless of how much of it is currently visible, and only the *placement*
                // — what this layout node reports upward to Scaffold's topBar slot, which is what
                // actually drives every LazyColumn/LazyVerticalGrid's own top content padding via
                // paddingValues.calculateTopPadding() — shrinks/grows continuously with
                // scrollBehavior.state.heightOffset. clipToBounds() crops the natural-size content
                // to that same smaller placed height instead of letting it draw past it.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                            headerHeightPx = placeable.height.toFloat()
                            val visibleHeight = (placeable.height + scrollBehavior.state.heightOffset)
                                .coerceIn(0f, placeable.height.toFloat())
                                .toInt()
                            layout(placeable.width, visibleHeight) { placeable.placeRelative(0, 0) }
                        },
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 3.dp,
                ) {
                    Column {
                        // Title area only: the gradient background sits on this nested Column,
                        // applied *before* statusBarsPadding (rather than after, on the Row inside
                        // it) so it still paints from the true top of the screen behind the status
                        // bar — the same bleed the whole header had before — while stopping right
                        // after the title instead of also covering the search bar/chips below.
                        // Confining it to just the title matches the Settings page, where the same
                        // gradient only ever covers its TopAppBar, never the search bar below it.
                        // (The header used to bleed *and* cover the whole thing at once; splitting
                        // it into its own Column here is what lets it keep doing the former without
                        // the latter — same underlying surfaceContainerHigh color as Settings on
                        // both, confirmed by sampling the actual rendered pixels, but the search bar
                        // used to read as a visibly different color purely from that extra green
                        // context bleeding around it.)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                            MaterialTheme.colorScheme.background,
                                        )
                                    )
                                )
                                .statusBarsPadding()
                        ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Library",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,                                    fontSize = 36.sp,
                                )
                                Text(
                                    if (showDuplicatesOnly) {
                                        "${duplicateAttempts.size} ${if (duplicateAttempts.size == 1) "duplicate" else "duplicates"}"
                                    } else {
                                        "${visibleItems.size} ${if (visibleItems.size == 1) "item" else "items"}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Favorites and the Grid/List toggle moved up here from the scrollable
                            // chip row below — they're both view-changing controls someone reaches
                            // for on every visit, not situational filters like Deleted/Duplicates/
                            // Audio, so they earn a fixed spot in the header instead of living
                            // wherever the chip row's horizontal scroll happens to leave them.
                            IconToggleButton(checked = favoritesOnly, onCheckedChange = {
                                favoritesOnly = it
                                if (favoritesOnly) { showDeletedOnly = false; showDuplicatesOnly = false; audioOnly = false }
                            }) {
                                Icon(
                                    FeatherIcons.Star,
                                    contentDescription = "Favorites only",
                                    tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                gridView = !gridView
                                GalleryDlPreferences.setLibraryGridView(context, gridView)
                            }) {
                                Icon(
                                    if (gridView) FeatherIcons.List else FeatherIcons.Grid,
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
                                        Icon(FeatherIcons.Download, contentDescription = "Active downloads")
                                    }
                                } else {
                                    Icon(FeatherIcons.Download, contentDescription = "Active downloads")
                                }
                            }
                        }
                        }
                        // Same pill search bar as the Settings page, in the same position relative
                        // to its own title — full width, right below it — instead of the old
                        // "Search" chip that toggled a separate full-screen search bar in its place.
                        PillSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "Search downloads",
                            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
                        )
                        // better-interface review: this row silently overflowed past the last
                        // couple of chips ("Deleted", Grid/List) on typical phone widths with no
                        // visible cue that anything more was scrollable. Two earlier attempts (an
                        // edge fade, then a scrollbar-style track/thumb strip) both worked but the
                        // user didn't want either look — this is a one-time "nudge" instead: a
                        // brief auto-scroll-and-back on first appearance, the physical equivalent
                        // of someone tapping the row and pointing right. Scrolls all the way to
                        // maxValue (the real end), not a small hinting bump — a fixed small nudge
                        // (reported live, and true of the analogous Queue-screen fix too) stopped
                        // short of actually revealing the last chip.
                        val toolbarScrollState = rememberScrollState()
                        LaunchedEffect(Unit) {
                            // Give the static state a beat to register before moving anything —
                            // also lets the real maxValue (only known post-layout) settle so a
                            // screen where every chip already fits doesn't nudge toward nothing.
                            delay(500)
                            // An automatic scroll the user didn't ask for — same reduce-motion
                            // gate as the entrance animations above, not just decorative here.
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
                                // Only top grew (2dp -> 8dp) to bring the chips down a little —
                                // bottom stays 10dp so the thin shadow strip below this row (the
                                // header Surface's own shadowElevation, visible right above the
                                // list) doesn't grow along with it.
                                .padding(top = 8.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            Box {
                                LibraryToolbarChip(
                                    icon = FeatherIcons.Sliders,
                                    label = "Sort",
                                    onClick = { sortMenuExpanded = true },
                                )
                                DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                                    LibrarySort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            leadingIcon = if (option == sortOption) {
                                                { Icon(FeatherIcons.Check, contentDescription = null) }
                                            } else null,
                                            onClick = { sortOption = option; sortMenuExpanded = false },
                                        )
                                    }
                                }
                            }
                            LibraryToolbarChip(
                                icon = FeatherIcons.Trash2,
                                label = "Deleted",
                                active = showDeletedOnly,
                                onClick = {
                                    showDeletedOnly = !showDeletedOnly
                                    if (showDeletedOnly) { favoritesOnly = false; showDuplicatesOnly = false; audioOnly = false }
                                },
                            )
                            LibraryToolbarChip(
                                icon = FeatherIcons.Copy,
                                label = "Duplicates",
                                active = showDuplicatesOnly,
                                count = duplicateAttempts.size,
                                onClick = {
                                    showDuplicatesOnly = !showDuplicatesOnly
                                    if (showDuplicatesOnly) { favoritesOnly = false; showDeletedOnly = false; audioOnly = false }
                                },
                            )
                            LibraryToolbarChip(
                                icon = FeatherIcons.Music,
                                label = "Audio",
                                active = audioOnly,
                                onClick = {
                                    audioOnly = !audioOnly
                                    if (audioOnly) { favoritesOnly = false; showDeletedOnly = false; showDuplicatesOnly = false }
                                },
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        // Wraps the whole branch below (previously each ending in its own early return@Scaffold)
        // so the bottom gradient scrim further down can sit as one unconditional sibling instead
        // of needing to be duplicated into every branch — see that scrim's own comment for why it
        // exists at all.
        Box(modifier = Modifier.fillMaxSize()) {
        if (showDuplicatesOnly) {
            if (duplicateAttempts.isEmpty()) {
                EmptyState(
                    icon = FeatherIcons.Copy,
                    title = "No duplicates",
                    subtitle = "A link you share in that's already queued, running, or finished lands here instead of starting a second copy.",
                    modifier = Modifier.padding(top = paddingValues.calculateTopPadding(), bottom = navBarClearance()),
                )
            } else {
                DuplicatesList(
                    attempts = duplicateAttempts,
                    contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = navBarClearance()),
                    onRedownload = { viewModel.redownloadDuplicate(it) },
                    onDismiss = { viewModel.dismissDuplicateAttempt(it) },
                )
            }
        } else if (visibleItems.isEmpty()) {
            val searching = searchQuery.isNotBlank()
            EmptyState(
                icon = if (searching) FeatherIcons.Search else if (showDeletedOnly) FeatherIcons.Trash2 else if (favoritesOnly) FeatherIcons.Star else FeatherIcons.Image,
                title = if (searching) "No matches" else if (showDeletedOnly) "Nothing deleted" else if (favoritesOnly) "No favorites yet" else "Nothing here yet",
                subtitle = if (searching) "Try a different search." else if (showDeletedOnly) "Pictures you remove from your device gallery will show up here." else if (favoritesOnly) "Star a download to pin it here." else "Downloaded pictures will show up in this gallery.",
                modifier = Modifier.padding(top = paddingValues.calculateTopPadding(), bottom = navBarClearance()),
            )
        } else if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), start = 8.dp, end = 8.dp, bottom = navBarClearance()),
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
                contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = navBarClearance()),
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

        // Mirrors the header's own top gradient (primary fading into background) at the bottom of
        // the screen instead — the header provided a visual "ceiling" that content faded into near
        // the status bar; once it collapses away on scroll, content now reaches edge-to-edge with
        // nothing softening where it meets the floating nav pill either, which read as an abrupt,
        // unfinished edge rather than an intentional one. Tied directly to the same
        // scrollBehavior.state.collapsedFraction driving the header's own collapse (0f fully
        // expanded, 1f fully collapsed) so it fades in exactly as the header fades away, not as a
        // separate on/off toggle of its own. FloatingNavBar itself (MainScreen.kt) composes after —
        // on top of — this whole screen, so this scrim sits correctly behind the pill without this
        // screen needing to know anything about it directly.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBarClearance())
                .graphicsLayer { alpha = scrollBehavior.state.collapsedFraction }
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
        )

        // Same underlying problem at the top: the header's own gradient (bleeding up under the
        // status bar via its own internal statusBarsPadding) is what currently keeps that area
        // from being flat, undifferentiated background — once the header collapses away, that
        // goes with it. This is a real "status bar text protection" scrim now (a Samsung Gallery-
        // style treatment), not a tinted echo of the header's own primary-colored gradient: pure
        // black, alpha-blended, fading to fully transparent — legible white status bar icons
        // against literally any content scrolled underneath, not just this app's own palette.
        // Extends STATUS_BAR_SCRIM_EXTRA_HEIGHT past the real status bar height rather than
        // stopping exactly at it, so the fade reads as a soft falloff into whatever's scrolled
        // there rather than a hard-edged strip — same reasoning Samsung's own implementation uses.
        //
        // NOT driven by the raw collapsedFraction the way the bottom scrim is — that produced a
        // visible hard pop-in, reproduced live: the header's own status-bar-height sliver is the
        // FIRST part of its content (the statusBarsPadding spacer sits above the title/search/
        // chips) and the LAST part clipped away, since Modifier.layout's collapsing placement
        // above keeps the header's top edge fixed and clips from the bottom up. So for nearly the
        // whole collapse gesture, the header's own real (opaque) status-bar strip is still fully
        // there occluding this scrim completely regardless of this scrim's own alpha — it only
        // stops being occluded in the final sliver of the gesture, once the header's own visible
        // height drops below the status bar's own height. Fading this scrim in across the WHOLE
        // collapsedFraction range meant it was already most of the way faded in by the time that
        // occlusion finally lifted, so it suddenly snapped into view instead of easing in. Scoping
        // the fade to only that final sliver — 0 while the header still fully covers the status
        // bar, ramping to 1 exactly as the header's own edge reaches the status bar's own height —
        // makes this scrim's reveal actually match when it becomes physically visible at all.
        val density = LocalDensity.current
        val statusBarPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
        val visibleHeaderPx = (headerHeightPx + scrollBehavior.state.heightOffset).coerceAtLeast(0f)
        val statusBarScrimAlpha = if (statusBarPx > 0f) {
            ((statusBarPx - visibleHeaderPx) / statusBarPx).coerceIn(0f, 1f)
        } else 0f
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
                Icon(FeatherIcons.ArrowUp, contentDescription = "Scroll to top")
            }
        }
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
                Icon(FeatherIcons.Trash2, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
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
                            Icon(FeatherIcons.Copy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Icon(FeatherIcons.RotateCcw, contentDescription = "Redownload")
                    }
                    IconButton(onClick = { onDismiss(attempt.id) }) {
                        Icon(FeatherIcons.X, contentDescription = "Dismiss")
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
    Box(modifier = modifier) {
        // A real FilterChip instead of a hand-rolled Row+clip+background+clickable — same tokens,
        // same animated color transition (FilterChip animates its own colors on selection change
        // internally), but now with the chip API's own accessibility semantics, minimum touch
        // target, and selected-state contract for free instead of reimplementing them.
        FilterChip(
            selected = active,
            onClick = onClick,
            modifier = Modifier.onSizeChanged { chipWidthPx = it.width },
            shape = MaterialTheme.shapes.large,
            leadingIcon = {
                // better-interface review: this icon's contentDescription duplicated the visible
                // Text right next to it inside one clickable (merged-semantics) row — decorative
                // next to real text, so null here, not a repeat of the same name TalkBack already
                // gets from the label.
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            label = { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium) },
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
                    DownloadStatus.ERRORED -> FeatherIcons.AlertTriangle
                    DownloadStatus.DELETED -> FeatherIcons.Trash2
                    else -> FeatherIcons.Image
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
                    Icon(FeatherIcons.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
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
                Icon(FeatherIcons.Star, contentDescription = "Favorite", tint = FavoriteGold, modifier = Modifier.size(11.dp))
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
                Icon(FeatherIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
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
        DownloadStatus.FINISHED -> Triple(FeatherIcons.CheckCircle, SuccessGreen40, "Succeeded")
        DownloadStatus.ERRORED -> Triple(FeatherIcons.AlertCircle, MaterialTheme.colorScheme.error, "Failed")
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
                    DownloadStatus.ERRORED -> FeatherIcons.AlertTriangle
                    DownloadStatus.DELETED -> FeatherIcons.Trash2
                    else -> FeatherIcons.Image
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
                            Icon(FeatherIcons.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
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
                        Icon(FeatherIcons.Star, contentDescription = "Favorite", tint = FavoriteGold, modifier = Modifier.size(11.dp))
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
                    Icon(FeatherIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
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
            MetaRow(icon = FeatherIcons.Layers, text = "${item.downloadedItems} • ${sdf.format(Date(item.effectiveDate))}")
            Spacer(Modifier.height(2.dp))
            MetaRow(icon = FeatherIcons.HardDrive, text = "Size • ${formatFileSize(item.totalBytes)}")
        }

        // Bulk actions in the selection-mode top bar replace the per-item menu while selecting.
        if (!selectionMode) Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(FeatherIcons.MoreVertical, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (item.isFavorite) "Remove from Favorites" else "Add to Favorites") },
                    leadingIcon = { Icon(FeatherIcons.Star, contentDescription = null) },
                    onClick = { menuExpanded = false; onToggleFavorite() },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(FeatherIcons.Edit2, contentDescription = null) },
                    onClick = { menuExpanded = false; showRenameDialog = true },
                )
                DropdownMenuItem(
                    text = { Text("Share Image") },
                    leadingIcon = { Icon(FeatherIcons.Share2, contentDescription = null) },
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
                    leadingIcon = { Icon(FeatherIcons.ExternalLink, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy Post Link") },
                    leadingIcon = { Icon(FeatherIcons.Copy, contentDescription = null) },
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
                    leadingIcon = { Icon(FeatherIcons.Trash2, contentDescription = null) },
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


