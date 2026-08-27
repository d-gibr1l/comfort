package com.example.gallerydl.data

import androidx.room.Entity

/** Tracks which source filenames have already been moved into the gallery for a given download,
 * independent of gallery-dl's own --download-archive. That archive isn't reliably updated before
 * a pause/cancel interrupt can land — a file already moved and counted on our side can still get
 * re-announced as "fresh" by gallery-dl on a later resume, which would otherwise double-count it
 * and save a duplicate copy. This table is the actual source of truth for "have we already
 * handled this specific file for this download". */
@Entity(tableName = "downloaded_files", primaryKeys = ["downloadId", "filename"])
data class DownloadedFileRecord(
    val downloadId: String,
    val filename: String,
)
