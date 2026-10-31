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

/** The Library header — real list content now (its caller composes it via `item { }` /
 * `item(span = { GridItemSpan(maxLineSpan) }) { }`), so it's a fixed, static block like any other
 * row: nothing here shrinks or fades on its own any more. It moves exactly as far and as fast as
 * the finger drags the list, because from the list's own point of view it IS the list — there's
 * nothing left to keep "in sync" with scroll position, which is the whole point. */
@Composable
internal fun LibraryHeader(
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
    deletedCount: Int,
    onClearDeleted: () -> Unit,
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
        // Kept while searching too: favourites and the grid/list switch apply to the results.
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
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
                        placeholder = stringResource(R.string.search_downloads),
                        modifier = Modifier.weight(1f),
                        autoFocus = true,
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
                            contentDescription = stringResource(R.string.close_search),
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
                            stringResource(R.string.shortcut_library_short),
                            // Same style declaration as the Settings root header's own "Settings"
                            // title (see SettingsScreen.kt's SettingsRootScreen) — displayMedium (30sp)
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
                            contentDescription = stringResource(R.string.search),
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
                        label = stringResource(R.string.sort),
                        onClick = { onSortMenuExpandedChange(true) },
                        groupIndex = 0, groupSize = 4,
                    )
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { onSortMenuExpandedChange(false) }) {
                        LibrarySort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.label)) },
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
                    label = stringResource(R.string.deleted),
                    active = showDeletedOnly,
                    onClick = onToggleShowDeletedOnly,
                    groupIndex = 1, groupSize = 4,
                )
                LibraryToolbarChip(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.duplicates),
                    active = showDuplicatesOnly,
                    count = duplicateAttemptsCount,
                    onClick = onToggleShowDuplicatesOnly,
                    groupIndex = 2, groupSize = 4,
                )
                LibraryToolbarChip(
                    icon = Icons.Outlined.MusicNote,
                    label = stringResource(R.string.audio),
                    active = audioOnly,
                    onClick = onToggleAudioOnly,
                    groupIndex = 3, groupSize = 4,
                )
            }
        }
        // Deleted: their files are gone, so all that's left to do with them is clear them out.
        if (showDeletedOnly && deletedCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.downloads_files_deleted, deletedCount, deletedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearDeleted) { Text(stringResource(R.string.clear_all)) }
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
internal fun LibraryCompactBar(
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
    
    // 45dp is the rough density-independent height of displayMedium (45sp) text.
    val initialHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 45.dp.toPx() }
    var libraryHeightPx by remember { mutableFloatStateOf(initialHeight) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val maxTranslatePx = (76.dp - 4.dp).toPx()
                val rawOffset = headerScrollOffsetPx()
                val offset = if (rawOffset == Float.POSITIVE_INFINITY) maxTranslatePx else rawOffset.coerceIn(0f, maxTranslatePx)
                val fraction = 1f - (offset / maxTranslatePx)
                val currentTranslate = maxTranslatePx * fraction
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
                .graphicsLayer {
                    val maxTranslatePx = (76.dp - 4.dp).toPx()
                    val rawOffset = headerScrollOffsetPx()
                    val offset = if (rawOffset == Float.POSITIVE_INFINITY) maxTranslatePx else rawOffset.coerceIn(0f, maxTranslatePx)
                    val fraction = 1f - (offset / maxTranslatePx)
                    translationY = maxTranslatePx * fraction
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
            // This structure EXACTLY mirrors LibraryRealHeader, guaranteeing the layout heights
            // perfectly match in the expanded state, preventing background overlap on the chips!
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.shortcut_library_short),
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = HeaderFontFamily,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.onSizeChanged { libraryHeightPx = it.height.toFloat() }
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        // Fades in whole rather than being uncovered: its layout height still grows
                        // with the transform below (that's what lands the title on the real
                        // header's), but the text itself is no longer clipped to that growing
                        // height — clipping it read as a sliced, half-height line (reported
                        // live). Alpha only starts rising halfway through, so the line isn't
                        // visible yet while its slot is still too short and it's overlapping.
                        .graphicsLayer {
                            val maxTranslatePx = (76.dp - 4.dp).toPx()
                            val rawOffset = headerScrollOffsetPx()
                            val offset = if (rawOffset == Float.POSITIVE_INFINITY) maxTranslatePx else rawOffset.coerceIn(0f, maxTranslatePx)
                            val progress = 1f - (offset / maxTranslatePx)
                            alpha = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
                        }
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
                // Eliminate the 11dp background gap: this row is 60dp tall naturally, which props
                // open the parent Box when the Main Row shrinks to 51dp. Reporting 0 height lets
                // the Box perfectly hug the shrinking text block instead.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, 0) {
                        placeable.place(0, 0)
                    }
                }
                .graphicsLayer {
                    val maxTranslatePx = (76.dp - 4.dp).toPx()
                    val rawOffset = headerScrollOffsetPx()
                    val offset = if (rawOffset == Float.POSITIVE_INFINITY) maxTranslatePx else rawOffset.coerceIn(0f, maxTranslatePx)
                    val fraction = offset / maxTranslatePx
                    
                    // We perfectly align the Centers of both bounding boxes.
                    // In compact state (fraction=1), the Main Row's top is 4dp.
                    // The center of the Library text layout box is 4dp + (libraryHeightPx / 2).
                    // To perfectly center the Actions (48dp), we subtract half its height.
                    // The user requested a tiny manual nudge downward, so we add 3.dp.
                    val targetScreenTop = 4.dp.toPx() + (libraryHeightPx / 2f) - 24.dp.toPx() + 3.dp.toPx()
                    
                    // Natural top is 12.dp, so we translate the difference.
                    val endTranslateY = targetScreenTop - 12.dp.toPx()
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
            contentDescription = stringResource(R.string.favorites_only),
            tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    IconButton(onClick = onToggleGridView) {
        Icon(
            if (gridView) Icons.AutoMirrored.Outlined.List else Icons.Outlined.GridView,
            contentDescription = if (gridView) stringResource(R.string.switch_to_list_view) else stringResource(R.string.switch_to_grid_view),
        )
    }
    // The badge sits beside the button, not inside it: BadgedBox placed it past the icon's edge,
    // and inside the button that part was clipped (a 3-digit count was cut off at the screen
    // edge). Aligned to the button's own top-end corner it always stays within its 48dp area.
    Box {
        IconButton(onClick = onOpenQueue) {
            Icon(Icons.Outlined.DownloadForOffline, contentDescription = stringResource(R.string.active_downloads))
        }
        if (hasActiveDownloads) {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp),
            ) {
                Text(badgeCountText(activeDownloadsCount))
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
            ) { Text(badgeCountText(count)) }
        }
    }
}
