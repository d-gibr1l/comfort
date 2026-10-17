package com.comfort.app.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.comfort.app.music.MusicPlayer
import com.comfort.app.music.Song
import kotlinx.coroutines.delay
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The music player's full screen: the song's cover blurred behind, the cover, title and
 * artist · album, a seek bar, and shuffle / previous / play-pause / next / repeat. Opened from the
 * mini player, by tapping a song, or from the music notification; collapsed by the arrow or Back
 * (the music goes on — [MusicPlayer] plays in the background).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val state by MusicPlayer.state.collectAsState()
    val song = state.song
    BackHandler(onBack = onClose)
    // Declared after the player's own, so it runs first while the queue is open.
    LightSystemBars()

    // The song's lyrics (from its own tags), and whether they're shown in place of the cover.
    val context = androidx.compose.ui.platform.LocalContext.current
    var lyrics by remember { mutableStateOf<com.comfort.app.music.Lyrics?>(null) }
    LaunchedEffect(song?.uri) {
        lyrics = null
        lyrics = song?.let { com.comfort.app.music.Lyrics.load(context, it.uri) }
    }
    var showLyrics by remember { mutableStateOf(false) }
    val lyricsShown = showLyrics && lyrics != null

    var position by remember { mutableLongStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    // Synced lyrics move on line by line (the full view, or the two lines under the cover), so
    // the position is read more often for them.
    val followLyrics = lyrics?.synced == true
    LaunchedEffect(state.isPlaying, song?.id, followLyrics, state.seekId) {
        position = MusicPlayer.position()
        while (state.isPlaying) {
            delay(if (followLyrics) 100 else 250)
            position = MusicPlayer.position()
        }
    }
    // The cover eases back a little while paused, as in most music players.
    val coverScale by animateFloatAsState(if (state.isPlaying) 1f else 0.9f, label = "coverScale")

    // Swipe down: the screen follows and, far enough, closes to the mini player. Swipe up: the queue.
    var showQueue by remember { mutableStateOf(false) }
    val dragY = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val closeAfter = with(density) { 120.dp.toPx() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val queueAfter = with(density) { 60.dp.toPx() }

    // A swipe on the cover changes song with its own slide (see SwipeSkipState).
    val coverSkip = rememberSwipeSkipState()
    // Back: the queue first, then the player.
    BackHandler(enabled = showQueue) { showQueue = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = if (showQueue) 0f else dragY.value.coerceAtLeast(0f) }
            .background(Color.Black)
            // Takes every touch: nothing under this screen reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val y = dragY.value
                            if (showQueue) {
                                // As far as closing the player: a pull past the list's top that
                                // the list passes up shouldn't close it by accident.
                                if (y > closeAfter) {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                                    showQueue = false
                                }
                                dragY.snapTo(0f)
                                return@launch
                            }
                            if (y > closeAfter || y < -queueAfter) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                            if (y > closeAfter) {
                                // On down from where the finger left it, then closed: snapping back
                                // up first and sliding out from the top flickered.
                                dragY.animateTo(size.height.toFloat(), androidx.compose.animation.core.tween(180))
                                onClose()
                                return@launch
                            }
                            if (y < -queueAfter) showQueue = true
                            dragY.animateTo(0f, androidx.compose.animation.core.spring(stiffness = 600f))
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { dragY.snapTo(dragY.value + amount) }
                }
            },
    ) {
        CoverImage(song, blurred = true, modifier = Modifier.fillMaxSize().blur(60.dp).scale(1.2f))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.6f to Color.Black.copy(alpha = 0.6f),
                    1f to Color.Black.copy(alpha = 0.85f),
                )
            )
        )

        // Auxio's queue: the player morphs into a bar at the top (its cover and title carried
        // across), and the queue comes up under it. The same in reverse to close it.
        androidx.compose.animation.SharedTransitionLayout {
            val shared = this
            androidx.compose.animation.AnimatedContent(
                targetState = showQueue,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
                },
                label = "queueMorph",
            ) { queue ->
                val anim = this
                with(shared) {
                    if (!queue) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Auxio's top bar: close, "Now playing" over where in the queue, and the queue.
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Now playing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                if (state.count > 1) "Song ${state.index + 1} of ${state.count}" else "Your Library",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        if (lyrics != null) {
                            IconButton(
                                onClick = { showLyrics = !showLyrics },
                                modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (lyricsShown) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                ),
                            ) {
                                Icon(
                                    Icons.Outlined.Lyrics,
                                    contentDescription = androidx.compose.ui.res.stringResource(
                                        if (lyricsShown) com.comfort.app.R.string.lyrics_hide else com.comfort.app.R.string.lyrics_show,
                                    ),
                                    tint = Color.White,
                                )
                            }
                        }
                        IconButton(onClick = { showQueue = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Queue", tint = Color.White)
                        }
                    }

                    // The cover, as big as fits (a tablet or landscape gets a square, not a stretch);
                    // swipe it sideways to change song.
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).swipeToSkip(coverSkip),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = lyricsShown,
                            transitionSpec = {
                                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250, delayMillis = 50)) +
                                    androidx.compose.animation.scaleIn(initialScale = 0.94f, animationSpec = androidx.compose.animation.core.tween(250, delayMillis = 50))) togetherWith
                                    (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) +
                                        androidx.compose.animation.scaleOut(targetScale = 0.94f, animationSpec = androidx.compose.animation.core.tween(150)))
                            },
                            contentAlignment = Alignment.Center,
                            label = "coverOrLyrics",
                        ) { showingLyrics ->
                        val shownLyrics = lyrics
                        if (showingLyrics && shownLyrics != null) {
                            LyricsView(
                                lyrics = shownLyrics,
                                positionMs = position,
                                onSeek = { target ->
                                    MusicPlayer.seekTo(target)
                                    position = target
                                },
                                modifier = Modifier.widthIn(max = 560.dp).fillMaxSize(),
                            )
                        } else Box(
                            modifier = Modifier
                                .widthIn(max = 520.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .sharedElement(rememberSharedContentState("cover"), anim)
                                .scale(coverScale)
                                .shadow(24.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            SongTransition(modifier = Modifier.fillMaxSize(), skip = coverSkip) { shown ->
                                CoverImage(shown, blurred = false, modifier = Modifier.fillMaxSize())
                            }
                        }
                        }
                    }

                    Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                        // Spotify's lyric line under the cover, as two (the line and the next);
                        // a tap opens the full lyrics. Not while those are up.
                        val previewLyrics = lyrics
                        androidx.compose.animation.AnimatedVisibility(
                            visible = previewLyrics?.synced == true && !lyricsShown,
                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                        ) {
                            if (previewLyrics != null) {
                                LyricsPreview(
                                    lyrics = previewLyrics,
                                    positionMs = position,
                                    onClick = { showLyrics = true },
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                )
                            }
                        }
                        // Title, artist and album, left-aligned (Auxio's), with the song's menu.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongTransition(modifier = Modifier.weight(1f).sharedBounds(rememberSharedContentState("title"), anim)) { shown ->
                                Column {
                                    Text(
                                        shown?.title ?: "",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        modifier = Modifier.fillMaxWidth().basicMarquee(),
                                    )
                                    shown?.artist?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    shown?.album?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            SongMenu(song)
                        }

                        Spacer(Modifier.height(16.dp))
                        WavySeekBar(
                            position = dragPosition?.let { (it * state.duration).toLong() } ?: position,
                            duration = state.duration,
                            playing = state.isPlaying,
                            onDrag = { dragPosition = it },
                            onDragEnd = {
                                dragPosition?.let { fraction ->
                                    val target = (fraction * state.duration).toLong()
                                    MusicPlayer.seekTo(target)
                                    position = target
                                }
                                dragPosition = null
                            },
                        )

                        Spacer(Modifier.height(16.dp))
                        PlaybackButtons(state)
                        Spacer(Modifier.height(12.dp))
                    }

                    // Auxio's "Queue" handle along the bottom (a tap, or a swipe up anywhere, opens it).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showQueue = true }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.5f)))
                        Spacer(Modifier.height(6.dp))
                        Text("Queue", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                    } else {
                        QueueLayout(
                            state = state,
                            position = position,
                            onCloseQueue = { showQueue = false },
                            coverModifier = Modifier.sharedElement(rememberSharedContentState("cover"), anim),
                            titleModifier = Modifier.sharedBounds(rememberSharedContentState("title"), anim),
                            panelModifier = Modifier.animateEnterExit(
                                enter = androidx.compose.animation.slideInVertically { it / 2 },
                                exit = androidx.compose.animation.slideOutVertically { it / 2 },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** A song's cover for Coil (its Library thumbnail shown at once), or a music note without one. */
@Composable
internal fun CoverImage(song: Song?, blurred: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(song?.id) { mutableStateOf(false) }
    val request = remember(song?.id) {
        coil.request.ImageRequest.Builder(context).data(song?.cover).placeholderMemoryCacheKey(song?.placeholderKey).build()
    }
    if (song?.cover != null && !failed) {
        AsyncImage(
            model = request,
            contentDescription = if (blurred) null else song.title,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = modifier,
        )
    } else if (!blurred) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(96.dp))
        }
    }
}

/** Light status and navigation bar icons while this dark screen is up, the theme's after. */
@Composable
private fun LightSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val wasLightStatus = controller?.isAppearanceLightStatusBars
        val wasLightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            wasLightStatus?.let { controller.isAppearanceLightStatusBars = it }
            wasLightNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** The seek bar as Auxio's: a wave while it plays (flat when paused), with the times under it. A
 * transparent slider on top takes the drags. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WavySeekBar(position: Long, duration: Long, playing: Boolean, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    val total = duration.coerceAtLeast(1L)
    val fraction = (position.toFloat() / total).coerceIn(0f, 1f)
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, label = "waveAmplitude")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        androidx.compose.material3.LinearWavyProgressIndicator(
            progress = { fraction },
            amplitude = { amplitude },
            // Calmer than the default (a wavelength a second): longer waves, drifting slowly.
            wavelength = 40.dp,
            waveSpeed = 10.dp,
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth().height(14.dp),
        )
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
            thumb = { SliderDefaults.Thumb(interactionSource = thumbInteraction, colors = colors, thumbSize = androidx.compose.ui.unit.DpSize(7.dp, 40.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(position), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
    }
}

/** Auxio's row of controls: repeat and shuffle as outlined toggles at the ends, tonal previous and
 * next, and a wide pill play/pause in the middle. A pressed button grows a little. */
@Composable
private fun PlaybackButtons(state: com.comfort.app.music.MusicState) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleButton(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            description = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat this song"
                Player.REPEAT_MODE_ALL -> "Repeat all"
                else -> "Repeat off"
            },
            checked = state.repeatMode != Player.REPEAT_MODE_OFF,
            onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
                MusicPlayer.cycleRepeat()
            },
            modifier = Modifier.weight(1f),
        )
        ControlButton(Icons.Rounded.SkipPrevious, "Previous", { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.previous() }, Color.White.copy(alpha = 0.16f), Color.White, Modifier.weight(1.2f))
        ControlButton(
            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (state.isPlaying) "Pause" else "Play",
            MusicPlayer::togglePlay,
            Color.White,
            Color.Black,
            Modifier.weight(2f),
            iconSize = 36.dp,
        )
        ControlButton(Icons.Rounded.SkipNext, "Next", { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.next() }, Color.White.copy(alpha = 0.16f), Color.White, Modifier.weight(1.2f))
        ToggleButton(
            icon = Icons.Rounded.Shuffle,
            description = if (state.shuffle) "Shuffle on" else "Shuffle off",
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
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(36.dp),
        color = if (checked) Color.White.copy(alpha = 0.9f) else Color.Transparent,
        contentColor = if (checked) Color.Black else Color.White,
        border = if (checked) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
        modifier = modifier.height(56.dp).semantics {
            contentDescription = description
            stateDescription = if (checked) "On" else "Off"
        },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
    }
}

/** The song's ⋯ menu: share the file. */
@Composable
private fun SongMenu(song: Song?) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = Color.White)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                enabled = song != null && song.uri != android.net.Uri.EMPTY,
                onClick = {
                    open = false
                    val s = song ?: return@DropdownMenuItem
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("audio/*")
                        .putExtra(android.content.Intent.EXTRA_STREAM, s.uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, s.title)) }
                },
            )
        }
    }
}

/** The queue state of Now Playing: the song playing as a bar at the top (cover, title and artist,
 * play/pause, next, its progress along the bottom) — a tap or a swipe down on it closes the queue
 * — and the queue under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueLayout(
    state: com.comfort.app.music.MusicState,
    position: Long,
    onCloseQueue: () -> Unit,
    coverModifier: Modifier,
    titleModifier: Modifier,
    panelModifier: Modifier,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCloseQueue)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(coverModifier.size(52.dp).clip(RoundedCornerShape(10.dp))) {
                CoverImage(state.song, blurred = false, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f).then(titleModifier)) {
                Text(state.song?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.song?.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            FilledIconButton(
                onClick = MusicPlayer::togglePlay,
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.size(width = 48.dp, height = 44.dp),
            ) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.isPlaying) "Pause" else "Play")
            }
            Spacer(Modifier.size(6.dp))
            androidx.compose.material3.OutlinedIconButton(
                onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.next() },
                enabled = state.hasNext,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Color.White),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
            }
        }
        androidx.compose.material3.LinearProgressIndicator(
            progress = { if (state.duration > 0) (position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp),
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = panelModifier.fillMaxSize(),
        ) {
            QueuePanel()
        }
    }
}
