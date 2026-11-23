package com.comfort.app.data

import androidx.room.Entity

/** Where each file of a download was saved (its MediaStore Uri) and its place in the post: what
 * the media viewer pages through for a carousel. [DownloadedFileRecord] only knows the source
 * file's name, which needn't be the saved one (WebP saved as JPG, MediaStore's " (1)" on a name
 * already taken). */
@Entity(tableName = "saved_files", primaryKeys = ["downloadId", "uri"])
data class SavedFile(
    val downloadId: String,
    val uri: String,
    /** 1-based, in the order the engine delivered the files (a carousel's own order). */
    val position: Int,
    /** The file's name as the engine saved it (its staging name, as in downloaded_files): which
     * item it was, for "Download missing items" once it's deleted (see DeletedFiles). Null for a
     * file saved before this was kept. (Added in database v30 for another use, since dropped.) */
    val path: String? = null,
)
