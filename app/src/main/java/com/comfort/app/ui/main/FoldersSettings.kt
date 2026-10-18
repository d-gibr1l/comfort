package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Folders.

/** Paths, filenames, and local storage — split out of what used to be one long "Downloads" page,
 * matching YTDLnis's own Folders/Downloads/Processing split (see gallery-dl.md's "Break up the
 * Downloads settings page" entry) instead of one screen covering everything. */
@Composable
internal fun FoldersSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var filenameFormat by remember { mutableStateOf(GalleryDlPreferences.getFilenameFormat(context)) }
    var filenameFormatSaved by remember { mutableStateOf(false) }
    var restrictFilenames by remember { mutableStateOf(GalleryDlPreferences.isRestrictFilenames(context)) }
    var trimFilenames by remember { mutableStateOf(GalleryDlPreferences.isTrimFilenames(context)) }
    var downloadLocationUri by remember { mutableStateOf(GalleryDlPreferences.getDownloadLocationUri(context)) }
    var audioLocationUri by remember { mutableStateOf(GalleryDlPreferences.getAudioLocationUri(context)) }
    var videoLocationUri by remember { mutableStateOf(GalleryDlPreferences.getVideoLocationUri(context)) }

    // Choosing another folder or going back to the default never releases the old folder's
    // permission: everything already saved there is still in the Library, and opening,
    // sharing, renaming or checking those files goes through it.
    val downloadLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            downloadLocationUri = uri
            GalleryDlPreferences.setDownloadLocationUri(context, uri)
        }
    }

    // Imported from YTDLnis's own separate music/video folder settings — each independently
    // optional, falling back to the shared downloadLocationUri above (then the built-in default)
    // when unset. Same take-persistable-permission dance as the shared picker.
    val audioLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            audioLocationUri = uri
            GalleryDlPreferences.setAudioLocationUri(context, uri)
        }
    }
    val videoLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            videoLocationUri = uri
            GalleryDlPreferences.setVideoLocationUri(context, uri)
        }
    }

    SettingsSubScaffold(title = stringResource(R.string.folders), topicIcon = Icons.Outlined.Folder, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = stringResource(R.string.filename_format), icon = Icons.Outlined.TextFields) {
            Text(
                stringResource(R.string.filename_format_applied_to_every_downloaded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val saveFormat = {
                val toSave = filenameFormat.ifBlank { GalleryDlPreferences.DEFAULT_FILENAME_FORMAT }
                filenameFormat = toSave
                GalleryDlPreferences.setFilenameFormat(context, toSave)
                filenameFormatSaved = true
            }

            OutlinedTextField(
                value = filenameFormat,
                onValueChange = { filenameFormat = it; filenameFormatSaved = false },
                modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                label = { Text(stringResource(R.string.filename_format)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveFormat() }),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Placeholders: {uploader} {title} {id} {extension} and more — see gallery-dl's format string docs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = saveFormat,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_format))
            }

            ToggleReveal(filenameFormatSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = stringResource(R.string.filename_format_saved), tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.TextFields,
                title = stringResource(R.string.restrict_filenames),
                subtitle = stringResource(R.string.removes_special_characters_and_replaces_spaces),
                checked = restrictFilenames,
                onCheckedChange = {
                    restrictFilenames = it
                    GalleryDlPreferences.setRestrictFilenames(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.ContentCut,
                title = stringResource(R.string.trim_filenames),
                subtitle = stringResource(R.string.caps_long_titles_at_150_characters),
                checked = trimFilenames,
                onCheckedChange = {
                    trimFilenames = it
                    GalleryDlPreferences.setTrimFilenames(context, it)
                },
            )
        }

        SettingsSection(title = stringResource(R.string.download_location), icon = Icons.Outlined.Folder) {
            val locationName by produceState<String?>(initialValue = null, downloadLocationUri) {
                value = withContext(Dispatchers.IO) {
                    downloadLocationUri?.let { uri ->
                        runCatching {
                            val doc = DocumentFile.fromTreeUri(context, uri)
                            if (doc != null && doc.exists() && doc.canWrite()) doc.name else null
                        }.getOrNull()
                    }
                }
            }
            Text(
                when {
                    downloadLocationUri == null -> stringResource(R.string.saving_to_the_default_download_comfort)
                    locationName != null -> stringResource(R.string.saving_to_x, locationName.orEmpty())
                    else -> stringResource(R.string.selected_folder_is_inaccessible_or_permission)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (downloadLocationUri != null && locationName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locationName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.custom_folders_won_t_automatically_appear),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { downloadLocationLauncher.launch(null) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.choose_folder))
                }
                if (downloadLocationUri != null) {
                    OutlinedButton(
                        onClick = {
                            downloadLocationUri = null
                            GalleryDlPreferences.setDownloadLocationUri(context, null)
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(stringResource(R.string.use_default))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            FolderOverrideRow(
                label = stringResource(R.string.audio_folder),
                fallbackDescription = stringResource(R.string.audio_downloads_land_in_the_folder),
                uri = audioLocationUri,
                onChoose = { audioLocationLauncher.launch(null) },
                onUseDefault = {
                    audioLocationUri = null
                    GalleryDlPreferences.setAudioLocationUri(context, null)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            FolderOverrideRow(
                label = stringResource(R.string.video_folder),
                fallbackDescription = stringResource(R.string.video_downloads_land_in_the_folder),
                uri = videoLocationUri,
                onChoose = { videoLocationLauncher.launch(null) },
                onUseDefault = {
                    videoLocationUri = null
                    GalleryDlPreferences.setVideoLocationUri(context, null)
                },
            )
        }

        SettingsSection(title = stringResource(R.string.storage), icon = Icons.Outlined.SdStorage) {
            var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
            var isClearingCache by remember { mutableStateOf(false) }
            var cacheCleared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                cacheSizeBytes = withContext(Dispatchers.IO) {
                    runCatching { context.cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } }.getOrNull() ?: 0L
                }
            }
            Text(
                cacheSizeBytes?.let { bytes ->
                    val mb = bytes / (1024f * 1024f)
                    if (mb >= 1f) stringResource(R.string.cache_x_mb, String.format(Locale.getDefault(), "%.1f", mb)) else stringResource(R.string.cache_x_kb, bytes / 1024)
                } ?: stringResource(R.string.calculating_cache_size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.leftover_temp_files_from_interrupted_downloads),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (isClearingCache) return@OutlinedButton
                    isClearingCache = true
                    scope.launch {
                        val remaining = withContext(Dispatchers.IO) {
                            runCatching {
                                val dao = AppDatabase.getDatabase(context).downloadDao()
                                val activeIds = dao.getActiveInQueueOrderOnce().map { it.id }.toSet()
                                val allKnownIds = dao.getAllOnce().map { it.id }.toSet()

                                val cacheRoot = context.cacheDir ?: return@runCatching 0L
                                val rootItems = cacheRoot.listFiles() ?: return@runCatching 0L

                                for (item in rootItems) {
                                    when {
                                        // Active staging folders
                                        item.isDirectory && item.name == "gallery-dl-staging" -> {
                                            item.listFiles()?.forEach { sub ->
                                                if (sub.name !in activeIds) {
                                                    sub.deleteRecursively()
                                                }
                                            }
                                        }
                                        // Cookies files for downloads
                                        item.isFile && item.name.startsWith("cookies-normalized-") && item.name.endsWith(".txt") -> {
                                            val fileDownloadId = item.name.removePrefix("cookies-normalized-").removeSuffix(".txt")
                                            if (fileDownloadId !in activeIds) {
                                                item.delete()
                                            }
                                        }
                                        // Extracted audio album art - preserve for any downloads currently in the library
                                        item.isDirectory && item.name == "audio_art" -> {
                                            item.listFiles()?.forEach { artFile ->
                                                val artId = artFile.nameWithoutExtension
                                                if (artId !in allKnownIds) {
                                                    artFile.delete()
                                                }
                                            }
                                        }
                                        // Other caches (image_cache, WebView, update-*.apk, etc.)
                                        else -> {
                                            item.deleteRecursively()
                                        }
                                    }
                                }
                                cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            }.getOrDefault(0L)
                        }
                        cacheSizeBytes = remaining
                        cacheCleared = true
                        isClearingCache = false
                    }
                },
                enabled = !isClearingCache,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (isClearingCache) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clearing_cache))
                } else {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_cache))
                }
            }
            ToggleReveal(cacheCleared) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = stringResource(R.string.cache_cleared), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A compact "override the shared download location for just this media type" row — Audio
 * folder/Video folder in Settings > Downloads. Unset (the default) means whatever
 * [saveMediaToGallery]'s own fallback chain resolves to for that type; only shown as a name once
 * actually set, matching the shared Download location row's own "only show Use default once
 * there's something to reset" pattern. */
@Composable
private fun FolderOverrideRow(
    label: String,
    fallbackDescription: String,
    uri: android.net.Uri?,
    onChoose: () -> Unit,
    onUseDefault: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val name by produceState<String?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            uri?.let {
                runCatching {
                    val doc = DocumentFile.fromTreeUri(context, it)
                    if (doc != null && doc.exists() && doc.canWrite()) doc.name else null
                }.getOrNull()
            }
        }
    }
    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            uri == null -> stringResource(R.string.not_set_x, fallbackDescription)
            name != null -> stringResource(R.string.saving_to_x, name.orEmpty())
            else -> stringResource(R.string.folder_inaccessible_or_permission_lost_x, fallbackDescription)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (uri != null && name == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onChoose,
            modifier = Modifier.weight(1f).height(50.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.choose_folder))
        }
        if (uri != null) {
            OutlinedButton(
                onClick = onUseDefault,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.use_default))
            }
        }
    }
}
