package com.comfort.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus {
    RUNNING, QUEUED, SCHEDULED, PAUSED, CANCELLED, ERRORED, FINISHED, SAVED, DELETED
}

// DownloadWorker calls updateLiveBytes multiple times a second while anything is RUNNING, and
// every one of those writes invalidates every Flow query below (getQueueFlow/getHistoryFlow/
// getDeletedFlow) regardless of which rows actually changed — Room re-runs each one to check.
// Without an index matching a query's own status/order columns, that re-run is a full table scan
// *and* a full sort (SQLite's own "Filesort") over the whole downloads table, up to ~10 times a
// second, entirely on a background thread but still real, silent CPU churn that compounds as
// history grows. These two match getHistoryFlow/getDeletedFlow's own WHERE status = ...
// ORDER BY dateAdded shape, and getQueueFlow's WHERE status NOT IN (...) ORDER BY queueOrder,
// dateAdded shape, respectively — see MIGRATION_9_10 in AppDatabase.kt for the matching migration.
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status", "dateAdded"]),
        Index(value = ["status", "queueOrder", "dateAdded"]),
    ],
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val thumbnailPath: String?,
    val status: DownloadStatus,
    val progress: Float,
    val downloadedItems: Int,
    val totalItems: Int,
    val speedMbs: Float,
    val etaSeconds: Long,
    val errorMessage: String?,
    val dateAdded: Long,

    // YTDLnis architectural adoptions
    @ColumnInfo(defaultValue = "0")
    var downloadStartTime: Long = 0,
    var logID: Long? = null,
    @ColumnInfo(defaultValue = "0")
    var incognito: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var queueOrder: Int = 0,
    val downloadPath: String = "",
    @ColumnInfo(defaultValue = "0")
    val totalBytes: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    val workRequestId: String? = null,
    // Known-upfront total size of the file currently downloading (yt-dlp reports this before any
    // bytes move) and how far into it the download has actually gotten — together these drive a
    // byte-accurate progress bar/speed for single-item downloads instead of the item-count-only
    // fraction, which can only ever jump straight from 0% to 100% when there's just one item.
    // Both go stale immediately once the item finishes (superseded by totalBytes/downloadedItems),
    // which is fine since neither is read once the download leaves RUNNING.
    @ColumnInfo(defaultValue = "0")
    val expectedBytes: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val liveBytes: Long = 0,
    // gallery-dl `--filter "num in {...}"` expression, set when the user picked specific items
    // in the share sheet instead of the whole gallery. Persisted so retry/resume re-applies the
    // same selection instead of re-fetching everything.
    val itemFilter: String? = null,
    // VideoQuality enum name, set when the share-sheet picker offered a per-download quality
    // choice (only shown when the listing contains a video item) instead of the global Settings
    // default. Null means "use whatever Settings says at download time" — same as before this
    // field existed — so a retry/resume still re-applies the quality the user actually picked
    // rather than silently falling back to the global default if it's since changed.
    val videoQuality: String? = null,
    // "start-end" timestamps (e.g. "00:10-01:30"), set on the Home screen's paste-a-link field
    // before starting the download. Only meaningful for yt-dlp-routed downloads — gallery-dl has
    // no concept of trimming a gallery of images to a time range, so this is simply never read on
    // that path. Null means download the whole thing, same as before this field existed.
    val clipRange: String? = null,
) {
    /** When this download actually happened, not when the link was submitted — those can differ
     * a lot with Wi-Fi-only or a schedule window in play, where a download can sit QUEUED for
     * hours before DownloadWorker ever runs. Falls back to [dateAdded] for anything that hasn't
     * started running yet (downloadStartTime stays at its 0 default until DownloadWorker's own
     * first line, `dao.setStartTime(...)`, actually fires), so a still-queued item still sorts/
     * displays sensibly. */
    val effectiveDate: Long
        get() = downloadStartTime.takeIf { it > 0 } ?: dateAdded
}
