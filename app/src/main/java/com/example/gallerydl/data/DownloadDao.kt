package com.example.gallerydl.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE status IN ('FINISHED', 'SAVED') ORDER BY dateAdded DESC")
    fun getHistoryFlow(): Flow<List<DownloadEntity>>

    /** One-shot snapshot of finished downloads with a thumbnail — scanned periodically to notice
     * when the underlying file was deleted from the gallery outside the app, so it can be moved
     * into the Deleted section instead of showing a broken thumbnail forever. */
    @Query("SELECT * FROM downloads WHERE status IN ('FINISHED', 'SAVED') AND thumbnailPath IS NOT NULL")
    suspend fun getHistoryWithThumbnailOnce(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'DELETED' ORDER BY dateAdded DESC")
    fun getDeletedFlow(): Flow<List<DownloadEntity>>

    // queueOrder first so "Start now" (which jumps a waiting download to a very negative order)
    // moves it to the top regardless of when it was added; dateAdded as the tiebreaker keeps
    // everything else in plain oldest-added-first/newest-added-last order.
    @Query("SELECT * FROM downloads WHERE status NOT IN ('FINISHED', 'SAVED', 'DELETED') ORDER BY queueOrder ASC, dateAdded ASC")
    fun getQueueFlow(): Flow<List<DownloadEntity>>

    /** Bumps a still-waiting download to the front of the queue — see [DownloadDispatcher.startNow]. */
    @Query("UPDATE downloads SET queueOrder = :order WHERE id = :id")
    suspend fun setQueueOrder(id: String, order: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: DownloadStatus)
    
    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateError(id: String, status: DownloadStatus, errorMessage: String?)

    @Query("UPDATE downloads SET downloadStartTime = :startTime WHERE id = :id")
    suspend fun setStartTime(id: String, startTime: Long)

    @Query("UPDATE downloads SET downloadedItems = :downloadedItems, speedMbs = :speed WHERE id = :id")
    suspend fun updateLiveProgress(id: String, downloadedItems: Int, speed: Float)

    /** Zeroed out on pause/cancel so a resumed download doesn't show its old speed reading until
     * fresh progress actually comes in — otherwise the last-known number just sits there stale. */
    @Query("UPDATE downloads SET speedMbs = 0 WHERE id = :id")
    suspend fun resetSpeed(id: String)

    /** yt-dlp reports a file's total size before any bytes move — set once per item as soon as
     * it's known, so the UI can show a real size and a byte-accurate progress fraction instead of
     * an item-count-only one. */
    @Query("UPDATE downloads SET expectedBytes = :bytes WHERE id = :id")
    suspend fun setExpectedBytes(id: String, bytes: Long)

    /** Absolute (not additive) — overwritten on every progress tick with how far into the
     * currently-downloading file we are, unlike addBytes' running total across completed files. */
    @Query("UPDATE downloads SET liveBytes = :bytes, speedMbs = :speed WHERE id = :id")
    suspend fun updateLiveBytes(id: String, bytes: Long, speed: Float)

    /** Known ahead of time only when the download came from the share-sheet item picker (its
     * selection count) or when we enumerate the gallery first via list_items(); otherwise stays 0
     * and the UI falls back to an indeterminate progress indicator. */
    @Query("UPDATE downloads SET totalItems = :totalItems WHERE id = :id")
    suspend fun setTotalItems(id: String, totalItems: Int)

    @Query("UPDATE downloads SET thumbnailPath = :thumbnailUri WHERE id = :id AND thumbnailPath IS NULL")
    suspend fun setThumbnailIfAbsent(id: String, thumbnailUri: String)

    /** Unconditional — for a single-item download (every yt-dlp download, or a lone-item
     * gallery-dl one) there's no "which item should stay pinned as the thumbnail" ambiguity like
     * there is for a multi-item gallery (see setThumbnailIfAbsent), so the real local file's
     * thumbnail should always win over an earlier remote preview URL, not just fill an empty slot. */
    @Query("UPDATE downloads SET thumbnailPath = :thumbnailUri WHERE id = :id")
    suspend fun setThumbnail(id: String, thumbnailUri: String)

    @Query("UPDATE downloads SET totalBytes = totalBytes + :bytes WHERE id = :id")
    suspend fun addBytes(id: String, bytes: Long)

    @Query("UPDATE downloads SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE downloads SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE downloads SET workRequestId = :workRequestId WHERE id = :id")
    suspend fun setWorkRequestId(id: String, workRequestId: String?)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    /** One-shot (non-Flow) snapshot of everything still waiting to start — used to re-submit
     * their WorkManager jobs when a setting that affects delay (like the schedule window)
     * changes, since a job's setInitialDelay() is fixed at the moment it was enqueued and won't
     * otherwise notice the setting changed. Covers SCHEDULED too, since turning the schedule off
     * needs to pull those back into QUEUED just as much as turning it on needs to push QUEUED
     * items into SCHEDULED. */
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'SCHEDULED')")
    suspend fun getQueuedOnce(): List<DownloadEntity>

    /** One-shot snapshot of everything still marked RUNNING — used at app startup to catch a
     * download whose WorkManager job died out from under it (process killed/frozen mid-download,
     * an OS out-of-memory kill, ...) without ever getting the chance to write a terminal status.
     * Unlike [getQueuedOnce] this is never called reactively from pause/cancel — a RUNNING row's
     * job dying isn't a normal WorkManager chain-cascade side effect the way an unstarted one's is,
     * so there's no equivalent "just happened, check right now" trigger; a fresh app process is the
     * only reliable point to notice a stale RUNNING row from a process that no longer exists. */
    @Query("SELECT * FROM downloads WHERE status = 'RUNNING'")
    suspend fun getRunningOnce(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    /** Records that [filename] has been moved into the gallery for [downloadId]. Returns -1 if it
     * was already recorded (a duplicate announcement from gallery-dl) or the new row id otherwise
     * — callers use that to tell "genuinely new file" apart from "already handled, don't recount". */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordDownloadedFile(record: DownloadedFileRecord): Long

    @Query("DELETE FROM downloaded_files WHERE downloadId = :downloadId")
    suspend fun clearDownloadedFileRecords(downloadId: String)
}
