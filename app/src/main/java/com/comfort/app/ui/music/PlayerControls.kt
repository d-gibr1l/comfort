package com.comfort.app.ui.music

// The music player's controls: the wavy seek bar and the playback buttons.

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.comfort.app.music.MusicPlayer
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal fun WavySeekBar(position: Long, duration: Long, playing: Boolean, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    val total = duration.coerceAtLeast(1L)
    val fraction = (position.toFloat() / total).coerceIn(0f, 1f)
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, label = "waveAmplitude")
    com.comfort.app.ui.MediaLtr {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        // The wave drifts while it shows (playing, or flattening out after a pause) and stands still
        // once flat, so a paused player draws nothing. The indicator takes its speed once, as it's
        // first drawn (opened paused, the wave never drifted, and showed only as the position
        // ticked over), so it's built again each time that changes.
        val moving = playing || amplitude > 0.01f
        androidx.compose.runtime.key(moving) {
        androidx.compose.material3.LinearWavyProgressIndicator(
            progress = { fraction },
            amplitude = { amplitude },
            // Calmer than the default (a wavelength a second): longer waves, drifting slowly.
            wavelength = 40.dp,
            waveSpeed = if (moving) 10.dp else 0.dp,
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            modifier = Modifier
                .fillMaxWidth()
                // The Slider's own track: inset half the handle's width at each end, so the wave
                // ends where the handle is (it ran on a little past it).
                .padding(horizontal = SEEK_THUMB_WIDTH / 2)
                .height(14.dp)
                // A gap either side of the handle: the wave stopped at it and the line beyond went
                // on from under it, seen through the handle as a "+". Cleared, not painted over —
                // the backdrop behind is the blurred cover.
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val gap = SEEK_THUMB_WIDTH.toPx() / 2 + 4.dp.toPx()
                    val x = fraction * size.width
                    drawRect(
                        Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(x - gap, 0f),
                        size = androidx.compose.ui.geometry.Size(gap * 2, size.height),
                        blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
                    )
                },
        )
        }
        val thumbInteraction = remember { MutableInteractionSource() }
        val colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
        )
        Slider(
            value = fraction,
            onValueChange = onDrag,
            onValueChangeFinished = onDragEnd,
            colors = colors,
            interactionSource = thumbInteraction,
            // A thicker handle than the default 4 dp.
            thumb = { SliderDefaults.Thumb(interactionSource = thumbInteraction, colors = colors, thumbSize = androidx.compose.ui.unit.DpSize(SEEK_THUMB_WIDTH, 40.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(position), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
    }
    }
}

/** Auxio's row of controls: repeat and shuffle as outlined toggles at the ends, tonal previous and
 * next, and a wide pill play/pause in the middle. A pressed button grows a little. */
@Composable
internal fun PlaybackButtons(state: com.comfort.app.music.MusicState) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    com.comfort.app.ui.MediaLtr {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleButton(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            description = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> stringResource(R.string.repeat_this_song)
                Player.REPEAT_MODE_ALL -> stringResource(R.string.repeat_all)
                else -> stringResource(R.string.repeat_off)
            },
            checked = state.repeatMode != Player.REPEAT_MODE_OFF,
            onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
                MusicPlayer.cycleRepeat()
            },
            modifier = Modifier.weight(1f),
        )
        ControlButton(Icons.Rounded.SkipPrevious, stringResource(R.string.widget_music_previous), { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.previous() }, Color.White.copy(alpha = 0.16f), Color.White, Modifier.weight(1.2f))
        ControlButton(
            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (state.isPlaying) stringResource(R.string.widget_music_pause) else stringResource(R.string.widget_music_play),
            MusicPlayer::togglePlay,
            Color.White,
            Color.Black,
            Modifier.weight(2f),
            iconSize = 36.dp,
        )
        ControlButton(Icons.Rounded.SkipNext, stringResource(R.string.widget_music_next), { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.next() }, Color.White.copy(alpha = 0.16f), Color.White, Modifier.weight(1.2f))
        ToggleButton(
            icon = Icons.Rounded.Shuffle,
            description = if (state.shuffle) stringResource(R.string.shuffle_on) else stringResource(R.string.shuffle_off),
            checked = state.shuffle,
            onClick = {
                haptics.performHapticFeedback(
                    if (state.shuffle) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                    else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                )
                MusicPlayer.toggleShuffle()
            },
            modifier = Modifier.weight(1f),
        )
    }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.08f else 1f, label = "pressScale")
    val corner by animateFloatAsState(if (pressed) 16f else 36f, label = "pressCorner")
    androidx.compose.material3.Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(corner.dp),
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxHeight().scale(scale).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize)) }
    }
}

@Composable
private fun ToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val toggleState = stringResource(if (checked) R.string.on else R.string.sponsorblock_off)
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(36.dp),
        color = if (checked) Color.White.copy(alpha = 0.9f) else Color.Transparent,
        contentColor = if (checked) Color.Black else Color.White,
        border = if (checked) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
        modifier = modifier.height(56.dp).semantics {
            contentDescription = description
            stateDescription = toggleState
        },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
    }
}
