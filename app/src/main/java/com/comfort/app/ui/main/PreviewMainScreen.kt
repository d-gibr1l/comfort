@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

// The preview sheet's main screen: the item, its format choices and the Download button.
package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.AudioFormat
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MainPreviewScreen(
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
    incognito: Boolean,
    onToggleIncognito: () -> Unit,
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
    /** "Start at…" (the Download button's trailing half): the time picked (null: none) and what
     * tapping it opens. */
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
                        IncognitoChip(incognito, onToggleIncognito)
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
                        // (Incognito is in the row above: a video's sheet has two rows, and it
                        // showed in both.)
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
                            shape = MIDDLE_CHIP_SHAPE,
                            onClick = onOpenTemplates,
                        )
                        IncognitoChip(incognito, onToggleIncognito)
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
            // Download and, on its trailing half, "Start at…" (StartAtDialog): added now, started
            // at the time picked. The leading half says when once one is picked. It was a chip at
            // the end of a chip row, where it ran off the screen.
            // Full width like the button it replaced: SplitButtonLayout sizes its halves to their
            // content, so the halves are laid out here (its look: rounded outer ends, small inner
            // corners, a 2 dp gap).
            val outer = 28.dp
            val inner = 4.dp
            Row(modifier = Modifier.weight(1f).height(56.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = downloadEnabled,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner),
                ) {
                    Icon(
                        if (startAt != null) Icons.Outlined.Schedule else Icons.Outlined.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Known ahead of time (DownloadPreviewSheet's own isDuplicate check), not discovered
                    // only after tapping — replaces the old flow where this always said "Download" and a
                    // duplicate only surfaced afterward via a Snackbar with its own separate "Redownload"
                    // action to confirm.
                    Text(
                        if (startAt != null) startAtLabel + countSuffix
                        else stringResource(if (isDuplicate) R.string.redownload else R.string.download_action) + countSuffix,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onOpenStartAt,
                    enabled = downloadEnabled,
                    modifier = Modifier.width(64.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.start_at), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** The preview sheet's Incognito chip: this download leaves no Library entry or history. */
@Composable
private fun IncognitoChip(on: Boolean, onToggle: () -> Unit) {
    PreviewChip(
        label = stringResource(R.string.incognito),
        selected = on,
        icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = R.drawable.ic_domino_mask),
        shape = LAST_CHIP_SHAPE,
        onClick = onToggle,
    )
}
