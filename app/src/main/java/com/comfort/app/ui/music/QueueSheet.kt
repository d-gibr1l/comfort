package com.comfort.app.ui.music

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.comfort.app.music.MusicPlayer
import com.comfort.app.music.Song

private val ROW_HEIGHT = 72.dp

/**
 * The queue, from Now Playing (its queue button, or a swipe up), after Auxio's: the song playing
 * at the top with its controls, then every song in play order — those already played faded, the
 * one on highlighted with bars over its cover. A tap plays one, the handle drags one to a new
 * place (with shuffle off: a shuffled order is the player's own), a sideways swipe takes one out,
 * and Clear leaves only the song on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueuePanel() {
    val state by MusicPlayer.state.collectAsState()
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // The rows as shown: the player's queue, kept as is while a row is dragged. The dragged row
    // stays in its slot (only drawn under the finger) and the rows it passes slide aside, so the
    // list never re-lays it out mid-drag (that shifted the finger's position by a row and rows
    // swapped back and forth); the queue is reordered once, on drop.
    var rows by remember { mutableStateOf(state.queue) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragStart by remember { mutableStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.queue) { if (dragging == null) rows = state.queue }
    val dragTarget = if (dragging == null) -1 else
        (dragStart + kotlin.math.round(dragOffset / rowHeightPx).toInt()).coerceIn(0, rows.lastIndex)
    val reorderable = !state.shuffle
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.queue.indexOfFirst { it.first == state.index }.coerceAtLeast(0),
    )

    run {
        Column(modifier = Modifier.fillMaxSize()) {
            // A handle, then "Queue" (the song playing is Now Playing's bar above this).
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("Queue")
                        if (state.shuffle) append(" · shuffled")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.count > 1) {
                    androidx.compose.material3.TextButton(onClick = MusicPlayer::clearQueue) { Text("Clear") }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                state = listState,
                // Clears the navigation bar at the end.
                contentPadding = PaddingValues(bottom = 24.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // Keyed by the song (and which copy, for one queued twice), not its place in the
                // queue: a reorder renumbers the places, and every row below was rebuilt (its
                // cover reloading: a flicker on drop).
                val keys = run {
                    val seen = HashMap<String, Int>()
                    rows.map { (_, song) -> val n = seen.merge(song.id, 1, Int::plus)!!; "${song.id}#$n" }
                }
                itemsIndexed(rows, key = { pos, _ -> keys[pos] }) { pos, (index, song) ->
                    val isDragged = dragging == index
                    // A row the dragged one passes moves a slot up or down out of its way.
                    val shift = when {
                        dragging == null || isDragged -> 0f
                        pos in (dragStart + 1)..dragTarget -> -rowHeightPx
                        pos in dragTarget until dragStart -> rowHeightPx
                        else -> 0f
                    }
                    val shown by androidx.compose.animation.core.animateFloatAsState(
                        shift,
                        animationSpec = if (dragging == null) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(stiffness = 600f),
                        label = "queueShift",
                    )
                    val currentPos = rows.indexOfFirst { it.first == state.index }
                    QueueRow(
                        song = song,
                        // Unchanged by a drag (rows stay in their slots): fading them in and out
                        // as one started and ended flickered.
                        played = currentPos >= 0 && pos < currentPos,
                        current = index == state.index,
                        isPlaying = state.isPlaying,
                        onClick = { MusicPlayer.playAt(index) },
                        // Swipe sideways to take a song out (not the one on).
                        onRemove = if (index == state.index || dragging != null) null else ({ MusicPlayer.removeAt(index) }),
                        handle = if (!reorderable) null else Modifier.pointerInput(index) {
                            detectDragGestures(
                                onDragStart = {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    dragStart = rows.indexOfFirst { it.first == index }
                                    dragOffset = 0f
                                    dragging = index
                                },
                                onDragEnd = {
                                    // Worked out here, from the drag's state now: this handler was made
                                    // when the row was first drawn and doesn't see later recompositions.
                                    val from = dragStart
                                    val to = (from + kotlin.math.round(dragOffset / rowHeightPx).toInt()).coerceIn(0, rows.lastIndex)
                                    // The rows in their new order and the drag ended in one go: the
                                    // dropped row lands where it was drawn, the others where they slid.
                                    if (to != from && to >= 0) rows = rows.toMutableList().also { it.add(to, it.removeAt(from)) }
                                    dragging = null
                                    dragOffset = 0f
                                    // Without shuffle the rows are the queue's own order.
                                    if (to != from && to >= 0) MusicPlayer.move(from, to)
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
                                },
                                onDragCancel = { dragging = null; dragOffset = 0f },
                            ) { change, amount ->
                                change.consume()
                                // Not past the list's ends.
                                val before = kotlin.math.round(dragOffset / rowHeightPx).toInt()
                                dragOffset = (dragOffset + amount.y).coerceIn(-dragStart * rowHeightPx, (rows.lastIndex - dragStart) * rowHeightPx)
                                // A tick each time the dragged song passes another.
                                if (kotlin.math.round(dragOffset / rowHeightPx).toInt() != before) {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                                }
                            }
                        },
                        modifier = if (isDragged) {
                            Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                                .shadow(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        } else {
                            Modifier.graphicsLayer { translationY = shown }
                        },
                    )
                }
            }

        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    played: Boolean,
    current: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    handle: Modifier?,
    modifier: Modifier = Modifier,
    /** Swiped away sideways (null: can't be — the song on). */
    onRemove: (() -> Unit)? = null,
) {
    // Always the swipe box, only its swipes switched off where a song can't go: a row that changed
    // between the two (as a drag started and ended) was rebuilt, its cover reloading — a flicker.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dismiss = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != androidx.compose.material3.SwipeToDismissBoxValue.Settled && onRemove != null) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                onRemove()
            }
            true
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = dismiss,
        modifier = modifier,
        enableDismissFromStartToEnd = onRemove != null,
        enableDismissFromEndToStart = onRemove != null,
        backgroundContent = {
            // Behind the row as it slides: red, with a bin on the side it's uncovered from.
            val fromStart = dismiss.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            Box(
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        QueueRowContent(song, played, current, isPlaying, onClick, handle, Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow))
    }
}

@Composable
private fun QueueRowContent(
    song: Song,
    played: Boolean,
    current: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    handle: Modifier?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (current) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp)
            // Already played: faded, as in Auxio.
            .graphicsLayer { alpha = if (played) 0.5f else 1f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            CoverImage(song, blurred = false, modifier = Modifier.size(48.dp))
            // The song playing: bars over its cover (still while paused).
            if (current) {
                Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.45f)))
                PlayingBars(animating = isPlaying)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            song.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (handle != null) {
            Box(modifier = Modifier.size(48.dp).then(handle), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragHandle, contentDescription = "Drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Three bars rising and falling over the playing song's cover (Auxio's playing indicator). */
@Composable
private fun PlayingBars(animating: Boolean) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars")
    val phases = listOf(0, 180, 90).map { delayMs ->
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(450, delayMillis = delayMs),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "bar",
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(18.dp)) {
        phases.forEachIndexed { i, phase ->
            val h = if (animating) phase.value else listOf(0.5f, 0.8f, 0.6f)[i]
            Box(Modifier.width(4.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

