package com.comfort.app.ui.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.music.MusicPlayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

/** Height of [MiniPlayer] plus the gap under it — what pages add to their bottom clearance while it shows. */
val MINI_PLAYER_HEIGHT = 72.dp

/**
 * The song playing, in a bar above the navigation while there's a queue: its cover, title and
 * artist, play/pause, next, and an X that stops the music. Tapping it opens Now Playing. A thin
 * line along its bottom shows how far into the song it is.
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by MusicPlayer.state.collectAsState()
    val song = state.song ?: return
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isPlaying, song.id, state.seekId) {
        position = MusicPlayer.position()
        while (state.isPlaying) {
            delay(500)
            position = MusicPlayer.position()
        }
    }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = "Now playing: ${song.title}. Open player" }
            // Swipe up opens Now Playing, like a tap.
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -40.dp.toPx()) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                            onOpen()
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    total += amount
                }
            },
    ) {
        Column {
            // How far into the song, along the top edge (Auxio's mini player).
            LinearProgressIndicator(
                progress = { if (state.duration > 0) (position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Row(
                modifier = Modifier.weight(1f).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cover and title: swipe them sideways to change song.
                val skip = rememberSwipeSkipState()
                SongTransition(modifier = Modifier.weight(1f).swipeToSkip(skip), skip = skip) { shown ->
                    if (shown != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) {
                            CoverImage(shown, blurred = false, modifier = Modifier.size(48.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(shown.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            shown.subtitle?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (state.count > 1) {
                    IconButton(onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.previous() }) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                    }
                }
                IconButton(onClick = MusicPlayer::togglePlay) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                if (state.count > 1) {
                    IconButton(onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.next() }) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                    }
                }
                IconButton(onClick = MusicPlayer::stop) {
                    Icon(Icons.Rounded.Close, contentDescription = "Stop music")
                }
            }
        }
    }
}

/** Plays a list of songs (the Library's Play all / Shuffle) and opens Now Playing; set in MainScreen. */
val LocalPlaySongs = androidx.compose.runtime.staticCompositionLocalOf<(List<com.comfort.app.data.DownloadEntity>, Boolean) -> Unit> { { _, _ -> } }
