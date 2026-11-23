package com.comfort.app.ui.main

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadEntity
import com.comfort.app.theme.HeaderFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The download whose items a Library card's stack button asked to see; DownloadsHistoryScreen
 * shows them in [LibraryBatchSheet]. */
internal object BatchViewRequest {
    var download by mutableStateOf<DownloadEntity?>(null)
}

/** One file of a multi-item download, as the batch sheet lists it. [pageId] is the media viewer's
 * page for it (the download's id for its first file, "id#n" after). */
private data class BatchItem(
    val uri: Uri,
    val pageId: String,
    val number: Int,
    val name: String,
    val mime: String?,
    val sizeBytes: Long,
    val durationMs: Long?,
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isLyrics get() = ext == "lrc"
    /** A file that goes with another one (lyrics, subtitles), not a picture, video or song. */
    val isSidecar get() = isLyrics || ext in setOf("srt", "vtt", "ass", "ssa")
}

private const val PREFS = "library_batch_view"

/**
 * Every file of a multi-item download (a carousel, a gallery, a playlist), in a sheet: as a grid of
 * thumbnails or a list with names, sizes and lengths (remembered). A tap opens the file (the media
 * viewer on its page, or the music player for a song); the bin deletes that one file from the
 * phone, the download's count and size following.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBatchSheet(download: DownloadEntity, onDismiss: () -> Unit, onOpen: (pageId: String, uri: Uri, mime: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var grid by remember { mutableStateOf(prefs.getBoolean("grid", true)) }
    var revision by remember { mutableIntStateOf(0) }
    val items by produceState<List<BatchItem>?>(null, download.id, revision) {
        value = withContext(Dispatchers.IO) { loadItems(context, download) }
    }
    // A scroll that runs into the list's end doesn't go on to pull the sheet down.
    val keepSheet = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
        }
    }
    // The file is gone: its record and the download's count and size follow.
    suspend fun forget(item: BatchItem) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        dao.deleteSavedFile(download.id, item.uri.toString())
        dao.decrementDownloadedItems(download.id, 1)
        if (item.sizeBytes > 0) dao.addBytes(download.id, -item.sizeBytes)
    }
    // A file Android only deletes with the user's OK (its own dialog).
    var awaitingOk by remember { mutableStateOf<BatchItem?>(null) }
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val item = awaitingOk ?: return@rememberLauncherForActivityResult
        awaitingOk = null
        if (result.resultCode == android.app.Activity.RESULT_OK) scope.launch { forget(item); revision++ }
    }
    fun delete(item: BatchItem) {
        scope.launch {
            android.util.Log.i("LibraryBatchSheet", "delete #${item.number} ${item.uri} (${item.mime})")
            val needsOk = withContext(Dispatchers.IO) {
                com.comfort.app.util.MediaStoreHelper.deleteSavedFiles(context, listOf(item.uri)).also { if (it.isEmpty()) forget(item) }
            }
            if (needsOk.isEmpty()) { revision++; return@launch }
            val request = runCatching { MediaStore.createDeleteRequest(context.contentResolver, needsOk) }.getOrNull()
            if (request != null) {
                awaitingOk = item
                consent.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                android.widget.Toast.makeText(context, R.string.batch_item_not_deleted, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // A song's lyrics (or a video's subtitles) open the file they go with: the one of the same name.
    fun open(item: BatchItem) {
        val target = if (item.isSidecar) {
            items.orEmpty().firstOrNull { !it.isSidecar && it.name.substringBeforeLast('.') == item.name.substringBeforeLast('.') }
                ?: items.orEmpty().firstOrNull { !it.isSidecar }
        } else item
        target?.let { onOpen(it.pageId, it.uri, it.mime) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        val height = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = height).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        download.title.ifBlank { download.url },
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = HeaderFontFamily),
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val shown = items
                    if (shown != null) {
                        val total = shown.sumOf { it.sizeBytes }
                        Text(
                            pluralStringResource(R.plurals.item_count, shown.size, shown.size) +
                                (if (total > 0) " · " + android.text.format.Formatter.formatShortFileSize(context, total) else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = grid,
                        onClick = { grid = true; prefs.edit().putBoolean("grid", true).apply() },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = {},
                    ) { Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.grid_view), modifier = Modifier.size(18.dp)) }
                    SegmentedButton(
                        selected = !grid,
                        onClick = { grid = false; prefs.edit().putBoolean("grid", false).apply() },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = {},
                    ) { Icon(Icons.Outlined.ViewAgenda, contentDescription = stringResource(R.string.list_view), modifier = Modifier.size(18.dp)) }
                }
            }
            val shown = items
            when {
                shown == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).nestedScroll(keepSheet),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(shown, key = { it.uri.toString() }) { item -> GridTile(item, onClick = { open(item) }, onDelete = { delete(item) }) }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).nestedScroll(keepSheet),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(shown, key = { it.uri.toString() }) { item -> ListRow(item, onClick = { open(item) }, onDelete = { delete(item) }) }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun GridTile(item: BatchItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Column {
            Box(Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Thumb(item, Modifier.fillMaxSize())
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    Text("#${item.number}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(36.dp)) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_from_phone), tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(5.dp).size(18.dp))
                    }
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ListRow(item: BatchItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Thumb(item, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${item.number}. ${item.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_from_phone), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Thumb(item: BatchItem, modifier: Modifier) {
    val audio = item.mime?.startsWith("audio/") == true
    if (audio || item.isSidecar) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                when {
                    item.isLyrics -> Icons.Outlined.Lyrics
                    item.isSidecar -> Icons.Outlined.Subtitles
                    else -> Icons.Outlined.MusicNote
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Box(modifier) {
            coil.compose.AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.mime?.startsWith("video/") == true) {
                Icon(Icons.Outlined.PlayCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
        }
    }
}

@Composable
private fun meta(item: BatchItem): String {
    val context = LocalContext.current
    val kind = when {
        item.isLyrics -> stringResource(R.string.lyrics)
        item.isSidecar -> stringResource(R.string.subtitles)
        item.mime?.startsWith("video/") == true -> stringResource(R.string.video)
        item.mime?.startsWith("audio/") == true -> stringResource(R.string.song)
        else -> stringResource(R.string.picture)
    }
    return listOfNotNull(
        kind,
        item.durationMs?.takeIf { it > 0 }?.let { android.text.format.DateUtils.formatElapsedTime(it / 1000) },
        item.sizeBytes.takeIf { it > 0 }?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
    ).joinToString(" · ")
}

/** The download's recorded files that are still on the phone, in its order, with their names,
 * types, sizes and lengths from MediaStore (or the file's own provider). */
private suspend fun loadItems(context: Context, download: DownloadEntity): List<BatchItem> {
    val dao = AppDatabase.getDatabase(context).downloadDao()
    val files = dao.savedFiles(download.id)
    val resolver = context.contentResolver
    return files.mapIndexedNotNull { i, f ->
        val uri = Uri.parse(f.uri)
        var name: String? = null
        var size = 0L
        var duration: Long? = null
        val present = runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use false
                c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(MediaStore.MediaColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                c.getColumnIndex(MediaStore.MediaColumns.DURATION).takeIf { it >= 0 && !c.isNull(it) }?.let { duration = c.getLong(it) }
                true
            } ?: false
        }.getOrDefault(false)
        // Gone from the phone: not listed (the card says so, and offers it again).
        if (!present && com.comfort.app.util.MediaStoreHelper.reallyGone(context, uri)) return@mapIndexedNotNull null
        BatchItem(
            uri = uri,
            pageId = if (i == 0) download.id else "${download.id}#$i",
            number = i + 1,
            name = name ?: f.path ?: uri.lastPathSegment.orEmpty(),
            mime = runCatching { resolver.getType(uri) }.getOrNull(),
            sizeBytes = size,
            durationMs = duration,
        )
    }
}
