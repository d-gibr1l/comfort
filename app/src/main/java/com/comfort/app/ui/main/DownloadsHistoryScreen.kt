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
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    LaunchedEffect(isQueueOpen) {
        if (!isQueueOpen) {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }

    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    // Checks each finished download's thumbnail against the real MediaStore once per Library
    // visit — cheap enough for typical history sizes, and it's the only way to notice a file the
    // user deleted from their gallery outside the app.
    LaunchedEffect(Unit) { viewModel.scanForDeletedMedia() }

    val visibleItems = (if (showDeletedOnly) deletedItems else historyItems)
        .let { if (favoritesOnly) it.filter { item -> item.isFavorite } else it }
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
                Surface(
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
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.comfort.app.R.font.crystal_radio_kit)),
                                    fontSize = 40.sp,
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
                                icon = FeatherIcons.Star,
                                label = "Favorites",
                                active = favoritesOnly,
                                onClick = {
                                    favoritesOnly = !favoritesOnly
                                    if (favoritesOnly) { showDeletedOnly = false; showDuplicatesOnly = false }
                                },
                            )
                            LibraryToolbarChip(
                                icon = FeatherIcons.Trash2,
                                label = "Deleted",
                                active = showDeletedOnly,
                                onClick = {
                                    showDeletedOnly = !showDeletedOnly
                                    if (showDeletedOnly) { favoritesOnly = false; showDuplicatesOnly = false }
                                },
                            )
                            LibraryToolbarChip(
                                icon = FeatherIcons.Copy,
                                label = "Duplicates",
                                active = showDuplicatesOnly,
                                onClick = {
                                    showDuplicatesOnly = !showDuplicatesOnly
                                    if (showDuplicatesOnly) { favoritesOnly = false; showDeletedOnly = false }
                                },
                            )
                            LibraryToolbarChip(
                                icon = if (gridView) FeatherIcons.List else FeatherIcons.Grid,
                                label = if (gridView) "List" else "Grid",
                                onClick = {
                                    gridView = !gridView
                                    GalleryDlPreferences.setLibraryGridView(context, gridView)
                                },
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (showDuplicatesOnly) {
            if (duplicateAttempts.isEmpty()) {
                EmptyState(
                    icon = FeatherIcons.Copy,
                    title = "No duplicates",
                    subtitle = "A link you share in that's already queued, running, or finished lands here instead of starting a second copy.",
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                DuplicatesList(
                    attempts = duplicateAttempts,
                    contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = navBarClearance()),
                    onRedownload = { viewModel.redownloadDuplicate(it) },
                    onDismiss = { viewModel.dismissDuplicateAttempt(it) },
                )
            }
            return@Scaffold
        }
        if (visibleItems.isEmpty()) {
            val searching = searchQuery.isNotBlank()
            EmptyState(
                icon = if (searching) FeatherIcons.Search else if (showDeletedOnly) FeatherIcons.Trash2 else if (favoritesOnly) FeatherIcons.Star else FeatherIcons.Image,
                title = if (searching) "No matches" else if (showDeletedOnly) "Nothing deleted" else if (favoritesOnly) "No favorites yet" else "Nothing here yet",
                subtitle = if (searching) "Try a different search." else if (showDeletedOnly) "Pictures you remove from your device gallery will show up here." else if (favoritesOnly) "Star a download to pin it here." else "Downloaded pictures will show up in this gallery.",
                modifier = Modifier.padding(paddingValues),
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
                        }
                    }
                }
            }
        }
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
    LazyColumn(contentPadding = contentPadding.let { PaddingValues(top = it.calculateTopPadding(), bottom = it.calculateBottomPadding(), start = 16.dp, end = 16.dp) }) {
        items(attempts, key = { it.id }) { attempt ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
) {
    val bg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(200),
        label = "chipBg",
    )
    val tint by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "chipTint",
    )
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // better-interface review: this icon's contentDescription duplicated the visible Text
        // right next to it inside one clickable (merged-semantics) row — decorative next to real
        // text, so null here, not a repeat of the same name TalkBack already gets from the Text.
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Medium)
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onTap()
                    } else if (hasThumbnail) {
                        val uri = Uri.parse(item.thumbnailPath)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
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
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onTap()
                    } else if (hasThumbnail) {
                        val uri = Uri.parse(item.thumbnailPath)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(76.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
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


