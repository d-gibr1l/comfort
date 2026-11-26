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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreVert
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

    var position by remember { mutableLongStateOf(MusicPlayer.position()) }
    // What the bar shows: glides to [position] as the player opens or the app comes back, instead
    // of jumping there. The lyrics follow [position] itself, settling on its line.
    val shownPosition by rememberGlidingPosition { position }
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
    // Landscape: the cover on the left, the controls (or the queue, in their place) on the right.
    val landscapeLayout = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
        // The song before's backdrop stays up while the next one loads (black showed in between).
        val seenBackdrop = remember { arrayOfNulls<String>(1) }
        val priorBackdrop = remember(song?.id) { seenBackdrop[0].also { seenBackdrop[0] = song?.id } }
        if (coverBackdrop) CoverImage(song, blurred = true, modifier = Modifier.fillMaxSize().clipToBounds().scale(1.2f), priorId = priorBackdrop)
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
                // In landscape the whole screen stays; only its right side turns into the queue.
                targetState = showQueue && !landscapeLayout,
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
                        // Its sides too: 3-button navigation sits on the side in landscape, and the
                        // lyrics button went under it.
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Auxio's top bar: close, "Now playing" over where in the queue, and the queue.
                    // Landscape with the queue open: gone, the cover and queue moved up into its room.
                    androidx.compose.animation.AnimatedVisibility(visible = !(landscapeLayout && showQueue)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.close_player), tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        if (landscapeLayout) Spacer(Modifier.weight(1f)) else Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    }

                    // Landscape: the cover on the left, the title, bar and buttons on the right (one
                    // under the other, they didn't fit the height).
                    val landscape = landscapeLayout
                    // Spotify's lyric line under the cover, as two (the line and the next);
                    // a tap opens the full lyrics. Not while those are up.
                    val lyricLines: @Composable () -> Unit = {
                        val previewLyrics = lyrics
                        androidx.compose.animation.AnimatedVisibility(
                            visible = previewLyrics?.synced == true && !lyricsShown && lyricLine && !linesHidden,
                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                        ) {
                            if (previewLyrics != null) {
                                LyricsPreview(
                                    lyrics = previewLyrics,
                                    // The real position, not the bar's glide (rememberGlidingPosition).
                                    positionMs = position,
                                    onClick = { showLyrics = true },
                                    compact = landscape,
                                    // Clear of the cover above and the title below.
                                    modifier = Modifier.fillMaxWidth().padding(top = if (landscape) 10.dp else 4.dp, bottom = if (landscape) 0.dp else 20.dp),
                                )
                            }
                        }
                    }
                    PortraitOrSideBySide(landscape, first = {
                    // The cover, as big as fits (a tablet or landscape gets a square, not a stretch);
                    // swipe it sideways to change song.
                    val coverContent: @Composable () -> Unit = {
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
                    if (!landscape) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).swipeToSkip(coverSkip),
                            contentAlignment = Alignment.Center,
                        ) { coverContent() }
                    } else {
                        // Landscape: the cover as tall as fits, with room under it for the lyric lines.
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                            val side = (maxHeight - LANDSCAPE_LYRICS_SPACE).coerceAtLeast(120.dp)
                            Column(Modifier.width(side)) {
                                Box(Modifier.size(side).swipeToSkip(coverSkip), contentAlignment = Alignment.Center) { coverContent() }
                                Box(Modifier.fillMaxWidth().height(LANDSCAPE_LYRICS_SPACE), contentAlignment = Alignment.TopCenter) { lyricLines() }
                            }
                        }
                    }
                    }, second = {
                    val controls: @Composable () -> Unit = {
                    Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                        if (!landscape) lyricLines()
                        // Landscape: what the top bar says in portrait, over the title.
                        if (landscape) {
                            Text(
                                stringResource(R.string.now_playing) + (if (state.count > 1) " · " + stringResource(R.string.song_x_of_x, state.index + 1, state.count) else ""),
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.height(6.dp))
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
                            position = dragPosition?.let { (it * state.duration).toLong() } ?: shownPosition,
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
                    if (!landscape) controls() else {
                        // Landscape: the queue takes the controls' place; the cover stays.
                        androidx.compose.animation.AnimatedContent(
                            targetState = showQueue,
                            transitionSpec = {
                                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) +
                                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { it / 6 }) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
                            },
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                            label = "landscapeQueue",
                        ) { inQueue ->
                            if (inQueue) {
                                QueueLayout(
                                    state = state,
                                    position = shownPosition,
                                    onCloseQueue = { showQueue = false },
                                    coverModifier = Modifier,
                                    titleModifier = Modifier,
                                    panelModifier = Modifier,
                                    inPanel = true,
                                )
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { controls() }
                            }
                        }
                    }
                    })
                }
                    } else {
                        QueueLayout(
                            state = state,
                            position = shownPosition,
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
internal fun CoverImage(song: Song?, blurred: Boolean, modifier: Modifier = Modifier, priorId: String? = null) {
    val context = LocalContext.current
    var failed by remember(song?.id) { mutableStateOf(false) }
    val request = remember(song?.id, blurred) {
        coil.request.ImageRequest.Builder(context).data(song?.cover)
            .apply {
                if (blurred) {
                    transformations(SmallBlur)
                    // Its own key, so the next song's backdrop can show this one while it loads.
                    song?.id?.let { memoryCacheKey("backdrop:$it") }
                    priorId?.let { placeholderMemoryCacheKey("backdrop:$it") }
                } else {
                    placeholderMemoryCacheKey(song?.placeholderKey)
                }
            }
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
internal val SEEK_THUMB_WIDTH = 7.dp

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
        // An option opens its own sheet over this one; Back (or a tap outside, or a pull down)
        // from it comes back here, as a sub-page does. Closing this one too sent Back straight
        // out of the menu (reported).
        fun pick(action: () -> Unit) { action() }
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
                        // Leaves the app for the share sheet: the menu's done.
                        open = false
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

/** What the sleep timer is set to, for the menu: the time left, "End of song", or null when off. */
@Composable
internal fun sleepTimerLabel(): String? {
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
    // Landscape's right side, beside the player's cover: no status bar room, no small cover.
    inPanel: Boolean = false,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (inPanel) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))),
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
            if (!inPanel) {
                Box(coverModifier.size(52.dp).clip(RoundedCornerShape(10.dp))) {
                    CoverImage(state.song, blurred = false, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.size(12.dp))
            }
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
            QueuePanel(headerScrolls = inPanel)
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

/** [first] then [second], one under the other in the column; in landscape, side by side ([first]
 * as wide as it is, [second] the rest, centred on the height). */
@Composable
private fun ColumnScope.PortraitOrSideBySide(
    landscape: Boolean,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    if (!landscape) {
        first()
        second()
    } else {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) { first() }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { second() }
        }
    }
}

/** Landscape: the room under the cover for the two lyric lines. */
private val LANDSCAPE_LYRICS_SPACE = 100.dp
