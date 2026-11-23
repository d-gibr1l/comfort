package com.comfort.app.ui.music

import com.comfort.app.ui.main.swipeRemoveAction
import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.PlaylistRemove
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.comfort.app.music.MusicPlayer
import com.comfort.app.music.Song

private val ROW_HEIGHT = 72.dp
private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The queue, from Now Playing (its queue button, or a swipe up), dark over the player's own
 * backdrop: "Up next" with how many songs are left, shuffle and repeat, then the song on as a lit
 * card, the songs already played (faded) above it and those to come below. A tap plays one, the handle drags one to a new place (with shuffle off: a shuffled
 * order is the player's own), a sideways swipe takes one out, and Clear leaves only the song on.
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
    // Held only from a drag's start until the player has the new order: [heldRows] (the dragged
    // order, then the dropped one) over [heldFrom] (the queue it was made from). Once the player's
    // queue is no longer that one, it's shown as is, in the same frame: switching over a frame
    // later (an effect) drew the old numbering for a frame — the "playing" highlight on the wrong
    // song (the flicker on a drop).
    var heldRows by remember { mutableStateOf<List<Pair<Int, com.comfort.app.music.Song>>?>(null) }
    var heldFrom by remember { mutableStateOf<List<Pair<Int, com.comfort.app.music.Song>>?>(null) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragStart by remember { mutableStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // A function, not a value: the drag handlers below are made once and call it for the rows now.
    fun shownRows() = heldRows?.takeIf { dragging != null || state.queue == heldFrom } ?: state.queue
    val rows = shownRows()
    LaunchedEffect(state.queue) { if (dragging == null && state.queue != heldFrom) { heldRows = null; heldFrom = null } }
    // Where the song on is, once (not per row).
    val currentPos = rows.indexOfFirst { it.first == state.index }
    val upcoming = if (currentPos >= 0) rows.size - currentPos - 1 else rows.size
    val dragTarget = if (dragging == null) -1 else
        (dragStart + kotlin.math.round(dragOffset / rowHeightPx).toInt()).coerceIn(0, rows.lastIndex)
    val reorderable = !state.shuffle
    // Opens on the song on (the played ones above it, a scroll up).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPos.coerceAtLeast(0))

    Column(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f)))
        }
        // "Up next" and how many, Clear; then shuffle and repeat.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.queue_up_next), style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.queue_songs_count, upcoming, upcoming),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            if (state.count > 1) {
                // A pill with an icon, as Now Playing's Queue one: plain text was easy to miss.
                androidx.compose.material3.FilledTonalButton(
                    onClick = MusicPlayer::clearQueue,
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.search_clear), fontFamily = com.comfort.app.theme.HeaderFontFamily, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(
                selected = state.shuffle,
                label = stringResource(R.string.shuffle),
                icon = Icons.Rounded.Shuffle,
                onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                    MusicPlayer.toggleShuffle()
                },
            )
            val repeatDescription = stringResource(
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.string.repeat_this_song
                    Player.REPEAT_MODE_ALL -> R.string.repeat_all
                    else -> R.string.repeat_off
                }
            )
            ModeChip(
                selected = state.repeatMode != Player.REPEAT_MODE_OFF,
                label = stringResource(R.string.queue_repeat),
                icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                description = repeatDescription,
                onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                    MusicPlayer.cycleRepeat()
                },
            )
        }

        LazyColumn(
            state = listState,
            // Clears the navigation bar at the end.
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
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
                QueueRow(
                    song = song,
                    // Unchanged by a drag (rows stay in their slots): fading them in and out
                    // as one started and ended flickered. But the dragged one is drawn as it'll
                    // be where it's held: a played song taken below the one on is unplayed there
                    // (it stayed faded under the finger, then popped bright on the drop).
                    played = if (isDragged && currentPos >= 0) {
                        val newCurrent = currentPos -
                            (if (dragStart < currentPos && dragTarget >= currentPos) 1 else 0) +
                            (if (dragStart > currentPos && dragTarget <= currentPos) 1 else 0)
                        dragTarget < newCurrent
                    } else currentPos >= 0 && pos < currentPos,
                    current = index == state.index,
                    dragged = isDragged,
                    isPlaying = state.isPlaying,
                    onClick = { MusicPlayer.playAt(index) },
                    // Swipe sideways to take a song out (not the one on).
                    onRemove = if (index == state.index || dragging != null) null else ({ MusicPlayer.removeAt(index) }),
                    handle = if (!reorderable) null else Modifier.pointerInput(index) {
                        detectDragGestures(
                            onDragStart = {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                heldFrom = state.queue
                                heldRows = state.queue
                                dragStart = state.queue.indexOfFirst { it.first == index }
                                dragOffset = 0f
                                dragging = index
                            },
                            onDragEnd = {
                                // Worked out here, from the drag's state now: this handler was made
                                // when the row was first drawn and doesn't see later recompositions.
                                val from = dragStart
                                val current = shownRows()
                                val to = (from + kotlin.math.round(dragOffset / rowHeightPx).toInt()).coerceIn(0, current.lastIndex)
                                // The rows in their new order and the drag ended in one go: the
                                // dropped row lands where it was drawn, the others where they slid.
                                // No move: back to the player's queue (it may have changed meanwhile).
                                heldRows = if (to != from && to >= 0) current.toMutableList().also { it.add(to, it.removeAt(from)) } else null
                                dragging = null
                                dragOffset = 0f
                                // Without shuffle the rows are the queue's own order.
                                if (to != from && to >= 0) MusicPlayer.move(from, to)
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
                            },
                            onDragCancel = { dragging = null; dragOffset = 0f; heldRows = null },
                        ) { change, amount ->
                            change.consume()
                            // Not past the list's ends.
                            val before = kotlin.math.round(dragOffset / rowHeightPx).toInt()
                            dragOffset = (dragOffset + amount.y).coerceIn(-dragStart * rowHeightPx, (shownRows().lastIndex - dragStart) * rowHeightPx)
                            // A tick each time the dragged song passes another.
                            if (kotlin.math.round(dragOffset / rowHeightPx).toInt() != before) {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            }
                        }
                    },
                    modifier = if (isDragged) {
                        Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                    } else {
                        // None once the drag's over, at once: the animated value only caught up a
                        // frame later, so on the drop the rows that had slid aside, already in their
                        // new places, were drawn a slot off for a frame (the flicker).
                        Modifier.graphicsLayer { translationY = if (dragging == null) 0f else shown }
                    },
                )
            }
        }
    }
}

/** Shuffle or repeat, as a chip under "Up next": white when on. */
@Composable
private fun ModeChip(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit, description: String? = null) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontFamily = com.comfort.app.theme.HeaderFontFamily) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = RoundedCornerShape(50),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.08f),
            labelColor = Color.White.copy(alpha = 0.85f),
            iconColor = Color.White.copy(alpha = 0.85f),
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
            selectedLeadingIconColor = Color.Black,
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color.White.copy(alpha = 0.25f),
            selectedBorderWidth = 0.dp,
        ),
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
    )
}

@Composable
private fun QueueRow(
    song: Song,
    played: Boolean,
    current: Boolean,
    dragged: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    handle: Modifier?,
    modifier: Modifier = Modifier,
    /** Swiped away sideways (null: can't be — the song on). */
    onRemove: (() -> Unit)? = null,
) {
    // Library's swipe-to-remove card (its haptics, the latest handler, never shown half-dismissed).
    // Always the card, only its swipe switched off where a song can't go: a row that changed
    // between the two (as a drag started and ended) was rebuilt, its cover reloading — a flicker.
    com.comfort.app.ui.main.SwipeToDeleteCard(
        onDelete = { onRemove?.invoke() },
        modifier = modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        shape = ROW_SHAPE,
        enabled = onRemove != null,
        // The rows are see-through (the player's backdrop under them).
        revealOnly = true,
    ) {
        QueueRowContent(song, played, current, dragged, isPlaying, onClick, handle)
    }
}

@Composable
private fun QueueRowContent(
    song: Song,
    played: Boolean,
    current: Boolean,
    dragged: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    handle: Modifier?,
) {
    val playedAlpha by androidx.compose.animation.core.animateFloatAsState(if (played) 0.45f else 1f, label = "queuePlayed")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT - 6.dp)
            .then(if (dragged) Modifier.shadow(12.dp, ROW_SHAPE) else Modifier)
            .clip(ROW_SHAPE)
            .background(
                when {
                    // Lifted: solid, over the rows it passes.
                    dragged -> Color(0xFF2E2E2E)
                    // The song on: a lit card.
                    current -> Color.White.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
            )
            .then(if (current) Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), ROW_SHAPE) else Modifier)
            .swipeRemoveAction()
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp)
            // Already played: faded (eased, as a dragged song crosses the one on).
            .graphicsLayer { alpha = playedAlpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            CoverImage(song, blurred = false, modifier = Modifier.size(50.dp))
            // The song playing: bars over its cover (still while paused).
            if (current) {
                Box(Modifier.size(50.dp).background(Color.Black.copy(alpha = 0.45f)))
                PlayingBars(animating = isPlaying)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            song.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (handle != null) {
            Box(modifier = Modifier.size(48.dp).then(handle), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragHandle, contentDescription = stringResource(R.string.drag_to_reorder), tint = Color.White.copy(alpha = 0.55f))
            }
        }
    }
}

/** Three bars rising and falling over the playing song's cover (Auxio's playing indicator). */
@Composable
private fun PlayingBars(animating: Boolean) {
    // Paused: still bars, and no animation running every frame.
    if (!animating) {
        Bars(listOf(0.5f, 0.8f, 0.6f))
        return
    }
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
    Bars(phases.map { it.value })
}

@Composable
private fun Bars(heights: List<Float>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(18.dp)) {
        heights.forEach { h ->
            Box(Modifier.width(4.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

