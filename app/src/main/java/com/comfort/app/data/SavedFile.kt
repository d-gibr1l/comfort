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
)
