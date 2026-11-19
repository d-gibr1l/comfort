package com.comfort.app.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The player's sheets (its ⋮ menu, Music settings, Sleep timer, Speed and pitch, Song properties),
 * drawn inside the player's own screen. Material's ModalBottomSheet opens a window of its own for
 * each, and on a low-end phone (a Galaxy A22) building that window took ~170-200 ms on the first
 * frame, then 65-80 ms frames while it slid up.
 *
 * A sheet is put up by composing [InWindowSheet] where it's wanted; the [PlayerSheetHost] at the
 * top of the player's screen draws the one put up last, over a dimmed backdrop. A tap outside,
 * Back, or a drag down on it calls its onDismiss.
 */
@Stable
internal class PlayerSheetHost {
    internal class Entry {
        var onDismiss: () -> Unit by mutableStateOf({})
        var content: @Composable ColumnScope.() -> Unit by mutableStateOf({})
    }

    internal val entries = mutableStateListOf<Entry>()
    val top: Entry? get() = entries.lastOrNull()
}

internal val LocalPlayerSheets = staticCompositionLocalOf<PlayerSheetHost?> { null }

/** Provides a [PlayerSheetHost] to [content] and draws its sheets over it. */
@Composable
internal fun PlayerSheetHost(content: @Composable () -> Unit) {
    val host = remember { PlayerSheetHost() }
    CompositionLocalProvider(LocalPlayerSheets provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            SheetLayer(host)
        }
    }
}

/** Puts up a sheet with [content] while this is composed; [onDismiss] is asked to take it down. */
@Composable
internal fun InWindowSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalPlayerSheets.current ?: return
    val entry = remember { PlayerSheetHost.Entry() }
    SideEffect {
        entry.onDismiss = onDismiss
        entry.content = content
    }
    DisposableEffect(entry) {
        entry.onDismiss = onDismiss
        entry.content = content
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SheetLayer(host: PlayerSheetHost) {
    val top = host.top
    // The sheet going away is still drawn while it slides out.
    var shown by remember { mutableStateOf<PlayerSheetHost.Entry?>(null) }
    if (top != null && shown !== top) shown = top
    val visible = top != null
    BackHandler(enabled = visible) { top?.onDismiss?.invoke() }
    val scrim by animateFloatAsState(if (visible) 0.5f else 0f, tween(220), label = "sheetScrim")
    if (scrim > 0f) {
        // The dim over the player, drawn (no layer); it takes every touch, a tap closing the sheet.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = scrim)) }
                .pointerInput(top) { detectTapGestures { top?.onDismiss?.invoke() } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume() } },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val maxSheet = maxHeight * 0.9f
        AnimatedVisibility(
            visible = visible,
            // Held off a moment: the first frame builds the sheet (up to ~150 ms on a Galaxy A22), and
            // a slide already running jumped ahead by that much. Started after it, it runs smooth.
            enter = slideInVertically(tween(250, delayMillis = 48)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            val entry = shown ?: return@AnimatedVisibility
            val scope = rememberCoroutineScope()
            val drag = remember(entry) { Animatable(0f) }
            // As the download preview sheet: closed only once pulled this far down, however fast; a
            // flick (or a scroll that ran into it) springs back.
            val dismissAfter = with(LocalDensity.current) { 160.dp.toPx() }
            LaunchedEffect(entry) { drag.snapTo(0f) }
            // A sheet whose content scrolls (Music settings): a pull down once it's at its top moves
            // the sheet, a push back up moves it back first, and letting go far enough closes it
            // (the list took every drag before, so it couldn't be pulled down).
            val pullDown = remember(entry) {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                        if (available.y >= 0f || drag.value <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                        val used = maxOf(available.y, -drag.value)
                        scope.launch { drag.snapTo(drag.value + used) }
                        return androidx.compose.ui.geometry.Offset(0f, used)
                    }

                    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                        if (available.y <= 0f || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                        scope.launch { drag.snapTo(drag.value + available.y) }
                        return androidx.compose.ui.geometry.Offset(0f, available.y)
                    }

                    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                        if (drag.value <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                        if (drag.value >= dismissAfter) entry.onDismiss() else drag.animateTo(0f)
                        return available
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheet)
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    // Its own taps stay on it (not the dim behind).
                    .pointerInput(Unit) { detectTapGestures { } }
                    // Dragged down by any part that doesn't scroll: far enough, it closes.
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        onDragStopped = { velocity ->
                            if (drag.value >= dismissAfter) entry.onDismiss() else drag.animateTo(0f)
                        },
                    )
                    .nestedScroll(pullDown)
                    .animateContentSize()
                    .navigationBarsPadding(),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                entry.content(this)
            }
        }
    }
}
