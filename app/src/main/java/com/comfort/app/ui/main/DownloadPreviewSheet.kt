@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.comfort.app.ui.main

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.util.GalleryDlListing
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import java.util.UUID

/** One cut of the source video. Several of these download as one concatenated file — see
 * yt_dlp_wrapper.py's own _parse_clip_range, which feeds them all to yt-dlp's download_ranges. */
data class TrimSegment(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long = 0L,
    val endMs: Long = DEFAULT_SEGMENT_LENGTH_MS,
)

/** Stands in for the real media duration, which isn't known before the download: the listing pass
 * returns a title and thumbnail but no duration, and resolving one would need a whole extra
 * extraction pass per link (5-60s, measured live on this device). The trim screen therefore works
 * against a nominal timeline the user scrubs freely — yt-dlp clamps anything past the real end
 * itself when the download actually runs. */
private const val NOMINAL_DURATION_MS = 10 * 60 * 1000L
private const val DEFAULT_SEGMENT_LENGTH_MS = 30 * 1000L

/** Which of the sheet's screens is showing. MAIN is always composed; every other value renders as
 * a panel that slides up from the bottom and overlays MAIN in place, rather than a second real
 * ModalBottomSheet: a popup inside a popup gets dismissed *along with* its parent on tap in this
 * Compose version (reproduced live earlier with a DropdownMenu inside a ModalBottomSheet, which
 * closed both), so nesting real sheets here would reintroduce exactly that bug. The overlay panel
 * is a plain Surface animated with slideInVertically/slideOutVertically instead. */
private enum class PreviewScreen { MAIN, COMMANDS, TRIM, TEMPLATES, VIEW_TEMPLATES }

/** Snapshot of everything an overlay sub-screen (Commands/Trim/Templates) can touch, taken the
 * moment MAIN opens one — restored verbatim by [PreviewScreen] back-out paths that aren't Done
 * (scrim tap, hardware Back, drag-to-dismiss, Cancel), so backing out of a sub-screen is always a
 * true no-op instead of each path hand-rolling its own (previously inconsistent, sometimes wrong)
 * idea of what "discard" means. */
private data class OverlaySnapshot(
    val segments: List<TrimSegment>,
    val commands: List<String>,
    val filenameTemplate: String?,
)

/** Everything the sheet collects, handed back to the caller by [onDownload] when the user commits.
 * Every field is a per-download override — null/blank means "whatever Settings says at download
 * time", which is what a download did before this sheet existed. */
data class DownloadOptions(
    val quality: VideoQuality,
    val outputFormat: OutputFormat,
    val saveThumbnail: Boolean,
    val extraCommands: String?,
    val clipRange: String?,
    val filenameTemplate: String?,
)

/** Same spoofed User-Agent/Referer the queue and share picker already use for remote preview
 * thumbnails — many sites (Instagram among them) reject a hotlinked image request without them,
 * which shows up as a silently blank image area rather than any kind of error. */
@Composable
private fun thumbnailRequest(thumbnail: String, pageUrl: String): ImageRequest =
    ImageRequest.Builder(LocalContext.current)
        .data(thumbnail)
        .addHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        )
        .addHeader("Referer", pageUrl)
        .build()

private fun parseTimestampToMs(value: String): Long? {
    val v = value.trim()
    if (v.isEmpty()) return null
    return try {
        val parts = v.split(":").map { it.toFloatOrNull() ?: 0f }
        var seconds = 0f
        for (part in parts) {
            seconds = seconds * 60 + part
        }
        (seconds * 1000).toLong()
    } catch (e: Exception) {
        null
    }
}
private fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}

/** The "start-end" (or comma-separated multi-range) string DownloadWorker hands to
 * yt_dlp_wrapper.py. Null when the segments cover everything from zero, i.e. nothing to trim. */
private fun List<TrimSegment>.toClipRange(): String? {
    if (isEmpty()) return null
    return joinToString(",") { "${formatTimestamp(it.startMs)}-${formatTimestamp(it.endMs)}" }
}

/**
 * The download preview sheet: shown after a link is submitted and before the download starts, so
 * the user can set quality/format/trim/commands/filename for this one download instead of only
 * through the global Settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadPreviewSheet(
    url: String,
    onDismiss: () -> Unit,
    onDownload: (DownloadOptions) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var screen by remember { mutableStateOf(PreviewScreen.MAIN) }

    // Seeded from the global defaults, then only ever changed by this sheet's own controls — a
    // per-download override, never a write back to the global Settings value.
    var quality by remember { mutableStateOf(GalleryDlPreferences.getVideoQuality(context)) }
    var outputFormat by remember { mutableStateOf(GalleryDlPreferences.getOutputFormat(context)) }
    var saveThumbnail by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf<List<String>>(emptyList()) }
    var segments by remember { mutableStateOf<List<TrimSegment>>(emptyList()) }
    var filenameTemplate by remember { mutableStateOf<String?>(null) }

    // Taken once, the moment MAIN opens a sub-screen (openOverlay below) — null again means
    // there's nothing open right now (or the last open/close cycle already resolved). Every
    // non-Done way out of a sub-screen restores from this instead of each one independently
    // deciding what "discard" means, which is what let scrim/Back/drag silently keep edits that
    // Cancel discarded (and vice versa for a value that predated this visit, which Cancel used to
    // wipe out to empty/null instead of restoring).
    var overlaySnapshot by remember { mutableStateOf<OverlaySnapshot?>(null) }

    // Opens a sub-screen from MAIN, snapshotting current values first — must run before any
    // caller-side seeding (like Trim's default segment below) so the snapshot reflects what was
    // true before this visit, not after.
    fun openOverlay(target: PreviewScreen) {
        if (overlaySnapshot == null) {
            overlaySnapshot = OverlaySnapshot(segments, commands, filenameTemplate)
        }
        screen = target
    }

    // Done on any sub-screen: whatever's live right now becomes the real value, snapshot forgotten.
    fun commitOverlay() {
        overlaySnapshot = null
        screen = PreviewScreen.MAIN
    }

    // Every other way out of a sub-screen (Cancel, scrim tap, hardware Back, drag-to-dismiss):
    // restores exactly what openOverlay captured, discarding this visit's edits regardless of
    // which of those four paths triggered it.
    fun revertOverlay() {
        overlaySnapshot?.let {
            segments = it.segments
            commands = it.commands
            filenameTemplate = it.filenameTemplate
        }
        overlaySnapshot = null
        screen = PreviewScreen.MAIN
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // A swipe-to-hide drag normally commits straight to Hidden. With an overlay panel open,
        // reject that commit and back out to MAIN instead — the sheet snaps back to expanded the
        // same way it would if the user hadn't dragged far enough, rather than visibly collapsing
        // and then being forced back open.
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && screen != PreviewScreen.MAIN) {
                revertOverlay()
                false
            } else {
                true
            }
        },
    )

    // Title/thumbnail for the preview card. The listing pass is the same one the share picker
    // already uses, so this costs nothing new on the engine side.
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewUploader by remember { mutableStateOf<String?>(null) }
    var previewThumbnail by remember { mutableStateOf<String?>(null) }
    var previewFilesize by remember { mutableStateOf<Long?>(null) }
    val previewStreamUrlsState = remember { mutableStateOf<List<String>>(emptyList()) }
    val previewDurationMsState = remember { mutableStateOf<Long?>(null) }
    var previewLoading by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        previewLoading = true
        val info = GalleryDlListing.fetchPreviewInfo(context, url)
        previewTitle = info?.title
        previewUploader = info?.uploader
        previewThumbnail = info?.thumbnail
        previewFilesize = info?.filesizeBytes
        previewStreamUrlsState.value = info?.streamUrls ?: emptyList()
        previewDurationMsState.value = info?.durationMs
        previewLoading = false
    }

    // Back returns to the main screen from a sub-screen (reversing the slide) rather than closing
    // the whole sheet, matching the forward navigation — and, like every other non-Done way out,
    // discards whatever this visit changed rather than leaving it silently applied.
    BackHandler(enabled = screen != PreviewScreen.MAIN) { revertOverlay() }

    ModalBottomSheet(
        // Tapping the scrim outside the sheet's own bounds fires this directly (it doesn't go
        // through sheetState/confirmValueChange above, which only guards drag-to-hide). With an
        // overlay panel open this should back out to MAIN first, same as Back and the overlay's
        // own scrim — only a tap with nothing open should actually dismiss the sheet.
        onDismissRequest = { if (screen != PreviewScreen.MAIN) revertOverlay() else onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // The default (BottomSheetDefaults.windowInsets) pads our whole content lambda above the
        // navigation bar *before* it ever reaches us — which is exactly how MAIN gets to bleed
        // behind the bar for free (the sheet's own Surface still extends the rest of the way, we
        // just never draw there). Our overlay panel is a second, differently-colored Surface drawn
        // *inside* that already-inset content, though, so it can't reach that reserved strip at
        // all — it would stop short with a visible seam above the bar instead of bleeding through
        // it in its own color. Disabling the default here and applying navigationBarsPadding()
        // ourselves (below, in both MAIN and the overlay) lets each one grow into that space with
        // its own background instead of only the sheet's base color showing through it.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // Broken out into its own (non-extension) composable so the AnimatedVisibility calls below
        // aren't lexically inside ModalBottomSheet's ColumnScope receiver — with that receiver in
        // scope, Kotlin resolves the plain top-level AnimatedVisibility to Compose's ColumnScope-
        // extension overload instead and refuses to call it without an explicit receiver.
        PreviewSheetOverlayHost(
            screen = screen,
            onScreenChange = { screen = it },
            onOpenOverlay = ::openOverlay,
            onRevertOverlay = ::revertOverlay,
            onCommitOverlay = ::commitOverlay,
            url = url,
            context = context,
            scope = scope,
            clipboard = clipboard,
            previewTitle = previewTitle,
            previewUploader = previewUploader,
            previewThumbnail = previewThumbnail,
            previewStreamUrls = previewStreamUrlsState.value,
            previewDurationMs = previewDurationMsState.value,
            previewFilesize = previewFilesize,
            previewLoading = previewLoading,
            quality = quality,
            onQualityChange = { quality = it },
            outputFormat = outputFormat,
            onToggleFormat = {
                outputFormat = if (outputFormat == OutputFormat.MP4) OutputFormat.MKV else OutputFormat.MP4
            },
            saveThumbnail = saveThumbnail,
            onToggleSaveThumbnail = { saveThumbnail = !saveThumbnail },
            segments = segments,
            onSegmentsChange = { segments = it },
            commands = commands,
            onCommandsChange = { commands = it },
            filenameTemplate = filenameTemplate,
            onFilenameTemplateChange = { filenameTemplate = it },
            onDismiss = onDismiss,
            onDownload = onDownload,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewSheetOverlayHost(
    screen: PreviewScreen,
    onScreenChange: (PreviewScreen) -> Unit,
    onOpenOverlay: (PreviewScreen) -> Unit,
    onRevertOverlay: () -> Unit,
    onCommitOverlay: () -> Unit,
    url: String,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    previewTitle: String?,
    previewUploader: String?,
    previewThumbnail: String?,
    previewStreamUrls: List<String>,
    previewDurationMs: Long?,
    previewFilesize: Long?,
    previewLoading: Boolean,
    quality: VideoQuality,
    onQualityChange: (VideoQuality) -> Unit,
    outputFormat: OutputFormat,
    onToggleFormat: () -> Unit,
    saveThumbnail: Boolean,
    onToggleSaveThumbnail: () -> Unit,
    segments: List<TrimSegment>,
    onSegmentsChange: (List<TrimSegment>) -> Unit,
    commands: List<String>,
    onCommandsChange: (List<String>) -> Unit,
    filenameTemplate: String?,
    onFilenameTemplateChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    onDownload: (DownloadOptions) -> Unit,
) {
    val overlayOpen = screen != PreviewScreen.MAIN

    // Live drag offset for the overlay panel's own handle — follows the finger while dragging
    // down (like the outer sheet's own handle does), snapped back to 0 whenever the overlay opens
    // fresh for a different screen.
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    LaunchedEffect(overlayOpen) { if (overlayOpen) dragOffsetPx = 0f }

    Box(modifier = Modifier.fillMaxWidth()) {
        MainPreviewScreen(
            url = url,
            title = previewTitle,
            uploader = previewUploader,
            thumbnail = previewThumbnail,
            filesizeBytes = previewFilesize,
            loading = previewLoading,
            quality = quality,
            onQualityChange = onQualityChange,
            outputFormat = outputFormat,
            onToggleFormat = onToggleFormat,
            saveThumbnail = saveThumbnail,
            onToggleSaveThumbnail = onToggleSaveThumbnail,
            trimmed = segments.isNotEmpty(),
            commandCount = commands.size,
            filenameTemplate = filenameTemplate,
            onCopyLink = { clipboard.setText(AnnotatedString(url)) },
            onCancel = onDismiss,
            onOpenCommands = { onOpenOverlay(PreviewScreen.COMMANDS) },
            onOpenTrim = {
                // Snapshot first — via onOpenOverlay — so a revert (scrim/Back/drag/Cancel without
                // Done) restores the empty state this default segment is about to replace, instead
                // of leaving it behind as a silent, never-confirmed 30-second clip.
                onOpenOverlay(PreviewScreen.TRIM)
                if (segments.isEmpty()) {
                    onSegmentsChange(listOf(TrimSegment(startMs = 0L, endMs = DEFAULT_SEGMENT_LENGTH_MS)))
                }
            },
            onOpenTemplates = { onOpenOverlay(PreviewScreen.TEMPLATES) },
            onDownload = {
                scope.launch {
                    onDownload(
                        DownloadOptions(
                            quality = quality,
                            outputFormat = outputFormat,
                            saveThumbnail = saveThumbnail,
                            extraCommands = commands.joinToString(" ").takeIf { it.isNotBlank() },
                            clipRange = segments.toClipRange(),
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
        AnimatedVisibility(
            visible = overlayOpen,
            enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
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
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtLeast(0f)
                            },
                            onDragEnd = {
                                if (dragOffsetPx > 48.dp.toPx()) {
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
                                    quality = quality,
                                    outputFormat = outputFormat,
                                    saveThumbnail = saveThumbnail,
                                    segments = segments,
                                    filenameTemplate = filenameTemplate,
                                    commands = commands,
                                    onCommandsChange = onCommandsChange,
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(buildPreviewCommand(
                                            context = context,
                                            url = url,
                                            quality = quality,
                                            outputFormat = outputFormat,
                                            saveThumbnail = saveThumbnail,
                                            segments = segments,
                                            filenameTemplate = filenameTemplate,
                                            extraCommands = commands,
                                        )))
                                    },
                                    onCancel = onRevertOverlay,
                                    onDone = onCommitOverlay,
                                )
                                PreviewScreen.TRIM -> TrimVideoScreen(
                                    segments = segments,
                                    onSegmentsChange = onSegmentsChange,
                                    thumbnail = previewThumbnail,
                                    pageUrl = url,
                                    streamUrls = previewStreamUrls,
                                    durationMs = previewDurationMs,
                                    onCancel = onRevertOverlay,
                                    onDone = onCommitOverlay,
                                )

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
}

/** A chip row that scrolls horizontally rather than wrapping, per the sheet's own spec. */
@Composable
private fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** [icon] is the chip's own resting icon (what it *is*); the selected state replaces it with a
 * check, per the M3 chip spec, so a selected chip still reads as selected at a glance. */
@Composable
private fun PreviewChip(
    label: String,
    selected: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.CircleShape,
    onClick: () -> Unit,
) {
    val leading = when {
        selected -> FeatherIcons.Check
        else -> icon
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(31.dp),
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
                        borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp
        ),
        // 8dp rather than the pill the theme's shape scale would otherwise give a chip — the
        // sheet's own spec calls for squarer chips than the fully-rounded buttons around them.
        shape = shape,
        leadingIcon = leading?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(15.dp)) }
        },
    )
}

/** The sheet's own short quality labels ("1080", not the Settings screen's "1080p") — the chip row
 * has to fit five of them across a phone, so it uses tighter text than the full-width Settings
 * picker does for the same enum. */
private val VideoQuality.chipLabel: String
    get() = when (this) {
        VideoQuality.BEST -> "Best Available"
        VideoQuality.P1080 -> "1080"
        VideoQuality.P720 -> "720"
        VideoQuality.P480 -> "480"
        VideoQuality.AUDIO_ONLY -> "Audio"
    }

/** Bytes as the compact size the preview card shows ("10mb"). */
private fun formatFilesize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1fgb".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)}mb"
    bytes >= 1024L -> "${bytes / 1024L}kb"
    else -> "${bytes}b"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainPreviewScreen(
    url: String,
    title: String?,
    uploader: String?,
    thumbnail: String?,
    filesizeBytes: Long?,
    loading: Boolean,
    quality: VideoQuality,
    onQualityChange: (VideoQuality) -> Unit,
    outputFormat: OutputFormat,
    onToggleFormat: () -> Unit,
    saveThumbnail: Boolean,
    onToggleSaveThumbnail: () -> Unit,
    trimmed: Boolean,
    commandCount: Int,
    filenameTemplate: String?,
    onCopyLink: () -> Unit,
    onCancel: () -> Unit,
    onOpenCommands: () -> Unit,
    onOpenTrim: () -> Unit,
    onOpenTemplates: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
            // contentWindowInsets = 0 on the sheet means nothing pads this above the nav bar for
            // us anymore — this keeps the Download button clear of it while the sheet's own
            // background (now free to size past this Column) still bleeds behind the bar.
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onCopyLink, modifier = Modifier.size(48.dp)) {
                Icon(FeatherIcons.Copy, contentDescription = "Copy link")
            }
            FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(FeatherIcons.XCircle, contentDescription = "Cancel")
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val total = VideoQuality.entries.size
            VideoQuality.entries.forEachIndexed { index, option ->
                val segmentedShape = when (index) {
                    0 -> androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = androidx.compose.foundation.shape.CornerSize(50),
                        bottomStart = androidx.compose.foundation.shape.CornerSize(50),
                        topEnd = androidx.compose.foundation.shape.CornerSize(8.dp),
                        bottomEnd = androidx.compose.foundation.shape.CornerSize(8.dp)
                    )
                    total - 1 -> androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = androidx.compose.foundation.shape.CornerSize(8.dp),
                        bottomStart = androidx.compose.foundation.shape.CornerSize(8.dp),
                        topEnd = androidx.compose.foundation.shape.CornerSize(50),
                        bottomEnd = androidx.compose.foundation.shape.CornerSize(50)
                    )
                    else -> androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                }
                PreviewChip(
                    label = option.chipLabel,
                    selected = quality == option,
                    shape = segmentedShape,
                    onClick = { onQualityChange(option) },
                )
            }
        }

        // The preview card: an inset image box (not edge-to-edge — the card's own background
        // shows as a margin around it, matching the reference), then title/uploader below in the
        // same padded column. The loading indicator sits layered over the image box while the
        // listing pass is still resolving it.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnail != null) {
                        AsyncImage(
                            model = thumbnailRequest(thumbnail, url),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (!loading) {
                        Icon(
                            FeatherIcons.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    if (loading) {
                        ContainedLoadingIndicator()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    title ?: if (loading) "Loading…" else "Untitled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                // Uploader and size share a row so the size sits bottom-right of the card, level
                // with the uploader line, rather than adding a whole row of its own.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        uploader.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (filesizeBytes != null) {
                        Text(
                            formatFilesize(filesizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        val firstShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = androidx.compose.foundation.shape.CornerSize(50),
            bottomStart = androidx.compose.foundation.shape.CornerSize(50),
            topEnd = androidx.compose.foundation.shape.CornerSize(8.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(8.dp)
        )
        val middleShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        val lastShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = androidx.compose.foundation.shape.CornerSize(8.dp),
            bottomStart = androidx.compose.foundation.shape.CornerSize(8.dp),
            topEnd = androidx.compose.foundation.shape.CornerSize(50),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(50)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PreviewChip(
                    label = "Save thumbnail",
                    selected = saveThumbnail,
                    icon = FeatherIcons.Image,
                    shape = firstShape,
                    onClick = onToggleSaveThumbnail,
                )
                PreviewChip(
                    label = if (commandCount > 0) "Commands ($commandCount)" else "Add extra Commands",
                    selected = commandCount > 0,
                    icon = FeatherIcons.Terminal,
                    shape = lastShape,
                    onClick = onOpenCommands,
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PreviewChip(
                    label = "Trim Video",
                    selected = trimmed,
                    icon = FeatherIcons.Scissors,
                    shape = firstShape,
                    onClick = onOpenTrim,
                )
                PreviewChip(
                    label = outputFormat.name.lowercase().replaceFirstChar { it.uppercase() },
                    icon = FeatherIcons.Film,
                    shape = middleShape,
                    onClick = onToggleFormat,
                )
                PreviewChip(
                    label = "Filename Templates.",
                    selected = filenameTemplate != null,
                    icon = FeatherIcons.Tag,
                    shape = lastShape,
                    onClick = onOpenTemplates,
                )
            }
        }

        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Icon(FeatherIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Download")
        }
    }
}

/** Assembles a human-readable shell-style yt-dlp command from the current sheet state plus global
 *  preferences — mirrors what DownloadWorker actually passes to yt_dlp_wrapper.py. */
private fun buildPreviewCommand(
    context: android.content.Context,
    url: String,
    quality: VideoQuality,
    outputFormat: OutputFormat,
    saveThumbnail: Boolean,
    segments: List<TrimSegment>,
    filenameTemplate: String?,
    extraCommands: List<String>,
): String {
    val parts = mutableListOf("yt-dlp")

    // ── Quality ──────────────────────────────────────────────────────────────
    when (quality) {
        VideoQuality.AUDIO_ONLY -> parts += "-f bestaudio"
        VideoQuality.BEST      -> {} // yt-dlp default, no flag needed
        else -> {
            val h = quality.resolutionCap()
            parts += "-f \"bestvideo[height<=$h]+bestaudio\""
        }
    }

    // ── Output container ─────────────────────────────────────────────────────
    parts += "--merge-output-format ${outputFormat.extension}"

    // ── Thumbnail ─────────────────────────────────────────────────────────────
    if (GalleryDlPreferences.isEmbedThumbnail(context)) parts += "--embed-thumbnail"
    if (saveThumbnail) parts += "--write-thumbnail"

    // ── Metadata ──────────────────────────────────────────────────────────────
    if (GalleryDlPreferences.isEmbedMetadata(context)) parts += "--embed-metadata"
    if (GalleryDlPreferences.isWriteInfoFiles(context)) parts += "--write-info-json"

    // ── Subtitles ─────────────────────────────────────────────────────────────
    if (GalleryDlPreferences.isDownloadSubtitles(context)) {
        parts += "--write-subs"
        val langs = GalleryDlPreferences.getSubtitleLanguages(context)
        if (langs.isNotBlank()) parts += "--sub-langs $langs"
    }

    // ── Playlist ──────────────────────────────────────────────────────────────
    if (GalleryDlPreferences.isNoPlaylist(context)) parts += "--no-playlist"

    // ── Live streams ──────────────────────────────────────────────────────────
    if (GalleryDlPreferences.isLiveFromStart(context)) parts += "--live-from-start"

    // ── Rate / size limits ────────────────────────────────────────────────────
    val limitRate = GalleryDlPreferences.getSpeedLimit(context)
    if (limitRate.isNotBlank()) parts += "-r $limitRate"

    val maxFilesize = GalleryDlPreferences.getEffectiveMaxFilesize(context).orEmpty()
    if (maxFilesize.isNotBlank()) parts += "--max-filesize $maxFilesize"

    // ── Network ───────────────────────────────────────────────────────────────
    val retries = GalleryDlPreferences.getNetworkRetries(context)
    parts += "--retries $retries"

    // ── Proxy ─────────────────────────────────────────────────────────────────
    val proxy = GalleryDlPreferences.getProxyUrl(context)
    if (proxy.isNotBlank()) parts += "--proxy \"$proxy\""

    // ── Extractor args ────────────────────────────────────────────────────────
    val extractorArgs = GalleryDlPreferences.getExtractorArgs(context)
    if (extractorArgs.isNotBlank()) parts += "--extractor-args \"$extractorArgs\""

    // ── Trim ──────────────────────────────────────────────────────────────────
    // The actual download path doesn't go through this flag at all — DownloadOptions.clipRange
    // is built by the same toClipRange()/formatTimestamp() this reuses, then handed to
    // yt_dlp_wrapper.py's _parse_clip_range, which installs a download_ranges callable on
    // ydl_opts directly (yt-dlp's Python API), never a CLI arg. --download-sections is yt-dlp's
    // own CLI-equivalent of that same callable, so shown here it's still a command a user could
    // actually run to get the identical result — just reusing the real formatter now instead of a
    // separate H:MM:SS one that silently rounded away everything sub-second.
    segments.toClipRange()?.let { clip -> parts += "--download-sections \"*$clip\"" }

    // ── Filename template ─────────────────────────────────────────────────────
    filenameTemplate?.takeIf { it.isNotBlank() }?.let { parts += "-o \"$it\"" }

    // ── Global extra args (Settings > Advanced) ───────────────────────────────
    val globalExtra = GalleryDlPreferences.getExtraArgs(context)
    if (globalExtra.isNotBlank()) parts += globalExtra

    // ── Per-download extra commands ───────────────────────────────────────────
    extraCommands.forEach { parts += it }

    // ── URL ───────────────────────────────────────────────────────────────────
    parts += "\"$url\""

    return parts.joinToString(" \\\n  ")
}

@Composable
private fun ExtraCommandsScreen(
    url: String,
    quality: VideoQuality,
    outputFormat: OutputFormat,
    saveThumbnail: Boolean,
    segments: List<TrimSegment>,
    filenameTemplate: String?,
    commands: List<String>,
    onCommandsChange: (List<String>) -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var input by remember { mutableStateOf("") }

    fun add() {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
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
        val fullCommand = buildPreviewCommand(
            context = context,
            url = url,
            quality = quality,
            outputFormat = outputFormat,
            saveThumbnail = saveThumbnail,
            segments = segments,
            filenameTemplate = filenameTemplate,
            extraCommands = commands,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Current Command",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = onCopy,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(FeatherIcons.Copy, contentDescription = "Copy current command")
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    fullCommand,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add Command") },
            leadingIcon = { Icon(FeatherIcons.Terminal, contentDescription = null) },
            trailingIcon = {
                PreviewChip(label = "Add", onClick = { add() })
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(label = "Cancel", onClick = onCancel)
            PreviewChip(label = "Done", selected = true, onClick = onDone)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimVideoScreen(
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
    val exoPlayer = remember { 
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("ExoPlayer", "Playback error", error)
                }
            })
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
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
        if (kotlin.math.abs(exoPlayer.currentPosition - playheadMs) > 500L) {
            exoPlayer.seekTo(playheadMs)
        }
    }

    // Tracks drags on the Slider below specifically (shared with its own interactionSource) so
    // the poll below can tell "the user is actively dragging our Slider" apart from every other
    // way the real position can move.
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isDraggingSlider by sliderInteractionSource.collectIsDraggedAsState()

    LaunchedEffect(exoPlayer) {
        while (true) {
            // Only gating on isPlaying here (rather than syncing unconditionally) meant this froze
            // the instant playback paused — reproduced live: pausing, then seeking via the video's
            // own built-in ExoPlayer controller (a tap-to-seek or drag on its scrubber, not our
            // Slider below) moved the real player position but never touched playheadMs, so both
            // the Slider and the visible timestamp stayed stuck wherever they were at the moment of
            // pausing, completely disconnected from the frame actually on screen. Syncing whenever
            // the user isn't actively dragging *our own* Slider (rather than whenever the player
            // isn't playing) covers that case — and a paused native seek, and a playing native
            // seek — while still not fighting the user's own drag on our Slider mid-gesture.
            if (!isDraggingSlider) {
                playheadMs = exoPlayer.currentPosition
            }
            kotlinx.coroutines.delay(100)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Trim video", style = MaterialTheme.typography.bodyMedium)

        Surface(
            modifier = Modifier.fillMaxWidth().height(176.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (streamUrls.isNotEmpty() && !streamLoadFailed) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            androidx.media3.ui.PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
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
            }
        }

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
        // Still widens past whatever length is used here if a typed/dragged end or the playhead
        // itself goes further, exactly as before.
        val effectiveDurationMs = durationMs?.takeIf { it > 0L } ?: NOMINAL_DURATION_MS
        val maxSliderMs = maxOf(effectiveDurationMs.toFloat(), active?.endMs?.toFloat() ?: 0f, playheadMs.toFloat())
        Slider(
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
                SliderDefaults.Track(
                    sliderState = state,
                    trackCornerSize = 8.dp,
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(
                label = "Set Start",
                onClick = {
                    // playheadMs only mirrors the player's real position while it's actively
                    // playing (see the polling LaunchedEffect above) — pausing and then scrubbing
                    // via the video's own built-in ExoPlayer controller (rather than the Slider
                    // below) never updates it, so it could read anywhere up to several minutes
                    // stale relative to the frame actually on screen. Reading currentPosition
                    // directly here (and syncing playheadMs to match, so the Slider/timestamp
                    // catch up too) makes this always match whatever the video is showing at the
                    // moment of the tap, not a cached mirror of it. Falls back to playheadMs itself
                    // when there's no real stream (see NOMINAL_DURATION_MS) — nothing for the
                    // player to be authoritative about in that case.
                    val newStart = if (streamUrls.isNotEmpty()) exoPlayer.currentPosition else playheadMs
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
                label = "Set End",
                onClick = {
                    // Same staleness fix as Set Start above.
                    val newEnd = if (streamUrls.isNotEmpty()) exoPlayer.currentPosition else playheadMs
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
        
        LaunchedEffect(active?.startMs) {
            if (parseTimestampToMs(startInput) != active?.startMs) {
                startInput = formatTimestamp(active?.startMs ?: 0L)
            }
        }
        LaunchedEffect(active?.endMs) {
            if (parseTimestampToMs(endInput) != active?.endMs) {
                endInput = formatTimestamp(active?.endMs ?: 0L)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
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
                        if (ms <= (active?.endMs ?: 0L)) {
                            updateActive { segment -> segment.copy(startMs = ms) }
                        }
                    }
                },
                label = { Text("Start") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { 
                        isStartFocused = it.isFocused
                        if (!it.isFocused) {
                            startInput = formatTimestamp(active?.startMs ?: 0L)
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
            )
            OutlinedTextField(
                value = endInput,
                onValueChange = { 
                    endInput = it
                    parseTimestampToMs(it)?.let { ms ->
                        if (ms >= (active?.startMs ?: 0L)) {
                            updateActive { segment -> segment.copy(endMs = ms) }
                        }
                    }
                },
                label = { Text("End") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { 
                        isEndFocused = it.isFocused
                        if (!it.isFocused) {
                            endInput = formatTimestamp(active?.endMs ?: 0L)
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }


        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    Icon(FeatherIcons.Trash2, contentDescription = "Delete segment")
                }
            }
            ChipRow(modifier = Modifier.weight(1f)) {
                segments.forEachIndexed { index, segment ->
                    PreviewChip(
                        label = "Cut ${index + 1}",
                        selected = segment.id == activeId,
                        onClick = {
                            activeId = segment.id
                            playheadMs = segment.startMs
                        },
                    )
                }
                PreviewChip(
                    label = "Add a segment",
                    onClick = {
                        val start = segments.maxOfOrNull { it.endMs } ?: 0L
                        val segment = TrimSegment(
                            startMs = start,
                            endMs = start + DEFAULT_SEGMENT_LENGTH_MS,
                        )
                        onSegmentsChange(segments + segment)
                        activeId = segment.id
                        playheadMs = segment.startMs
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(label = "Cancel", onClick = onCancel)
            PreviewChip(label = "Done", selected = true, onClick = onDone)
        }
    }
}

@Composable
private fun FilenameTemplatesScreen(
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

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; message = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add FileName template") },
            leadingIcon = { Icon(FeatherIcons.Tag, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            supportingText = message?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(label = "View Templates", onClick = onViewTemplates)
            PreviewChip(
                label = "Add",
                onClick = {
                    val trimmed = input.trim()
                    when {
                        trimmed.isEmpty() -> message = "Enter a template first"
                        !GalleryDlPreferences.addFilenameTemplate(context, trimmed) ->
                            message = "That template is already saved"
                        else -> {
                            onApply(trimmed)
                            input = ""
                            message = "Template saved and applied"
                        }
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(label = "Cancel", onClick = onCancel)
            PreviewChip(label = "Done", selected = true, onClick = onDone)
        }
    }
}

@Composable
private fun ViewTemplatesScreen(
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
            }
            Text("Saved templates", style = MaterialTheme.typography.titleMedium)
        }

        if (templates.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    "No saved templates yet. Add one on the previous screen and it'll show up here.",
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
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { onPick(template) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                template,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                GalleryDlPreferences.removeFilenameTemplate(context, template)
                                templates = GalleryDlPreferences.getFilenameTemplates(context)
                            }) {
                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = "Delete template",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}











