@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.comfort.app.R
import com.comfort.app.viewmodel.rememberSheetViewModelStoreOwner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfort.app.data.AudioFormat
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.PreviewInfo
import com.comfort.app.util.TrackPreview
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun ExtraCommandsScreen(
    url: String,
    options: DownloadOptions,
    commands: List<String>,
    onCommandsChange: (List<String>) -> Unit,
    onCopy: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val templateContext = androidx.compose.ui.platform.LocalContext.current
    var templates by remember { mutableStateOf(com.comfort.app.data.CommandTemplates.getAll(templateContext)) }
    // The commands being saved as a new template (the dialog asks for its name).
    var savingTemplate by remember { mutableStateOf<String?>(null) }

    // An unclosed quote would make the engines skip every extra command: not added until fixed.
    val unclosedQuotes = input.isNotBlank() && hasUnmatchedQuotes(input)

    fun add() {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || unclosedQuotes) return
        onCommandsChange(commands + trimmed)
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        // Off the main thread: it reads the preferences and the cookie file. Keyed on everything
        // the sheet can change, so it follows each pick; the last command stays up meanwhile.
        val shownCommands by produceState(emptyList<com.comfort.app.worker.CurrentCommand.Shown>(), url, options) {
            value = withContext(Dispatchers.IO) {
                val entity = com.comfort.app.worker.CurrentCommand.sheetEntity(
                    url = url.trim(), videoQuality = options.quality, outputFormat = options.outputFormat.name,
                    saveThumbnail = options.saveThumbnail, clipRange = options.clipRange,
                    extraCommands = options.extraCommands, filenameTemplate = options.filenameTemplate,
                    itemFilter = options.itemFilter, engineOverride = options.engine,
                    audioFormat = options.audioFormat?.name, audioLanguage = options.audioLanguage,
                    subtitleLanguages = options.subtitleLanguages,
                    overrideTitle = options.overrideTitle, overrideArtist = options.overrideArtist,
                )
                com.comfort.app.worker.CurrentCommand.forDownload(context, url.trim(), entity)
            }
        }

        CommandCodePanel(
            commands = shownCommands,
            subtitle = stringResource(R.string.current_command_download_subtitle),
            onCopy = onCopy,
            // More room under the drag handle; shrinks so the chips, Add field and Done fit the
            // sheet's capped height.
            modifier = Modifier.weight(1f, fill = false).padding(top = 8.dp),
            shrinkable = true,
        )

        if (commands.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                commands.forEachIndexed { index, cmd ->
                    InputChip(
                        selected = true,
                        onClick = { onCommandsChange(commands.filterIndexed { i, _ -> i != index }) },
                        label = { Text(cmd, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.remove_x_2, cmd),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        // Saved templates (Settings > Advanced > Command templates): a tap adds one's arguments
        // as a command; the current commands can be saved as a new one.
        if (templates.isNotEmpty() || commands.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                templates.forEach { template ->
                    val added = template.args in commands
                    AssistChip(
                        onClick = { if (!added) onCommandsChange(commands + template.args) },
                        enabled = !added,
                        label = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
                if (commands.isNotEmpty()) {
                    AssistChip(
                        onClick = { savingTemplate = commands.joinToString(" ") },
                        label = { Text(androidx.compose.ui.res.stringResource(com.comfort.app.R.string.command_template_save_as)) },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        savingTemplate?.let { args ->
            CommandTemplateDialog(
                initial = com.comfort.app.data.CommandTemplate("", args),
                onDismiss = { savingTemplate = null },
                onSave = { saved ->
                    com.comfort.app.data.CommandTemplates.put(templateContext, saved)
                    templates = com.comfort.app.data.CommandTemplates.getAll(templateContext)
                    savingTemplate = null
                },
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
            placeholder = { Text(stringResource(R.string.add_command)) },
            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
            trailingIcon = {
                FilledTonalIconButton(
                    onClick = { add() },
                    enabled = input.isNotBlank() && !unclosedQuotes,
                    modifier = Modifier.padding(end = 6.dp).size(40.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add))
                }
            },
            singleLine = true,
            isError = unclosedQuotes,
            supportingText = if (unclosedQuotes) {
                { Text(stringResource(R.string.unclosed_quotation_marks_arguments_may_be)) }
            } else null,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            shape = androidx.compose.foundation.shape.CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConfirmCancelSplitButton(
                label = stringResource(R.string.done),
                icon = Icons.Outlined.Check,
                onConfirm = onDone,
                onCancel = onCancel,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrimVideoScreen(
    segments: List<TrimSegment>,
    onSegmentsChange: (List<TrimSegment>) -> Unit,
    thumbnail: String?,
    pageUrl: String,
    streamUrls: List<String>,
    durationMs: Long?,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var activeId by remember(segments.firstOrNull()?.id) { mutableStateOf(segments.firstOrNull()?.id) }
    val active = segments.firstOrNull { it.id == activeId } ?: segments.firstOrNull()

    // The scrub position stands in for a player playhead: with no playable stream before download
    // (see NOMINAL_DURATION_MS), Set Start/Set End capture *this* rather than an ExoPlayer
    // currentPosition, keeping the same tactile "mark the boundary where I am" interaction.
    var playheadMs by remember { mutableStateOf(active?.startMs ?: 0L) }

    fun updateActive(transform: (TrimSegment) -> TrimSegment) {
        val target = active ?: return
        onSegmentsChange(segments.map { if (it.id == target.id) transform(it) else it })
    }
    val context = LocalContext.current
    var playerPlaying by remember { mutableStateOf(false) }
    val exoPlayer = remember { 
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("ExoPlayer", "Playback error", error)
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playerPlaying = isPlaying }
            })
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    var streamLoadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(streamUrls) {
        if (streamUrls.isNotEmpty()) {
            // yt-dlp/site extractors resolve to whatever container/manifest format that source
            // actually serves — confirmed live, a YouTube Shorts link resolved to an HLS (.m3u8)
            // stream rather than a plain progressive file, and DefaultMediaSourceFactory throws a
            // hard IllegalStateException for any content type with no matching extension on the
            // classpath (media3-exoplayer-hls, added alongside this, covers the one actually seen
            // so far — but a DASH/SmoothStreaming/RTSP stream from some other extractor would hit
            // the same gap). That exception isn't caught anywhere up this LaunchedEffect's
            // coroutine, so it crashed the whole app instead of just failing to preview. Falling
            // back to the static thumbnail here — same as an empty streamUrls already does — keeps
            // this a cosmetic miss instead of a crash regardless of what future content type shows up.
            streamLoadFailed = false
            try {
                val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                val mediaSources = streamUrls.map { factory.createMediaSource(androidx.media3.common.MediaItem.fromUri(it)) }
                val mediaSource = if (mediaSources.size > 1) {
                    androidx.media3.exoplayer.source.MergingMediaSource(*mediaSources.toTypedArray())
                } else {
                    mediaSources.first()
                }
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
            } catch (e: Exception) {
                android.util.Log.e("ExoPlayer", "Unsupported stream for Trim preview", e)
                streamLoadFailed = true
            }
        }
    }

    LaunchedEffect(playheadMs) {
        if (streamUrls.isNotEmpty() && !streamLoadFailed) {
            if (kotlin.math.abs(exoPlayer.currentPosition - playheadMs) > 500L) {
                exoPlayer.seekTo(playheadMs)
            }
        }
    }

    // Tracks drags on the Slider below specifically (shared with its own interactionSource) so
    // the poll below can tell "the user is actively dragging our Slider" apart from every other
    // way the real position can move.
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isDraggingSlider by sliderInteractionSource.collectIsDraggedAsState()

    LaunchedEffect(exoPlayer, streamUrls, streamLoadFailed) {
        if (streamUrls.isNotEmpty() && !streamLoadFailed) {
            while (true) {
                // Only sync position when player actually has a media stream loaded and user is not dragging
                if (!isDraggingSlider && exoPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                    playheadMs = exoPlayer.currentPosition
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    // The header, the video and its slider stay put and the rest scrolls under them: inside the
    // scroll, scrolling down to Done took the sheet's title with it and slid half the video out of
    // view.
    Column(modifier = Modifier.fillMaxWidth()) {
    SheetHeader(
        icon = Icons.Outlined.ContentCut,
        title = stringResource(R.string.trim_video_2),
        subtitle = stringResource(R.string.trim_subtitle),
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp),
    )

        val realDurationMs = durationMs?.takeIf { it > 0L }
        val effectiveDurationMs = realDurationMs ?: NOMINAL_DURATION_MS
        // Hard-capped at the real duration once it's known, rather than still widening for
        // whatever a segment's own endMs/the playhead reach (the old behavior) — that ratchet let
        // Set Start/Add a segment push a boundary arbitrarily far past the actual video, which
        // yt_dlp_wrapper.py's _LocalTrimPP then trimmed with no validation, silently replacing an
        // already-finished download with an empty file (reproduced live). toClipRange() clamps
        // again right before a download actually starts as the last line of defense regardless of
        // how a segment got here, but keeping the slider itself from ever going further than the
        // real video exists is what stops the bogus value from being created in the first place.
        val maxSliderMs = realDurationMs?.toFloat()
            ?: maxOf(effectiveDurationMs.toFloat(), active?.endMs?.toFloat() ?: 0f, playheadMs.toFloat())

        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (streamUrls.isNotEmpty() && !streamLoadFailed) {
                    // Media3's own controls are off: they were a second seek bar over the one
                    // below, with previous/next buttons and a settings gear that do nothing here.
                    // A tap plays or pauses; the slider below seeks.
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            androidx.media3.ui.PlayerView(ctx).apply {
                                useController = false
                                player = exoPlayer
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (exoPlayer.isPlaying) exoPlayer.pause()
                                else {
                                    if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) exoPlayer.seekTo(0)
                                    exoPlayer.play()
                                }
                            },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !playerPlaying,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(),
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.widget_music_play),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                } else if (thumbnail != null) {
                    AsyncImage(
                        model = thumbnailRequest(thumbnail, pageUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Show the time overlay whenever the player isn't (no stream, or one this device
                // can't play — see streamLoadFailed above) — ExoPlayer has its own UI otherwise.
                if (streamUrls.isEmpty() || streamLoadFailed) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            formatTimestamp(playheadMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                if (isDraggingSlider && maxSliderMs > 0f) {
                    val dragFrac = (playheadMs.toFloat() / maxSliderMs).coerceIn(0f, 1f)
                    Surface(
                        modifier = Modifier
                            .align(androidx.compose.ui.BiasAlignment(dragFrac * 2f - 1f, 1f))
                            .padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            formatTimestamp(playheadMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // The M3 Expressive slider: a thick track and a tall handle rather than the default thin
        // track + round dot, per the spec's own component guidance.
        //
        // Ranged against the real video length (durationMs, from yt-dlp's own listing) whenever
        // it's known, rather than always the fixed NOMINAL_DURATION_MS — otherwise a 19-second
        // clip and a 19-minute one got the identical fixed 10-minute range, cramming the former's
        // entire real length into a sliver at the very start of the track and letting the latter's
        // last 9 minutes go completely unreachable. NOMINAL_DURATION_MS now only covers the case
        // this was originally written for: no stream/duration resolved at all (see its own doc
        // comment) — there's nothing real to range against yet, so it's the one honest fallback.

        // A video's timeline: left to right in any language (see MediaLtr), the trim band with it.
        com.comfort.app.ui.MediaLtr {
        Slider(
            modifier = Modifier.padding(horizontal = 16.dp),
            value = playheadMs.toFloat().coerceIn(0f, maxSliderMs),
            onValueChange = { playheadMs = it.toLong() },
            valueRange = 0f..maxSliderMs,
            interactionSource = sliderInteractionSource,
            thumb = { state ->
                SliderDefaults.Thumb(
                    interactionSource = sliderInteractionSource,
                    thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 44.dp),
                )
            },
            track = { state ->
                // The stock track only ever shows the single playhead position — nothing about the
                // active segment's own [start, end] span was visible anywhere on it, so the
                // "length" the user was trimming to had no representation except the numeric Start/
                // End fields below. This overlay draws that span as a highlighted band, proportional
                // to maxSliderMs, on top of the default M3 track.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    SliderDefaults.Track(
                        sliderState = state,
                        trackCornerSize = 8.dp,
                    )
                    val segStartMs = active?.startMs?.toFloat() ?: 0f
                    val segEndMs = active?.endMs?.toFloat() ?: 0f
                    if (maxSliderMs > 0f && segEndMs > segStartMs) {
                        val startFrac = (segStartMs / maxSliderMs).coerceIn(0f, 1f)
                        val endFrac = (segEndMs / maxSliderMs).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = maxWidth * startFrac)
                                .width(maxWidth * (endFrac - startFrac))
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                // Its own colour: a lighter primary disappeared over the part
                                // already played, which is primary too.
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                }
            },
        )
        }

        // Where the playhead is, for Set Start/Set End: the player's own time display went with
        // its controls.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTimestamp(playheadMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (realDurationMs != null) {
                Text(
                    formatTimestamp(realDurationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(
                label = stringResource(R.string.set_start),
                onClick = {
                    val hasActivePlayer = streamUrls.isNotEmpty() && !streamLoadFailed && exoPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE
                    val newStart = if (hasActivePlayer) exoPlayer.currentPosition else playheadMs
                    playheadMs = newStart
                    // Mirrors the spec's safety constraint: a start at or past the current end
                    // would be an invalid (negative-length) clip, so the end is pushed out to keep
                    // at least a second of video rather than letting the segment collapse.
                    updateActive { segment ->
                        val newEnd = if (newStart >= segment.endMs) newStart + 1000L else segment.endMs
                        segment.copy(startMs = newStart, endMs = newEnd)
                    }
                },
            )
            PreviewChip(
                label = stringResource(R.string.set_end),
                onClick = {
                    val hasActivePlayer = streamUrls.isNotEmpty() && !streamLoadFailed && exoPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE
                    val newEnd = if (hasActivePlayer) exoPlayer.currentPosition else playheadMs
                    playheadMs = newEnd
                    // The reverse constraint, clamped at zero so the start can't go negative.
                    updateActive { segment ->
                        val newStart = if (newEnd <= segment.startMs) (newEnd - 1000L).coerceAtLeast(0L) else segment.startMs
                        segment.copy(startMs = newStart, endMs = newEnd)
                    }
                },
            )
        }
        var startInput by remember { mutableStateOf(formatTimestamp(active?.startMs ?: 0L)) }
        var endInput by remember { mutableStateOf(formatTimestamp(active?.endMs ?: 0L)) }
        
        LaunchedEffect(active?.id, active?.startMs) {
            if (parseTimestampToMs(startInput) != active?.startMs) {
                startInput = formatTimestamp(active?.startMs ?: 0L)
            }
        }
        LaunchedEffect(active?.id, active?.endMs) {
            if (parseTimestampToMs(endInput) != active?.endMs) {
                endInput = formatTimestamp(active?.endMs ?: 0L)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var isStartFocused by remember { mutableStateOf(false) }
            var isEndFocused by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = startInput,
                onValueChange = { 
                    startInput = it
                    parseTimestampToMs(it)?.let { ms ->
                        if (ms <= (active?.endMs ?: 0L) && (realDurationMs == null || ms < realDurationMs)) {
                            updateActive { segment -> segment.copy(startMs = ms) }
                        }
                    }
                },
                label = { Text(stringResource(R.string.start)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { 
                        isStartFocused = it.isFocused
                        if (!it.isFocused) {
                            startInput = formatTimestamp(active?.startMs ?: 0L)
                        }
                    }
                    .clearFocusOnKeyboardDismiss(),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
            )
            OutlinedTextField(
                value = endInput,
                onValueChange = { 
                    endInput = it
                    parseTimestampToMs(it)?.let { typed ->
                        // Past the end it's the end: "25" on a 19-second video was kept as 0:25.
                        val ms = realDurationMs?.let { d -> typed.coerceAtMost(d) } ?: typed
                        if (ms >= (active?.startMs ?: 0L)) {
                            updateActive { segment -> segment.copy(endMs = ms) }
                        }
                    }
                },
                label = { Text(stringResource(R.string.end)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { 
                        isEndFocused = it.isFocused
                        if (!it.isFocused) {
                            endInput = formatTimestamp(active?.endMs ?: 0L)
                        }
                    }
                    .clearFocusOnKeyboardDismiss(),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }


        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Hidden rather than merely disabled on the last segment: the export always needs at
            // least one valid time boundary, so there's never a case where deleting it is right.
            if (segments.size > 1) {
                FilledTonalIconButton(
                    onClick = {
                        val target = active ?: return@FilledTonalIconButton
                        val remaining = segments.filterNot { it.id == target.id }
                        onSegmentsChange(remaining)
                        val next = remaining.firstOrNull()
                        activeId = next?.id
                        playheadMs = next?.startMs ?: 0L
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_segment))
                }
            }
            ChipRow(modifier = Modifier.weight(1f)) {
                segments.forEachIndexed { index, segment ->
                    PreviewChip(
                        label = stringResource(R.string.cut_x, index + 1),
                        selected = segment.id == activeId,
                        onClick = {
                            activeId = segment.id
                            playheadMs = segment.startMs
                        },
                    )
                }
                PreviewChip(
                    label = stringResource(R.string.add_a_segment),
                    onClick = {
                        // Clamped against the real duration once it's known — repeatedly tapping
                        // this used to keep stacking 30s blocks past the actual end of the video
                        // with nothing to stop it (see maxSliderMs's own doc comment above for the
                        // consequence once that reaches _LocalTrimPP).
                        val rawStart = segments.maxOfOrNull { it.endMs } ?: 0L
                        val start = realDurationMs?.let { d -> rawStart.coerceIn(0L, (d - 1000L).coerceAtLeast(0L)) }
                            ?: rawStart
                        val end = realDurationMs?.let { d -> (start + DEFAULT_SEGMENT_LENGTH_MS).coerceAtMost(d) }
                            ?: (start + DEFAULT_SEGMENT_LENGTH_MS)
                        val segment = TrimSegment(startMs = start, endMs = end)
                        onSegmentsChange(segments + segment)
                        activeId = segment.id
                        playheadMs = segment.startMs
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConfirmCancelSplitButton(
                label = stringResource(R.string.done),
                icon = Icons.Outlined.Check,
                onConfirm = onDone,
                onCancel = onCancel,
            )
        }
    }
    }
}

@Composable
internal fun FilenameTemplatesScreen(
    current: String,
    onApply: (String) -> Unit,
    onViewTemplates: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetHeader(
            icon = Icons.Outlined.Tag,
            title = stringResource(R.string.filename_templates),
            subtitle = stringResource(R.string.filename_templates_subtitle),
            modifier = Modifier.padding(top = 8.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                current,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        val onAddTemplate = {
            val trimmed = input.trim()
            when {
                trimmed.isEmpty() -> message = context.getString(R.string.enter_a_template_first)
                !GalleryDlPreferences.addFilenameTemplate(context, trimmed) ->
                    message = context.getString(R.string.that_template_is_already_saved)
                else -> {
                    onApply(trimmed)
                    input = ""
                    message = context.getString(R.string.template_saved_and_applied)
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; message = null },
            modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
            label = { Text(stringResource(R.string.add_filename_template)) },
            leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            supportingText = message?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onAddTemplate() }),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(label = stringResource(R.string.view_templates), onClick = onViewTemplates)
            PreviewChip(
                label = stringResource(R.string.add),
                onClick = onAddTemplate,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConfirmCancelSplitButton(
                label = stringResource(R.string.done),
                icon = Icons.Outlined.Check,
                onConfirm = onDone,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
internal fun ViewTemplatesScreen(
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var templates by remember { mutableStateOf(GalleryDlPreferences.getFilenameTemplates(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetHeader(
            icon = Icons.Outlined.Bookmarks,
            title = stringResource(R.string.saved_templates),
            subtitle = stringResource(R.string.saved_templates_subtitle),
            modifier = Modifier.padding(top = 8.dp),
            leading = {
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )

        if (templates.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    stringResource(R.string.no_saved_templates_yet_add_one),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                templates.forEach { template ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { onPick(template) },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                template,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    GalleryDlPreferences.removeFilenameTemplate(context, template)
                                    templates = GalleryDlPreferences.getFilenameTemplates(context)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.delete_template),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
