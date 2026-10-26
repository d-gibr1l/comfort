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
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Which card/chip layout MainPreviewScreen renders — VIDEO is today's original layout,
 * unchanged; SONG_SINGLE is the square-art/artist-album card for one song; SONG_LIST is the
 * per-track checklist for a Spotify album/playlist. See DownloadPreviewSheet's own mode
 * computation for exactly how a URL/quality combination picks one of these. */
private enum class PreviewMode { VIDEO, SONG_SINGLE, SONG_LIST }

/** The end of a list that's only partly loaded (a channel's newest videos): load the next page,
 * or everything. Everything can take most of a minute for a big channel, so it says so. */
@Composable
private fun LoadMoreRow(loading: Boolean, onLoadMore: (all: Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.loading_more),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onLoadMore(false) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.load_more_count, 50), maxLines = 1)
                }
                OutlinedButton(onClick = { onLoadMore(true) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.load_all), maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.load_all_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Everything MainPreviewScreen needs for the song-styled modes, bundled into one param instead
 * of eight so PreviewSheetOverlayHost/MainPreviewScreen's own already-long signatures don't grow
 * by one new parameter per song field. [mode] drives which card/chip layout renders; the rest are
 * only ever read when [mode] isn't VIDEO. [selectedNums] and its two callbacks are only relevant
 * for SONG_LIST (a Spotify album/playlist) — see DownloadPreviewSheet's own selection-state doc
 * comment for what they mean and how the "num in {...}" filter string is built from them. */
private data class SongPreviewState(
    val mode: PreviewMode,
    val showQualityRow: Boolean,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val tracks: List<TrackPreview>,
    val collectionTitle: String?,
    val collectionArtist: String?,
    val collectionThumbnail: String?,
    val selectedNums: Set<Int>,
    val onToggleNum: (Int) -> Unit,
    val onToggleAll: () -> Unit,
    /** More of the list can be loaded (a channel, see PreviewSheetViewModel.loadMore). */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val onLoadMore: (all: Boolean) -> Unit = {},
    /** Editable title/artist for SONG_SINGLE's own card — null while the preview is still loading
     * (no source value to seed from yet). Not used in SONG_LIST or VIDEO; the per-track checklist
     * doesn't get individually-editable rows. */
    val editedTitle: String?,
    val editedArtist: String?,
    val onEditedTitleChange: (String) -> Unit,
    val onEditedArtistChange: (String) -> Unit,
)

/** Snapshot of everything an overlay sub-screen (Commands/Trim/Templates) can touch, taken the
 * moment MAIN opens one — restored verbatim by [PreviewScreen] back-out paths that aren't Done
 * (scrim tap, hardware Back, drag-to-dismiss, Cancel), so backing out of a sub-screen is always a
 * true no-op instead of each path hand-rolling its own (previously inconsistent, sometimes wrong)
 * idea of what "discard" means. */
private data class OverlaySnapshot(
    val segments: List<TrimSegment>,
    val segmentsEdited: Boolean,
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
    /** Same "num in {1,3,4}" gallery-dl-syntax expression SharePickerScreen already builds for its
     * own multi-item selection — null means "everything". Set only for a song-list preview
     * (Spotify album/playlist) where the user unchecked at least one track; see DownloadWorker's
     * own per-engine translation of DownloadEntity.itemFilter for how each engine consumes it. */
    val itemFilter: String? = null,
    /** The real selected count when [itemFilter] is set (0 otherwise) — passed straight through to
     * DownloadEntity.totalItems so the progress bar's denominator is right immediately, instead of
     * DownloadWorker's own pre-flight listing pass overwriting it with the full album's size. */
    val totalItems: Int = 0,
    /** The song preview sheet's own editable title/artist (SongPreviewCard) — null unless the
     * sheet was in a song mode with a value to edit. Overrides the source's own title/artist for
     * both the embedded file tags and this download's own DB `title`/`artist`; the on-disk
     * filename still derives from the source's raw values regardless (see
     * DownloadEntity.overrideTitle/overrideArtist's own doc comment). */
    val overrideTitle: String? = null,
    val overrideArtist: String? = null,
    /** The engine picked for this download in the sheet's Engine chips; null = Auto (Settings). */
    val engine: com.comfort.app.data.DownloadEngine? = null,
    /** The format an audio download is saved in — see DownloadEntity.audioFormat. */
    val audioFormat: AudioFormat? = null,
    /** The audio track picked — see DownloadEntity.audioLanguage; null follows Settings. */
    val audioLanguage: String? = null,
    /** The subtitle languages picked — see DownloadEntity.subtitleLanguages; null follows Settings. */
    val subtitleLanguages: String? = null,
)

/** The engines the Configure sheet offers for [url]: only links more than one engine can download
 * (gallery-dl sites, Instagram posts), only engines switched on, in Settings' order — empty when
 * there's no real choice (YouTube, Spotify), so the chips don't show. */
internal fun sheetEngineOptions(context: android.content.Context, url: String): List<com.comfort.app.data.DownloadEngine> {
    // A site on the user's gallery-dl list has no choice to offer: gallery-dl only.
    if (VideoSiteRouter.isGalleryDlOnly(url) && !VideoSiteRouter.isInstagramPost(url)) return emptyList()
    val candidates = when {
        VideoSiteRouter.isInstagramPost(url) -> setOf(
            com.comfort.app.data.DownloadEngine.INSTALOADER, com.comfort.app.data.DownloadEngine.GALLERY_DL, com.comfort.app.data.DownloadEngine.YT_DLP,
        )
        VideoSiteRouter.classify(url) == com.comfort.app.data.DownloadEngine.GALLERY_DL -> setOf(
            com.comfort.app.data.DownloadEngine.GALLERY_DL, com.comfort.app.data.DownloadEngine.YT_DLP,
        )
        else -> emptySet()
    }
    val choice = com.comfort.app.worker.EngineChoice.load(context)
    return choice.order.filter { it in candidates && choice.isOn(it) }.takeIf { it.size >= 2 }.orEmpty()
}

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
        // toFloatOrNull() returning null (a genuinely non-numeric part, e.g. "hh:mm" typed
        // literally) must fail the whole parse — silently coercing it to 0f used to make any
        // garbage text parse "successfully" as 00:00.000 and get applied to the segment as if it
        // were a real, deliberate edit.
        val parts = v.split(":").map { it.toFloatOrNull() ?: return null }
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
 * yt_dlp_wrapper.py. Null when the segments cover everything from zero, i.e. nothing to trim.
 *
 * [realDurationMs] clamps every segment against the video's actual known length, regardless of
 * which UI path (Add a segment, Set Start/End, manual typing) let a boundary run past it — this
 * is the one place every segment funnels through before reaching the download, so it's also the
 * one place that has to catch an out-of-range value no matter how it was created. Without this, a
 * start past the real end of the file reached yt_dlp_wrapper.py's _LocalTrimPP, whose ffmpeg build
 * (stream-copy only, no encoders) wrote an empty output for that range — and _LocalTrimPP.run()
 * replaced the already-fully-downloaded file with it unconditionally, with no error surfaced.
 * Segments that clamp down to zero-or-negative length are dropped rather than sent through at all. */
private fun List<TrimSegment>.toClipRange(realDurationMs: Long?): String? {
    if (isEmpty()) return null
    val cap = realDurationMs?.takeIf { it > 0L }
    val ranges = mapNotNull { segment ->
        val start = segment.startMs.coerceAtLeast(0L).let { if (cap != null) it.coerceAtMost(cap) else it }
        val end = segment.endMs.let { if (cap != null) it.coerceAtMost(cap) else it }
        if (end <= start) null else "${formatTimestamp(start)}-${formatTimestamp(end)}"
    }
    return ranges.joinToString(",").ifEmpty { null }
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
    // Shown as-is instead of fetched — see LinkRoute.Preview.preloaded.
    preloadedPreview: com.comfort.app.util.PreviewInfo? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    var screen by remember { mutableStateOf(PreviewScreen.MAIN) }

    // Seeded from the global defaults, then only ever changed by this sheet's own controls — a
    // per-download override, never a write back to the global Settings value.
    var quality by remember { mutableStateOf(GalleryDlPreferences.getVideoQuality(context)) }
    var outputFormat by remember { mutableStateOf(GalleryDlPreferences.getOutputFormat(context)) }
    var audioFormat by remember { mutableStateOf(GalleryDlPreferences.getAudioFormat(context)) }
    // The audio track (a dub instead of the original); null follows Settings until one is picked.
    var audioLanguage by remember { mutableStateOf<String?>(null) }
    // The sheet's own subtitle pick ("" none); null until changed, following Settings.
    var subtitleLanguages by remember { mutableStateOf<String?>(null) }
    var saveThumbnail by remember { mutableStateOf(false) }
    // Per-download engine (null = Auto) and the engines the link can use.
    var engineOverride by remember { mutableStateOf<com.comfort.app.data.DownloadEngine?>(null) }
    val engineOptions = remember(url) { sheetEngineOptions(context, url) }
    var commands by remember { mutableStateOf<List<String>>(emptyList()) }
    var segments by remember { mutableStateOf<List<TrimSegment>>(emptyList()) }
    // False for the default segment onOpenTrim below seeds just to give the Trim screen something
    // to render — only flips true once the user actually touches Set Start/Set End, a manual
    // timestamp, or Add/Delete (see the TrimVideoScreen call site's own onSegmentsChange wrapper).
    // Gates both MAIN's "trimmed" chip state and the real clipRange sent to the download itself:
    // without this, opening "Trim Video" and tapping Done with zero edits silently clipped every
    // download to the untouched default's first 30 seconds — reproduced live.
    var segmentsEdited by remember { mutableStateOf(false) }
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
            overlaySnapshot = OverlaySnapshot(segments, segmentsEdited, commands, filenameTemplate)
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
            segmentsEdited = it.segmentsEdited
            commands = it.commands
            filenameTemplate = it.filenameTemplate
        }
        overlaySnapshot = null
        screen = PreviewScreen.MAIN
    }

    // A plain rememberModalBottomSheetState() uses Material3's own default drag thresholds (a
    // fairly short distance commits straight to Hidden) — reproduced live as "too easy to
    // dismiss" once the top icon row and Download button became fixed (see MainPreviewScreen's
    // own doc comment below): a slow, incidental drag starting anywhere on that non-scrolling
    // chrome would close the whole sheet. SheetState's own public constructor (not the
    // convenience rememberModalBottomSheetState() function, which hardcodes Material3's
    // defaults) exposes positionalThreshold directly, raised well above the default here —
    // verified live against AnchoredDraggableState's own source (androidx.compose.foundation.
    // gestures.AnchoredDraggable.kt): a slow drag below this distance now snaps back instead of
    // dismissing, confirmed with real on-device drags both under and over the threshold.
    // velocityThreshold is still passed (SheetState's constructor requires it), but confirmed
    // dead for this component: ModalBottomSheet's real fling behavior
    // (AnchoredDraggableDefaults.flingBehavior) hardcodes its own ~125dp/s fling floor and never
    // reads this field, so any release at or above that floor — including a slow-looking but
    // not-slow-enough drag — still commits straight to Hidden regardless of this value. Not
    // fixable without forking that internal fling behavior; not worth doing for this app.
    val density = LocalDensity.current
    // Material's fling closes the sheet at ~125dp/s however short the drag (see above), so a small
    // scroll that reached the sheet closed it. The close is refused unless the sheet has been pulled
    // at least this far down from where it sits open: it springs back instead.
    val minDismissDragPx = with(density) { 160.dp.toPx() }
    val sheetStateRef = remember { arrayOfNulls<SheetState>(1) }
    var expandedOffsetPx by remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }
    val sheetState = remember {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 200.dp.toPx() } },
            velocityThreshold = { with(density) { 800.dp.toPx() } },
            initialValue = SheetValue.Expanded,
            // A swipe-to-hide drag normally commits straight to Hidden. With an overlay panel
            // open, reject that commit and back out to MAIN instead — the sheet snaps back to
            // expanded the same way it would if the user hadn't dragged far enough, rather than
            // visibly collapsing and then being forced back open.
            confirmValueChange = { target ->
                if (target == SheetValue.Hidden && screen != PreviewScreen.MAIN) {
                    if (screen == PreviewScreen.VIEW_TEMPLATES) {
                        screen = PreviewScreen.TEMPLATES
                    } else {
                        revertOverlay()
                    }
                    false
                } else if (target == SheetValue.Hidden) {
                    val offset = sheetStateRef[0]?.let { runCatching { it.requireOffset() }.getOrNull() }
                    offset == null || expandedOffsetPx.isNaN() || offset - expandedOffsetPx >= minDismissDragPx
                } else {
                    true
                }
            },
            skipHiddenState = false,
        )
    }
    sheetStateRef[0] = sheetState
    // Where the sheet sits open (its content's height decides it), read whenever it comes to rest.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue to sheetState.isAnimationRunning }.collect { (value, animating) ->
            if (value == SheetValue.Expanded && !animating) {
                runCatching { sheetState.requireOffset() }.getOrNull()?.let { expandedOffsetPx = it }
            }
        }
    }

    // The link's data — the listing pass, the selection, the editable song title/artist and the
    // duplicate check — lives in PreviewSheetViewModel, scoped to this sheet so every opening starts
    // fresh. What's left here is the sheet's own UI state (sub-screen, overlay, slide state).
    val sheetViewModelOwner = rememberSheetViewModelStoreOwner()
    val sheet: com.comfort.app.viewmodel.PreviewSheetViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = sheetViewModelOwner,
        key = url,
    ) { com.comfort.app.viewmodel.PreviewSheetViewModel(context.applicationContext as android.app.Application, url, preloadedPreview) }
    val preview by sheet.preview.collectAsState()
    val previewLoading by sheet.loading.collectAsState()
    val previewStatus by sheet.status.collectAsState()
    val isDuplicate by sheet.isDuplicate.collectAsState()
    val selectedNums by sheet.selectedNums.collectAsState()
    val loadingMore by sheet.loadingMore.collectAsState()
    val itemFilter by sheet.itemFilter.collectAsState()
    val editedTitle by sheet.editedTitle.collectAsState()
    val editedArtist by sheet.editedArtist.collectAsState()
    val tracks = preview?.tracks.orEmpty()

    val isSongSource = remember(url) { VideoSiteRouter.isSongSource(url) }
    // Any multi-entry listing gets the checklist — not just song sources. fetchPreviewInfo already
    // populates [tracks] for a plain multi-video yt-dlp playlist the exact same way it does for a
    // Spotify album (both just read yt-dlp's own {"entries": [...]} shape), so this was purely a
    // gating condition, not a data gap: a YouTube playlist used to render as a single-video VIDEO
    // card (previewing only its first entry, even though the whole playlist still downloaded) —
    // now it gets the same real per-item checklist a Spotify album already had. showQualityRow
    // (below) already keys off isSongSource independently of mode, so a video-list still shows its
    // quality picker; see the chips Row further down for how it also still gets Format/no-Trim
    // treatment instead of song styling.
    val mode = when {
        tracks.isNotEmpty() -> PreviewMode.SONG_LIST
        isSongSource || quality == VideoQuality.AUDIO_ONLY -> PreviewMode.SONG_SINGLE
        else -> PreviewMode.VIDEO
    }

    // Subscribe (Settings › Subscriptions) is offered for a YouTube link that listed as several
    // items — a channel or a playlist — the same non-empty [tracks] that gives it the checklist.
    val canSubscribe = tracks.isNotEmpty() && remember(url) { VideoSiteRouter.isYouTube(url) }
    var subscribing by remember { mutableStateOf(false) }
    val onSubscribe: (com.comfort.app.data.SubscriptionOptions) -> Unit = { options ->
        if (!subscribing) {
            subscribing = true
            scope.launch {
                // A yt-dlp playlist's listing carries no title of its own (preview.title is its
                // first video's), so the link stands in until the first check, moments later,
                // saves the real channel/playlist name and picture.
                val result = try {
                    com.comfort.app.data.Subscriptions.subscribe(
                        context, url, preview?.collectionTitle ?: url, preview?.collectionThumbnail, options,
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Toast.makeText(context, context.getString(R.string.subscribe_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    null
                }
                subscribing = false
                when (result) {
                    is com.comfort.app.data.Subscriptions.SubscribeResult.Added -> {
                        Toast.makeText(context, context.getString(R.string.subscribed), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                    // Left open: the link can still be downloaded from here.
                    is com.comfort.app.data.Subscriptions.SubscribeResult.AlreadySubscribed ->
                        Toast.makeText(context, context.getString(R.string.already_subscribed_to_x, result.subscription.title), Toast.LENGTH_SHORT).show()
                    null -> Unit
                }
            }
        }
    }

    // Back returns to the main screen from a sub-screen (reversing the slide) rather than closing
    // the whole sheet, matching the forward navigation — and, like every other non-Done way out,
    // discards whatever this visit changed rather than leaving it silently applied.
    BackHandler(enabled = screen != PreviewScreen.MAIN) {
        if (screen == PreviewScreen.VIEW_TEMPLATES) {
            screen = PreviewScreen.TEMPLATES
        } else {
            revertOverlay()
        }
    }

    ModalBottomSheet(
        // Tapping the scrim outside the sheet's own bounds fires this directly (it doesn't go
        // through sheetState/confirmValueChange above, which only guards drag-to-hide). With an
        // overlay panel open this should back out to MAIN first, same as Back and the overlay's
        // own scrim — only a tap with nothing open should actually dismiss the sheet.
        onDismissRequest = {
            when {
                screen == PreviewScreen.VIEW_TEMPLATES -> screen = PreviewScreen.TEMPLATES
                screen != PreviewScreen.MAIN -> revertOverlay()
                else -> onDismiss()
            }
        },
        sheetState = sheetState,
        // Never under the status bar: opened full height, its top (and the handle) sat under the
        // camera. The padding is outside the sheet's surface, so a shorter sheet looks the same.
        modifier = Modifier.statusBarsPadding(),
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
        // The outer sheet's own default drag handle renders above PreviewSheetOverlayHost's whole
        // content Box — outside the scrim it draws inside that Box — so with an overlay open it
        // stayed fully lit, undimmed, floating above the overlay's own handle further down: a
        // visible seam that read as two disconnected sheets stacked with a gap between them
        // (reported live, circled in a screenshot). The overlay panel already has its own handle
        // once it's open, so MAIN's is only ever needed when MAIN itself is actually on top.
        //
        // alpha 0, not swapped out for an empty composable — this slot's own height feeds directly
        // into the outer ModalBottomSheet's total measured height, so dropping it to zero-size the
        // instant an overlay opens made the *whole sheet* jump upward by the handle's own height in
        // one frame, with nothing to animate that discontinuity (reported live: "looks cut off when
        // there's another overlay" — the overlay's rounded top ended up sitting almost flush
        // against the sheet's own top edge instead of leaving MAIN's dimmed chrome visible above
        // it). graphicsLayer only affects the draw phase, not layout, so the handle keeps occupying
        // the exact same space at every screen — invisible, never absent.
        dragHandle = null,
    ) {
        // Broken out into its own (non-extension) composable so the AnimatedVisibility calls below
        // aren't lexically inside ModalBottomSheet's ColumnScope receiver — with that receiver in
        // scope, Kotlin resolves the plain top-level AnimatedVisibility to Compose's ColumnScope-
        // extension overload instead and refuses to call it without an explicit receiver.
        PreviewSheetOverlayHost(
            screen = screen,
            isDuplicate = isDuplicate,
            onScreenChange = { screen = it },
            onOpenOverlay = ::openOverlay,
            onRevertOverlay = ::revertOverlay,
            onCommitOverlay = ::commitOverlay,
            url = url,
            context = context,
            scope = scope,
            clipboard = clipboard,
            previewTitle = preview?.title,
            previewUploader = preview?.uploader,
            previewThumbnail = preview?.thumbnail,
            previewStreamUrls = preview?.streamUrls ?: emptyList(),
            previewDurationMs = preview?.durationMs,
            // The selected chip's own size when the preview reported per-quality sizes (it used to
            // always show the best format's — 718 MB with 720 selected); falls back to filesizeBytes
            // when a specific quality or audio stream's exact size is not separately recorded.
            previewFilesize = preview?.let { p ->
                if (p.sizesByQuality.isEmpty()) {
                    p.filesizeBytes
                } else {
                    val effectiveQuality = if (isSongSource) VideoQuality.AUDIO_ONLY else quality
                    if (effectiveQuality == VideoQuality.AUDIO_ONLY) {
                        p.sizesByQuality["AUDIO_ONLY|mkv"]
                            ?: p.sizesByQuality["AUDIO_ONLY|mp4"]
                            ?: p.filesizeBytes
                    } else {
                        p.sizesByQuality["${effectiveQuality.name}|${outputFormat.extension}"]
                            ?: p.filesizeBytes
                    }
                }
            },
            previewLoading = previewLoading,
            previewStatus = previewStatus,
            song = SongPreviewState(
                mode = mode,
                showQualityRow = !isSongSource,
                artist = preview?.artist,
                album = preview?.album,
                durationMs = preview?.durationMs,
                tracks = tracks,
                collectionTitle = preview?.collectionTitle,
                collectionArtist = preview?.collectionArtist,
                collectionThumbnail = preview?.collectionThumbnail,
                selectedNums = selectedNums,
                onToggleNum = { num -> sheet.toggleItem(num) },
                onToggleAll = { sheet.toggleAll() },
                hasMore = preview?.hasMore == true,
                loadingMore = loadingMore,
                onLoadMore = { all -> sheet.loadMore(all) },
                editedTitle = editedTitle,
                editedArtist = editedArtist,
                onEditedTitleChange = { sheet.setEditedTitle(it) },
                onEditedArtistChange = { sheet.setEditedArtist(it) },
            ),
            quality = quality,
            onQualityChange = {
                quality = it
                // Imported from YTDLnis's own "Remember download type" setting — off by default,
                // same "global default, per-download override" relationship as every other such
                // setting: writing back here only ever changes what the *next* download's own
                // preview sheet starts pre-selected at, never this one's own already-open state.
                if (GalleryDlPreferences.isRememberDownloadType(context)) {
                    GalleryDlPreferences.setVideoQuality(context, it)
                }
            },
            outputFormat = outputFormat,
            onFormatChange = { outputFormat = it },
            audioFormat = audioFormat,
            onAudioFormatChange = { audioFormat = it },
            audioTracks = preview?.audioTracks.orEmpty(),
            // Shown only for a video with dubs: the track Settings (or this sheet's pick) gets.
            audioTrack = preview?.audioTracks?.takeIf { it.size > 1 }?.let { tracks ->
                com.comfort.app.data.AudioLanguage.pick(tracks, audioLanguage ?: GalleryDlPreferences.getAudioLanguage(context))
            },
            onAudioLanguageChange = { audioLanguage = if (it.original) com.comfort.app.data.AudioLanguage.ORIGINAL else it.code },
            // Shown for a video with subtitles, or one the site can caption in the app's language.
            subtitles = preview?.let { info ->
                val auto = GalleryDlPreferences.isSubtitleAuto(context)
                val appLanguage = com.comfort.app.data.SubtitleLanguage.requested(com.comfort.app.data.SubtitleLanguage.APP)
                val translated = com.comfort.app.data.SubtitleTrack(appLanguage, auto = true)
                    .takeIf { info.subtitleTranslations && auto && info.subtitleTracks.none { it.code == appLanguage || it.code.startsWith("$appLanguage-") } }
                if (info.subtitleTracks.isEmpty() && translated == null) return@let null
                val setting = subtitleLanguages
                    ?: if (GalleryDlPreferences.isDownloadSubtitles(context)) GalleryDlPreferences.getSubtitleLanguages(context) else ""
                SubtitleChoice(
                    tracks = info.subtitleTracks,
                    selected = com.comfort.app.data.SubtitleLanguage.pick(info.subtitleTracks, setting, info.language, auto, info.subtitleTranslations),
                    translated = translated,
                )
            },
            onSubtitlesChange = { picked -> subtitleLanguages = picked.joinToString(",") { it.code } },
            saveThumbnail = saveThumbnail,
            onToggleSaveThumbnail = { saveThumbnail = !saveThumbnail },
            engineOptions = engineOptions,
            engineOverride = engineOverride,
            onEngineChange = { engineOverride = it },
            segments = segments,
            onSegmentsChange = { segments = it },
            segmentsEdited = segmentsEdited,
            onSegmentsEdited = { segmentsEdited = true },
            commands = commands,
            onCommandsChange = { commands = it },
            filenameTemplate = filenameTemplate,
            onFilenameTemplateChange = { filenameTemplate = it },
            itemFilter = itemFilter,
            selectedTrackCount = selectedNums.size,
            onDismiss = onDismiss,
            onDownload = onDownload,
            onSubscribe = if (canSubscribe) onSubscribe else null,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewSheetOverlayHost(
    screen: PreviewScreen,
    isDuplicate: Boolean,
    onScreenChange: (PreviewScreen) -> Unit,
    onOpenOverlay: (PreviewScreen) -> Unit,
    onRevertOverlay: () -> Unit,
    onCommitOverlay: () -> Unit,
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
    val overlayOpen = screen != PreviewScreen.MAIN

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
                    onSegmentsChange(listOf(TrimSegment(startMs = 0L, endMs = DEFAULT_SEGMENT_LENGTH_MS)))
                }
            },
            onOpenTemplates = { onOpenOverlay(PreviewScreen.TEMPLATES) },
            onDownload = { onDownload(currentOptions()) },
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
                                PreviewScreen.TRIM -> TrimVideoScreen(
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

/** The audio format for this one download (Settings > Processing > Audio format is the default):
 * a chip showing the current pick that opens a small panel of all of them, each with what it's for. */
@Composable
private fun AudioFormatChip(
    format: AudioFormat,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (AudioFormat) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PreviewChip(label = format.displayLabel, icon = Icons.Outlined.MusicNote, shape = shape, onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.width(264.dp),
        ) {
            Text(
                stringResource(R.string.save_audio_as),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            AudioFormat.entries.forEach { option ->
                val selected = option == format
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onChange(option)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            option.displayLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            audioFormatHint(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The video container: a chip naming it, opening a panel of both with what each is for. Same
 * panel as [AudioFormatChip]. */
@Composable
private fun VideoFormatChip(
    format: OutputFormat,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (OutputFormat) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PreviewChip(label = format.label, icon = Icons.Outlined.Movie, shape = shape, onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(min = 160.dp, max = 216.dp),
        ) {
            Text(
                stringResource(R.string.save_video_as),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            OutputFormat.entries.forEach { option ->
                val selected = option == format
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onChange(option)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(if (option == OutputFormat.MKV) R.string.video_format_mkv_hint else R.string.video_format_mp4_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The subtitle tracks a video has and the ones this download gets ([selected]); [translated] is
 * the app's language when the site can translate captions into it and has no track of its own. */
internal data class SubtitleChoice(
    val tracks: List<com.comfort.app.data.SubtitleTrack>,
    val selected: List<com.comfort.app.data.SubtitleTrack>,
    val translated: com.comfort.app.data.SubtitleTrack?,
)

/** A video's subtitles: a chip naming the languages that download ("Off" for none), opening a
 * panel to tick any of them — uploaded tracks first, auto-generated ones marked, and the app's
 * language as a translation where the site offers one. Same panel shape as [AudioLanguageChip]. */
@Composable
private fun SubtitleChip(
    choice: SubtitleChoice,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (List<com.comfort.app.data.SubtitleTrack>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = choice.tracks + listOfNotNull(choice.translated)
    Box {
        PreviewChip(
            label = if (choice.selected.isEmpty()) stringResource(R.string.subtitles_off)
            else choice.selected.joinToString(", ") { com.comfort.app.data.SubtitleLanguage.displayName(it.code) },
            selected = choice.selected.isNotEmpty(),
            icon = Icons.Outlined.Subtitles,
            shape = shape,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(min = 180.dp, max = 264.dp).heightIn(max = 420.dp),
        ) {
            Text(
                stringResource(R.string.subtitles),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            options.forEach { option ->
                val checked = choice.selected.any { it.code == option.code }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        // Several can be ticked, so the panel stays open.
                        .toggleable(value = checked, role = Role.Checkbox) { on ->
                            onChange(if (on) choice.selected + option else choice.selected.filter { it.code != option.code })
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            com.comfort.app.data.SubtitleLanguage.displayName(option.code),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        val note = when {
                            option === choice.translated -> stringResource(R.string.subtitles_translated)
                            option.auto -> stringResource(R.string.subtitles_auto_generated)
                            else -> null
                        }
                        note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (checked) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The audio track of a video with dubs: a chip naming the one that downloads, opening a panel of
 * them all with the original marked. Same panel shape as [AudioFormatChip]. */
@Composable
private fun AudioLanguageChip(
    track: com.comfort.app.data.AudioTrack,
    tracks: List<com.comfort.app.data.AudioTrack>,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (com.comfort.app.data.AudioTrack) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PreviewChip(
            label = java.util.Locale.forLanguageTag(track.code).let { it.getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { track.code } }
                .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) },
            icon = Icons.Outlined.Language,
            shape = shape,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(min = 180.dp, max = 264.dp).heightIn(max = 420.dp),
        ) {
            Text(
                stringResource(R.string.audio_track),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            tracks.forEach { option ->
                val selected = option == track
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onChange(option)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            com.comfort.app.data.AudioLanguage.displayName(option.code),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        if (option.original) {
                            Text(
                                stringResource(R.string.audio_track_original),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun audioFormatHint(format: AudioFormat): String = when (format) {
    AudioFormat.ORIGINAL -> stringResource(R.string.as_the_site_sends_it_not)
    AudioFormat.MP3 -> stringResource(R.string.plays_on_anything)
    AudioFormat.M4A -> stringResource(R.string.aac_small_and_good_quality)
    AudioFormat.OPUS -> stringResource(R.string.smallest_at_the_same_quality)
    AudioFormat.FLAC -> stringResource(R.string.lossless_biggest_file)
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
        selected -> Icons.Outlined.Check
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
    @Composable get() = when (this) {
        VideoQuality.BEST -> stringResource(R.string.best_available)
        VideoQuality.P2160 -> "4K"
        VideoQuality.P1440 -> "1440"
        VideoQuality.P1080 -> "1080"
        VideoQuality.P720 -> "720"
        VideoQuality.P480 -> "480"
        VideoQuality.AUDIO_ONLY -> stringResource(R.string.audio)
    }

/** Bytes as the compact size the preview card shows ("10mb"). */
internal fun formatFilesize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    return when {
        b >= 1024L * 1024L * 1024L -> "%.1fgb".format(b / (1024.0 * 1024.0 * 1024.0))
        b >= 1024L * 1024L -> "${b / (1024L * 1024L)}mb"
        b >= 1024L -> "${b / 1024L}kb"
        else -> "${b}b"
    }
}

/** "3:42" / "1:02:11" — no existing formatter has this shape: formatTimestamp (Trim screen) is
 * "mm:ss.mmm", and formatFilesize above is the naming/placement precedent for this one. */
private fun formatDurationShort(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "Artist — Album" (or just "Artist" when the album isn't known) — shared between
 * DownloadsHistoryScreen's Library card subtitle and this sheet's own song preview card so the
 * two formats can't drift apart. Null (not a fallback string) when there's no artist at all;
 * callers own their own fallback (the Library card's domain, this sheet's own "Untitled"-style
 * treatment). */
internal fun audioSubtitle(artist: String?, album: String?): String? =
    artist?.let { a -> album?.let { "$a — $it" } ?: a }

// Matches a parenthesized/bracketed segment containing common upload clutter — "(Official Video)",
// "[Official Music Video]", "(Lyrics)", "[HD]", "(Visualizer)", etc. Matched case-insensitively,
// keyword-anchored with \b so it doesn't eat a segment that merely contains one of these words as
// part of something else (e.g. a real "(Radio Edit)" stays untouched — "edit" isn't in the list).
private val CLUTTER_TAG_RE = Regex(
    """[(\[][^()\[\]]*\b(official\s*(video|audio|music\s*video|lyric\s*video)?|lyrics?|visualizer|explicit|clean\s*version|hd|4k|m/?v)\b[^()\[\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)
private val EXTRA_SPACES_RE = Regex("""\s{2,}""")
private val TOPIC_SUFFIX_RE = Regex("""\s*-\s*Topic\s*$""", RegexOption.IGNORE_CASE)

/** Starting value for the preview sheet's own editable title field — strips common YouTube-upload
 * clutter tags so the field opens already tidy rather than a raw scrape; the user can still edit
 * (or restore) anything from there. Deliberately conservative: only removes whole bracketed/
 * parenthesized segments matching known clutter keywords, never touches free text outside them. */
internal fun cleanTrackTitle(raw: String): String =
    raw.replace(CLUTTER_TAG_RE, "").replace(EXTRA_SPACES_RE, " ").trim()

/** Starting value for the preview sheet's own editable artist field — strips the " - Topic" suffix
 * YouTube's auto-generated artist channels carry (the preview's own listing pass doesn't run
 * through MetadataParserPP the way a real download does — see yt_dlp_wrapper.py's own
 * progress_hook — so this same cleanup needs its own copy here for the editable field's starting
 * value). */
internal fun cleanArtistName(raw: String): String = raw.replace(TOPIC_SUFFIX_RE, "").trim()

private val FIRST_CHIP_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = androidx.compose.foundation.shape.CornerSize(50),
    bottomStart = androidx.compose.foundation.shape.CornerSize(50),
    topEnd = androidx.compose.foundation.shape.CornerSize(8.dp),
    bottomEnd = androidx.compose.foundation.shape.CornerSize(8.dp)
)
private val MIDDLE_CHIP_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
private val LAST_CHIP_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = androidx.compose.foundation.shape.CornerSize(8.dp),
    bottomStart = androidx.compose.foundation.shape.CornerSize(8.dp),
    topEnd = androidx.compose.foundation.shape.CornerSize(50),
    bottomEnd = androidx.compose.foundation.shape.CornerSize(50)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainPreviewScreen(
    url: String,
    isDuplicate: Boolean,
    title: String?,
    uploader: String?,
    thumbnail: String?,
    filesizeBytes: Long?,
    loading: Boolean,
    loadingStatus: String?,
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
    engineOptions: List<com.comfort.app.data.DownloadEngine>,
    engine: com.comfort.app.data.DownloadEngine?,
    onEngineChange: (com.comfort.app.data.DownloadEngine?) -> Unit,
    trimmed: Boolean,
    commandCount: Int,
    filenameTemplate: String?,
    onCopyLink: () -> Unit,
    onCancel: () -> Unit,
    onOpenCommands: () -> Unit,
    onOpenTrim: () -> Unit,
    onOpenTemplates: () -> Unit,
    onDownload: () -> Unit,
    /** Shown as a Subscribe button beside Download when non-null — a YouTube channel/playlist. */
    onSubscribe: (() -> Unit)? = null,
) {
    // A plain LazyColumn cannot nest inside a verticalScroll parent (unbounded height), which is
    // exactly what SONG_LIST's track list needs to host efficiently. The top icon row and the
    // Download button are fixed siblings *outside* this LazyColumn (in the Column below) rather
    // than items inside it, so both stay put — visible and tappable — no matter how far the list
    // itself is scrolled; only the LazyColumn's own weight(1f) fills whatever height is left. The
    // overlay-panel system (Commands/Trim/Templates) lives in a sibling Box in
    // PreviewSheetOverlayHost, not inside this composable, so it's unaffected by any of this.
    //
    // fillMaxHeight()/weight(1f) only apply in SONG_LIST: that's the one mode whose content can
    // outgrow the sheet and needs its own internal scroll. VIDEO/SONG_SINGLE render a single card
    // — forcing the same fillMaxHeight() there left the LazyColumn stretched to fill the whole
    // sheet regardless of how short its one item was, showing as a large dead gap between the
    // card and the fixed chips/Download footer (reproduced live, screenshot from the user).
    val isList = song.mode == PreviewMode.SONG_LIST
    Column(modifier = Modifier.fillMaxWidth().let { if (isList) it.fillMaxHeight() else it }.navigationBarsPadding()) {
        androidx.compose.material3.BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onCopyLink, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_link))
            }
            // Only SONG_LIST has a selection to toggle — the control TrackListHeader used to
            // provide before it was removed, moved here so it survives without needing its own
            // dedicated row.
            if (isList) {
                TextButton(onClick = song.onToggleAll) {
                    Text(if (song.selectedNums.size == song.tracks.size) stringResource(R.string.deselect_all) else stringResource(R.string.select_all))
                }
            }
            FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.HighlightOff, contentDescription = stringResource(R.string.action_cancel))
            }
        }

        // Quality picker — outside the LazyColumn below, so it never scrolls out of view either
        // (reported live via screenshot, same "static" treatment already applied to the top icon
        // row/chips/Download button). Hidden when the song-ness came from the URL itself (Spotify/
        // known song host) — quality is meaningless there (Spotify ignores it outright; a known
        // song host downloads audio regardless — see DownloadOptions' own construction). Kept
        // visible when the user only got here by manually picking "Audio" on an otherwise-ordinary
        // link, so they still have a way back to a video quality without dismissing the whole sheet.
        if (song.showQualityRow) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(12.dp))
                val total = VideoQuality.entries.size
                VideoQuality.entries.forEachIndexed { index, option ->
                    val segmentedShape = when (index) {
                        0 -> FIRST_CHIP_SHAPE
                        total - 1 -> LAST_CHIP_SHAPE
                        else -> MIDDLE_CHIP_SHAPE
                    }
                    PreviewChip(
                        label = option.chipLabel,
                        selected = quality == option,
                        shape = segmentedShape,
                        onClick = { onQualityChange(option) },
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // The list stops at the chips below it, and a row it cut in half sat flush against them
        // (reported live). While there's more below, its last few dp fade out instead.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().let { if (isList) it.weight(1f) else it }
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (listState.canScrollForward) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Black, Color.Transparent),
                                startY = size.height - fadePx,
                                endY = size.height,
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - fadePx),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                },
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        when (song.mode) {
            PreviewMode.VIDEO -> item {
                VideoPreviewCard(
                    url = url, title = title, uploader = uploader, thumbnail = thumbnail,
                    filesizeBytes = filesizeBytes, loading = loading,
                )
            }
            PreviewMode.SONG_SINGLE -> item {
                SongPreviewCard(
                    url = url,
                    title = song.editedTitle ?: title,
                    artist = song.editedArtist ?: song.artist,
                    album = song.album,
                    onTitleChange = song.onEditedTitleChange,
                    onArtistChange = song.onEditedArtistChange,
                    durationMs = song.durationMs, filesizeBytes = filesizeBytes,
                    thumbnail = thumbnail, loading = loading, loadingStatus = loadingStatus,
                )
            }
            PreviewMode.SONG_LIST -> {
                // An album/playlist of songs gets its own header, so the list reads as a record
                // rather than a bare checklist. Not for a list of videos.
                if (!song.showQualityRow && song.collectionTitle != null) {
                    item(key = "album-header") {
                        AlbumHeader(
                            url = url,
                            title = song.collectionTitle,
                            artist = song.collectionArtist,
                            thumbnail = song.collectionThumbnail,
                            trackCount = song.tracks.size,
                            totalDurationMs = remember(song.tracks) {
                                song.tracks.mapNotNull { it.durationMs }.sum().takeIf { it > 0L }
                            },
                        )
                    }
                }
                items(song.tracks, key = { it.num }) { track ->
                    TrackRow(
                        track = track,
                        url = url,
                        selected = track.num in song.selectedNums,
                        onToggle = { song.onToggleNum(track.num) },
                        squareArt = !song.showQualityRow,
                    )
                }
                if (song.hasMore) {
                    item(key = "load-more") {
                        LoadMoreRow(loading = song.loadingMore, onLoadMore = song.onLoadMore)
                    }
                }
            }
        }

        } // end LazyColumn

        // Fixed chips row — outside the LazyColumn above, so it never scrolls out of view
        // alongside the top icon row and the Download button below. Bottom padding keeps it from
        // crowding the Download button now that both are fixed and always touching.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // A video list (song.mode == SONG_LIST but showQualityRow is still true, since it's
            // not a song source) gets the same chip set as a single VIDEO — Format toggle, no
            // song-only "cover art" wording — minus Trim, which doesn't make sense across several
            // selected videos at once. song.mode == VIDEO keeps Trim since there's exactly one.
            // Which engine downloads this link: Auto follows Settings › Updates and engines;
            // picking one runs only that engine. Only for links more than one can handle.
            if (engineOptions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(12.dp))
                    PreviewChip(
                        label = stringResource(R.string.auto_engine),
                        selected = engine == null,
                        icon = Icons.Outlined.Tune,
                        shape = FIRST_CHIP_SHAPE,
                        onClick = { onEngineChange(null) },
                    )
                    engineOptions.forEachIndexed { index, option ->
                        PreviewChip(
                            label = when (option) {
                                com.comfort.app.data.DownloadEngine.YT_DLP -> "yt-dlp"
                                com.comfort.app.data.DownloadEngine.GALLERY_DL -> "gallery-dl"
                                com.comfort.app.data.DownloadEngine.INSTALOADER -> "Instaloader"
                                com.comfort.app.data.DownloadEngine.SPOTIFY -> "Spotify"
                            },
                            selected = engine == option,
                            icon = null,
                            shape = if (index == engineOptions.lastIndex) LAST_CHIP_SHAPE else MIDDLE_CHIP_SHAPE,
                            onClick = { onEngineChange(option) },
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }

            val videoStyledChips = song.mode == PreviewMode.VIDEO || song.showQualityRow
            if (videoStyledChips) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(12.dp))
                        PreviewChip(
                            label = stringResource(R.string.save_thumbnail),
                            selected = saveThumbnail,
                            icon = Icons.Outlined.Image,
                            shape = FIRST_CHIP_SHAPE,
                            onClick = onToggleSaveThumbnail,
                        )
                        PreviewChip(
                            label = if (commandCount > 0) stringResource(R.string.commands_x, commandCount) else stringResource(R.string.add_extra_commands),
                            selected = commandCount > 0,
                            icon = Icons.Outlined.Terminal,
                            shape = LAST_CHIP_SHAPE,
                            onClick = onOpenCommands,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    // Trim only makes sense against a single clip — song.mode == VIDEO. A video
                    // list (song.mode == SONG_LIST, several selected videos at once) skips it and
                    // starts straight at Format, same shape-relabeling SharePickerScreen's own
                    // segmented chip rows already do when a middle chip becomes the first.
                    val showTrim = song.mode == PreviewMode.VIDEO
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(12.dp))
                        if (showTrim) {
                            PreviewChip(
                                label = stringResource(R.string.trim_video),
                                selected = trimmed,
                                icon = Icons.Outlined.ContentCut,
                                shape = FIRST_CHIP_SHAPE,
                                onClick = onOpenTrim,
                            )
                        }
                        // Audio only saves no video, so the video container means nothing there —
                        // the audio format does.
                        if (quality == VideoQuality.AUDIO_ONLY) {
                            AudioFormatChip(
                                format = audioFormat,
                                shape = if (showTrim) MIDDLE_CHIP_SHAPE else FIRST_CHIP_SHAPE,
                                onChange = onAudioFormatChange,
                            )
                        } else {
                            VideoFormatChip(
                                format = outputFormat,
                                shape = if (showTrim) MIDDLE_CHIP_SHAPE else FIRST_CHIP_SHAPE,
                                onChange = onFormatChange,
                            )
                        }
                        // Only for a video with dubbed audio tracks.
                        audioTrack?.let { track ->
                            AudioLanguageChip(track = track, tracks = audioTracks, shape = MIDDLE_CHIP_SHAPE, onChange = onAudioLanguageChange)
                        }
                        // Only for a video with subtitles (or captions the site can translate).
                        subtitles?.let { choice ->
                            SubtitleChip(choice = choice, shape = MIDDLE_CHIP_SHAPE, onChange = onSubtitlesChange)
                        }
                        PreviewChip(
                            label = stringResource(R.string.filename_templates),
                            selected = filenameTemplate != null,
                            icon = Icons.Outlined.Tag,
                            shape = LAST_CHIP_SHAPE,
                            onClick = onOpenTemplates,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                } else {
                    // Song modes: no Trim (not a video) and no Mp4/Mkv toggle — the audio format
                    // instead, then the cover-art save toggle, Commands and Filename Templates.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(12.dp))
                        AudioFormatChip(
                            format = audioFormat,
                            shape = FIRST_CHIP_SHAPE,
                            onChange = onAudioFormatChange,
                        )
                        PreviewChip(
                            label = stringResource(R.string.save_cover_art),
                            selected = saveThumbnail,
                            icon = Icons.Outlined.Image,
                            shape = MIDDLE_CHIP_SHAPE,
                            onClick = onToggleSaveThumbnail,
                        )
                        PreviewChip(
                            label = if (commandCount > 0) stringResource(R.string.commands_x, commandCount) else stringResource(R.string.add_extra_commands),
                            selected = commandCount > 0,
                            icon = Icons.Outlined.Terminal,
                            shape = MIDDLE_CHIP_SHAPE,
                            onClick = onOpenCommands,
                        )
                        PreviewChip(
                            label = stringResource(R.string.filename_templates),
                            selected = filenameTemplate != null,
                            icon = Icons.Outlined.Tag,
                            shape = LAST_CHIP_SHAPE,
                            onClick = onOpenTemplates,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        // Fixed footer — outside the LazyColumn above, so it never scrolls out of view. Same
        // empty-selection guard as before: with a non-empty track list and nothing checked,
        // disabled — same guard SharePickerScreen already uses for its own selection, so
        // unchecking every song can never be mistaken for (or silently become) "download
        // everything".
        val downloadEnabled = song.mode != PreviewMode.SONG_LIST || song.selectedNums.isNotEmpty()
        // The selection's size too, when every selected item's is known — a sum over only some of
        // them would read as the whole download's.
        val selectedSize = remember(song.tracks, song.selectedNums) {
            song.tracks.filter { it.num in song.selectedNums }
                .takeIf { picked -> picked.isNotEmpty() && picked.all { it.sizeBytes != null } }
                ?.sumOf { it.sizeBytes!! }
        }
        val countSuffix = if (song.mode == PreviewMode.SONG_LIST) {
            " ${song.selectedNums.size}" + (selectedSize?.let { " · ${formatFilesize(it)}" } ?: "")
        } else ""
        // 24dp horizontal inset matches MainScreen's own bottom nav pill's margin
        // (navBarPill's own Box uses padding(horizontal = 24.dp)) — kept consistent so
        // this button reads the same width as that pill rather than running edge-to-edge.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Subscribing saves the channel/playlist with the choices above and downloads nothing
            // now (see Subscriptions.subscribe), so it sits beside Download as its own action. Not
            // gated on the selection: a subscription is about future uploads.
            if (onSubscribe != null) {
                OutlinedButton(
                    onClick = onSubscribe,
                    modifier = Modifier.height(56.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.Outlined.Subscriptions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subscribe))
                }
            }
            Button(
                onClick = onDownload,
                enabled = downloadEnabled,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                // Known ahead of time (DownloadPreviewSheet's own isDuplicate check), not discovered
                // only after tapping — replaces the old flow where this always said "Download" and a
                // duplicate only surfaced afterward via a Snackbar with its own separate "Redownload"
                // action to confirm.
                Text(
                    stringResource(if (isDuplicate) R.string.redownload else R.string.download_action) + countSuffix,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Today's original video-styled preview card — unchanged from before the song-preview redesign,
 * just broken out into its own composable so MainPreviewScreen's per-mode branch reads as three
 * sibling card composables instead of one large inlined conditional. */
@Composable
private fun VideoPreviewCard(
    url: String,
    title: String?,
    uploader: String?,
    thumbnail: String?,
    filesizeBytes: Long?,
    loading: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(12.dp),
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
                        Icons.Outlined.Image,
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
                title ?: if (loading) stringResource(R.string.loading) else stringResource(R.string.untitled),
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
}

/** Single-song card, laid out like a music player's now-playing row rather than a picture: a
 * small square cover beside the (editable) title and artist, the album under them, and a
 * decorative waveform with the duration below — so a song reads as audio at a glance. The earlier
 * version put the title on top of a large square cover, which looked like an image preview
 * (reported live). Used for a Spotify track, a music.youtube.com/soundcloud.com link, or any link
 * where the user picked "Audio" quality. */
@Composable
private fun SongPreviewCard(
    url: String,
    title: String?,
    artist: String?,
    album: String?,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    durationMs: Long?,
    filesizeBytes: Long?,
    thumbnail: String?,
    loading: Boolean,
    loadingStatus: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
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
                            Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    if (loading) ContainedLoadingIndicator()
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.song), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    if (title != null) {
                        // Editable, pre-filled with the auto-cleaned scraped title (see
                        // cleanTrackTitle) — it's what the saved file gets tagged and named with.
                        // The pencil says so without turning the card into a form.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = title,
                                onValueChange = onTitleChange,
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f).clearFocusOnKeyboardDismiss(),
                            )
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.title_and_artist_are_editable),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            )
                        }
                    } else {
                        Text(
                            if (loading) loadingStatus ?: stringResource(R.string.loading) else stringResource(R.string.untitled),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (artist != null) {
                        BasicTextField(
                            value = artist,
                            onValueChange = onArtistChange,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                        )
                    }
                    if (album != null) {
                        Text(
                            album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongWaveform(seed = title ?: url, modifier = Modifier.weight(1f).height(28.dp))
                if (durationMs != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        formatDurationShort(durationMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (filesizeBytes != null) {
                Spacer(Modifier.height(12.dp))
                InfoPill {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.audio_x, formatFilesize(filesizeBytes)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A still, decorative waveform — rounded bars whose heights come from [seed] (the title), so a
 * given song always draws the same shape. Not the real audio: nothing is downloaded yet. */
@Composable
private fun SongWaveform(seed: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val heights = remember(seed) {
        val random = kotlin.random.Random(seed.hashCode())
        List(48) { i ->
            // A gentle swell across the width, so it reads as a track rather than noise.
            val envelope = 0.45f + 0.55f * kotlin.math.sin(Math.PI * (i + 0.5) / 48).toFloat()
            (0.2f + 0.8f * random.nextFloat()) * envelope
        }
    }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (heights.size - 1)) / heights.size).coerceAtLeast(1f)
        heights.forEachIndexed { i, h ->
            val barHeight = (size.height * h).coerceAtLeast(barWidth)
            drawRoundRect(
                color = color.copy(alpha = 0.55f),
                topLeft = androidx.compose.ui.geometry.Offset(i * (barWidth + gap), (size.height - barHeight) / 2),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

/** The album/playlist a SONG_LIST's tracks belong to: cover, title, artist, and how many songs
 * and how long in total. */
@Composable
private fun AlbumHeader(
    url: String,
    title: String,
    artist: String?,
    thumbnail: String?,
    trackCount: Int,
    totalDurationMs: Long?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
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
            } else {
                Icon(
                    Icons.Outlined.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.album), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                artist,
                pluralStringResource(R.plurals.song_count, trackCount, trackCount),
                totalDurationMs?.let { formatDurationShort(it) },
            ).joinToString(" · ")
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One selectable song in a SONG_LIST track list — a Seal-style list row: leading checkbox, a
 * horizontal 16:9 thumbnail, then title/artist with duration trailing. [track]'s own thumbnail is
 * real per-track art when the source engine provides one (YouTube Music playlists do), or the
 * whole collection's cover as a fallback (see [TrackPreview.thumbnail]'s own doc comment) — either
 * way there's always something to show, never a bare placeholder box next to real art elsewhere in
 * the same list. */
@Composable
private fun TrackRow(track: TrackPreview, url: String, selected: Boolean, onToggle: () -> Unit, squareArt: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                this.role = Role.Checkbox
            }
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        // Square cover art for songs (an album/playlist), a 16:9 frame for videos.
        Box(
            modifier = Modifier
                .let { if (squareArt) it.size(56.dp) else it.width(112.dp).aspectRatio(16f / 9f) }
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (track.thumbnail != null) {
                AsyncImage(
                    model = thumbnailRequest(track.thumbnail, url),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            // Picture or video, in a listing that mixes them (an Instagram carousel, say) — the
            // thumbnails alone don't tell them apart. Nothing when the listing doesn't say.
            if (track.isVideo != null && !squareArt) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Icon(
                        if (track.isVideo) Icons.Outlined.PlayArrow else Icons.Outlined.Image,
                        contentDescription = if (track.isVideo) stringResource(R.string.video) else stringResource(R.string.picture),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title ?: stringResource(R.string.untitled),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist != null) {
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val trailing = listOfNotNull(track.durationMs?.let(::formatDurationShort), track.sizeBytes?.let(::formatFilesize))
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtraCommandsScreen(
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetHeader(
            icon = Icons.Outlined.ContentCut,
            title = stringResource(R.string.trim_video_2),
            subtitle = stringResource(R.string.trim_subtitle),
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp),
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
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        )
                    }
                }
            },
        )

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
                        if (ms <= (active?.endMs ?: 0L)) {
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
                    parseTimestampToMs(it)?.let { ms ->
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
