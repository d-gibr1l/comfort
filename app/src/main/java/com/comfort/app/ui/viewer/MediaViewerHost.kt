package com.comfort.app.ui.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comfort.app.R
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.ui.main.rememberUndoableDelete
import com.comfort.app.viewmodel.DownloadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the viewer pages through, in this order, and the one it opens on. */
data class MediaViewerRequest(val downloads: List<DownloadEntity>, val startId: String)

/** Opens the media viewer (MainScreen hosts it over everything, nav bar included). */
val LocalOpenMediaViewer = staticCompositionLocalOf<(MediaViewerRequest) -> Unit> { {} }

/**
 * Photon Gallery's viewer (MediaViewerScreen) over Comfort's Library: each download's saved file as
 * one of Photon's GalleryItems, read from MediaStore; favourite, rename and delete through the
 * Library's own actions — a delete hides the item at once and the snackbar's Undo brings it back,
 * the same as in the Library (rememberUndoableDelete). A post with several files (a carousel) is
 * one page per file, with a filmstrip of them; favourite, rename and delete act on the whole post,
 * as they do in the Library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerHost(request: MediaViewerRequest, viewModel: DownloadsViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Live, so a favourite or a delete (and its Undo) shows here at once.
    val history by viewModel.historyFlow.collectAsStateWithLifecycle()
    val live = remember(history) { history.associateBy { it.id } }
    // The tapped download first, so the viewer opens at once; then everything around it (each
    // file's details, and every carousel's files, take a moment for a whole Library). The pager
    // keys its pages, so the page on screen stays put as the others arrive around it.
    val resolved by produceState<List<GalleryItem>?>(null, request) {
        val dao = com.comfort.app.data.AppDatabase.getDatabase(context).downloadDao()
        val tapped = request.downloads.firstOrNull { it.id == request.startId }
        if (tapped != null) value = withContext(Dispatchers.IO) { galleryItemsFor(context, dao, listOf(tapped)) }
        value = withContext(Dispatchers.IO) { galleryItemsFor(context, dao, request.downloads) }
    }
    val snackbar = remember { SnackbarHostState() }
    val requestDelete = rememberUndoableDelete(viewModel, snackbar)

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        val all = resolved
        if (all != null) {
            val items = remember(all, live) { all.filter { downloadIdOf(it) in live } }
            MediaViewerScreen(
                galleryItems = items,
                mediaId = request.startId,
                favoriteIds = remember(items, live) { items.filter { live[downloadIdOf(it)]?.isFavorite == true }.map { it.id }.toSet() },
                onToggleFavorite = { item -> live[downloadIdOf(item)]?.let { viewModel.setFavorite(it.id, !it.isFavorite) } },
                onDelete = { item -> requestDelete(setOf(downloadIdOf(item))) },
                renameText = { item -> live[downloadIdOf(item)]?.title.orEmpty() },
                onRename = { item, name -> viewModel.renameDownload(downloadIdOf(item), name) },
                groupKey = ::downloadIdOf,
                startMuted = remember { !com.comfort.app.data.GalleryDlPreferences.isViewerSound(context) },
                onSoundSettingChange = { com.comfort.app.data.GalleryDlPreferences.setViewerSound(context, it) },
                extraMenuItems = { item, close ->
                    val download = live[downloadIdOf(item)] ?: return@MediaViewerScreen
                    DropdownMenuItem(
                        text = { Text("View original source") },
                        leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_ms_open_in_new), contentDescription = null) },
                        onClick = {
                            close()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(download.url))) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy post link") },
                        leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_ms_content_copy), contentDescription = null) },
                        onClick = {
                            close()
                            scope.launch {
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("Post link", download.url)))
                            }
                        },
                    )
                },
                onBack = onClose,
            )
        }
        com.comfort.app.ui.main.DownloadEventSnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
        )
    }
}

/** A page's download: the first file of a post has the download's id as its own, the rest
 * "<id>#<n>" (GalleryItem ids are the pager's keys, so each must be unique). */
private fun downloadIdOf(item: GalleryItem) = item.id.substringBefore('#')

/** The downloads' saved files as GalleryItems, in order, each post's files in the post's order:
 * the ones recorded as it saved them (SavedFile); for a download from before that, its files found
 * by name, in the order they arrived; else just the file its Library tile shows (a song: the song,
 * its thumbnail is the cover). Files deleted from the phone since are left out. A few batched
 * queries for all of them: one query per file took ~3 s for a 100-item Library. */
private suspend fun galleryItemsFor(
    context: Context,
    dao: com.comfort.app.data.DownloadDao,
    downloads: List<DownloadEntity>,
): List<GalleryItem> {
    val viewable = downloads.filter { it.status != DownloadStatus.DELETED }
    val saved = viewable.map { it.id }.chunked(500).flatMap { dao.savedFiles(it) }.groupBy { it.downloadId }
    // Older carousels: their files' names, found in MediaStore all at once.
    val older = viewable.filter { !it.isAudio && (saved[it.id]?.size ?: 0) <= 1 && it.downloadedItems > 1 }
    val namesOf = older.associate { it.id to dao.downloadedFileNames(it.id) }
    val byName = filesByName(context, namesOf.values.flatten())
    val filesOf = viewable.associate { download ->
        val main = (download.mediaUri ?: download.thumbnailPath)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val recorded = saved[download.id].orEmpty().map { Uri.parse(it.uri) }
        val named = namesOf[download.id].orEmpty().mapNotNull { name ->
            byName[name] ?: if (name.endsWith(".webp", ignoreCase = true)) byName[name.dropLast(5) + ".jpg"] else null
        }
        download.id to when {
            download.isAudio -> listOfNotNull(main)
            recorded.size > 1 -> recorded
            named.size > 1 -> named
            else -> listOfNotNull(main)
        }
    }
    val details = mediaDetails(context, filesOf.values.flatten())
    return viewable.flatMap { download ->
        val files = filesOf[download.id].orEmpty()
        files.mapIndexedNotNull { index, uri ->
            val info = details[uri]
            // A carousel's file deleted from the phone since: left out.
            if (files.size > 1 && info == null) return@mapIndexedNotNull null
            val mime = info?.mime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            GalleryItem(
                id = if (index == 0) download.id else "${download.id}#$index",
                uri = uri,
                bucketName = info?.bucket.orEmpty(),
                dateAdded = download.dateAdded / 1000,
                size = info?.size?.takeIf { it > 0 } ?: download.totalBytes,
                name = info?.name ?: download.title,
                dateModified = info?.modified?.takeIf { it > 0 } ?: (download.dateAdded / 1000),
                path = info?.path.orEmpty(),
                // A song plays in the video player too (its controls, no picture).
                isVideo = mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true || download.isAudio,
                durationMs = info?.duration?.takeIf { it > 0 },
            )
        }
    }
}

private class MediaDetails(
    val name: String?, val size: Long, val path: String?, val bucket: String?,
    val modified: Long, val mime: String?, val duration: Long,
)

/** MediaStore's details for these files, in one query per 500 (they're all rows of its Files
 * table, whichever collection their Uri names). Files it no longer has are missing. */
private fun mediaDetails(context: Context, uris: List<Uri>): Map<Uri, MediaDetails> {
    val byId = uris.filter { it.authority == MediaStore.AUTHORITY }
        .mapNotNull { uri -> uri.lastPathSegment?.toLongOrNull()?.let { it to uri } }
        .groupBy({ it.first }, { it.second })
    val out = HashMap<Uri, MediaDetails>()
    byId.keys.chunked(500).forEach { ids ->
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DURATION,
                ),
                "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")})", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val details = MediaDetails(
                        name = c.getString(1), size = c.getLong(2), path = c.getString(3), bucket = c.getString(4),
                        modified = c.getLong(5), mime = c.getString(6), duration = c.getLong(7),
                    )
                    byId[c.getLong(0)]?.forEach { out[it] = details }
                }
            }
        }
    }
    return out
}

/** The saved files with these display names, the newest of each name, in one query per 500. */
private fun filesByName(context: Context, names: List<String>): Map<String, Uri> {
    if (names.isEmpty()) return emptyMap()
    val webp = names.filter { it.endsWith(".webp", ignoreCase = true) }.map { it.dropLast(5) + ".jpg" }
    val wanted = (names + webp).distinct()
    val collection = MediaStore.Files.getContentUri("external")
    val out = HashMap<String, Uri>()
    wanted.chunked(500).forEach { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        runCatching {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} IN ($placeholders)", chunk.toTypedArray(),
                "${MediaStore.MediaColumns._ID} ASC",
            )?.use { c ->
                // Ascending, so the newest of each name is the one left in the map.
                while (c.moveToNext()) out[c.getString(1)] = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
            }
        }
    }
    return out
}
