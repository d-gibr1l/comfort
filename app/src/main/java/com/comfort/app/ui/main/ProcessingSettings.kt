package com.comfort.app.ui.main

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.comfort.app.data.AudioFormat
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
    var audioFormat by remember { mutableStateOf(GalleryDlPreferences.getAudioFormat(context)) }
    var normalizeAudio by remember { mutableStateOf(GalleryDlPreferences.isNormalizeAudio(context)) }

    SettingsSubScaffold(title = "Processing", topicIcon = Icons.Outlined.Movie, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Video downloads", icon = Icons.Outlined.Movie) {
            ToggleReveal(formatIdOverride.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Format ID override is active (\"$formatIdOverride\"). Quality choices below will be bypassed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

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
                    .padding(horizontal = 20.dp)
                    .selectableGroup(),
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
                        modifier = Modifier
                            .height(40.dp)
                            .semantics { role = Role.RadioButton },
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
                "For videos from yt-dlp sources; anything gallery-dl fetches directly keeps its own format. MKV saves the streams exactly as the site sends them. MP4 plays everywhere: video that can't go in an MP4 as it is (VP9 or AV1, like most Instagram Reels and YouTube above 1080p) is converted to H.264 on your phone's video encoder after downloading, which takes a little longer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
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
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .semantics { role = Role.RadioButton },
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
                icon = Icons.Outlined.VideoFile,
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
                icon = Icons.AutoMirrored.Outlined.List,
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
            ToggleReveal(downloadSubtitles) {
                Spacer(Modifier.height(12.dp))
                val isSubtitlesError = subtitleLanguages.isNotBlank() && subtitleLanguages.split(',').any { segment ->
                    val trimmed = segment.trim()
                    trimmed.isNotEmpty() && !SUBTITLE_LANG_REGEX.matches(trimmed)
                }
                OutlinedTextField(
                    value = subtitleLanguages,
                    onValueChange = { input ->
                        val sanitized = input.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ',' || it == ' ' }
                        subtitleLanguages = sanitized
                        GalleryDlPreferences.setSubtitleLanguages(context, sanitized.trim())
                    },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    label = { Text("Subtitle languages") },
                    placeholder = { Text("en") },
                    singleLine = true,
                    isError = isSubtitlesError,
                    supportingText = {
                        if (isSubtitlesError) {
                            Text("Use comma-separated language codes, e.g. \"en, es, en-US\"", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Comma-separated language codes, e.g. \"en,es\".")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.medium,
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
            val isFormatOverrideError = formatIdOverride.isNotBlank() && !isValidFormatSelector(formatIdOverride)
            OutlinedTextField(
                value = formatIdOverride,
                onValueChange = { input ->
                    val sanitized = input.filterNot { it in "\"'$`" || it == '\n' || it == '\r' }
                    formatIdOverride = sanitized
                    GalleryDlPreferences.setFormatIdOverride(context, sanitized.trim())
                },
                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                label = { Text("Format selector") },
                placeholder = { Text("bv+ba/b") },
                singleLine = true,
                isError = isFormatOverrideError,
                supportingText = {
                    if (isFormatOverrideError) {
                        Text("Check expression syntax (unbalanced brackets or trailing operator)", color = MaterialTheme.colorScheme.error)
                    } else if (formatIdOverride.isNotBlank()) {
                        Text("Overrides quality and output format for all yt-dlp downloads.", color = MaterialTheme.colorScheme.tertiary)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = MaterialTheme.shapes.medium,
            )
        }

        SettingsSection(title = "Music downloads", icon = Icons.Outlined.MusicNote) {
            Text("Audio format", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "For songs and audio-only downloads. Original saves the site's own audio untouched (usually M4A, or Opus). The others convert it unless it's already in that format: MP3 plays on anything, FLAC is lossless but can't add back quality the source didn't have.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val formats = AudioFormat.entries
                formats.forEachIndexed { index, format ->
                    val selected = audioFormat == format
                    val interactionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            audioFormat = format
                            GalleryDlPreferences.setAudioFormat(context, format)
                        },
                        // "Original" gets a wider share: five equal chips cut it short.
                        modifier = Modifier
                            .weight(if (format == AudioFormat.ORIGINAL) 1.7f else 1f)
                            .height(40.dp)
                            .semantics { role = Role.RadioButton },
                        interactionSource = interactionSource,
                        shape = rememberMorphingChipShape(index, formats.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(format.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
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
                icon = Icons.Outlined.GraphicEq,
                title = "Even out volume",
                subtitle = "Makes every song play at the same loudness, so a quiet track isn't followed by a blaring one. Re-encodes the audio.",
                checked = normalizeAudio,
                onCheckedChange = {
                    normalizeAudio = it
                    GalleryDlPreferences.setNormalizeAudio(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("Lyrics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Looked up on LRCLIB by the song's title, artist and length, and saved inside the file. Synced lyrics carry timestamps, so players that support them scroll along with the song; songs without synced lyrics get plain ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
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
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .semantics { role = Role.RadioButton },
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

            ToggleReveal(lyricsMode != GalleryDlPreferences.LYRICS_OFF) {
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

private val SUBTITLE_LANG_REGEX = Regex("^[a-zA-Z0-9_-]+$")

internal fun isValidFormatSelector(selector: String): Boolean {
    val trimmed = selector.trim()
    if (trimmed.isEmpty()) return true
    var squareBrackets = 0
    var roundBrackets = 0
    for (char in trimmed) {
        when (char) {
            '[' -> squareBrackets++
            ']' -> {
                squareBrackets--
                if (squareBrackets < 0) return false
            }
            '(' -> roundBrackets++
            ')' -> {
                roundBrackets--
                if (roundBrackets < 0) return false
            }
        }
    }
    if (squareBrackets != 0 || roundBrackets != 0) return false
    val last = trimmed.last()
    if (last == '/' || last == '+' || last == ',' || last == '|' || last == '&') return false
    return true
}
