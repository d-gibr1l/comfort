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
// history grows. These match getHistoryFlow/getDeletedFlow's own WHERE status = ... ORDER BY
// dateAdded shape, and getQueueFlow's WHERE status NOT IN (...) ORDER BY queueOrder, dateAdded
// shape, respectively — see MIGRATION_9_10 in AppDatabase.kt for the matching migration.
// The third covers getQueueFlow's ERRORED branch, sorted by erroredAt instead of queueOrder (see
// MIGRATION_14_15) — added alongside the erroredAt column itself. Note this still doesn't let
// SQLite skip the sort step entirely: getQueueFlow's ORDER BY is a CASE expression picking between
// two different columns depending on status, and an index can only avoid sorting when ORDER BY is
// directly by indexed column(s), never through a CASE — so this index speeds up the WHERE status
// filtering into that branch, not the final sort. A real index-served sort would need a single
// persisted sort-key column instead, which is more machinery than this table's realistic size
// (a personal downloader's own queue, not a server-scale table) currently justifies.
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status", "dateAdded"]),
        Index(value = ["status", "queueOrder", "dateAdded"]),
        Index(value = ["status", "erroredAt"]),
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
    // "start-end" timestamps (e.g. "00:10-01:30"), set in the download preview sheet's Trim
    // screen before starting the download. Multiple comma-separated ranges are allowed
    // ("00:10-00:20,01:00-01:30") — yt-dlp's own download_ranges takes a list, so several cuts of
    // one video come out as one file. Only meaningful for yt-dlp-routed downloads — gallery-dl
    // has no concept of trimming a gallery of images to a time range, so this is simply never
    // read on that path. Null means download the whole thing.
    val clipRange: String? = null,
    // The rest of the per-download overrides the preview sheet collects. Each is null when the
    // user didn't override it there, in which case DownloadWorker falls back to the global
    // Settings value at download time — same behavior as before the sheet existed, and the reason
    // these are nullable rather than defaulted: a retry/resume re-applies what the user actually
    // picked for *this* download instead of silently drifting to a since-changed global default.
    val extraCommands: String? = null,
    val outputFormat: String? = null,
    val filenameTemplate: String? = null,
    val saveThumbnail: Boolean? = null,
    // When this download most recently entered ERRORED, not when it was added — getQueueFlow
    // sorts errored downloads by this (newest first) so a download that just failed jumps above
    // errors that have been sitting there a while, instead of all errors settling into plain
    // queueOrder/dateAdded order regardless of which one actually just happened. Null for anything
    // that has never errored.
    val erroredAt: Long? = null,
    // The real saved media file's own content Uri — distinct from thumbnailPath, which for an
    // audio download now holds a standalone extracted-cover-art image instead (see
    // MediaStoreHelper.extractAudioArtworkUri's own doc comment: Coil can't decode a video frame
    // OR embedded audio artwork straight out of an audio file's Uri the way it can for video, so
    // thumbnailPath had to stop being "the same Uri, dual-purposed as both display image and
    // open/play target" for audio specifically). Null for video/image downloads, where
    // thumbnailPath is still exactly that same saved file's own Uri and doubles as both, same as
    // before this field existed — the Library screen's own tap-to-open falls back to
    // thumbnailPath whenever this is null, so that behavior is unchanged.
    val mediaUri: String? = null,
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
