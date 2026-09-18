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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/** Which card/chip layout MainPreviewScreen renders — VIDEO is today's original layout,
 * unchanged; SONG_SINGLE is the square-art/artist-album card for one song; SONG_LIST is the
 * per-track checklist for a Spotify album/playlist. See DownloadPreviewSheet's own mode
 * computation for exactly how a URL/quality combination picks one of these. */
private enum class PreviewMode { VIDEO, SONG_SINGLE, SONG_LIST }

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
                    revertOverlay()
                    false
                } else {
                    true
                }
            },
            skipHiddenState = false,
        )
    }

    // Everything the listing pass returns — one state var instead of one per field (title/
    // uploader/thumbnail/filesize/streamUrls/durationMs used to each be their own, before this
    // grew artist/album/tracks/collection* alongside them for the song preview). The listing
    // pass is the same one the share picker already uses, so none of this costs anything new on
    // the engine side.
    var preview by remember { mutableStateOf<PreviewInfo?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    // Real-time status text from the listing pass itself (e.g. "Fetching info…", "Found 40
    // tracks…") — shown in place of a static "Loading…" while previewLoading is true, so a long
    // playlist's listing doesn't look stuck with no feedback. Null falls back to "Loading…".
    var previewStatus by remember { mutableStateOf<String?>(null) }
    // Checked once per url+itemFilter, independent of the listing fetch below — drives MAIN's own
    // Download button reading "Redownload" instead, so a duplicate is known *before* the user
    // commits to downloading rather than only surfacing afterward. Replaces the old post-hoc
    // Snackbar+its own separate "Redownload" action; the button itself already saying
    // "Redownload" here is the confirmation, so the caller's own onDownload now just forces
    // straight through. Re-keyed on the track selection below (see its own doc comment) so
    // unchecking a song doesn't leave a stale duplicate verdict from the full album.
    var isDuplicate by remember { mutableStateOf(false) }

    // Which songs are selected for a song-list (Spotify album/playlist) preview — 1-based
    // GalleryDlListing.TrackPreview.num values, same numbering "num in {...}" item-filter strings
    // already use app-wide (see DownloadOptions.itemFilter's own doc comment). Seeded to "every
    // track" the moment the track list arrives, same pattern SharePickerScreen already uses for
    // its own gallery-dl multi-item selection.
    var selectedNums by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(preview?.tracks) {
        preview?.tracks?.takeIf { it.isNotEmpty() }?.let { selectedNums = it.map { t -> t.num }.toSet() }
    }

    // "num in {1,3,4}" — same gallery-dl-syntax expression SharePickerScreen already builds for
    // its own selection, kept as one string format app-wide (see DownloadWorker's own per-engine
    // translation of DownloadEntity.itemFilter). Null whenever there's no song list or nothing's
    // been deselected — "everything" is the common case, not a special one.
    val tracks = preview?.tracks.orEmpty()
    val itemFilter = if (tracks.isEmpty() || selectedNums.size == tracks.size) {
        null
    } else {
        "num in {${selectedNums.sorted().joinToString(",")}}"
    }

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

    // SONG_SINGLE's own editable title/artist fields (SongPreviewCard) — null until the listing
    // pass resolves a real value to seed from. Seeded exactly once per url (the `== null` guard),
    // so a user edit survives the preview's own later state updates (e.g. isDuplicate's separate
    // effect re-running) instead of getting silently overwritten mid-edit.
    var editedTitle by remember(url) { mutableStateOf<String?>(null) }
    var editedArtist by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(preview?.title) {
        if (editedTitle == null) preview?.title?.let { editedTitle = cleanTrackTitle(it) }
    }
    LaunchedEffect(preview?.artist) {
        if (editedArtist == null) preview?.artist?.let { editedArtist = cleanArtistName(it) }
    }

    LaunchedEffect(url) {
        previewLoading = true
        previewStatus = null
        preview = GalleryDlListing.fetchPreviewInfo(context, url) { status -> previewStatus = status }
        previewLoading = false
    }
    // Re-checked whenever the selection changes (not just the url) — SharePickerScreen already
    // does the same thing for its own itemFilter, for the same reason: a 4-track subset and the
    // full 16-track album are different downloads (DownloadDao.findActiveOrFinishedByUrl treats
    // them that way), so "Redownload" should only show once the *current* selection, not some
    // earlier one, is confirmed to already exist.
    LaunchedEffect(url, itemFilter) {
        isDuplicate = DownloadDispatcher.isDuplicate(context, url, itemFilter)
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
            previewFilesize = preview?.filesizeBytes,
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
                onToggleNum = { num ->
                    selectedNums = if (num in selectedNums) selectedNums - num else selectedNums + num
                },
                onToggleAll = {
                    selectedNums = if (selectedNums.size == tracks.size) emptySet() else tracks.map { it.num }.toSet()
                },
                editedTitle = editedTitle,
                editedArtist = editedArtist,
                onEditedTitleChange = { editedTitle = it },
                onEditedArtistChange = { editedArtist = it },
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
            onToggleFormat = {
                outputFormat = if (outputFormat == OutputFormat.MP4) OutputFormat.MKV else OutputFormat.MP4
            },
            saveThumbnail = saveThumbnail,
            onToggleSaveThumbnail = { saveThumbnail = !saveThumbnail },
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
    clipboard: androidx.compose.ui.platform.ClipboardManager,
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
    onToggleFormat: () -> Unit,
    saveThumbnail: Boolean,
    onToggleSaveThumbnail: () -> Unit,
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
) {
    val overlayOpen = screen != PreviewScreen.MAIN

    // Live drag offset for the overlay panel's own handle — follows the finger while dragging
    // down (like the outer sheet's own handle does), snapped back to 0 whenever the overlay opens
    // fresh for a different screen.
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    LaunchedEffect(overlayOpen) { if (overlayOpen) dragOffsetPx = 0f }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 48.dp.toPx() } }
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
                    if (available.y > 1000f || dragOffsetPx > thresholdPx) {
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
            onToggleFormat = onToggleFormat,
            saveThumbnail = saveThumbnail,
            onToggleSaveThumbnail = onToggleSaveThumbnail,
            trimmed = segmentsEdited,
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
                            overrideTitle = song.editedTitle?.takeIf { song.mode == PreviewMode.SONG_SINGLE },
                            overrideArtist = song.editedArtist?.takeIf { song.mode == PreviewMode.SONG_SINGLE },
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
                                    // Same gate as the real download's clipRange below — showing a
                                    // --download-sections flag here for a trim that was never
                                    // actually confirmed would be a command that doesn't match what
                                    // downloading for real actually does.
                                    segments = if (segmentsEdited) segments else emptyList(),
                                    durationMs = previewDurationMs,
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
                                            segments = if (segmentsEdited) segments else emptyList(),
                                            durationMs = previewDurationMs,
                                            filenameTemplate = filenameTemplate,
                                            extraCommands = commands,
                                        )))
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
private val TOPIC_SUFFIX_RE = Regex(""" - Topic$""")

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
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy link")
            }
            // Only SONG_LIST has a selection to toggle — the control TrackListHeader used to
            // provide before it was removed, moved here so it survives without needing its own
            // dedicated row.
            if (isList) {
                TextButton(onClick = song.onToggleAll) {
                    Text(if (song.selectedNums.size == song.tracks.size) "Deselect all" else "Select all")
                }
            }
            FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.HighlightOff, contentDescription = "Cancel")
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

        LazyColumn(
            modifier = Modifier.fillMaxWidth().let { if (isList) it.weight(1f) else it },
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
                items(song.tracks, key = { it.num }) { track ->
                    TrackRow(
                        track = track,
                        url = url,
                        selected = track.num in song.selectedNums,
                        onToggle = { song.onToggleNum(track.num) },
                    )
                }
            }
        }

        } // end LazyColumn

        // Fixed chips row — outside the LazyColumn above, so it never scrolls out of view
        // alongside the top icon row and the Download button below. Bottom padding keeps it from
        // crowding the Download button now that both are fixed and always touching.
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // A video list (song.mode == SONG_LIST but showQualityRow is still true, since it's
            // not a song source) gets the same chip set as a single VIDEO — Format toggle, no
            // song-only "cover art" wording — minus Trim, which doesn't make sense across several
            // selected videos at once. song.mode == VIDEO keeps Trim since there's exactly one.
            val videoStyledChips = song.mode == PreviewMode.VIDEO || song.showQualityRow
            if (videoStyledChips) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(12.dp))
                        PreviewChip(
                            label = "Save thumbnail",
                            selected = saveThumbnail,
                            icon = Icons.Outlined.Image,
                            shape = FIRST_CHIP_SHAPE,
                            onClick = onToggleSaveThumbnail,
                        )
                        PreviewChip(
                            label = if (commandCount > 0) "Commands ($commandCount)" else "Add extra Commands",
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
                                label = "Trim Video",
                                selected = trimmed,
                                icon = Icons.Outlined.ContentCut,
                                shape = FIRST_CHIP_SHAPE,
                                onClick = onOpenTrim,
                            )
                        }
                        PreviewChip(
                            label = outputFormat.name.lowercase().replaceFirstChar { it.uppercase() },
                            icon = Icons.Outlined.Movie,
                            shape = if (showTrim) MIDDLE_CHIP_SHAPE else FIRST_CHIP_SHAPE,
                            onClick = onToggleFormat,
                        )
                        PreviewChip(
                            label = "Filename Templates.",
                            selected = filenameTemplate != null,
                            icon = Icons.Outlined.Tag,
                            shape = LAST_CHIP_SHAPE,
                            onClick = onOpenTemplates,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                } else {
                    // Song modes: no Trim (not a video), no Mp4/Mkv format toggle (yt-dlp's own
                    // audio-only output format isn't a container choice the way video is) — just
                    // the cover-art save toggle, Commands, and Filename Templates.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(12.dp))
                        PreviewChip(
                            label = "Save cover art",
                            selected = saveThumbnail,
                            icon = Icons.Outlined.Image,
                            shape = FIRST_CHIP_SHAPE,
                            onClick = onToggleSaveThumbnail,
                        )
                        PreviewChip(
                            label = if (commandCount > 0) "Commands ($commandCount)" else "Add extra Commands",
                            selected = commandCount > 0,
                            icon = Icons.Outlined.Terminal,
                            shape = MIDDLE_CHIP_SHAPE,
                            onClick = onOpenCommands,
                        )
                        PreviewChip(
                            label = "Filename Templates.",
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
        val countSuffix = if (song.mode == PreviewMode.SONG_LIST) " ${song.selectedNums.size}" else ""
        Button(
            onClick = onDownload,
            enabled = downloadEnabled,
            // 24dp horizontal inset matches MainScreen's own bottom nav pill's margin
            // (navBarPill's own Box uses padding(horizontal = 24.dp)) — kept consistent so
            // this button reads the same width as that pill rather than running edge-to-edge.
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp).height(56.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            // Known ahead of time (DownloadPreviewSheet's own isDuplicate check), not discovered
            // only after tapping — replaces the old flow where this always said "Download" and a
            // duplicate only surfaced afterward via a Snackbar with its own separate "Redownload"
            // action to confirm.
            Text("${if (isDuplicate) "Redownload" else "Download"}$countSuffix")
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
}

/** Single-song card: centered, inset square cover art (65% of the card's width) with title/
 * artist/duration scrimmed directly onto the image (a dark gradient rising from the bottom, like
 * a streaming app's own Now-Playing screen), instead of the video card's inset 16:9 rectangle
 * with text below it. "Artist — Album" subtitle via the shared [audioSubtitle] helper. Used for a
 * bare Spotify track link, a music.youtube.com/soundcloud.com link, or any other link where the
 * user manually picked "Audio" quality. Went through a full-bleed (edge-to-edge) version first —
 * sized back down to this inset, centered box after review found the full-bleed art too large
 * relative to the rest of the sheet. */
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
    // No outer Card here (unlike VideoPreviewCard/TrackListHeader) — the art box is the whole
    // card, on its own against the sheet's own background, rather than sitting inside a second,
    // visibly-colored container around it.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
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
                    modifier = Modifier.size(40.dp).align(Alignment.Center),
                )
            }
            if (loading) {
                ContainedLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Text sits directly on the art, so it needs its own scrim to stay legible over any
            // image — a fixed dark gradient rather than theme-derived colors (MaterialTheme's own
            // onPrimaryContainer isn't guaranteed to contrast against an arbitrary cover-art
            // photo the way it is against the flat placeholder background above).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column {
                    if (title != null) {
                        // Editable, pre-filled with the auto-cleaned scraped title (see
                        // cleanTrackTitle's own doc comment) — a plain BasicTextField rather than
                        // an OutlinedTextField/TextField, since this sits directly on the scrim
                        // over the art and needs no visible box/label of its own, just a cursor,
                        // to read as "the title, which happens to be editable" rather than a form.
                        BasicTextField(
                            value = title,
                            onValueChange = onTitleChange,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (loading) {
                        Text(
                            loadingStatus ?: "Loading…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (artist != null) {
                            BasicTextField(
                                value = artist,
                                onValueChange = onArtistChange,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.85f)),
                                singleLine = true,
                                cursorBrush = SolidColor(Color.White),
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        val trailing = listOfNotNull(
                            album,
                            durationMs?.let { formatDurationShort(it) },
                            filesizeBytes?.let { formatFilesize(it) },
                        ).joinToString(" · ")
                        if (trailing.isNotEmpty()) {
                            Text(
                                (if (artist != null) " — " else "") + trailing,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
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
private fun TrackRow(track: TrackPreview, url: String, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
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
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title ?: "Untitled",
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
        if (track.durationMs != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatDurationShort(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    durationMs: Long?,
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
    segments.toClipRange(durationMs)?.let { clip -> parts += "--download-sections \"*$clip\"" }

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
    durationMs: Long?,
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
            durationMs = durationMs,
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
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy current command")
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
            modifier = Modifier.fillMaxWidth().height(50.dp),
            placeholder = { Text("Add Command") },
            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
            trailingIcon = {
                Box(modifier = Modifier.padding(end = 8.dp)) {
                    PreviewChip(label = "Add", onClick = { add() })
                }
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
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Trim video",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp),
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
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete segment")
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
            leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            supportingText = message?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
                                    contentDescription = "Delete template",
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











