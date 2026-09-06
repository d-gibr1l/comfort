@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.comfort.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** Which of the sheet's screens is showing. They share one ModalBottomSheet and swap content with
 * a slide, rather than each being its own nested sheet: a popup inside a popup gets dismissed
 * *along with* its parent on tap in this Compose version (reproduced live earlier with a
 * DropdownMenu inside a ModalBottomSheet, which closed both), so nesting real sheets here would
 * reintroduce exactly that bug. */
private enum class PreviewScreen { MAIN, COMMANDS, TRIM, TEMPLATES, VIEW_TEMPLATES }

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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

    // Title/thumbnail for the preview card. The listing pass is the same one the share picker
    // already uses, so this costs nothing new on the engine side.
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewUploader by remember { mutableStateOf<String?>(null) }
    var previewThumbnail by remember { mutableStateOf<String?>(null) }
    var previewFilesize by remember { mutableStateOf<Long?>(null) }
    var previewLoading by remember { mutableStateOf(true) }

    LaunchedEffect(url) {
        previewLoading = true
        val info = GalleryDlListing.fetchPreviewInfo(context, url)
        previewTitle = info?.title
        previewUploader = info?.uploader
        previewThumbnail = info?.thumbnail
        previewFilesize = info?.filesizeBytes
        previewLoading = false
    }

    // Back returns to the main screen from a sub-screen (reversing the slide) rather than closing
    // the whole sheet, matching the forward navigation.
    BackHandler(enabled = screen != PreviewScreen.MAIN) { screen = PreviewScreen.MAIN }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState != PreviewScreen.MAIN
                if (forward) {
                    (slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300)))
                } else {
                    (slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)))
                }.using(SizeTransform(clip = false))
            },
            label = "previewScreen",
        ) { current ->
            when (current) {
                PreviewScreen.MAIN -> MainPreviewScreen(
                    url = url,
                    title = previewTitle,
                    uploader = previewUploader,
                    thumbnail = previewThumbnail,
                    filesizeBytes = previewFilesize,
                    loading = previewLoading,
                    quality = quality,
                    onQualityChange = { quality = it },
                    outputFormat = outputFormat,
                    onToggleFormat = {
                        outputFormat = if (outputFormat == OutputFormat.MP4) OutputFormat.MKV else OutputFormat.MP4
                    },
                    saveThumbnail = saveThumbnail,
                    onToggleSaveThumbnail = { saveThumbnail = !saveThumbnail },
                    trimmed = segments.isNotEmpty(),
                    commandCount = commands.size,
                    filenameTemplate = filenameTemplate,
                    onCopyLink = { clipboard.setText(AnnotatedString(url)) },
                    onCancel = onDismiss,
                    onOpenCommands = { screen = PreviewScreen.COMMANDS },
                    onOpenTrim = {
                        if (segments.isEmpty()) {
                            segments = listOf(TrimSegment(startMs = 0L, endMs = DEFAULT_SEGMENT_LENGTH_MS))
                        }
                        screen = PreviewScreen.TRIM
                    },
                    onOpenTemplates = { screen = PreviewScreen.TEMPLATES },
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

                PreviewScreen.COMMANDS -> ExtraCommandsScreen(
                    commands = commands,
                    onCommandsChange = { commands = it },
                    onCopy = { clipboard.setText(AnnotatedString(commands.joinToString(" "))) },
                    onCancel = {
                        commands = emptyList()
                        screen = PreviewScreen.MAIN
                    },
                    onDone = { screen = PreviewScreen.MAIN },
                )

                PreviewScreen.TRIM -> TrimVideoScreen(
                    segments = segments,
                    onSegmentsChange = { segments = it },
                    thumbnail = previewThumbnail,
                    pageUrl = url,
                    onCancel = {
                        segments = emptyList()
                        screen = PreviewScreen.MAIN
                    },
                    onDone = { screen = PreviewScreen.MAIN },
                )

                PreviewScreen.TEMPLATES -> FilenameTemplatesScreen(
                    current = filenameTemplate ?: GalleryDlPreferences.getFilenameFormat(context),
                    onApply = { filenameTemplate = it },
                    onViewTemplates = { screen = PreviewScreen.VIEW_TEMPLATES },
                    onCancel = {
                        filenameTemplate = null
                        screen = PreviewScreen.MAIN
                    },
                    onDone = { screen = PreviewScreen.MAIN },
                )

                PreviewScreen.VIEW_TEMPLATES -> ViewTemplatesScreen(
                    onPick = {
                        filenameTemplate = it
                        screen = PreviewScreen.TEMPLATES
                    },
                    onBack = { screen = PreviewScreen.TEMPLATES },
                )
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
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color.Black) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = androidx.compose.ui.graphics.Color.Black,
            borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp
        ),
        // 8dp rather than the pill the theme's shape scale would otherwise give a chip — the
        // sheet's own spec calls for squarer chips than the fully-rounded buttons around them.
        shape = shape,
        leadingIcon = leading?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.Black) }
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
            .padding(bottom = 24.dp),
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (filesizeBytes != null) {
                        Text(
                            formatFilesize(filesizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ExtraCommandsScreen(
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
                enabled = commands.isNotEmpty(),
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
                if (commands.isEmpty()) {
                    Text(
                        "No extra commands yet. Anything added here is passed to the download " +
                            "engine for this one download.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                } else {
                    Text(
                        commands.joinToString(" "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
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
                if (thumbnail != null) {
                    AsyncImage(
                        model = thumbnailRequest(thumbnail, pageUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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

        // The M3 Expressive slider: a thick track and a tall handle rather than the default thin
        // track + round dot, per the spec's own component guidance.
        Slider(
            value = playheadMs.toFloat(),
            onValueChange = { playheadMs = it.toLong() },
            valueRange = 0f..NOMINAL_DURATION_MS.toFloat(),
            thumb = { state ->
                SliderDefaults.Thumb(
                    interactionSource = remember { MutableInteractionSource() },
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
                    // Mirrors the spec's safety constraint: a start at or past the current end
                    // would be an invalid (negative-length) clip, so the end is pushed out to keep
                    // at least a second of video rather than letting the segment collapse.
                    updateActive { segment ->
                        val newStart = playheadMs
                        val newEnd = if (newStart >= segment.endMs) newStart + 1000L else segment.endMs
                        segment.copy(startMs = newStart, endMs = newEnd)
                    }
                },
            )
            PreviewChip(
                label = "Set End",
                onClick = {
                    // The reverse constraint, clamped at zero so the start can't go negative.
                    updateActive { segment ->
                        val newEnd = playheadMs
                        val newStart = if (newEnd <= segment.startMs) (newEnd - 1000L).coerceAtLeast(0L) else segment.startMs
                        segment.copy(startMs = newStart, endMs = newEnd)
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewChip(
                label = formatTimestamp(active?.startMs ?: 0L),
                onClick = { playheadMs = active?.startMs ?: 0L },
            )
            PreviewChip(
                label = formatTimestamp(active?.endMs ?: 0L),
                onClick = { playheadMs = active?.endMs ?: 0L },
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
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    "No saved templates yet. Add one on the previous screen and it'll show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
