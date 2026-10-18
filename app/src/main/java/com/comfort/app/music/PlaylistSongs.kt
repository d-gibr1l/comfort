package com.comfort.app.music

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadEntity
import com.comfort.app.util.AudioCover
import com.comfort.app.util.thumbnailCacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The songs in [downloads], in order: a single song as itself ([Song.from]), and a playlist or an
 * album — one download, several files — as every one of its songs. Those are found as the media
 * viewer finds a carousel's files: the ones recorded as they were saved (SavedFile), else, for a
 * download from before that, its files by name. Each one's title, artist and album come from its
 * own tags: the download's are the playlist's, not the song's. Files deleted since are left out.
 */
internal suspend fun songsFor(context: Context, downloads: List<DownloadEntity>): List<Song> = withContext(Dispatchers.IO) {
    val dao = AppDatabase.getDatabase(context).downloadDao()
    downloads.filter { it.isAudio }.flatMap { download ->
        if (download.downloadedItems <= 1) return@flatMap listOfNotNull(Song.from(download))
        val recorded = dao.savedFiles(download.id).map { Uri.parse(it.uri) }
        val files = recorded.takeIf { it.size > 1 } ?: filesByName(context, dao.downloadedFileNames(download.id))
        if (files.size <= 1) return@flatMap listOfNotNull(Song.from(download))
        files.mapIndexedNotNull { index, uri -> songFromFile(context, download, uri, index) }
            .ifEmpty { listOfNotNull(Song.from(download)) }
    }
}

/** One song of a playlist download, from the file's own tags. Null if the file is gone. */
private fun songFromFile(context: Context, download: DownloadEntity, uri: Uri, index: Int): Song? {
    // A playlist's download can hold other files too (its .lrc lyrics, a cover picture).
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    if (mime != null && !mime.startsWith("audio/")) return null
    val retriever = MediaMetadataRetriever()
    val tags = try {
        retriever.setDataSource(context, uri)
        Triple(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
        )
    } catch (e: Exception) {
        return null // deleted from the phone since, or unreadable
    } finally {
        runCatching { retriever.release() }
    }
    val (title, artist, album) = tags
    val file = uri.toString()
    // The first song keeps the download's id: that's what the Library's tile plays from.
    val id = if (index == 0) download.id else "${download.id}#$index"
    return Song(
        id = id,
        uri = uri,
        title = Song.trackTitle(title?.takeIf { it.isNotBlank() } ?: fileTitle(context, uri) ?: download.title, artist),
        artist = artist?.takeIf { it.isNotBlank() } ?: download.artist,
        album = album?.takeIf { it.isNotBlank() } ?: download.album,
        // Its own embedded cover (the download's thumbnail is the playlist's).
        cover = AudioCover(file, null),
        placeholderKey = thumbnailCacheKey(download.id),
        artworkUri = uri,
    )
}

/** The file's name without its extension, for a song with no title tag. */
private fun fileTitle(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
    }
}.getOrNull()

/** These saved files, by display name, in the given order (the newest of a name). */
private fun filesByName(context: Context, names: List<String>): List<Uri> {
    if (names.isEmpty()) return emptyList()
    val collection = MediaStore.Files.getContentUri("external")
    val found = HashMap<String, Uri>()
    names.distinct().chunked(500).forEach { chunk ->
        runCatching {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray(),
                "${MediaStore.MediaColumns._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) found[c.getString(1)] = ContentUris.withAppendedId(collection, c.getLong(0))
            }
        }
    }
    return names.mapNotNull { found[it] }.distinct()
}
