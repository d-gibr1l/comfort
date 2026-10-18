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
import com.comfort.app.R
import androidx.compose.ui.res.stringResource
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
    var splitChapters by remember { mutableStateOf(GalleryDlPreferences.isSplitChapters(context)) }
    var sponsorBlockMode by remember { mutableStateOf(GalleryDlPreferences.getSponsorBlockMode(context)) }
    var sponsorBlockCategories by remember { mutableStateOf(GalleryDlPreferences.getSponsorBlockCategories(context)) }
    var writeInfoFiles by remember { mutableStateOf(GalleryDlPreferences.isWriteInfoFiles(context)) }
    var downloadSubtitles by remember { mutableStateOf(GalleryDlPreferences.isDownloadSubtitles(context)) }
    var subtitleLanguages by remember { mutableStateOf(GalleryDlPreferences.getSubtitleLanguages(context)) }
    var saveSubtitleFiles by remember { mutableStateOf(GalleryDlPreferences.isSaveSubtitleFiles(context)) }
    var formatIdOverride by remember { mutableStateOf(GalleryDlPreferences.getFormatIdOverride(context)) }
    var lyricsMode by remember { mutableStateOf(GalleryDlPreferences.getLyricsMode(context)) }
    var lyricsLrc by remember { mutableStateOf(GalleryDlPreferences.isLyricsLrcFile(context)) }
    var audioFormat by remember { mutableStateOf(GalleryDlPreferences.getAudioFormat(context)) }
    var normalizeAudio by remember { mutableStateOf(GalleryDlPreferences.isNormalizeAudio(context)) }
    var webpToJpg by remember { mutableStateOf(GalleryDlPreferences.isWebpToJpg(context)) }

    SettingsSubScaffold(title = stringResource(R.string.processing), topicIcon = Icons.Outlined.Movie, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = stringResource(R.string.video_downloads), icon = Icons.Outlined.Movie) {
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
                            stringResource(R.string.format_id_override_is_active_x, formatIdOverride),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            Text(stringResource(R.string.quality), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.applies_to_video_links_handled_by),
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
                        label = { Text(quality.displayLabel, style = MaterialTheme.typography.labelLarge) },
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

            Text(stringResource(R.string.output_format), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.for_videos_from_yt_dlp_sources),
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
                title = stringResource(R.string.single_video_only),
                subtitle = stringResource(R.string.only_downloads_the_specific_video_from),
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
                title = stringResource(R.string.live_streams_from_the_start),
                subtitle = stringResource(R.string.downloads_live_streams_from_the_beginning),
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
                title = stringResource(R.string.embed_thumbnail),
                subtitle = stringResource(R.string.save_the_video_s_thumbnail_as),
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
                title = stringResource(R.string.embed_metadata),
                subtitle = stringResource(R.string.tag_the_file_with_its_title),
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
                title = stringResource(R.string.embed_chapters),
                subtitle = stringResource(R.string.saves_chapter_markers_inside_the_video),
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
                icon = Icons.Outlined.ContentCut,
                title = stringResource(R.string.split_chapters_title),
                subtitle = stringResource(R.string.split_chapters_subtitle),
                checked = splitChapters,
                onCheckedChange = {
                    splitChapters = it
                    GalleryDlPreferences.setSplitChapters(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.write_description_info_json_files),
                subtitle = stringResource(R.string.save_a_separate_json_metadata_file),
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
                title = stringResource(R.string.download_subtitles),
                subtitle = stringResource(R.string.fetch_and_embed_subtitles_when_they),
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
                    label = { Text(stringResource(R.string.subtitle_languages)) },
                    placeholder = { Text("en") },
                    singleLine = true,
                    isError = isSubtitlesError,
                    supportingText = {
                        if (isSubtitlesError) {
                            Text(stringResource(R.string.use_comma_separated_language_codes_e), color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.comma_separated_language_codes_e_g))
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
                title = stringResource(R.string.save_subtitle_files),
                subtitle = stringResource(R.string.saves_subtitles_as_a_separate_file),
                checked = saveSubtitleFiles,
                onCheckedChange = {
                    saveSubtitleFiles = it
                    GalleryDlPreferences.setSaveSubtitleFiles(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.format_id_override), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.yt_dlp_only_a_raw_format),
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
                label = { Text(stringResource(R.string.format_selector)) },
                placeholder = { Text("bv+ba/b") },
                singleLine = true,
                isError = isFormatOverrideError,
                supportingText = {
                    if (isFormatOverrideError) {
                        Text(stringResource(R.string.check_expression_syntax_unbalanced_brackets_or), color = MaterialTheme.colorScheme.error)
                    } else if (formatIdOverride.isNotBlank()) {
                        Text(stringResource(R.string.overrides_quality_and_output_format_for), color = MaterialTheme.colorScheme.tertiary)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = MaterialTheme.shapes.medium,
            )
        }

        SettingsSection(title = stringResource(R.string.sponsorblock_section), icon = Icons.Outlined.FastForward) {
            Column(modifier = highlightRowModifier(stringResource(R.string.sponsorblock_section))) {
                Text(stringResource(R.string.sponsorblock_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sponsorblock_description),
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
                        GalleryDlPreferences.SPONSORBLOCK_OFF to stringResource(R.string.sponsorblock_off),
                        GalleryDlPreferences.SPONSORBLOCK_MARK to stringResource(R.string.sponsorblock_mark),
                        GalleryDlPreferences.SPONSORBLOCK_REMOVE to stringResource(R.string.sponsorblock_remove),
                    )
                    modes.forEachIndexed { index, (mode, label) ->
                        val selected = sponsorBlockMode == mode
                        val interactionSource = remember { MutableInteractionSource() }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                sponsorBlockMode = mode
                                GalleryDlPreferences.setSponsorBlockMode(context, mode)
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
            }

            ToggleReveal(sponsorBlockMode != GalleryDlPreferences.SPONSORBLOCK_OFF) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.sponsorblock_segments), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        GalleryDlPreferences.SPONSORBLOCK_CATEGORIES.forEach { (id, label) ->
                            val selected = id in sponsorBlockCategories
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) sponsorBlockCategories - id else sponsorBlockCategories + id
                                    sponsorBlockCategories = next
                                    GalleryDlPreferences.setSponsorBlockCategories(context, next)
                                },
                                label = { Text(stringResource(label)) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }

        SettingsSection(title = stringResource(R.string.picture_downloads), icon = Icons.Outlined.Image) {
            IconToggleRow(
                icon = Icons.Outlined.Image,
                title = stringResource(R.string.save_webp_pictures_as_jpg),
                subtitle = stringResource(R.string.many_sites_send_pictures_as_webp),
                checked = webpToJpg,
                onCheckedChange = {
                    webpToJpg = it
                    GalleryDlPreferences.setWebpToJpg(context, it)
                },
            )
        }

        SettingsSection(title = stringResource(R.string.music_downloads), icon = Icons.Outlined.MusicNote) {
            // The whole setting (title, text, choices) lights up when Settings search jumps here.
            Column(modifier = highlightRowModifier(stringResource(R.string.audio_format))) {
                Text(stringResource(R.string.audio_format), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.for_songs_and_audio_only_downloads),
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
                                    Text(format.displayLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
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
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.GraphicEq,
                title = stringResource(R.string.even_out_volume),
                subtitle = stringResource(R.string.makes_every_song_play_at_the),
                checked = normalizeAudio,
                onCheckedChange = {
                    normalizeAudio = it
                    GalleryDlPreferences.setNormalizeAudio(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            // The whole setting (title, text, choices) lights up when Settings search jumps here.
            Column(modifier = highlightRowModifier(stringResource(R.string.lyrics))) {
                Text(stringResource(R.string.lyrics), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.looked_up_on_lrclib_by_the),
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
                        GalleryDlPreferences.LYRICS_SYNCED to stringResource(R.string.synced),
                        GalleryDlPreferences.LYRICS_PLAIN to stringResource(R.string.plain),
                        GalleryDlPreferences.LYRICS_OFF to stringResource(R.string.sponsorblock_off),
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
            }

            ToggleReveal(lyricsMode != GalleryDlPreferences.LYRICS_OFF) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))

                IconToggleRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.save_lrc_file),
                    subtitle = stringResource(R.string.also_saves_synced_lyrics_as_a),
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
