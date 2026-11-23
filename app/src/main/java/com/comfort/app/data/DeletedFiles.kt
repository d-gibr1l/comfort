package com.comfort.app.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** A multi-item download's files deleted from the phone since they were saved (in the gallery, a
 * file manager, another app): its card counts only what's left, and "Download missing items"
 * fetches exactly those again — see [prepareRedownload]. */
object DeletedFiles {
    private const val CHUNK = 500

    /** How many files each download has had deleted, by id (only those with any): the Library's
     * cards read it. Kept up by DownloadsViewModel.refreshDeleted. */
    val counts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Of [files], the ones no longer on the phone. One MediaStore query per volume for the lot
     * (by row id), not one per file; a file in the trash counts as deleted. A file whose state
     * can't be read (a folder the app lost access to) counts as still there: nothing's offered
     * for a file that may not be gone. */
    fun missing(context: Context, files: List<SavedFile>): List<SavedFile> {
        if (files.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        val gone = ArrayList<SavedFile>()
        val byVolume = HashMap<String, MutableList<Pair<SavedFile, Long>>>()
        for (file in files) {
            val uri = Uri.parse(file.uri)
            val id = uri.lastPathSegment?.toLongOrNull()
            if (uri.authority == MediaStore.AUTHORITY && id != null) {
                val volume = uri.pathSegments.firstOrNull() ?: continue
                byVolume.getOrPut(volume) { ArrayList() } += file to id
            } else if (!exists(context, uri)) {
                gone += file
            }
        }
        for ((volume, entries) in byVolume) {
            val present = HashSet<Long>()
            var readable = true
            for (chunk in entries.chunked(CHUNK)) {
                val ids = chunk.map { it.second }
                val selection = "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",") { "?" }})"
                val rows = runCatching {
                    resolver.query(
                        MediaStore.Files.getContentUri(volume), arrayOf(MediaStore.MediaColumns._ID),
                        selection, ids.map { it.toString() }.toTypedArray(), null,
                    )?.use { c -> while (c.moveToNext()) present += c.getLong(0); true }
                }.getOrNull()
                if (rows != true) { readable = false; break }
            }
            // Not in the query: gone, or hidden from the app (see MediaStoreHelper.reallyGone).
            if (readable) entries.filter { it.second !in present }
                .filter { com.comfort.app.util.MediaStoreHelper.reallyGone(context, Uri.parse(it.first.uri)) }
                .forEach { gone += it.first }
        }
        return gone
    }

    /** A file outside MediaStore (a folder chosen in Settings, through the storage picker). */
    private fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: true
    } catch (_: java.io.FileNotFoundException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: Exception) {
        true
    }

    // yt-dlp's names end in "[id] [extractor].ext" (its filename template), which is how its
    // archive knows an item: "extractor id".
    private val YT_DLP_NAME = Regex("""\[([^\[\]]+)] \[([^\[\]]+)]\.[A-Za-z0-9]+$""")

    /** Gets [downloadId] ready to fetch its [gone] files again: forgets them (their saved-file rows,
     * their names in downloaded_files, which would turn their copies away as already saved, and
     * the count), and takes them out of the engines' archives, which would skip them. yt-dlp's
     * items come out of its archive one by one (its file names say which), so a re-run fetches
     * only those; a file it can't place (gallery-dl's, Instaloader's, a download saved before
     * names were kept) clears the archives, and the files still there aren't saved twice
     * (downloaded_files). */
    suspend fun prepareRedownload(context: Context, downloadId: String, gone: List<SavedFile>) {
        val archives = File(context.filesDir, "archives")
        if (gone.isEmpty()) {
            // Nothing deleted: items that failed (or a run stopped before them). What the engines
            // recorded may cover them (a file fetched but never saved), so they forget it; the
            // files already saved aren't saved twice (downloaded_files).
            clearArchives(archives, downloadId)
            return
        }
        val dao = AppDatabase.getDatabase(context).downloadDao()
        // Names: kept with each file since this was added; an older download's come from the
        // order they arrived in, when that order lines up with its files one for one.
        val names = dao.downloadedFileNames(downloadId)
        val saved = dao.savedFiles(downloadId)
        val byOrder = if (names.size == saved.size) saved.zip(names).associate { (f, n) -> f.uri to n } else emptyMap()
        val ytdlp = File(archives, "$downloadId.ytdlp.txt")
        val ytdlpLines = if (ytdlp.isFile) ytdlp.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        val archiveLines = HashSet<String>()
        var unplaced = false
        for (file in gone) {
            val name = file.path ?: byOrder[file.uri]
            if (name != null) dao.forgetDownloadedFile(downloadId, name)
            // A yt-dlp item only if its "extractor id" really is in yt-dlp's archive: gallery-dl's
            // names can end in "[id] [site].jpg" too (a filename format), and taking one of those
            // for yt-dlp's left gallery-dl's archive in place, so nothing came back (reported).
            val key = name?.let { YT_DLP_NAME.find(it) }?.let { "${it.groupValues[2]} ${it.groupValues[1]}".lowercase() }
            val line = key?.let { k -> ytdlpLines.firstOrNull { it.lowercase() == k } }
            if (line != null) archiveLines += line else unplaced = true
            dao.deleteSavedFile(downloadId, file.uri)
        }
        dao.decrementDownloadedItems(downloadId, gone.size)
        if (unplaced) {
            clearArchives(archives, downloadId)
        } else {
            val kept = ytdlpLines.filter { it !in archiveLines }
            ytdlp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        }
    }

    private fun clearArchives(archives: File, downloadId: String) {
        File(archives, "$downloadId.ytdlp.txt").delete()
        File(archives, "$downloadId.sqlite3").delete()
        File(archives, "$downloadId.instaloader.txt").delete()
    }
}
