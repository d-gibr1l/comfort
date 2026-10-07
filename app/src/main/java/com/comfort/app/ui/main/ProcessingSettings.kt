package com.comfort.app.ui.main

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import compose.icons.feathericons.Instagram
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Processing.

/** Quality/format and embed-into-the-file choices — the other half of what used to be one long
 * "Downloads" page, split out to match YTDLnis's own Processing screen. */
@Composable
internal fun ProcessingSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videoQuality by remember { mutableStateOf(GalleryDlPreferences.getVideoQuality(context)) }
    var outputFormat by remember { mutableStateOf(GalleryDlPreferences.getOutputFormat(context)) }
    var noPlaylist by remember { mutableStateOf(GalleryDlPreferences.isNoPlaylist(context)) }
    var liveFromStart by remember { mutableStateOf(GalleryDlPreferences.isLiveFromStart(context)) }
    var embedThumbnail by remember { mutableStateOf(GalleryDlPreferences.isEmbedThumbnail(context)) }
    var embedMetadata by remember { mutableStateOf(GalleryDlPreferences.isEmbedMetadata(context)) }
    var embedChapters by remember { mutableStateOf(GalleryDlPreferences.isEmbedChapters(context)) }
    var writeInfoFiles by remember { mutableStateOf(GalleryDlPreferences.isWriteInfoFiles(context)) }
    var downloadSubtitles by remember { mutableStateOf(GalleryDlPreferences.isDownloadSubtitles(context)) }
    var subtitleLanguages by remember { mutableStateOf(GalleryDlPreferences.getSubtitleLanguages(context)) }
    var saveSubtitleFiles by remember { mutableStateOf(GalleryDlPreferences.isSaveSubtitleFiles(context)) }
    var formatIdOverride by remember { mutableStateOf(GalleryDlPreferences.getFormatIdOverride(context)) }
    var lyricsMode by remember { mutableStateOf(GalleryDlPreferences.getLyricsMode(context)) }
    var lyricsLrc by remember { mutableStateOf(GalleryDlPreferences.isLyricsLrcFile(context)) }

    SettingsSubScaffold(title = "Processing", topicIcon = Icons.Outlined.Movie, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Video downloads", icon = Icons.Outlined.Movie) {
            Text("Quality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Applies to video links handled by yt-dlp (YouTube, Twitter/X, and similar).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                // Bleeds past the settings page's own 20dp side margin so the row's scrollable
                // viewport spans the full screen width, then re-adds that 20dp as inner padding so
                // the resting position still looks inset like the rest of the page — chips can now
                // scroll flush to the true screen edge instead of getting clipped mid-chip right at
                // the page margin, which read as "cut off." Widens via a custom layout rather than
                // Modifier.padding with a negative value — Compose's padding() throws at runtime on
                // negative dp, it isn't a supported way to do this.
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val bleed = 20.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                        layout(placeable.width - bleed * 2, placeable.height) {
                            placeable.placeRelative(-bleed, 0)
                        }
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val qualities = VideoQuality.entries
                qualities.forEachIndexed { index, quality ->
                    val selected = videoQuality == quality
                    val interactionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            videoQuality = quality
                            GalleryDlPreferences.setVideoQuality(context, quality)
                        },
                        modifier = Modifier.height(40.dp),
                        interactionSource = interactionSource,
                        shape = rememberMorphingChipShape(index, qualities.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                        label = { Text(quality.label, style = MaterialTheme.typography.labelLarge) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = null,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("Output format", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Only applies when video and audio need merging (most yt-dlp sources). A single already-muxed file, or anything gallery-dl fetches directly, keeps its own format regardless. If MP4 is picked but a source's video can't actually go in an MP4 (Instagram Reels are usually like this), that one download saves as MKV instead rather than an unplayable MP4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val formats = OutputFormat.entries
                formats.forEachIndexed { index, format ->
                    val selected = outputFormat == format
                    val interactionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            outputFormat = format
                            GalleryDlPreferences.setOutputFormat(context, format)
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        interactionSource = interactionSource,
                        shape = rememberMorphingChipShape(index, formats.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(format.label, style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = null,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.List,
                title = "Single video only",
                subtitle = "Only downloads the specific video from a link, even if it belongs to a larger playlist or channel.",
                checked = noPlaylist,
                onCheckedChange = {
                    noPlaylist = it
                    GalleryDlPreferences.setNoPlaylist(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.FastRewind,
                title = "Live streams from the start",
                subtitle = "Downloads live streams from the beginning instead of the current moment.",
                checked = liveFromStart,
                onCheckedChange = {
                    liveFromStart = it
                    GalleryDlPreferences.setLiveFromStart(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Image,
                title = "Embed thumbnail",
                subtitle = "Save the video's thumbnail as cover art inside the file.",
                checked = embedThumbnail,
                onCheckedChange = {
                    embedThumbnail = it
                    GalleryDlPreferences.setEmbedThumbnail(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Tag,
                title = "Embed metadata",
                subtitle = "Tag the file with its title, uploader, and other details.",
                checked = embedMetadata,
                onCheckedChange = {
                    embedMetadata = it
                    GalleryDlPreferences.setEmbedMetadata(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.List,
                title = "Embed chapters",
                subtitle = "Saves chapter markers inside the video file.",
                checked = embedChapters,
                onCheckedChange = {
                    embedChapters = it
                    GalleryDlPreferences.setEmbedChapters(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Description,
                title = "Write description / info.json files",
                subtitle = "Save a separate JSON metadata file alongside each download.",
                checked = writeInfoFiles,
                onCheckedChange = {
                    writeInfoFiles = it
                    GalleryDlPreferences.setWriteInfoFiles(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (downloadSubtitles) Icons.Outlined.Subtitles else Icons.Outlined.SubtitlesOff,
                title = "Download subtitles",
                subtitle = "Fetch and embed subtitles when they're available.",
                checked = downloadSubtitles,
                onCheckedChange = {
                    downloadSubtitles = it
                    GalleryDlPreferences.setDownloadSubtitles(context, it)
                },
            )
            if (downloadSubtitles) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = subtitleLanguages,
                    onValueChange = {
                        subtitleLanguages = it
                        GalleryDlPreferences.setSubtitleLanguages(context, it)
                    },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    label = { Text("Subtitle languages") },
                    placeholder = { Text("en") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Comma-separated language codes, e.g. \"en,es\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (saveSubtitleFiles) Icons.Outlined.Subtitles else Icons.Outlined.SubtitlesOff,
                title = "Save subtitle files",
                subtitle = "Saves subtitles as a separate file (.srt/.vtt) next to the video instead of only embedding them.",
                checked = saveSubtitleFiles,
                onCheckedChange = {
                    saveSubtitleFiles = it
                    GalleryDlPreferences.setSaveSubtitleFiles(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("Format ID override", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "yt-dlp only — a raw format selector (e.g. \"137+140\", or any of yt-dlp's own -f expression syntax) that fully replaces Video quality above for every download. Leave blank to let the quality picker choose as usual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = formatIdOverride,
                onValueChange = {
                    formatIdOverride = it
                    GalleryDlPreferences.setFormatIdOverride(context, it)
                },
                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                label = { Text("Format selector") },
                placeholder = { Text("bv+ba/b") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }

        SettingsSection(title = "Music downloads", icon = Icons.Outlined.MusicNote) {
            Text("Lyrics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Looked up on LRCLIB by the song's title, artist and length, and saved inside the file. Synced lyrics carry timestamps, so players that support them scroll along with the song; songs without synced lyrics get plain ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val modes = listOf(
                    GalleryDlPreferences.LYRICS_SYNCED to "Synced",
                    GalleryDlPreferences.LYRICS_PLAIN to "Plain",
                    GalleryDlPreferences.LYRICS_OFF to "Off",
                )
                modes.forEachIndexed { index, (mode, label) ->
                    val selected = lyricsMode == mode
                    val interactionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            lyricsMode = mode
                            GalleryDlPreferences.setLyricsMode(context, mode)
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        interactionSource = interactionSource,
                        shape = rememberMorphingChipShape(index, modes.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = null,
                    )
                }
            }

            if (lyricsMode != GalleryDlPreferences.LYRICS_OFF) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))

                IconToggleRow(
                    icon = Icons.Outlined.Description,
                    title = "Save .lrc file",
                    subtitle = "Also saves synced lyrics as a .lrc file next to the song, for players that only read those.",
                    checked = lyricsLrc,
                    onCheckedChange = {
                        lyricsLrc = it
                        GalleryDlPreferences.setLyricsLrcFile(context, it)
                    },
                )
            }
        }
    }
}
