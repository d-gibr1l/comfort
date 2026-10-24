package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.CheckCircle as FilledCheckCircle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Advanced.

@Composable
internal fun AdvancedSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var extractorArgs by remember { mutableStateOf(GalleryDlPreferences.getExtractorArgs(context)) }
    var extractorArgsSaved by remember { mutableStateOf(false) }
    var formatSort by remember { mutableStateOf(GalleryDlPreferences.getFormatSort(context)) }
    var formatSortSaved by remember { mutableStateOf(false) }
    var customHeaders by remember { mutableStateOf(GalleryDlPreferences.getCustomHeaders(context)) }
    var customHeadersSaved by remember { mutableStateOf(false) }
    var verboseLogging by remember { mutableStateOf(GalleryDlPreferences.isVerboseLogging(context)) }
    var youtubeClientRotation by remember { mutableStateOf(GalleryDlPreferences.isYoutubeClientRotationEnabled(context)) }
    var impersonateEnabled by remember { mutableStateOf(GalleryDlPreferences.isImpersonateEnabled(context)) }

    val isExtractorArgsDirty = extractorArgs.trim() != GalleryDlPreferences.getExtractorArgs(context).trim()
    val isFormatSortDirty = formatSort.trim() != GalleryDlPreferences.getFormatSort(context).trim()
    val isFormatSortError = formatSort.isNotBlank() && !isValidFormatSort(formatSort)

    val isCustomHeadersDirty = customHeaders.trim() != GalleryDlPreferences.getCustomHeaders(context).trim()
    val customHeadersError = validateCustomHeaders(customHeaders)?.let { stringResource(it.message, it.line) }

    SettingsSubScaffold(title = stringResource(R.string.advanced), topicIcon = Icons.Outlined.Terminal, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = stringResource(R.string.extra_arguments), icon = Icons.Outlined.Terminal) {
            Text(
                stringResource(R.string.extra_command_line_arguments_added_to),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ExtraArgsSheetField()
        }

        SettingsSection(title = androidx.compose.ui.res.stringResource(com.comfort.app.R.string.command_templates_section), icon = Icons.Outlined.Bookmarks) {
            Column(modifier = highlightRowModifier(stringResource(R.string.command_templates_section))) {
                CommandTemplatesSection()
            }
        }

        SettingsSection(title = stringResource(R.string.youtube), icon = Icons.Outlined.Memory) {
            IconToggleRow(
                icon = Icons.Outlined.Refresh,
                title = stringResource(R.string.rotate_player_clients),
                subtitle = stringResource(R.string.automatically_switches_between_android_ios_and),
                checked = youtubeClientRotation,
                onCheckedChange = {
                    youtubeClientRotation = it
                    GalleryDlPreferences.setYoutubeClientRotationEnabled(context, it)
                },
            )
        }

        SettingsSection(title = stringResource(R.string.bot_detection), icon = Icons.Outlined.Lock) {
            IconToggleRow(
                icon = Icons.Outlined.GppBad,
                title = stringResource(R.string.impersonate_a_browser),
                subtitle = stringResource(R.string.makes_the_app_look_like_a),
                checked = impersonateEnabled,
                onCheckedChange = {
                    impersonateEnabled = it
                    GalleryDlPreferences.setImpersonateEnabled(context, it)
                },
            )
        }

        SettingsSection(title = stringResource(R.string.yt_dlp_extractor_arguments), icon = Icons.Outlined.Tune) {
            Text(
                stringResource(R.string.site_specific_yt_dlp_tuning_throttling),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = extractorArgs,
                onValueChange = { extractorArgs = it; extractorArgsSaved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp).clearFocusOnKeyboardDismiss(),
                label = { Text(stringResource(R.string.e_g_youtube_player_client_android)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            if (isExtractorArgsDirty) {
                Button(
                    onClick = {
                        val cleaned = extractorArgs.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
                        GalleryDlPreferences.setExtractorArgs(context, cleaned)
                        extractorArgsSaved = true
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_extractor_arguments))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        val cleaned = extractorArgs.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
                        GalleryDlPreferences.setExtractorArgs(context, cleaned)
                        extractorArgsSaved = true
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_extractor_arguments))
                }
            }

            ToggleReveal(extractorArgsSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = stringResource(R.string.extractor_arguments_saved), tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Imported from YTDLnis's own advanced_preferences.xml (Format Sorting, User Agent
        // Header) — yt-dlp only, same reasoning as this screen's other two fields above.
        SettingsSection(title = stringResource(R.string.format_sort), icon = Icons.Outlined.FilterAlt) {
            Text(
                stringResource(R.string.raw_yt_dlp_format_sort_syntax),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formatSort,
                onValueChange = { formatSort = it; formatSortSaved = false },
                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                label = { Text(stringResource(R.string.e_g_codec_vp9_fps)) },
                singleLine = true,
                isError = isFormatSortError,
                supportingText = {
                    if (isFormatSortError) {
                        Text(stringResource(R.string.cannot_contain_quotes_newlines_or_shell), color = MaterialTheme.colorScheme.error)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            if (isFormatSortDirty) {
                Button(
                    onClick = {
                        GalleryDlPreferences.setFormatSort(context, formatSort.trim())
                        formatSortSaved = true
                    },
                    enabled = !isFormatSortError,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_format_sort))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        GalleryDlPreferences.setFormatSort(context, formatSort.trim())
                        formatSortSaved = true
                    },
                    enabled = !isFormatSortError,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_format_sort))
                }
            }

            ToggleReveal(formatSortSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = stringResource(R.string.format_sort_saved), tint = MaterialTheme.colorScheme.primary)
            }
        }

        SettingsSection(title = stringResource(R.string.custom_headers), icon = Icons.Outlined.Terminal) {
            Text(
                stringResource(R.string.one_header_name_value_per_line),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = customHeaders,
                onValueChange = { customHeaders = it; customHeadersSaved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp).clearFocusOnKeyboardDismiss(),
                label = { Text(stringResource(R.string.e_g_referer_https_example_com)) },
                isError = customHeadersError != null,
                supportingText = {
                    if (customHeadersError != null) {
                        Text(customHeadersError, color = MaterialTheme.colorScheme.error)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            if (isCustomHeadersDirty) {
                Button(
                    onClick = {
                        GalleryDlPreferences.setCustomHeaders(context, customHeaders.trim())
                        customHeadersSaved = true
                    },
                    enabled = customHeadersError == null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_headers))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        GalleryDlPreferences.setCustomHeaders(context, customHeaders.trim())
                        customHeadersSaved = true
                    },
                    enabled = customHeadersError == null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_headers))
                }
            }

            ToggleReveal(customHeadersSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = stringResource(R.string.headers_saved), tint = MaterialTheme.colorScheme.primary)
            }
        }

        SettingsSection(title = stringResource(R.string.debugging), icon = Icons.Outlined.Code) {
            IconToggleRow(
                icon = Icons.Outlined.Terminal,
                title = stringResource(R.string.verbose_logging),
                subtitle = stringResource(R.string.logs_all_internal_yt_dlp_debug),
                checked = verboseLogging,
                onCheckedChange = {
                    verboseLogging = it
                    GalleryDlPreferences.setVerboseLogging(context, it)
                },
            )
            Spacer(Modifier.height(16.dp))
            ExportLogsRow()
        }
    }
}

/** Advanced > Debugging > Export logs: saves the downloads' log (DownloadLog) to a file the user
 * picks, to attach to a bug report. */
@Composable
private fun ExportLogsRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.comfort.app.util.DownloadLog.export(context, uri) }
                context.getString(R.string.logs_exported)
            }.getOrElse { context.getString(R.string.logs_export_failed, it.message ?: it.javaClass.simpleName) }
            busy = false
        }
    }
    val title = stringResource(R.string.export_logs)
    val heading = stringResource(R.string.logs_title)
    Column(modifier = highlightRowModifier(heading)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.export_logs_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        var viewing by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { viewing = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.view_logs), maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())
                    launcher.launch("comfort-logs-$stamp.txt")
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, maxLines = 1)
            }
        }
        if (viewing) LogViewer(onDismiss = { viewing = false })
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
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
        title = stringResource(R.string.use_extra_arguments),
        subtitle = stringResource(R.string.off_keeps_them_saved_but_adds),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            GalleryDlPreferences.setExtraArgsEnabled(context, it)
        },
    )
    Spacer(Modifier.height(12.dp))

    val setCount = listOf(bothArgs, ytDlpArgs, galleryDlArgs).count { it.isNotBlank() }
    val summary = when {
        setCount == 0 -> stringResource(R.string.none)
        else -> stringResource(
            R.string.set_for_x,
            listOfNotNull(
                stringResource(R.string.extra_args_both_short).takeIf { bothArgs.isNotBlank() },
                "yt-dlp".takeIf { ytDlpArgs.isNotBlank() },
                "gallery-dl".takeIf { galleryDlArgs.isNotBlank() },
            ).joinToString(", "),
        )
    }
    val summaryDescription = stringResource(R.string.extra_arguments_x, summary)
    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = summaryDescription },
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
                Text(stringResource(R.string.extra_arguments), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        val bothHasUnclosedQuotes = hasUnmatchedQuotes(draftBoth)
        val ytDlpHasUnclosedQuotes = hasUnmatchedQuotes(draftYtDlp)
        val galleryDlHasUnclosedQuotes = hasUnmatchedQuotes(draftGalleryDl)
        val hasAnyQuoteError = bothHasUnclosedQuotes || ytDlpHasUnclosedQuotes || galleryDlHasUnclosedQuotes

        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.extra_arguments), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.real_command_line_flags_each_engine),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExtraArgsSection(
                    title = stringResource(R.string.both),
                    subtitle = stringResource(R.string.passed_to_yt_dlp_and_gallery),
                    value = draftBoth,
                    onValueChange = { draftBoth = it },
                    placeholder = stringResource(R.string.e_g_no_mtime),
                    isError = bothHasUnclosedQuotes,
                    errorMessage = stringResource(R.string.unclosed_quotation_marks_arguments_may_be),
                )
                ExtraArgsSection(
                    title = "yt-dlp",
                    subtitle = stringResource(R.string.passed_to_yt_dlp_only),
                    value = draftYtDlp,
                    onValueChange = { draftYtDlp = it },
                    placeholder = stringResource(R.string.e_g_extractor_args_youtube_player),
                    isError = ytDlpHasUnclosedQuotes,
                    errorMessage = stringResource(R.string.unclosed_quotation_marks_arguments_may_be),
                )
                ExtraArgsSection(
                    title = "gallery-dl",
                    subtitle = stringResource(R.string.passed_to_gallery_dl_only),
                    value = draftGalleryDl,
                    onValueChange = { draftGalleryDl = it },
                    placeholder = stringResource(R.string.e_g_write_metadata),
                    isError = galleryDlHasUnclosedQuotes,
                    errorMessage = stringResource(R.string.unclosed_quotation_marks_arguments_may_be),
                )
                Text(
                    stringResource(R.string.instaloader_has_none_it_runs_as),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ConfirmCancelSplitButton(
                        label = stringResource(R.string.action_save),
                        icon = Icons.Filled.FilledCheckCircle,
                        confirmEnabled = !hasAnyQuoteError,
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
    isError: Boolean = false,
    errorMessage: String? = null,
) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clearFocusOnKeyboardDismiss(),
        placeholder = { Text(placeholder) },
        isError = isError,
        supportingText = {
            if (isError && errorMessage != null) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        shape = MaterialTheme.shapes.medium,
    )
}

/** A problem with the custom headers' text: which line, and what's wrong (a string resource
 * taking the line number). */
internal data class HeaderError(val line: Int, @androidx.annotation.StringRes val message: Int)

internal fun validateCustomHeaders(headersText: String): HeaderError? {
    for ((index, line) in headersText.lineSequence().withIndex()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (!trimmed.contains(':')) {
            return HeaderError(index + 1, R.string.line_x_missing_colon_between_header)
        }
        val name = trimmed.substringBefore(':').trim()
        if (name.isEmpty()) {
            return HeaderError(index + 1, R.string.line_x_header_name_cannot_be)
        }
        if (name.any { it.isWhitespace() }) {
            return HeaderError(index + 1, R.string.line_x_header_name_cannot_contain)
        }
    }
    return null
}

internal fun isValidFormatSort(formatSort: String): Boolean {
    return !formatSort.any { it in "\"'$`;|&" || it == '\n' || it == '\r' }
}

internal fun hasUnmatchedQuotes(args: String): Boolean {
    var inSingle = false
    var inDouble = false
    var escape = false
    for (char in args) {
        if (escape) {
            escape = false
            continue
        }
        when (char) {
            '\\' -> escape = true
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
        }
    }
    return inSingle || inDouble
}


