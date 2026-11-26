@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

// The preview sheet's overlay host: the sub-screens (commands, trim, templates) shown over the sheet.
package com.comfort.app.ui.main

import com.comfort.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.comfort.app.data.AudioFormat
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PreviewSheetOverlayHost(
    screen: PreviewScreen,
    isDuplicate: Boolean,
    onScreenChange: (PreviewScreen) -> Unit,
    onOpenOverlay: (PreviewScreen) -> Unit,
    onRevertOverlay: () -> Unit,
    onCommitOverlay: () -> Unit,
    // A back swipe's progress (0 to 1): the panel shrinks toward the bottom with it.
    backProgress: () -> Float = { 0f },
    url: String,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
    previewTitle: String?,
    previewUploader: String?,
    previewThumbnail: String?,
    previewStreamUrls: List<String>,
    previewDurationMs: Long?,
    previewFilesize: Long?,
    previewLoading: Boolean,
    previewStatus: String?,
    song: SongPreviewState,
    quality: VideoQuality,
    onQualityChange: (VideoQuality) -> Unit,
    outputFormat: OutputFormat,
    onFormatChange: (OutputFormat) -> Unit,
    audioFormat: AudioFormat,
    onAudioFormatChange: (AudioFormat) -> Unit,
    audioTracks: List<com.comfort.app.data.AudioTrack>,
    audioTrack: com.comfort.app.data.AudioTrack?,
    onAudioLanguageChange: (com.comfort.app.data.AudioTrack) -> Unit,
    subtitles: SubtitleChoice?,
    onSubtitlesChange: (List<com.comfort.app.data.SubtitleTrack>) -> Unit,
    saveThumbnail: Boolean,
    onToggleSaveThumbnail: () -> Unit,
    incognito: Boolean,
    onToggleIncognito: () -> Unit,
    engineOptions: List<com.comfort.app.data.DownloadEngine>,
    engineOverride: com.comfort.app.data.DownloadEngine?,
    onEngineChange: (com.comfort.app.data.DownloadEngine?) -> Unit,
    segments: List<TrimSegment>,
    onSegmentsChange: (List<TrimSegment>) -> Unit,
    segmentsEdited: Boolean,
    onSegmentsEdited: () -> Unit,
    commands: List<String>,
    onCommandsChange: (List<String>) -> Unit,
    filenameTemplate: String?,
    onFilenameTemplateChange: (String?) -> Unit,
    itemFilter: String?,
    selectedTrackCount: Int,
    onDismiss: () -> Unit,
    onDownload: (DownloadOptions) -> Unit,
    // Null hides Subscribe: the link isn't a YouTube channel or playlist.
    onSubscribe: ((com.comfort.app.data.SubscriptionOptions) -> Unit)?,
) {
    // Trim isn't drawn in this panel but in a sheet of its own (below), so it can be as tall as its
    // video needs without the main sheet growing behind it.
    val overlayOpen = screen != PreviewScreen.MAIN && screen != PreviewScreen.TRIM

    // "Start at…": the time this download waits for, if one was picked (StartAtDialog, shared
    // with the Queue's cards). Only this sheet's: a new opening starts without one.
    var startAt by remember { mutableStateOf<Long?>(null) }
    // Dropped once it's passed: the button kept saying "Starts at 3:00 PM" at 3:07, for a
    // download that would start at once (a time gone by isn't sent, see currentOptions).
    LaunchedEffect(startAt) {
        val at = startAt ?: return@LaunchedEffect
        kotlinx.coroutines.delay((at - System.currentTimeMillis()).coerceAtLeast(0L))
        startAt = null
    }
    var showStartAt by remember { mutableStateOf(false) }
    if (showStartAt) {
        StartAtDialog(
            initial = startAt,
            onDismiss = { showStartAt = false },
            onPicked = { at -> startAt = at; showStartAt = false },
            onRemove = if (startAt != null) ({ startAt = null; showStartAt = false }) else null,
        )
    }

    // Live drag offset for the overlay panel's own handle — follows the finger while dragging
    // down (like the outer sheet's own handle does), snapped back to 0 whenever the overlay opens
    // fresh for a different screen.
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    LaunchedEffect(overlayOpen) { if (overlayOpen) dragOffsetPx = 0f }

    val density = androidx.compose.ui.platform.LocalDensity.current
    // How far the overlay panel has to be pulled down to close (it was 48dp: a nudge closed it).
    val thresholdPx = remember(density) { with(density) { 140.dp.toPx() } }
    val nestedScrollConnection = remember(scope, onRevertOverlay, thresholdPx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < 0 && dragOffsetPx > 0) {
                    val old = dragOffsetPx
                    dragOffsetPx = (dragOffsetPx + available.y).coerceAtLeast(0f)
                    return androidx.compose.ui.geometry.Offset(0f, available.y - (dragOffsetPx - old))
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (available.y > 0) {
                    dragOffsetPx += available.y
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (dragOffsetPx > 0f) {
                    if ((available.y > 4000f && dragOffsetPx > thresholdPx / 3) || dragOffsetPx > thresholdPx) {
                        onRevertOverlay()
                    } else {
                        scope.launch {
                            androidx.compose.animation.core.animate(dragOffsetPx, 0f) { value, _ -> dragOffsetPx = value }
                        }
                    }
                    return available
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    // What Download adds — the Commands screen shows the command for this same value, so the
    // preview can't differ from the download.
    fun currentOptions() = DownloadOptions(
        // Spotify ignores quality entirely, but music.youtube.com/soundcloud.com
        // go through the normal yt-dlp/gallery-dl path and would otherwise
        // download video despite the sheet showing song styling — !showQualityRow
        // means the song-ness came from the URL itself, not just a manual "Audio"
        // pick, so this is the one case that needs forcing rather than trusting
        // whatever quality happens to be selected.
        quality = if (!song.showQualityRow) VideoQuality.AUDIO_ONLY else quality,
        outputFormat = outputFormat,
        saveThumbnail = saveThumbnail,
        incognito = incognito,
        extraCommands = commands.joinToString(" ").takeIf { it.isNotBlank() },
        clipRange = if (segmentsEdited) segments.toClipRange(previewDurationMs) else null,
        filenameTemplate = filenameTemplate?.takeIf { it.isNotBlank() },
        itemFilter = itemFilter,
        totalItems = if (itemFilter != null) selectedTrackCount else 0,
        // Only SONG_SINGLE has editable title/artist at all (SONG_LIST's own
        // per-track rows aren't individually editable, and VIDEO never shows
        // these fields) — null here means "use the source's own title/artist",
        // same as if this sheet had never had an editable field to begin with.
        overrideTitle = song.editedTitle?.takeIf { song.mode == PreviewMode.SONG_SINGLE && it.isNotBlank() },
        overrideArtist = song.editedArtist?.takeIf { song.mode == PreviewMode.SONG_SINGLE && it.isNotBlank() },
        engine = engineOverride,
        audioFormat = audioFormat,
        audioLanguage = audioTrack?.let { if (it.original) com.comfort.app.data.AudioLanguage.ORIGINAL else it.code },
        // What the chip shows is what downloads; no chip (no subtitles listed) follows Settings.
        subtitleLanguages = subtitles?.selected?.joinToString(",") { it.code },
        // A time that has passed while the sheet was open just starts it as usual.
        scheduledAt = startAt?.takeIf { it > System.currentTimeMillis() },
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        MainPreviewScreen(
            url = url,
            isDuplicate = isDuplicate,
            title = previewTitle,
            uploader = previewUploader,
            thumbnail = previewThumbnail,
            filesizeBytes = previewFilesize,
            loading = previewLoading,
            loadingStatus = previewStatus,
            song = song,
            quality = quality,
            onQualityChange = onQualityChange,
            outputFormat = outputFormat,
            onFormatChange = onFormatChange,
            audioFormat = audioFormat,
            onAudioFormatChange = onAudioFormatChange,
            audioTracks = audioTracks,
            audioTrack = audioTrack,
            onAudioLanguageChange = onAudioLanguageChange,
            subtitles = subtitles,
            onSubtitlesChange = onSubtitlesChange,
            saveThumbnail = saveThumbnail,
            onToggleSaveThumbnail = onToggleSaveThumbnail,
            incognito = incognito,
            onToggleIncognito = onToggleIncognito,
            engineOptions = engineOptions,
            engine = engineOverride,
            onEngineChange = onEngineChange,
            trimmed = segmentsEdited,
            commandCount = commands.size,
            filenameTemplate = filenameTemplate,
            onCopyLink = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(context.getString(R.string.link), url)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCancel = onDismiss,
            onOpenCommands = { onOpenOverlay(PreviewScreen.COMMANDS) },
            onOpenTrim = {
                // Snapshot first — via onOpenOverlay — so a revert (scrim/Back/drag/Cancel without
                // Done) restores the empty state this default segment is about to replace, instead
                // of leaving it behind as a silent, never-confirmed 30-second clip.
                onOpenOverlay(PreviewScreen.TRIM)
                if (segments.isEmpty()) {
                    // No longer than the video: a 19-second one showed "End 00:30.000".
                    val end = previewDurationMs?.takeIf { it > 0L }?.coerceAtMost(DEFAULT_SEGMENT_LENGTH_MS)
                        ?: DEFAULT_SEGMENT_LENGTH_MS
                    onSegmentsChange(listOf(TrimSegment(startMs = 0L, endMs = end)))
                }
            },
            onOpenTemplates = { onOpenOverlay(PreviewScreen.TEMPLATES) },
            onDownload = { onDownload(currentOptions()) },
            startAt = startAt,
            onOpenStartAt = { showStartAt = true },
            // The choices that make sense for every future upload: not the trim, the item
            // selection or an edited title, which belong to this listing only.
            onSubscribe = onSubscribe?.let { subscribe ->
                {
                    subscribe(
                        com.comfort.app.data.SubscriptionOptions(
                            // Same forcing as the download above for a known song host.
                            videoQuality = if (!song.showQualityRow) VideoQuality.AUDIO_ONLY else quality,
                            outputFormat = outputFormat,
                            audioFormat = audioFormat,
                            saveThumbnail = saveThumbnail,
                            extraCommands = commands.joinToString(" ").takeIf { it.isNotBlank() },
                            filenameTemplate = filenameTemplate?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            },
        )

        // Scrim: dims MAIN behind the overlay panel and, tapped, backs out of the overlay the
        // same way Cancel on each sub-screen does (discarding whatever that sub-screen hadn't
        // committed yet) rather than closing the whole sheet.
        AnimatedVisibility(
            visible = overlayOpen,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onRevertOverlay() },
            )
        }

        // The overlay panel itself: pops up from the bottom and sits on top of MAIN, rather
        // than replacing it — Commands/Trim/Templates/View Templates all render inside this
        // one panel, swapping via a plain Crossfade so switching between them (e.g. Templates
        // -> View Templates) doesn't re-trigger the slide-up entrance.
        //
        // matchParentSize(), not align(Alignment.BottomCenter) — same reasoning as the scrim
        // right above using it: align() still lets this child's own measured height count toward
        // the outer Box's own intrinsic size, so a taller overlay (Trim, with its video preview
        // + slider + segment chips, easily taller than MAIN's own content) instantly grew the
        // *whole* ModalBottomSheet the moment it opened — a hard snap with nothing to animate it,
        // on top of a shorter overlay doing the reverse on the way back to MAIN (reported live:
        // "looks cut off when there's another overlay"). matchParentSize() is measured only after
        // the Box's size is already settled from MAIN alone, so the sheet's overall height now
        // stays constant across every screen; the inner Box below aligns the actual panel to the
        // bottom of that fixed area the same way the outer align() used to.
        AnimatedVisibility(
            visible = overlayOpen,
            enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(300)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(nestedScrollConnection)
                    // Predictive back: smaller toward its bottom edge as the swipe goes on.
                    .graphicsLayer {
                        val p = backProgress()
                        scaleX = 1f - 0.1f * p
                        scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    // Moves the whole panel down with the finger as dragOffsetPx tracks the drag
                    // below — this is what actually makes the drag look like it's moving the
                    // sheet, rather than just quietly counting distance toward a threshold.
                    .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
                    // A drag anywhere on the panel — not just the handle — needs to be caught here
                    // and not just on the handle: reproduced live, dragging down from elsewhere on
                    // the panel fell straight through to the outer ModalBottomSheet's own
                    // swipe-to-dismiss beneath it, same bug as the handle-only version of this fix.
                    // Being on the Surface (an ancestor of the panel's own verticalScroll content)
                    // means an inner scrollable still gets first claim on the drag while it has
                    // room to scroll — this only takes over once a scroll is already at its bound
                    // (or there's no scrollable under the finger at all), which is also normal
                    // "drag past the top to dismiss" behavior for a sheet like this.
                    .pointerInput(onRevertOverlay) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtLeast(0f)
                            },
                            onDragEnd = {
                                if (dragOffsetPx > thresholdPx) {
                                    onRevertOverlay()
                                } else {
                                    dragScope.launch {
                                        animate(dragOffsetPx, 0f) { value, _ -> dragOffsetPx = value }
                                    }
                                }
                            },
                        )
                    },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                // navigationBarsPadding() grows this Column (and so the Surface wrapping it,
                // which sizes to its content) by the nav-bar's own height — without it the panel
                // stops flush with its last row of content, well short of the true screen edge,
                // instead of bleeding its own surfaceContainerHigh color behind the bar the way
                // MAIN's sheet background does.
                Column(modifier = Modifier.navigationBarsPadding()) {
                    // A drag-handle-style bar, matching the outer sheet's own, so the panel reads
                    // as "another sheet" rather than an inline section of the first. Purely visual
                    // now — the drag handling above lives on the whole Surface.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 32.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                    }

                    Box(modifier = Modifier.heightIn(max = 520.dp)) {
                        Crossfade(targetState = screen, label = "overlayScreen") { current ->
                            when (current) {
                                PreviewScreen.COMMANDS -> ExtraCommandsScreen(
                                    url = url,
                                    // The same options Download would add, so the command shown
                                    // is the one the download runs.
                                    options = currentOptions(),
                                    commands = commands,
                                    onCommandsChange = onCommandsChange,
                                    onCopy = { fullCmd ->
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(context.getString(R.string.current_command), fullCmd)))
                                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                                Toast.makeText(context, context.getString(R.string.command_copied), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onCancel = onRevertOverlay,
                                    onDone = onCommitOverlay,
                                )
                                PreviewScreen.TRIM -> {}

                                PreviewScreen.TEMPLATES -> FilenameTemplatesScreen(
                                    current = filenameTemplate ?: GalleryDlPreferences.getFilenameFormat(context),
                                    onApply = onFilenameTemplateChange,
                                    onViewTemplates = { onScreenChange(PreviewScreen.VIEW_TEMPLATES) },
                                    onCancel = onRevertOverlay,
                                    onDone = onCommitOverlay,
                                )

                                PreviewScreen.VIEW_TEMPLATES -> ViewTemplatesScreen(
                                    onPick = {
                                        onFilenameTemplateChange(it)
                                        onScreenChange(PreviewScreen.TEMPLATES)
                                    },
                                    onBack = { onScreenChange(PreviewScreen.TEMPLATES) },
                                )

                                PreviewScreen.MAIN -> {}
                            }
                        }
                    }
                }
            }
            }
        }

        // Trim, a sheet of its own over the main one: as tall as its video needs (a portrait video
        // nearly the whole screen) while the main sheet stays as it is. Swiping it down or Back
        // (with its own predictive shrink) leaves it as Cancel does.
        if (screen == PreviewScreen.TRIM) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = onRevertOverlay,
                sheetState = rememberSturdySheetState(),
                // Never under the status bar, as the main sheet (a tall video's opened it up there).
                modifier = Modifier.statusBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
            TrimVideoScreen(
                segments = segments,
                onSegmentsChange = {
                    // The only place segments actually change once TrimVideoScreen
                    // is showing — marking edited here (rather than in
                    // onSegmentsChange itself) leaves onOpenTrim's initial default-
                    // segment seed, which runs before this screen ever composes,
                    // correctly NOT counted as a real edit.
                    onSegmentsEdited()
                    onSegmentsChange(it)
                },
                thumbnail = previewThumbnail,
                pageUrl = url,
                streamUrls = previewStreamUrls,
                durationMs = previewDurationMs,
                onCancel = onRevertOverlay,
                onDone = onCommitOverlay,
                            )
                }
            }
        }
    }
}
