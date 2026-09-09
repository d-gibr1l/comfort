package com.comfort.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A log of "you already have this" moments — recorded whenever [DownloadDispatcher.enqueueDownload]
 * short-circuits a link that "Prevent duplicate downloads" (Settings > Downloads) recognizes as
 * already queued, running, or finished, instead of silently dropping the attempt with no trace.
 * Shown in Library's own "Duplicates" filter (DownloadsHistoryScreen) — the point being a link
 * shared in from outside the app that got skipped isn't just gone with no record it was ever
 * tried, and can be explicitly redownloaded from there (or straight from the share sheet's own
 * snackbar at the moment it happens — see ShareActivity). */
@Entity(tableName = "duplicate_attempts")
data class DuplicateAttempt(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val thumbnailPath: String?,
    // The existing downloads row this attempt matched — lets a "Redownload" action or just this
    // entry's own tap target jump straight to what's already there, not just a bare URL.
    val originalDownloadId: String,
    val dateAdded: Long,
)
