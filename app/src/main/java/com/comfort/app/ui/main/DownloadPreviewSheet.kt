@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.comfort.app.ui.main

import com.comfort.app.R
import com.comfort.app.viewmodel.rememberSheetViewModelStoreOwner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.comfort.app.data.AudioFormat
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.PreviewInfo
import com.comfort.app.util.TrackPreview
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
    /** A channel's checked videos by their own links: each is queued as a download of its own
     * (LinkRouterViewModel.download). Picked by number ([itemFilter]), the channel listed again
     * when the download started, and a video uploaded in between moved every pick onto the next
     * video. Null for anything else. */
    val itemUrls: List<String>? = null,
    /** This download only leaves no Library entry or history (the sheet's Incognito chip); null
     * follows Settings › Downloads' "Incognito by default". */
    val incognito: Boolean? = null,
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
internal fun List<TrimSegment>.toClipRange(realDurationMs: Long?): String? {
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
    // This download only: no Library entry or history once it's saved (Settings › Downloads'
    // "Incognito by default" is the starting point).
    val incognitoContext = androidx.compose.ui.platform.LocalContext.current
    var incognito by remember { mutableStateOf(GalleryDlPreferences.isIncognitoDefault(incognitoContext)) }
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
                    // A drag must go far enough to close it; Back (and the scrim) ask with the
                    // sheet where it sits, not dragged at all, and close it. The distance rule
                    // alone also refused those: Back did nothing (reported).
                    val offset = sheetStateRef[0]?.let { runCatching { it.requireOffset() }.getOrNull() }
                    val dragged = if (offset == null || expandedOffsetPx.isNaN()) null else offset - expandedOffsetPx
                    dragged == null || dragged < 1f || dragged >= minDismissDragPx
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
            incognito = incognito,
            onToggleIncognito = { incognito = !incognito },
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
            onDownload = { options ->
                val picked = tracks.filter { it.num in selectedNums }
                val links = picked.mapNotNull { it.key }
                onDownload(
                    if (sheet.isChannel && links.isNotEmpty() && links.size == picked.size) options.copy(itemUrls = links)
                    else options,
                )
            },
            onSubscribe = if (canSubscribe) onSubscribe else null,
        )
    }
}

// Matches a parenthesized/bracketed segment containing common upload clutter — "(Official Video)",
// "[Official Music Video]", "(Lyrics)", "[HD]", "(Visualizer)", etc. Matched case-insensitively,
// keyword-anchored with \b so it doesn't eat a segment that merely contains one of these words as
// part of something else (e.g. a real "(Radio Edit)" stays untouched — "edit" isn't in the list).
internal val CLUTTER_TAG_RE = Regex(
    """[(\[][^()\[\]]*\b(official\s*(video|audio|music\s*video|lyric\s*video)?|lyrics?|visualizer|explicit|clean\s*version|hd|4k|m/?v)\b[^()\[\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)
internal val EXTRA_SPACES_RE = Regex("""\s{2,}""")
internal val TOPIC_SUFFIX_RE = Regex("""\s*-\s*Topic\s*$""", RegexOption.IGNORE_CASE)
