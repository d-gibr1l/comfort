package com.comfort.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.Instagram
import androidx.compose.material.icons.filled.CheckCircle as FilledCheckCircle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.GalleryDlPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Advanced.

@Composable
internal fun AdvancedSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var extractorArgs by remember { mutableStateOf(GalleryDlPreferences.getExtractorArgs(context)) }
    var extractorArgsSaved by remember { mutableStateOf(false) }
    var formatSort by remember { mutableStateOf(GalleryDlPreferences.getFormatSort(context)) }
    var formatSortSaved by remember { mutableStateOf(false) }
    var customHeaders by remember { mutableStateOf(GalleryDlPreferences.getCustomHeaders(context)) }
    var customHeadersSaved by remember { mutableStateOf(false) }
    var verboseLogging by remember { mutableStateOf(GalleryDlPreferences.isVerboseLogging(context)) }
    var youtubeClientRotation by remember { mutableStateOf(GalleryDlPreferences.isYoutubeClientRotationEnabled(context)) }
    var impersonateEnabled by remember { mutableStateOf(GalleryDlPreferences.isImpersonateEnabled(context)) }
    var instaloaderForInstagram by remember { mutableStateOf(GalleryDlPreferences.isInstaloaderForInstagram(context)) }

    SettingsSubScaffold(title = "Advanced", topicIcon = Icons.Outlined.Terminal, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Extra arguments", icon = Icons.Outlined.Terminal) {
            Text(
                "Extra command-line arguments added to every download. For advanced users.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ExtraArgsSheetField()
        }

        SettingsSection(title = "YouTube", icon = Icons.Outlined.Memory) {
            IconToggleRow(
                icon = Icons.Outlined.Refresh,
                title = "Rotate player clients",
                subtitle = "Automatically switches between Android, iOS, and Web clients if YouTube blocks or slows down a download.",
                checked = youtubeClientRotation,
                onCheckedChange = {
                    youtubeClientRotation = it
                    GalleryDlPreferences.setYoutubeClientRotationEnabled(context, it)
                },
            )
        }

        SettingsSection(title = "Instagram", icon = FeatherIcons.Instagram) {
            IconToggleRow(
                icon = FeatherIcons.Instagram,
                title = "Use Instaloader for Instagram",
                subtitle = "Downloads Instagram posts and reels with Instaloader, which handles carousels and captions and works on public posts without cookies. Falls back to gallery-dl and yt-dlp if it can't get a post. Off uses gallery-dl and yt-dlp only.",
                checked = instaloaderForInstagram,
                onCheckedChange = {
                    instaloaderForInstagram = it
                    GalleryDlPreferences.setInstaloaderForInstagram(context, it)
                },
            )
        }

        SettingsSection(title = "Bot detection", icon = Icons.Outlined.GppBad) {
            IconToggleRow(
                icon = Icons.Outlined.GppBad,
                title = "Impersonate a browser",
                subtitle = "Makes the app look like a real web browser to bypass bot detection on strict websites.",
                checked = impersonateEnabled,
                onCheckedChange = {
                    impersonateEnabled = it
                    GalleryDlPreferences.setImpersonateEnabled(context, it)
                },
            )
        }

        SettingsSection(title = "yt-dlp extractor arguments", icon = Icons.Outlined.Terminal) {
            Text(
                "Site-specific yt-dlp tuning (throttling workarounds, player client selection, etc). " +
                    "CLI syntax, e.g. \"youtube:player_client=android,web\". Separate multiple " +
                    "extractors with whitespace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = extractorArgs,
                onValueChange = { extractorArgs = it; extractorArgsSaved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("e.g. youtube:player_client=android") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    GalleryDlPreferences.setExtractorArgs(context, extractorArgs)
                    extractorArgsSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save extractor arguments")
            }

            if (extractorArgsSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Extractor arguments saved", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Imported from YTDLnis's own advanced_preferences.xml (Format Sorting, User Agent
        // Header) — yt-dlp only, same reasoning as this screen's other two fields above.
        SettingsSection(title = "Format sort", icon = Icons.Outlined.FilterAlt) {
            Text(
                "Raw yt-dlp --format-sort syntax (e.g. \"codec:vp9,fps\"), applied after this app's own " +
                    "quality-cap and MP4-compatibility bias so it can still reorder or override them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formatSort,
                onValueChange = { formatSort = it; formatSortSaved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("e.g. codec:vp9,fps") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    GalleryDlPreferences.setFormatSort(context, formatSort)
                    formatSortSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save format sort")
            }

            if (formatSortSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Format sort saved", tint = MaterialTheme.colorScheme.primary)
            }
        }

        SettingsSection(title = "Custom headers", icon = Icons.Outlined.Terminal) {
            Text(
                "One \"Header-Name: value\" per line, sent on every yt-dlp download — overrides that " +
                    "header's own default (including User-Agent/Referer) rather than only adding new ones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = customHeaders,
                onValueChange = { customHeaders = it; customHeadersSaved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("e.g. Referer: https://example.com") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    GalleryDlPreferences.setCustomHeaders(context, customHeaders)
                    customHeadersSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save headers")
            }

            if (customHeadersSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Headers saved", tint = MaterialTheme.colorScheme.primary)
            }
        }

        SettingsSection(title = "Debugging", icon = Icons.Outlined.Terminal) {
            IconToggleRow(
                icon = Icons.Outlined.Terminal,
                title = "Verbose logging",
                subtitle = "Logs all internal yt-dlp debug output for advanced troubleshooting.",
                checked = verboseLogging,
                onCheckedChange = {
                    verboseLogging = it
                    GalleryDlPreferences.setVerboseLogging(context, it)
                },
            )
        }
    }
}

/** Advanced > Extra arguments: an on/off toggle, and one button (summarising what's set) that
 * opens a sheet with the three sets — one for both engines, one each for yt-dlp and gallery-dl
 * only (see GalleryDlPreferences.getExtraArgsFor for how they combine). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtraArgsSheetField() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(GalleryDlPreferences.isExtraArgsEnabled(context)) }
    var bothArgs by remember { mutableStateOf(GalleryDlPreferences.getExtraArgs(context)) }
    var ytDlpArgs by remember { mutableStateOf(GalleryDlPreferences.getYtDlpExtraArgs(context)) }
    var galleryDlArgs by remember { mutableStateOf(GalleryDlPreferences.getGalleryDlExtraArgs(context)) }
    var showSheet by remember { mutableStateOf(false) }

    IconToggleRow(
        icon = Icons.Outlined.Terminal,
        title = "Use extra arguments",
        subtitle = "Off keeps them saved but adds none to downloads.",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            GalleryDlPreferences.setExtraArgsEnabled(context, it)
        },
    )
    Spacer(Modifier.height(12.dp))

    val setCount = listOf(bothArgs, ytDlpArgs, galleryDlArgs).count { it.isNotBlank() }
    val summary = when {
        setCount == 0 -> "None"
        else -> listOfNotNull(
            "both".takeIf { bothArgs.isNotBlank() },
            "yt-dlp".takeIf { ytDlpArgs.isNotBlank() },
            "gallery-dl".takeIf { galleryDlArgs.isNotBlank() },
        ).joinToString(", ", prefix = "Set for ")
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        onClick = { showSheet = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text("Extra arguments", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showSheet) {
        // Seeded once when the sheet opens; only Save writes anything back.
        var draftBoth by remember { mutableStateOf(bothArgs) }
        var draftYtDlp by remember { mutableStateOf(ytDlpArgs) }
        var draftGalleryDl by remember { mutableStateOf(galleryDlArgs) }
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Extra arguments", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Real command-line flags. Each engine gets the \"Both\" set plus its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExtraArgsSection(
                    title = "Both",
                    subtitle = "Passed to yt-dlp and gallery-dl. Only flags both understand.",
                    value = draftBoth,
                    onValueChange = { draftBoth = it },
                    placeholder = "e.g. --no-mtime",
                )
                ExtraArgsSection(
                    title = "yt-dlp",
                    subtitle = "Passed to yt-dlp only.",
                    value = draftYtDlp,
                    onValueChange = { draftYtDlp = it },
                    placeholder = "e.g. --extractor-args \"youtube:player_client=web\"",
                )
                ExtraArgsSection(
                    title = "gallery-dl",
                    subtitle = "Passed to gallery-dl only.",
                    value = draftGalleryDl,
                    onValueChange = { draftGalleryDl = it },
                    placeholder = "e.g. --write-metadata",
                )
                Text(
                    "Instaloader has none: it runs as a library here, not a command line.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ConfirmCancelSplitButton(
                        label = "Save",
                        icon = Icons.Filled.FilledCheckCircle,
                        onConfirm = {
                            bothArgs = draftBoth.trim()
                            ytDlpArgs = draftYtDlp.trim()
                            galleryDlArgs = draftGalleryDl.trim()
                            GalleryDlPreferences.setExtraArgs(context, bothArgs)
                            GalleryDlPreferences.setYtDlpExtraArgs(context, ytDlpArgs)
                            GalleryDlPreferences.setGalleryDlExtraArgs(context, galleryDlArgs)
                            showSheet = false
                        },
                        onCancel = { showSheet = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraArgsSection(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        placeholder = { Text(placeholder) },
        shape = MaterialTheme.shapes.medium,
    )
}
