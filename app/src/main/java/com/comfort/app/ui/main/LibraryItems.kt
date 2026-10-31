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

/** Library's "Duplicates" filter content — a plain audit log, not the rich gallery grid/list the
 * rest of this screen renders, since a duplicate attempt was never actually downloaded (no
 * progress, no size, no favorite/delete state — just "you tried this link and already had it").
 * Each row can redownload anyway (bypassing the duplicate check for just that one link) or be
 * dismissed on its own, independent of the real download it matched. */
@Composable
internal fun DuplicatesList(
    attempts: List<com.comfort.app.data.DuplicateAttempt>,
    contentPadding: PaddingValues,
    onRedownload: (com.comfort.app.data.DuplicateAttempt) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val sdf = remember { Locale.getDefault().let { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(it, "MMMdjmm"), it) } }
    // Same entrance/removal treatment as the real download rows further up (HistoryRow/
    // HistoryGridItem) — a plain instant pop-out on Dismiss read as jarring/inconsistent next to
    // every other list in this app already animating removals, once actually compared side by
    // side. Own id-keyed "already played its entrance" map (own id-space — DuplicateAttempt ids,
    // never collide with a real download's own id) rather than sharing the outer screen's
    // alreadyAnimatedIds: a Lazy layout recycling this composable in and out of view shouldn't
    // replay the slide-in every time, same reasoning as that other map's own doc comment.
    val alreadyAnimatedIds = remember { mutableSetOf<String>() }
    MarkShownAfterFirstFrame(alreadyAnimatedIds, attempts) { it.id }
    val reducedMotion = rememberIsReducedMotionEnabled()
    LazyColumn(contentPadding = contentPadding.let { PaddingValues(top = it.calculateTopPadding(), bottom = it.calculateBottomPadding(), start = 16.dp, end = 16.dp) }) {
        items(attempts, key = { it.id }) { attempt ->
            val visibleState = remember(attempt.id) {
                MutableTransitionState(alreadyAnimatedIds.contains(attempt.id)).apply { targetState = true }
            }
            SideEffect { alreadyAnimatedIds.add(attempt.id) }
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
                            stringResource(R.string.already_downloaded_tried_again_x, sdf.format(Date(attempt.dateAdded))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onRedownload(attempt) }) {
                        Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.redownload))
                    }
                    IconButton(onClick = { onDismiss(attempt.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.dismiss))
                    }
                }
            }
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryGridItem(
    item: DownloadEntity,
    height: androidx.compose.ui.unit.Dp,
    shape: com.comfort.app.util.MediaShape?,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: (String) -> Unit,
    onRedownload: () -> Unit,
    onDownloadMissing: () -> Unit,
) {
    val context = LocalContext.current
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

    Box(
        modifier = Modifier
            // Width comes from the tile's span in its row (justifiedCells), height from the row.
            // Square corners and edge to edge, like a gallery app's grid.
            .fillMaxWidth()
            .height(height)
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (selectionMode) {
                    Modifier.semantics {
                        this.selected = selected
                        role = Role.Checkbox
                    }
                } else Modifier
            )
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onTap()
                    } else if (hasThumbnail) {
                        // The media viewer (Photon Gallery's, ui/viewer); it has "Open with" for another app.
                        onOpen()
                    }
                },
                onLongClick = onLongPress,
            ),
    ) {
        if (hasThumbnail) {
            LibraryThumbnail(item, Modifier.fillMaxSize())
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

        // Bottom-left: what kind of file it is (a play icon on a video, a note on audio — icon only,
        // durations are in the list view) and, on a multi-item download, how many items. White on
        // a dark translucent pill, readable on any thumbnail.
        val isVideo = !item.isAudio && shape?.durationMs != null
        val typeIcon = when {
            item.isAudio -> Icons.Outlined.MusicNote
            isVideo -> Icons.Filled.PlayArrow
            else -> null
        }
        val incomplete = item.missingItems > 0
        if (typeIcon != null || item.downloadedItems > 1 || incomplete) {
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (typeIcon != null) MediaBadge(icon = typeIcon, text = null)
                if (incomplete) {
                    MediaBadge(icon = Icons.Outlined.Warning, text = "${item.downloadedItems}/${item.totalItems}", tint = Color(0xFFFFB74D))
                } else if (item.downloadedItems > 1) {
                    MediaBadge(icon = Icons.Outlined.Layers, text = "${item.downloadedItems}")
                }
            }
        }

        // Top-left: a video's quality, from its real short side (a portrait 720x1280 video is
        // "720p", not the "1280p" its format tag says).
        val quality = shape?.shortSide?.takeIf { isVideo }?.let { qualityLabel(it) }
        if (quality != null) {
            MediaBadge(icon = null, text = quality, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        }

        // Top-right: favourite, in the same dark pill as the other badges, then the ⋮ menu (the
        // list rows' actions; long-press still selects). Hidden while selecting, like the list's.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(start = 6.dp, top = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isFavorite) {
                MediaBadge(icon = Icons.Filled.Star, text = null, tint = Color(0xFFFFD54F), modifier = Modifier.padding(top = 4.dp))
            }
            if (!selectionMode) Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Box(
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options), tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
                DownloadActionsMenu(
                    item = item,
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onToggleFavorite = onToggleFavorite,
                    onRename = { showRenameDialog = true },
                    onRedownload = onRedownload,
                    onDownloadMissing = onDownloadMissing,
                    onDelete = onDelete,
                )
            }
        }

        // Bottom-right: the download's size, and the failure badge when it failed.
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.totalBytes > 0) MediaBadge(icon = null, text = formatFileSize(item.totalBytes))
            if (item.status == DownloadStatus.ERRORED) StatusBadge(status = item.status)
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
                Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/** Icon + short text on a dark translucent pill, white so it reads on any thumbnail — the list
 * thumbnail's duration and the grid tile's item count. [compact] for the 76 dp list thumbnail. */
@Composable
private fun MediaBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tint: Color = Color.White,
) {
    val side = when {
        text == null -> 3.dp
        compact -> 4.dp
        else -> 6.dp
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 5.dp else 6.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(start = if (icon != null) side / 2 + 1.dp else side, end = side, top = if (text == null) 3.dp else 1.dp, bottom = if (text == null) 3.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (compact) 11.dp else 14.dp))
        if (icon != null && text != null) Spacer(Modifier.width(2.dp))
        if (text != null) {
            Text(
                text,
                color = Color.White,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A video's quality badge from its short side in pixels, snapped to the usual steps (a 718 px
 * encode reads "720p"); anything off the ladder shows its own number. */
private fun qualityLabel(shortSide: Int): String {
    val step = QUALITY_STEPS.lastOrNull { it <= shortSide * 1.05f }
    return when {
        step == null -> "${shortSide}p"
        step >= 2160 -> "4K"
        else -> "${step}p"
    }
}

private val QUALITY_STEPS = listOf(144, 240, 360, 480, 540, 720, 1080, 1440, 2160)

/** "0:13", "1:21", "1:02:05" — a thumbnail's duration badge. */
private fun formatGridDuration(ms: Long): String {
    val total = (ms + 500) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Small circular success/failure badge shared by both the grid and list history rows —
 * FINISHED gets a check in the theme's primary color, ERRORED an alert in its error color (on a
 * themed surface pill — the fixed green/black versions ignored the selected theme); every other status (DELETED, CANCELLED)
 * already reads clearly enough from its own placeholder icon not to need one. */
@Composable
private fun StatusBadge(status: DownloadStatus, modifier: Modifier = Modifier) {
    val (icon, tint, description) = when (status) {
        DownloadStatus.FINISHED -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.succeeded))
        DownloadStatus.ERRORED -> Triple(Icons.Outlined.Error, MaterialTheme.colorScheme.error, stringResource(R.string.failed))
        else -> return
    }
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(12.dp))
    }
}

// One "Oct 9, 2026" (or "9. Okt. 2026", in the language's own order) formatter for every Library card: a remember in HistoryRow built a new one
// for each card scrolling into view, which showed on a fast fling. Rebuilt only if the language
// changes. SimpleDateFormat isn't thread-safe; it's shared only because it's used while
// composing, on the main thread.
private var historyDateFormatCache: Pair<Locale, SimpleDateFormat>? = null

private fun historyDateFormat(): SimpleDateFormat {
    val locale = Locale.getDefault()
    historyDateFormatCache?.let { (cachedLocale, format) -> if (cachedLocale == locale) return format }
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale).also { historyDateFormatCache = locale to it }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryRow(
    item: DownloadEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    durationMs: Long?,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: (String) -> Unit,
    onRedownload: () -> Unit,
    onDownloadMissing: () -> Unit,
) {
    val context = LocalContext.current
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
            .clip(LIBRARY_CARD_SHAPE)
            // "background" is the top-level scaffold/page role, not a role for a row sitting on
            // top of it as its own distinct surface — this row now reads as a real MD3 surface
            // (surfaceContainer, same tone the Queue screen's own cards use) instead of blending
            // into the page. The selected state used an alpha-blended primaryContainer, which
            // breaks the tonal-pairing contract other content on this row (onSurface title text,
            // not onPrimaryContainer) assumes a fully-opaque container guarantees — full-opacity
            // primaryContainer instead.
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .swipeRemoveAction()
            .then(
                if (selectionMode) {
                    Modifier.semantics {
                        this.selected = selected
                        role = Role.Checkbox
                    }
                } else Modifier
            )
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onTap()
                    } else if (hasThumbnail) {
                        // The media viewer (Photon Gallery's, ui/viewer); it has "Open with" for another app.
                        onOpen()
                    }
                },
                onLongClick = onLongPress,
            )
            // 8dp, not 10dp — MD3's spacing system is built on an 8dp grid. 12dp sides now that
            // the card itself is inset 16dp from the screen edge.
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(76.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The shared thumbnail shape (Components.kt thumbnailShape).
                    .clip(thumbnailShape())
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (hasThumbnail) {
                    LibraryThumbnail(item, Modifier.fillMaxSize())
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

                // No item-count or status overlay here (unlike the grid): the row's own text beside
                // the thumbnail already shows both. A video's or song's length ("▶ 0:13") sits in the
                // bottom-left corner, inside the thumbnail.
                if (durationMs != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(start = 3.dp, end = 5.dp, top = 1.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            formatGridDuration(durationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (item.isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Star, contentDescription = stringResource(R.string.favorite), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
                    }
                }
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
                    Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
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
            val domain = remember(item.url) { runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: context.getString(R.string.unknown_site) }
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

            MetaRow(icon = Icons.Outlined.Layers, text = stringResource(R.string.x_x, item.downloadedItems, historyDateFormat().format(Date(item.effectiveDate))))
            Spacer(Modifier.height(2.dp))
            MetaRow(icon = Icons.Outlined.SdStorage, text = stringResource(R.string.size_x, formatFileSize(item.totalBytes)))
            if (item.missingItems > 0) {
                Spacer(Modifier.height(2.dp))
                MetaRow(icon = Icons.Outlined.Warning, text = item.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        }

        // Bulk actions in the selection-mode top bar replace the per-item menu while selecting.
        if (!selectionMode) Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
            DownloadActionsMenu(
                item = item,
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onToggleFavorite = onToggleFavorite,
                onRename = { showRenameDialog = true },
                onRedownload = onRedownload,
                onDownloadMissing = onDownloadMissing,
                onDelete = onDelete,
            )
        }
    }
}

/** A download's ⋮ menu, shared by the list rows and the grid tiles. A download whose file was
 * deleted from the phone gets what still makes sense for it — download it again, open or copy its
 * link, remove it — rather than actions on a file that isn't there. */
@Composable
private fun DownloadActionsMenu(
    item: DownloadEntity,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onRedownload: () -> Unit,
    onDownloadMissing: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val fileGone = item.status == DownloadStatus.DELETED
    // The real file: a song's thumbnailPath is its cover art, and mediaUri the song.
    val fileUri = (item.mediaUri ?: item.thumbnailPath)?.takeIf { it.isNotBlank() && !fileGone }
    // Compress is offered for a saved video only (not a song, a picture or a multi-file post's
    // first file being an image). Asked of MediaStore the first time the menu opens (the menu's
    // items are only built then), not as the card is: that query, on the main thread for every
    // card scrolling into view, was two-thirds of building one (~3 ms each, traced), and a fast
    // fling stuttered through the Library.
    val isVideo = remember(fileUri, item.isAudio) {
        lazy {
            !item.isAudio && fileUri != null &&
                runCatching { context.contentResolver.getType(Uri.parse(fileUri)) }.getOrNull()?.startsWith("video/") == true
        }
    }
    var compressOpen by remember { mutableStateOf(false) }
    if (compressOpen && fileUri != null) {
        CompressDialog(item = item, fileUri = Uri.parse(fileUri), onDismiss = { compressOpen = false })
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (fileGone) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_again)) },
                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                onClick = { onDismiss(); onRedownload() },
            )
        } else {
            // Runs the same download again: its archives skip what's already saved.
            if (item.missingItems > 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download_missing_items)) },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    onClick = { onDismiss(); onDownloadMissing() },
                )
            }
            DropdownMenuItem(
                text = { Text(if (item.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites)) },
                leadingIcon = { Icon(Icons.Outlined.Star, contentDescription = null) },
                onClick = { onDismiss(); onToggleFavorite() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { onDismiss(); onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                enabled = fileUri != null,
                onClick = {
                    onDismiss()
                    val uri = Uri.parse(fileUri)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = context.contentResolver.getType(uri) ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.share))) }
                },
            )
            if (isVideo.value) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.compress_video)) },
                    leadingIcon = { Icon(Icons.Outlined.Compress, contentDescription = null) },
                    onClick = { onDismiss(); compressOpen = true },
                )
            }
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.view_original_source)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            onClick = {
                onDismiss()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.copy_post_link)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            onClick = {
                onDismiss()
                scope.launch {
                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText(context.getString(R.string.post_link), item.url)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (fileGone) stringResource(R.string.remove_from_library) else stringResource(R.string.sponsorblock_remove)) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            onClick = { onDismiss(); onDelete() },
        )
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
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun MetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How many of a finished download's items failed while the rest saved — DownloadWorker keeps the
 * note saying so as a FINISHED row's errorMessage. 0 for a complete download. */
private val DownloadEntity.missingItems: Int
    get() = if (status == DownloadStatus.FINISHED && !errorMessage.isNullOrBlank()) (totalItems - downloadedItems).coerceAtLeast(0) else 0



/** Library list-view card shape — same 20dp corners as the Download Queue's cards. */
internal val LIBRARY_CARD_SHAPE = RoundedCornerShape(20.dp)

/** A Library item's thumbnail: for a song, its cover (AppImageLoader's AudioCover), and a music
 * note when it has none — a song with no cover used to leave an empty tile. */
@Composable
internal fun LibraryThumbnail(item: DownloadEntity, modifier: Modifier = Modifier) {
    var failed by remember(item.id, item.thumbnailPath, item.mediaUri) { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val context = LocalContext.current
        val request = remember(item.id, item.thumbnailPath, item.mediaUri, item.isAudio) {
            coil.request.ImageRequest.Builder(context)
                .data(item.thumbnailModel())
                // Under the file's key, so the media viewer can show it at once while it loads.
                .memoryCacheKey(com.comfort.app.util.thumbnailCacheKey(item.id))
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            onSuccess = { failed = false },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Icon(
                if (item.isAudio) Icons.Outlined.MusicNote else Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
