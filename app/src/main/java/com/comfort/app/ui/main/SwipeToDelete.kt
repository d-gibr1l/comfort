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

/** Shared swipe-to-delete wrapper for both HistoryRow's own list and DuplicatesList below —
 * [modifier] and [shape] describe the card's own footprint (edge-to-edge for HistoryRow's plain
 * rows, a margined rounded pill for Duplicates' cards) and are applied to the reveal background
 * exactly once, from the same values the caller gives [content] itself, rather than each call site
 * hand-copying its card's own margin/shape into a second, separate background Box. That hand-copy
 * was exactly what caused Duplicates' cards to show a permanent colored halo at rest: its
 * background didn't match the rounded, margined Surface it sat behind. */
@Composable
internal fun SwipeToDeleteCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    /** Off: the card doesn't swipe (the music queue's song playing). */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The handler is kept by the swipe state from its first composition: read the latest onDelete
    // through this, or a card that moved (the music queue's rows are keyed by song, not place)
    // deleted by its old position.
    val latestOnDelete by androidx.compose.runtime.rememberUpdatedState(onDelete)
    // Once per dismiss: Material can confirm the same one more than once (a fling settling).
    var deleted by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = androidx.compose.material3.SwipeToDismissBoxDefaults.positionalThreshold
    // Not the saveable state: a list keeps that with the item, so a row brought back by Undo
    // came back still swiped away for a frame (a flash of the red background) before a reset.
    // A card that's composed at all should start settled.
    // Material deletes on any flick past its speed floor however short it was: a light flick
    // deleted the card. A delete now needs the card slid this much of its width; a flick that
    // didn't get it that far springs back.
    var cardWidthPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val stateRef = remember { arrayOfNulls<androidx.compose.material3.SwipeToDismissBoxState>(1) }
    val dismissState = remember {
        androidx.compose.material3.SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { value ->
                if (value != SwipeToDismissBoxValue.Settled) {
                    val slid = stateRef[0]?.let { runCatching { kotlin.math.abs(it.requireOffset()) }.getOrNull() } ?: 0f
                    if (cardWidthPx > 0f && slid < cardWidthPx * MIN_DELETE_SWIPE_FRACTION) return@SwipeToDismissBoxState false
                }
                if (value != SwipeToDismissBoxValue.Settled && !deleted) {
                    deleted = true
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                    latestOnDelete()
                }
                true
            },
            positionalThreshold = threshold,
        )
    }
    stateRef[0] = dismissState
    // A tick the moment a drag crosses the delete threshold (and a lighter one if it's dragged
    // back under it), so the point of no return is felt, not just seen. targetValue is what flips
    // at that threshold mid-drag; drop(1) skips its initial Settled emission.
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }
            .drop(1)
            .collect { target ->
                haptics.performHapticFeedback(
                    if (target != SwipeToDismissBoxValue.Settled) {
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate
                    } else {
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick
                    }
                )
            }
    }
    // Under the main pages, the card's outer strips (thumbnail, ⋮) turn the page instead
    // (PageSwipe.kt); the Queue's cards have no pages to turn.
    val pageSwipe = LocalPageSwipe.current
    SwipeToDismissBox(
        state = dismissState,
        modifier = (if (pageSwipe != null) modifier.pageSwipeEdges(pageSwipe) else modifier)
            .onSizeChanged { cardWidthPx = it.width.toFloat() },
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            // Only while the card is slid: at rest it was drawn (and its icon built) under every
            // card for nothing, its red showing at the card's rounded corners.
            if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.sponsorblock_remove), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        // The card's row takes the swipe as a TalkBack action (Modifier.swipeRemoveAction).
        androidx.compose.runtime.CompositionLocalProvider(LocalSwipeRemove provides if (enabled) ({ latestOnDelete() }) else null) {
            content()
        }
    }
}

/** What swiping the enclosing SwipeToDeleteCard away does, for [swipeRemoveAction]; null outside
 * one, or where it doesn't swipe. */
internal val LocalSwipeRemove = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** The enclosing SwipeToDeleteCard's swipe as a TalkBack "Remove" action, on the row a screen
 * reader focuses — a swipe it can't make. On the row itself, not the card around it: TalkBack
 * lands on the row (it's clickable), and an action on the card would never be offered. */
@Composable
internal fun Modifier.swipeRemoveAction(): Modifier {
    val remove = LocalSwipeRemove.current ?: return this
    val label = stringResource(R.string.sponsorblock_remove)
    return semantics {
        customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(label) { remove(); true })
    }
}

/** How much of its width a card has to be slid for a swipe to delete it (a flick included). */
private const val MIN_DELETE_SWIPE_FRACTION = 0.4f
