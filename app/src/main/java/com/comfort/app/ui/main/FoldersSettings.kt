package com.comfort.app.ui.main

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val downloadLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            downloadLocationUri = uri
            GalleryDlPreferences.setDownloadLocationUri(context, uri)
        }
    }

    // Imported from YTDLnis's own separate music/video folder settings — each independently
    // optional, falling back to the shared downloadLocationUri above (then the built-in default)
    // when unset. Same take-persistable-permission dance as the shared picker.
    var audioLocationUri by remember { mutableStateOf(GalleryDlPreferences.getAudioLocationUri(context)) }
    val audioLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            audioLocationUri = uri
            GalleryDlPreferences.setAudioLocationUri(context, uri)
        }
    }
    var videoLocationUri by remember { mutableStateOf(GalleryDlPreferences.getVideoLocationUri(context)) }
    val videoLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            videoLocationUri = uri
            GalleryDlPreferences.setVideoLocationUri(context, uri)
        }
    }

    SettingsSubScaffold(title = "Folders", topicIcon = Icons.Outlined.Folder, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Filename format", icon = Icons.Outlined.TextFields) {
            Text(
                "Filename format applied to every downloaded file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = filenameFormat,
                onValueChange = { filenameFormat = it; filenameFormatSaved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filename format") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Placeholders: {uploader} {title} {id} {extension} and more — see gallery-dl's format string docs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val toSave = filenameFormat.ifBlank { GalleryDlPreferences.DEFAULT_FILENAME_FORMAT }
                    filenameFormat = toSave
                    sharedPreferences.edit().putString(GalleryDlPreferences.KEY_FILENAME_FORMAT, toSave).apply()
                    filenameFormatSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save format")
            }

            if (filenameFormatSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Filename format saved", tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.TextFields,
                title = "Restrict filenames",
                subtitle = "Removes special characters and replaces spaces with underscores. Safer for sharing and older file systems.",
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
                title = "Trim filenames",
                subtitle = "Caps long titles at 150 characters (only applies to the default filename format).",
                checked = trimFilenames,
                onCheckedChange = {
                    trimFilenames = it
                    GalleryDlPreferences.setTrimFilenames(context, it)
                },
            )
        }

        SettingsSection(title = "Download location", icon = Icons.Outlined.Folder) {
            val locationName = remember(downloadLocationUri) {
                downloadLocationUri?.let { uri ->
                    runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                }
            }
            Text(
                if (locationName != null) "Saving to \"$locationName\"." else "Saving to the default Pictures/Comfort folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locationName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Custom folders won't automatically appear in Photos/Gallery apps — only the default location is indexed as media.",
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
                    Text("Choose folder")
                }
                if (locationName != null) {
                    OutlinedButton(
                        onClick = {
                            downloadLocationUri = null
                            GalleryDlPreferences.setDownloadLocationUri(context, null)
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Use default")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            FolderOverrideRow(
                label = "Audio folder",
                fallbackDescription = "audio downloads land in the folder above (or the default) unless this is set.",
                uri = audioLocationUri,
                onChoose = { audioLocationLauncher.launch(null) },
                onUseDefault = { audioLocationUri = null; GalleryDlPreferences.setAudioLocationUri(context, null) },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            FolderOverrideRow(
                label = "Video folder",
                fallbackDescription = "video downloads land in the folder above (or the default) unless this is set.",
                uri = videoLocationUri,
                onChoose = { videoLocationLauncher.launch(null) },
                onUseDefault = { videoLocationUri = null; GalleryDlPreferences.setVideoLocationUri(context, null) },
            )
        }

        SettingsSection(title = "Storage", icon = Icons.Outlined.SdStorage) {
            var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
            var cacheCleared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                cacheSizeBytes = withContext(Dispatchers.IO) {
                    runCatching { context.cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } }.getOrNull() ?: 0L
                }
            }
            Text(
                cacheSizeBytes?.let { bytes ->
                    val mb = bytes / (1024f * 1024f)
                    if (mb >= 1f) "Cache: %.1f MB".format(mb) else "Cache: ${bytes / 1024} KB"
                } ?: "Calculating cache size…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Leftover temp files from interrupted downloads and preview thumbnails. Safe to clear — nothing here is a finished download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
                        }
                        cacheSizeBytes = 0L
                        cacheCleared = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clear cache")
            }
            if (cacheCleared) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cache cleared", tint = MaterialTheme.colorScheme.primary)
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
    val name = remember(uri) {
        uri?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() }
    }
    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        if (name != null) "Saving to \"$name\"." else "Not set — $fallbackDescription",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text("Choose folder")
        }
        if (name != null) {
            OutlinedButton(
                onClick = onUseDefault,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Use default")
            }
        }
    }
}
