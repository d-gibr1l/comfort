package com.comfort.app.ui.music

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
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
fun NowPlayingScreen(
    onClose: () -> Unit,
    // Whether it's (being) shown: false while it slides out after closing.
    showing: Boolean = true,
    // Whether it's being dragged away from its place (what's under it then shows).
    onDragged: (Boolean) -> Unit = {},
) {
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
    // The two lyric lines under the cover, on or off (the top bar's lyrics button), kept.
    val playerPrefs = androidx.compose.ui.platform.LocalContext.current.let { remember { it.getSharedPreferences("music_player", android.content.Context.MODE_PRIVATE) } }
    var lyricLine by remember { mutableStateOf(playerPrefs.getBoolean(KEY_LYRIC_LINE, true)) }
    // The lines hidden from the top bar's lyrics button, with Lyric lines on in Music settings
    // (that setting is whether the button works the lines at all); kept for next time.
    var linesHidden by remember { mutableStateOf(playerPrefs.getBoolean(KEY_LYRIC_LINE_HIDDEN, false)) }
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
    // Shown again before its slide out had finished (a song tapped right after a swipe down): the
    // same screen comes back, still pushed down off the bottom by that swipe — open, but out of
    // sight, the mini player hidden under it. Back where it belongs as it reopens.
    LaunchedEffect(showing) { if (showing && dragY.value != 0f) dragY.snapTo(0f) }
    val latestOnDragged by androidx.compose.runtime.rememberUpdatedState(onDragged)
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { dragY.value != 0f }.collect { latestOnDragged(it) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val closeAfter = with(density) { 120.dp.toPx() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val queueAfter = with(density) { 60.dp.toPx() }

    // A swipe on the cover changes song with its own slide (see SwipeSkipState).
    val coverSkip = rememberSwipeSkipState()
    // Back: the queue first, then the full lyrics, then the player (the last one declared runs first).
    BackHandler(enabled = lyricsShown) { showLyrics = false }
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
    ) { PlayerSheetHost {
        // Blurred once, small (SmallBlur), not by a blur on the layer: that ran again on every frame
        // drawn, the whole screen each time (~20 ms a frame on a Galaxy A22).
        // Cut to the screen: scaled up, it showed past its top while the player was dragged down
        // (the blur on the layer cut it before).
        // Music settings › Player background: off, a plain dark backdrop instead of the cover's.
        val coverBackdrop by com.comfort.app.music.MusicSettings.coverBackground.value.collectAsState()
        if (coverBackdrop) CoverImage(song, blurred = true, modifier = Modifier.fillMaxSize().clipToBounds().scale(1.2f))
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
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.close_player), tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.now_playing), style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                if (state.count > 1) stringResource(R.string.song_x_of_x, state.index + 1, state.count) else stringResource(R.string.your_library),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        // Synced lyrics: turns the lyric lines under the cover on and off (a tap on
                        // them opens the full lyrics). Plain ones have no lines there: it opens them.
                        // The queue's button that sat beside it went: the Queue pill at the bottom
                        // (or a swipe up) opens it.
                        val shownLyrics = lyrics
                        if (shownLyrics != null) {
                            // With Lyric lines on in Music settings (and synced lyrics), it shows and hides
                            // the lines under the cover; otherwise it opens and closes the full lyrics.
                            // A tap on the lines opens the full lyrics either way.
                            val worksLines = shownLyrics.synced && lyricLine && !lyricsShown
                            val lit = if (worksLines) !linesHidden else lyricsShown
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                                    if (worksLines) {
                                        linesHidden = !linesHidden
                                        playerPrefs.edit().putBoolean(KEY_LYRIC_LINE_HIDDEN, linesHidden).apply()
                                    } else {
                                        showLyrics = !lyricsShown
                                    }
                                },
                                modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (lit) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                ),
                            ) {
                                Icon(
                                    Icons.Outlined.Lyrics,
                                    contentDescription = androidx.compose.ui.res.stringResource(
                                        when {
                                            worksLines && linesHidden -> com.comfort.app.R.string.lyrics_line_show
                                            worksLines -> com.comfort.app.R.string.lyrics_line_hide
                                            lyricsShown -> com.comfort.app.R.string.lyrics_hide
                                            else -> com.comfort.app.R.string.lyrics_show
                                        },
                                    ),
                                    tint = Color.White,
                                )
                            }
                        } else {
                            // Keeps "Now playing" centred, opposite the close button.
                            Spacer(Modifier.size(48.dp))
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
                            visible = previewLyrics?.synced == true && !lyricsShown && lyricLine && !linesHidden,
                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                        ) {
                            if (previewLyrics != null) {
                                LyricsPreview(
                                    lyrics = previewLyrics,
                                    positionMs = position,
                                    onClick = { showLyrics = true },
                                    // Clear of the cover above and the title below.
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
                                )
                            }
                        }
                        // Title and artist, left-aligned (Auxio's), with the song's menu.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongTransition(modifier = Modifier.weight(1f).sharedBounds(rememberSharedContentState("title"), anim)) { shown ->
                                Column {
                                    // Google Sans, as the lyric lines above: the system font's bold
                                    // looked light beside them.
                                    Text(
                                        shown?.title ?: "",
                                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        modifier = Modifier.fillMaxWidth().basicMarquee(),
                                    )
                                    shown?.artist?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            SongMenu(
                                song = song,
                                lyricLine = lyricLine,
                                onLyricLineChange = {
                                    lyricLine = it
                                    playerPrefs.edit().putBoolean(KEY_LYRIC_LINE, it).apply()
                                },
                            )
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
                    // A pill with the queue's icon, plainer to see than a caption under a handle.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showQueue = true }
                            .padding(top = 6.dp, bottom = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.6f)))
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable { showQueue = true }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.queue),
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                                color = Color.White,
                            )
                        }
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
    } }
}

/** A song's cover for Coil (its Library thumbnail shown at once), or a music note without one. */
@Composable
internal fun CoverImage(song: Song?, blurred: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(song?.id) { mutableStateOf(false) }
    val request = remember(song?.id, blurred) {
        coil.request.ImageRequest.Builder(context).data(song?.cover)
            .apply { if (blurred) transformations(SmallBlur) else placeholderMemoryCacheKey(song?.placeholderKey) }
            .build()
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
private val SEEK_THUMB_WIDTH = 7.dp

@Composable
private fun WavySeekBar(position: Long, duration: Long, playing: Boolean, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
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
private fun PlaybackButtons(state: com.comfort.app.music.MusicState) {
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

/** The song's ⋯ menu, as Auxio's: a sheet from the bottom with the song at its top (cover, "Song",
 * title, artist and album), then the player's settings, song properties and share. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SongMenu(song: Song?, lyricLine: Boolean, onLyricLineChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var propertiesOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    val hasFile = song != null && song.uri != android.net.Uri.EMPTY
    // Over the artwork's dark backdrop: a light fill while the menu is open, the dots stay white.
    IconButton(onClick = { open = true }, colors = com.comfort.app.ui.main.menuButtonColors(open, Color.White.copy(alpha = 0.2f))) {
        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options), tint = Color.White)
    }
    if (open && song != null) {
        // An option takes this sheet's place with its own (or does its thing).
        fun pick(action: () -> Unit) { open = false; action() }
        InWindowSheet(onDismiss = { open = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) {
                        CoverImage(song, blurred = false, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.song),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        song.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                SongMenuOption(Icons.Outlined.Settings, stringResource(R.string.nav_settings)) { pick { settingsOpen = true } }
                SongMenuOption(Icons.Outlined.Bedtime, stringResource(R.string.sleep_timer), value = sleepTimerLabel()) { pick { sleepOpen = true } }
                val speed by com.comfort.app.music.MusicSettings.speed.value.collectAsState()
                val pitch by com.comfort.app.music.MusicSettings.pitch.value.collectAsState()
                SongMenuOption(
                    Icons.Outlined.Speed,
                    stringResource(R.string.speed_and_pitch),
                    value = if (speed == 1f && pitch == 1f) null else "${formatFactor(speed)} · ${formatFactor(pitch)}",
                ) { pick { speedOpen = true } }
                SongMenuOption(Icons.Outlined.Info, stringResource(R.string.song_properties), enabled = hasFile) { pick { propertiesOpen = true } }
                SongMenuOption(Icons.Rounded.Share, stringResource(R.string.share), enabled = hasFile) {
                    pick {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("audio/*")
                            .putExtra(android.content.Intent.EXTRA_STREAM, song.uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, song.title)) }
                    }
                }
            }
        }
    }
    if (settingsOpen) MusicSettingsSheet(lyricLine, onLyricLineChange, onDismiss = { settingsOpen = false })
    if (sleepOpen) SleepTimerSheet(onDismiss = { sleepOpen = false })
    if (speedOpen) SpeedPitchSheet(onDismiss = { speedOpen = false })
    if (propertiesOpen && song != null) SongPropertiesSheet(song, onDismiss = { propertiesOpen = false })
}

/** One of the song menu's options: an icon and its name, the whole row a tap target. */
@Composable
private fun SongMenuOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, value: String? = null, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 1.25 as "1.25×". */
private fun formatFactor(f: Float): String = "%.2f×".format(java.util.Locale.ROOT, f).replace(".00×", "×").replace("0×", "×")

/** What the sleep timer is set to, for the menu: the time left, "End of song", or null when off. */
@Composable
private fun sleepTimerLabel(): String? {
    val at by com.comfort.app.music.MusicSettings.sleepAt.collectAsState()
    val endOfSong by com.comfort.app.music.MusicSettings.sleepAtEndOfSong.collectAsState()
    if (endOfSong) return stringResource(R.string.sleep_end_of_song)
    val until = at ?: return null
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(until) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1000)
        }
    }
    val left = ((until - now) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(left / 60, left % 60)
}

/** A sheet of the player's, titled, its content scrolling when it's taller than the screen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    InWindowSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** The sleep timer: off, a number of minutes, or the end of the song on. */
@Composable
private fun SleepTimerSheet(onDismiss: () -> Unit) {
    val at by com.comfort.app.music.MusicSettings.sleepAt.collectAsState()
    val endOfSong by com.comfort.app.music.MusicSettings.sleepAtEndOfSong.collectAsState()
    // Which choice set the timer that's running (the minutes it was set for), for its tick.
    var chosen by remember { mutableStateOf<Int?>(null) }
    PlayerSheet(stringResource(R.string.sleep_timer), onDismiss) {
        sleepTimerLabel()?.let {
            Text(stringResource(R.string.sleep_stops_in, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
        }
        @Composable
        fun choice(label: String, selected: Boolean, onPick: () -> Unit) = Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onPick(); onDismiss() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily))
        }
        choice(stringResource(R.string.subtitles_off_short), at == null && !endOfSong) { com.comfort.app.music.MusicSettings.setSleepTimer(null); chosen = null }
        for (min in listOf(15, 30, 45, 60, 90)) {
            choice(stringResource(R.string.sleep_x_min, min), at != null && chosen == min) { com.comfort.app.music.MusicSettings.setSleepTimer(min); chosen = min }
        }
        choice(stringResource(R.string.sleep_end_of_song), endOfSong) { com.comfort.app.music.MusicSettings.setSleepAtEndOfSong(); chosen = null }
        // Any length, 5 minutes to 3 hours: a slider, then Start.
        var custom by remember { mutableStateOf(false) }
        var minutes by remember { mutableStateOf(20) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { custom = !custom }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(selected = custom || (at != null && chosen != null && chosen !in listOf(15, 30, 45, 60, 90)), onClick = null)
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.sleep_custom), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), modifier = Modifier.weight(1f))
            if (custom) Text(formatMinutes(minutes), style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.animation.AnimatedVisibility(visible = custom) {
            Column {
                androidx.compose.material3.Slider(
                    value = minutes.toFloat(),
                    onValueChange = {
                        val v = (Math.round(it / 5f) * 5).coerceIn(5, 180)
                        if (v != minutes) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            minutes = v
                        }
                    },
                    valueRange = 5f..180f,
                )
                androidx.compose.material3.FilledTonalButton(
                    onClick = { com.comfort.app.music.MusicSettings.setSleepTimer(minutes); chosen = minutes; onDismiss() },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.sleep_start), fontFamily = com.comfort.app.theme.HeaderFontFamily) }
            }
        }
    }
}

/** 95 as "1 h 35 min", 40 as "40 min". */
@Composable
private fun formatMinutes(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.sleep_x_min, minutes)
    else if (minutes % 60 == 0) stringResource(R.string.sleep_x_h, minutes / 60)
    else stringResource(R.string.sleep_x_h_y_min, minutes / 60, minutes % 60)

/** Playback speed and pitch, each from half to double, and back to normal. */
@Composable
private fun SpeedPitchSheet(onDismiss: () -> Unit) {
    val speed by com.comfort.app.music.MusicSettings.speed.value.collectAsState()
    val pitch by com.comfort.app.music.MusicSettings.pitch.value.collectAsState()
    PlayerSheet(stringResource(R.string.speed_and_pitch), onDismiss) {
        FactorSlider(stringResource(R.string.speed), speed) { com.comfort.app.music.MusicSettings.speed.set(it) }
        FactorSlider(stringResource(R.string.pitch), pitch) { com.comfort.app.music.MusicSettings.pitch.set(it) }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.FilledTonalButton(
            onClick = { com.comfort.app.music.MusicSettings.speed.set(1f); com.comfort.app.music.MusicSettings.pitch.set(1f) },
            enabled = speed != 1f || pitch != 1f,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.reset_to_normal), fontFamily = com.comfort.app.theme.HeaderFontFamily) }
    }
}

/** A 0.5–2× slider in 0.05 steps, its value beside its name. */
@Composable
private fun FactorSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), modifier = Modifier.weight(1f))
            Text(formatFactor(value), style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = {
                val v = (Math.round(it * 20f) / 20f).coerceIn(0.5f, 2f)
                if (v != value) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                    onChange(v)
                }
            },
            valueRange = 0.5f..2f,
        )
    }
}

/** The player's settings (Now Playing › ⋮ › Settings): playback, sound, lyrics and the queue. */
@Composable
private fun MusicSettingsSheet(lyricLine: Boolean, onLyricLineChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val m = com.comfort.app.music.MusicSettings
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // A tick each time a slider moves on a step.
    val tick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) }
    PlayerSheet(stringResource(R.string.music_settings), onDismiss) {
        SettingsGroup(stringResource(R.string.music_group_playback))
        SettingSwitch(stringResource(R.string.skip_silence), stringResource(R.string.skip_silence_subtitle), m.skipSilence)
        val fade by m.fadeSeconds.value.collectAsState()
        SettingRow(stringResource(R.string.fade_between), stringResource(R.string.fade_between_subtitle)) {
            Text(if (fade == 0) stringResource(R.string.subtitles_off_short) else stringResource(R.string.x_seconds_short, fade), style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Slider(
            value = fade.toFloat(),
            onValueChange = { val v = Math.round(it); if (v != fade) { tick(); m.fadeSeconds.set(v) } },
            valueRange = 0f..12f,
            steps = 11,
        )
        SettingSwitch(stringResource(R.string.resume_after_interruption), stringResource(R.string.resume_after_interruption_subtitle), m.resumeAfterInterruption)
        SettingSwitch(stringResource(R.string.pause_on_disconnect), stringResource(R.string.pause_on_disconnect_subtitle), m.pauseOnDisconnect)

        SettingsGroup(stringResource(R.string.music_group_sound))
        val sessionId by m.audioSessionId.collectAsState()
        SettingRow(
            stringResource(R.string.equalizer),
            stringResource(R.string.equalizer_subtitle),
            onClick = {
                val panel = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                val opened = runCatching { context.startActivity(panel) }.isSuccess
                if (!opened) android.widget.Toast.makeText(context, R.string.no_equalizer, android.widget.Toast.LENGTH_SHORT).show()
            },
        ) {}
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            SettingSwitch(stringResource(R.string.volume_levelling), stringResource(R.string.volume_levelling_subtitle), m.volumeLevelling)
        }

        SettingsGroup(stringResource(R.string.appearance))
        SettingSwitch(stringResource(R.string.player_background), stringResource(R.string.player_background_subtitle), m.coverBackground)

        SettingsGroup(stringResource(R.string.lyrics))
        SettingRow(stringResource(R.string.lyric_lines), stringResource(R.string.lyric_lines_subtitle), onClick = {
            haptics.performHapticFeedback(if (lyricLine) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
            onLyricLineChange(!lyricLine)
        }) {
            androidx.compose.material3.Switch(checked = lyricLine, onCheckedChange = null)
        }
        // The full lyrics' size, as pinching them sets it (LyricsView's "lyrics_zoom").
        val zoomPrefs = remember { context.getSharedPreferences("music_player", android.content.Context.MODE_PRIVATE) }
        var zoom by remember { mutableStateOf(zoomPrefs.getFloat("lyrics_zoom", 1f).coerceIn(0.7f, 1.8f)) }
        SettingRow(stringResource(R.string.lyrics_text_size), null) {
            Text("${Math.round(zoom * 100)}%", style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Slider(
            value = zoom,
            onValueChange = { val v = (Math.round(it * 20f) / 20f).coerceIn(0.7f, 1.8f); if (v != zoom) { tick(); zoom = v } },
            onValueChangeFinished = { zoomPrefs.edit().putFloat("lyrics_zoom", zoom).apply() },
            valueRange = 0.7f..1.8f,
        )

        SettingsGroup(stringResource(R.string.queue))
        SettingSwitch(stringResource(R.string.previous_restarts), stringResource(R.string.previous_restarts_subtitle), m.previousRestarts)
        SettingSwitch(stringResource(R.string.play_tapped_only), stringResource(R.string.play_tapped_only_subtitle), m.playTappedOnly)
        SettingSwitch(stringResource(R.string.keep_queue), stringResource(R.string.keep_queue_subtitle), m.keepQueue)
    }
}

@Composable
private fun SettingsGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** A setting's name and what it does, with [trailing] at its end; a tap target when [onClick] is set. */
@Composable
private fun SettingRow(title: String, subtitle: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily))
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.size(12.dp))
        trailing()
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, setting: com.comfort.app.music.MusicSettings.Setting<Boolean>) {
    val on by setting.value.collectAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    SettingRow(title, subtitle, onClick = {
        haptics.performHapticFeedback(if (on) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
        setting.set(!on)
    }) {
        androidx.compose.material3.Switch(checked = on, onCheckedChange = null)
    }
}

/** What a song's file is: its name and where it's saved, format, length, bitrate, sample rate and
 * size (Auxio's "Song properties"). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SongPropertiesSheet(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val props by androidx.compose.runtime.produceState<List<Pair<Int, String>>?>(null, song.uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { songProperties(context, song.uri) }
    }
    InWindowSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.song_properties),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            props?.forEach { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** The song file's properties, labelled; those that can't be read are left out. */
private fun songProperties(context: android.content.Context, uri: android.net.Uri): List<Pair<Int, String>> {
    var name: String? = null
    var folder: String? = null
    var size: Long? = null
    runCatching {
        val cols = buildList {
            add(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
            add(android.provider.MediaStore.MediaColumns.SIZE)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) add(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = c.getLong(1).takeIf { it > 0 }
                if (cols.size > 2) folder = c.getString(2)
            }
        }
    }
    var duration: Long? = null
    var bitrate: Long? = null
    var sampleRate: Int? = null
    val retriever = android.media.MediaMetadataRetriever()
    runCatching {
        retriever.setDataSource(context, uri)
        duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            sampleRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
        }
    }
    runCatching { retriever.release() }
    val format = name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()
    return buildList {
        name?.let { add(R.string.prop_file to it) }
        folder?.let { add(R.string.path to it.trimEnd('/')) }
        format?.let { add(R.string.prop_format to it) }
        duration?.takeIf { it > 0 }?.let { ms ->
            val total = ms / 1000
            add(R.string.prop_length to if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%d:%02d".format(total / 60, total % 60))
        }
        bitrate?.takeIf { it > 0 }?.let { add(R.string.prop_bitrate to "${(it + 500) / 1000} kbps") }
        sampleRate?.takeIf { it > 0 }?.let { hz -> add(R.string.prop_sample_rate to (if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f))) }
        size?.let { add(R.string.sort_size to android.text.format.Formatter.formatFileSize(context, it)) }
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
            // Swipe the song (cover and title) sideways to change it, as on the mini player.
            Row(modifier = Modifier.weight(1f).swipeToSkip(), verticalAlignment = Alignment.CenterVertically) {
            Box(coverModifier.size(52.dp).clip(RoundedCornerShape(10.dp))) {
                CoverImage(state.song, blurred = false, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f).then(titleModifier)) {
                Text(state.song?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.song?.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            }
            // Previous, play, next in any language (see MediaLtr).
            com.comfort.app.ui.MediaLtr { Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedIconButton(
                // As the player's own: restarts the song, or goes back one near its start.
                onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.previous() },
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Color.White),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.widget_music_previous))
            }
            Spacer(Modifier.size(6.dp))
            FilledIconButton(
                onClick = MusicPlayer::togglePlay,
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.size(width = 48.dp, height = 44.dp),
            ) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.isPlaying) stringResource(R.string.widget_music_pause) else stringResource(R.string.widget_music_play))
            }
            Spacer(Modifier.size(6.dp))
            androidx.compose.material3.OutlinedIconButton(
                onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); MusicPlayer.next() },
                enabled = state.hasNext,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Color.White),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.widget_music_next))
            }
            } }
        }
        com.comfort.app.ui.MediaLtr {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { if (state.duration > 0) (position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp),
        )
        }
        Spacer(Modifier.height(8.dp))
        // A pull down on the list once it's at its top closes the queue too, as one on the header
        // above does (the list took those drags for itself, so only the header did). Watched, not
        // taken: the list still stretches under the finger as it's pulled.
        val closeAfter = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
        val pullToClose = remember(onCloseQueue) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                var pulled = 0f
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0f) pulled += available.y
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    // Not when flung back up at the end of it.
                    if (pulled > closeAfter && available.y >= 0f) {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                        onCloseQueue()
                    }
                    pulled = 0f
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        // See-through: the queue stays on the player's own dark, blurred-cover backdrop.
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = Color.White.copy(alpha = 0.06f),
            contentColor = Color.White,
            modifier = panelModifier.fillMaxSize().nestedScroll(pullToClose),
        ) {
            QueuePanel()
        }
    }
}

/** In "music_player" (MusicService's prefs, as LyricsView's zoom): whether the lyric lines under the cover are shown. */
private const val KEY_LYRIC_LINE = "lyric_line"

/** In "music_player": the lyric lines hidden from the top bar's button (with Lyric lines on). */
private const val KEY_LYRIC_LINE_HIDDEN = "lyric_line_hidden"

/** A cover blurred for the player's backdrop: shrunk to 48 px and box-blurred there (three passes,
 * close to a Gaussian), then drawn scaled up, which smooths it further. Done once per cover, not
 * on every frame as a blur on the layer was. */
private object SmallBlur : coil.transform.Transformation {
    override val cacheKey = "player-backdrop-blur-1"

    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        val scale = 48f / maxOf(input.width, input.height)
        val w = (input.width * scale).toInt().coerceAtLeast(1)
        val h = (input.height * scale).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(input.copy(android.graphics.Bitmap.Config.ARGB_8888, false), w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxBlur(px, tmp, w, h, 3, horizontal = true)
            boxBlur(tmp, px, w, h, 3, horizontal = false)
        }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = src[if (horizontal) line * w + j else j * w + line]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                dst[if (horizontal) line * w + i else i * w + line] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
