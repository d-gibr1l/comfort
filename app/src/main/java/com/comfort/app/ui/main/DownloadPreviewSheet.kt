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
internal const val NOMINAL_DURATION_MS = 10 * 60 * 1000L
internal const val DEFAULT_SEGMENT_LENGTH_MS = 30 * 1000L

/** Which of the sheet's screens is showing. MAIN is always composed; every other value renders as
 * a panel that slides up from the bottom and overlays MAIN in place, rather than a second real
 * ModalBottomSheet: a popup inside a popup gets dismissed *along with* its parent on tap in this
 * Compose version (reproduced live earlier with a DropdownMenu inside a ModalBottomSheet, which
 * closed both), so nesting real sheets here would reintroduce exactly that bug. The overlay panel
 * is a plain Surface animated with slideInVertically/slideOutVertically instead. */
internal enum class PreviewScreen { MAIN, COMMANDS, TRIM, TEMPLATES, VIEW_TEMPLATES }

/** Which card/chip layout MainPreviewScreen renders — VIDEO is today's original layout,
 * unchanged; SONG_SINGLE is the square-art/artist-album card for one song; SONG_LIST is the
 * per-track checklist for a Spotify album/playlist. See DownloadPreviewSheet's own mode
 * computation for exactly how a URL/quality combination picks one of these. */
internal enum class PreviewMode { VIDEO, SONG_SINGLE, SONG_LIST }

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
internal data class SongPreviewState(
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
internal data class OverlaySnapshot(
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
    /** "Start at…" picked in the sheet (epoch millis) — see DownloadEntity.scheduledAt; null starts
     * it as usual (now, or when the schedule window opens). */
    val scheduledAt: Long? = null,
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
internal fun thumbnailRequest(thumbnail: String, pageUrl: String): ImageRequest =
    ImageRequest.Builder(LocalContext.current)
        .data(thumbnail)
        .addHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        )
        .addHeader("Referer", pageUrl)
        .build()

internal fun parseTimestampToMs(value: String): Long? {
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
internal fun formatTimestamp(ms: Long): String {
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
    // The default segment seeded before the video's length was known (Trim opened while the
    // preview was still loading) is cut down to it once it arrives, without counting as an edit.
    val knownDurationMs = preview?.durationMs?.takeIf { it > 0L }
    LaunchedEffect(knownDurationMs) {
        val d = knownDurationMs ?: return@LaunchedEffect
        if (!segmentsEdited && segments.any { it.endMs > d }) {
            segments = segments.map { it.copy(endMs = it.endMs.coerceAtMost(d)) }
        }
    }
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

    // "Start at…": the time this download waits for, if one was picked (StartAtDialog, shared
    // with the Queue's cards). Only this sheet's: a new opening starts without one.
    var startAt by remember { mutableStateOf<Long?>(null) }
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
internal fun formatDurationShort(ms: Long): String {
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
    /** The "Start at…" chip: the time picked (null: none) and what tapping it opens. */
    startAt: Long? = null,
    onOpenStartAt: () -> Unit = {},
) {
    val startAtLabel = startAt?.let {
        stringResource(R.string.starts_at_x, android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(it)))
    } ?: stringResource(R.string.start_at)
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
                            shape = MIDDLE_CHIP_SHAPE,
                            onClick = onOpenCommands,
                        )
                        // Added now, started later (StartAtDialog).
                        PreviewChip(
                            label = startAtLabel,
                            selected = startAt != null,
                            icon = Icons.Outlined.Schedule,
                            shape = LAST_CHIP_SHAPE,
                            onClick = onOpenStartAt,
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
                            label = startAtLabel,
                            selected = startAt != null,
                            icon = Icons.Outlined.Schedule,
                            shape = MIDDLE_CHIP_SHAPE,
                            onClick = onOpenStartAt,
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
