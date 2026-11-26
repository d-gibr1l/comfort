/*
 * Ported from Photon Gallery (https://github.com/Bn5prS/Photon_Gallery), GPL-3.0, like Comfort.
 * Adapted for Comfort: package, imports and the pieces Photon's app provides that Comfort doesn't
 * (see the notes where they were changed).
 */
package com.comfort.app.ui.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.scale
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.res.vectorResource
import com.comfort.app.R
import androidx.compose.ui.graphics.vector.ImageVector


@Composable
fun VideoPlayerItem(
    uri: Uri,
    isCurrentPage: Boolean,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    controlsBottomPadding: androidx.compose.ui.unit.Dp = 64.dp,
    // Comfort: the sound setting lives with the viewer (shared by every video in it), and the
    // viewer hears whether this video plays (it keeps the controls up while paused).
    muted: Boolean = true,
    onMutedChange: (Boolean) -> Unit = {},
    onPlayingChange: (Boolean) -> Unit = {},
    // Comfort: Play or Pause pressed (the viewer keeps its controls up after).
    onPlayPausePressed: () -> Unit = {},
    // Comfort: the audio and subtitle tracks of the video on screen, for the viewer's ⋮ menu.
    onTracksChange: (VideoTracks?) -> Unit = {},
    /** Comfort: on the video itself, not its controls (the viewer's zoom). */
    videoModifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    VideoPlayerItemWithResolvedUri(
        uri = uri,
        isCurrentPage = isCurrentPage,
        showControls = showControls,
        muted = muted,
        onMutedChange = onMutedChange,
        onPlayingChange = onPlayingChange,
        onPlayPausePressed = onPlayPausePressed,
        onTracksChange = onTracksChange,
        controlsBottomPadding = controlsBottomPadding,
        modifier = modifier,
        videoModifier = videoModifier,
        onTap = onTap
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerItemWithResolvedUri(
    uri: Uri,
    isCurrentPage: Boolean,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    controlsBottomPadding: androidx.compose.ui.unit.Dp = 64.dp,
    muted: Boolean = true,
    onMutedChange: (Boolean) -> Unit = {},
    onPlayingChange: (Boolean) -> Unit = {},
    onPlayPausePressed: () -> Unit = {},
    onTracksChange: (VideoTracks?) -> Unit = {},
    videoModifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val builder = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            
        builder.build().apply {
            // Lands on the frame asked for, not the nearest keyframe before it.
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            // Subtitles start off: a downloaded video's are shown when picked in the ⋮ menu (an
            // embedded track is flagged "default", which would otherwise show it right away).
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                .build()
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var videoDuration by remember { mutableStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var videoAspectRatio by remember(uri) {
        val vs = exoPlayer.videoSize
        val initialRatio = if (vs.width > 0 && vs.height > 0) {
            val par = if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f
            val isRotated = vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270
            val rawW = if (isRotated) vs.height else vs.width
            val rawH = if (isRotated) vs.width else vs.height
            (rawW.toFloat() * par) / rawH.toFloat()
        } else null
        mutableStateOf(initialRatio)
    }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val widthStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val heightStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                retriever.release()
                val w = widthStr?.toFloatOrNull() ?: 0f
                val h = heightStr?.toFloatOrNull() ?: 0f
                val rot = rotationStr?.toIntOrNull() ?: 0
                if (w > 0f && h > 0f) {
                    val ratio = if (rot == 90 || rot == 270) h / w else w / h
                    if (ratio > 0f && videoAspectRatio == null) {
                        withContext(Dispatchers.Main) {
                            videoAspectRatio = ratio
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    // Comfort: from the viewer (muted to start, Photon's "Autoplay with sound" default).
    val isMuted = muted

    // Comfort: the video's thumbnail until its first frame is up (~0.5 s to start a player), not
    // an empty page.
    var firstFrameShown by remember(uri) { mutableStateOf(false) }
    val untitledTrack = androidx.compose.ui.res.stringResource(R.string.track_n)
    var tracks by remember(uri) { mutableStateOf<VideoTracks?>(null) }

    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameShown = true }
            override fun onTracksChanged(newTracks: androidx.media3.common.Tracks) {
                tracks = VideoTracks.from(exoPlayer, newTracks) { n -> untitledTrack.format(n) }
            }
            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                isPlaying = isPlayingState
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    videoDuration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val par = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                    val isRotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                    val rawW = if (isRotated) videoSize.height else videoSize.width
                    val rawH = if (isRotated) videoSize.width else videoSize.height
                    val computedRatio = (rawW.toFloat() * par) / rawH.toFloat()
                    if (computedRatio > 0f) {
                        videoAspectRatio = computedRatio
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // The viewer's ⋮ › Settings: loop and speed, followed at once.
    val loop by VideoSettings.loop.collectAsState()
    val speed by VideoSettings.speed.collectAsState()
    LaunchedEffect(exoPlayer, loop) {
        exoPlayer.repeatMode = if (loop) androidx.media3.common.Player.REPEAT_MODE_ONE else androidx.media3.common.Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(exoPlayer, speed) { exoPlayer.setPlaybackSpeed(speed) }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                exoPlayer.seekTo(0)
            }
            exoPlayer.play()
        }
    }

    // Comfort: the video pauses when the app goes to the background (Home, Recents, the screen
    // off) and goes on when it comes back, if it was playing then.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val currentPage by androidx.compose.runtime.rememberUpdatedState(isCurrentPage)
    DisposableEffect(lifecycleOwner, exoPlayer) {
        var resumeOnStart = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = exoPlayer.playWhenReady
                    exoPlayer.pause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (resumeOnStart && currentPage) exoPlayer.play()
                    resumeOnStart = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Where a scrub let go: the bar stays there until the player has caught up with it (its own
    // position read back the old place for a moment, and the handle jumped back, then forward).
    var seekTarget by remember(uri) { mutableStateOf<Long?>(null) }
    // Live scrubbing (the seek bar below): whether to play on once let go, and when it last moved
    // the picture.
    var scrubResume by remember(uri) { mutableStateOf(false) }
    var lastScrubSeek by remember(uri) { mutableStateOf(0L) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val pos = exoPlayer.currentPosition
            val target = seekTarget
            if (target == null || kotlin.math.abs(pos - target) < 400) {
                seekTarget = null
                currentPosition = pos
            }
            delay(100)
        }
    }

    LaunchedEffect(isPlaying, isCurrentPage) {
        if (isCurrentPage) onPlayingChange(isPlaying)
    }

    // Only the page on screen tells the viewer its tracks.
    LaunchedEffect(tracks, isCurrentPage) {
        if (isCurrentPage) onTracksChange(tracks)
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }
    // A video playing with sound takes the audio focus, so music playing (the app's own player,
    // or another app's) pauses for it, as in other players; muted it leaves the music alone.
    // Only the page on screen: the ones beside it are kept ready but don't play.
    LaunchedEffect(isMuted, isCurrentPage) {
        exoPlayer.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            !isMuted && isCurrentPage,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Comfort: transparent, so the viewer's backdrop shows (black with the controls hidden).
            .background(Color.Transparent)
            // Comfort: none without an onTap: the viewer's zoom takes the taps then (a clickable
            // here took them first).
            .then(if (onTap == null) Modifier else Modifier.clickable(
                onClick = { onTap() },
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ))
    ) {
        Box(
            modifier = Modifier.fillMaxSize().then(videoModifier),
            contentAlignment = Alignment.Center
        ) {
            // Comfort: the largest box of the video's shape that fits (the Box above centres it).
            // fillMaxSize() first made it width-first only: on a wide screen (landscape, a
            // tablet) a 4:3 or 16:9 video ran off the top and bottom, looking zoomed in.
            val playerModifier = if (videoAspectRatio != null && videoAspectRatio!! > 0f) {
                Modifier.aspectRatio(videoAspectRatio!!)
            } else {
                Modifier.fillMaxSize()
            }
            // Comfort: the player's surface only once the video's shape is known (or after a
            // moment): a surface laid out full-screen first flashed black over the whole page.
            var surfaceReady by remember(uri) { mutableStateOf(false) }
            LaunchedEffect(uri, videoAspectRatio) {
                if (videoAspectRatio == null) delay(400)
                surfaceReady = true
            }
            // Comfort: the video's own first frame until the player's is up, beneath the player
            // (a TextureView: see viewer_player_view.xml), which covers it once it draws. Not the
            // Library's thumbnail: for a download with an embedded cover that's the site's
            // thumbnail, which flashed up before the video (reported). The frame is where playback
            // starts, so the video takes over from it without a change. Full size and fitted, as
            // the video is: the same place, whatever the player's box.
            var frameGone by remember(uri) { mutableStateOf(false) }
            LaunchedEffect(firstFrameShown) {
                // A moment after the first frame, so it's surely on screen over this one.
                if (firstFrameShown) { delay(300); frameGone = true }
            }
            if (!frameGone) {
                coil.compose.AsyncImage(
                    model = remember(uri) {
                        coil.request.ImageRequest.Builder(context).data(uri).size(720)
                            .setParameter(com.comfort.app.util.AppImageLoader.PARAM_VIDEO_FRAME, true, memoryCacheKey = "frame")
                            // The same frame the viewer showed while opening (MediaViewerHost), at
                            // once: decoded again, the page was black for a few frames meanwhile.
                            .placeholderMemoryCacheKey(viewerOpeningKey(uri.toString()))
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (surfaceReady) AndroidView(
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx).inflate(R.layout.viewer_player_view, null) as PlayerView).apply {
                        // Comfort: the built-in controller off before the player is attached:
                        // attaching it first showed the controller, and turning it off after
                        // only faded it out, so it flashed on every video swiped to.
                        useController = false
                        controllerAutoShow = false
                        player = exoPlayer
                        // Comfort: no black shutter over the thumbnail shown until the first frame.
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        try {
                            val method = javaClass.getMethod("setEnableComposeSurfaceSyncWorkaround", Boolean::class.javaPrimitiveType)
                            method.invoke(this, true)
                        } catch (_: Throwable) {}
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { playerView ->
                    playerView.player = exoPlayer
                },
                modifier = playerModifier
            )
        }

        // Center Play/Pause button
        AnimatedVisibility(
            visible = showControls && isCurrentPage,
            enter = fadeIn(MotionTokens.snappySpring()) + scaleIn(MotionTokens.snappySpring()),
            exit = fadeOut(MotionTokens.gentleSpring()) + scaleOut(MotionTokens.gentleSpring()),
            modifier = Modifier.align(Alignment.Center)
        ) {
            FilledTonalIconButton(
                onClick = {
                    onPlayPausePressed()
                    if (isPlaying) {
                        exoPlayer.pause()
                    } else {
                        if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                            exoPlayer.seekTo(0)
                        }
                        exoPlayer.play()
                    }
                },
                modifier = Modifier
                    .size(56.dp)
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    // Comfort: the filled symbols; the outlined ones Photon ships read as "00" at this size.
                    imageVector = if (isPlaying) androidx.compose.material.icons.Icons.Rounded.Pause else androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.widget_music_pause) else stringResource(R.string.widget_music_play),
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Bottom Controls Overlay
        AnimatedVisibility(
            visible = showControls && isCurrentPage,
            enter = slideInVertically(
                animationSpec = MotionTokens.snappySpring(),
                initialOffsetY = { it / 2 }
            ) + fadeIn(MotionTokens.snappySpring()),
            exit = slideOutVertically(
                animationSpec = MotionTokens.gentleSpring(),
                targetOffsetY = { it / 2 }
            ) + fadeOut(MotionTokens.gentleSpring()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Comfort: measured from the top of the navigation bar, like the toolbar under it
                // (navigationBarsPadding there too). Photon's fixed 76dp from the screen's edge
                // only cleared a gesture bar: over a taller 3-button bar the card sat on the toolbar.
                .navigationBarsPadding()
                .padding(bottom = controlsBottomPadding, start = 16.dp, end = 16.dp)
        ) {
            Surface(
                shape = ShapeLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {} // Consume clicks so they don't toggle controller
            ) {
                // Comfort: time, seek bar, time and mute stay in this order in a right-to-left
                // language (see MediaLtr).
                com.comfort.app.ui.MediaLtr {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(dragPosition ?: currentPosition),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Slider(
                        value = (dragPosition ?: currentPosition).toFloat(),
                        // Live scrubbing: paused while dragged, the picture follows the finger
                        // (to the nearest keyframe, quick to find, at most every ~80 ms); let go,
                        // it lands on the exact frame and plays on if it was playing.
                        onValueChange = {
                            if (dragPosition == null) {
                                scrubResume = exoPlayer.playWhenReady
                                exoPlayer.pause()
                                exoPlayer.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                            }
                            dragPosition = it.toLong()
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastScrubSeek >= 80) {
                                lastScrubSeek = now
                                exoPlayer.seekTo(it.toLong())
                            }
                        },
                        onValueChangeFinished = {
                            exoPlayer.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
                            dragPosition?.let {
                                seekTarget = it
                                exoPlayer.seekTo(it)
                                currentPosition = it
                            }
                            dragPosition = null
                            if (scrubResume) exoPlayer.play()
                            scrubResume = false
                        },
                        valueRange = 0f..videoDuration.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )

                    Text(
                        text = formatTime(videoDuration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    // Audio mute/unmute
                    IconButton(
                        onClick = { onMutedChange(!isMuted) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) ImageVector.vectorResource(R.drawable.ic_ms_volume_off) else ImageVector.vectorResource(R.drawable.ic_ms_volume_up),
                            contentDescription = if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

/** The memory-cache key of the picture MediaViewerHost shows while the viewer opens on [file]; the
 * page then uses it until its own is ready, so nothing blank shows in between. */
internal fun viewerOpeningKey(file: String) = "viewer-opening:$file"
