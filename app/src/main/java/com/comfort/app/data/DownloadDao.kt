package com.comfort.app.data

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

    // ERRORED first (rank 0, everything else rank 1) so a failed download always surfaces at the
    // top instead of wherever plain chronological order happened to leave it — reproduced live: a
    // download that fails immediately keeps the queueOrder/dateAdded of when it was *added*, which
    // in a long queue buries it off-screen at the bottom, easy to miss entirely. Within the errored
    // rank, newest erroredAt first — the download that just failed jumps above errors that have
    // been sitting there a while, rather than all errors settling into queueOrder/dateAdded order
    // regardless of which one actually just happened (COALESCE to dateAdded covers rows from
    // before erroredAt existed). Within the non-errored rank: queueOrder first so "Start now"
    // (which jumps a waiting download to a very negative order) still moves it to the top of that
    // group regardless of when it was added; dateAdded as the tiebreaker keeps everything else in
    // plain oldest-added-first order.
    @Query("""
        SELECT * FROM downloads
        WHERE status NOT IN ('FINISHED', 'SAVED', 'DELETED')
        ORDER BY
            CASE WHEN status = 'ERRORED' THEN 0 ELSE 1 END ASC,
            CASE WHEN status = 'ERRORED' THEN -COALESCE(erroredAt, dateAdded) ELSE queueOrder END ASC,
            dateAdded ASC
    """)
    fun getQueueFlow(): Flow<List<DownloadEntity>>

    /** One-shot, in queue order: everything running, waiting or paused — Pause All / Resume All
     * read this (not the UI's StateFlow, which can be stale with no subscriber) to keep the order. */
    @Query("""
        SELECT * FROM downloads
        WHERE status IN ('RUNNING', 'QUEUED', 'SCHEDULED', 'PAUSED')
        ORDER BY queueOrder ASC, dateAdded ASC
    """)
    suspend fun getActiveInQueueOrderOnce(): List<DownloadEntity>

    /** Sends a retried download to the back of the queue, as if it had just been added: the queue
     * (and DownloadWorker's concurrency gate) order by queueOrder then dateAdded, so without this a
     * retry kept its original, usually older dateAdded and was *listed* near the top while
     * WorkManager actually ran it last, behind everything already queued. Also drops any old
     * "Up next" mark (negative queueOrder). */
    @Query("UPDATE downloads SET queueOrder = 0, dateAdded = :now WHERE id = :id")
    suspend fun moveToQueueEnd(id: String, now: Long)

    /** Bumps a still-waiting download to the front of the queue — see [DownloadDispatcher.startNow]. */
    @Query("UPDATE downloads SET queueOrder = :order WHERE id = :id")
    suspend fun setQueueOrder(id: String, order: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: DownloadStatus)
    
    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage, erroredAt = :erroredAt WHERE id = :id")
    suspend fun updateError(id: String, status: DownloadStatus, errorMessage: String?, erroredAt: Long = System.currentTimeMillis())

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

    /** Set/cleared from yt_dlp_wrapper.py's own "[phase] audio"/"[phase] video" signal — see
     * DownloadEntity.downloadingAudioTrack's own doc comment. */
    @Query("UPDATE downloads SET downloadingAudioTrack = :value WHERE id = :id")
    suspend fun setDownloadingAudioTrack(id: String, value: Boolean)

    /** From yt_dlp_wrapper.py's own "[format] ..." signal — see DownloadEntity.formatTags' own
     * doc comment. */
    @Query("UPDATE downloads SET formatTags = :tags WHERE id = :id")
    suspend fun setFormatTags(id: String, tags: String)

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

    // See DownloadEntity.mediaUri's own doc comment — set alongside setThumbnail/
    // setThumbnailIfAbsent only when thumbnailPath just got pointed at a standalone extracted
    // image instead of the real saved file (audio downloads), so the Library screen's tap-to-open
    // still has the real file's own Uri to open/play, not the cover art image.
    @Query("UPDATE downloads SET mediaUri = :uri WHERE id = :id")
    suspend fun setMediaUri(id: String, uri: String)

    @Query("UPDATE downloads SET mediaUri = :uri WHERE id = :id AND mediaUri IS NULL")
    suspend fun setMediaUriIfAbsent(id: String, uri: String)

    @Query("UPDATE downloads SET isAudio = :isAudio WHERE id = :id")
    suspend fun setIsAudio(id: String, isAudio: Boolean)

    // *IfAbsent only, deliberately no unconditional variant: artist/album/track are one-shot
    // metadata about the single extraction running for this download (yt-dlp's info_dict, or
    // Spotify's own scraped metadata when routed through spotify_wrapper.py) — there's no
    // per-item "which one should win" ambiguity the way thumbnail has for a multi-item gallery,
    // so IfAbsent alone is enough, and it also means a retry/resume never clobbers a value a
    // first attempt already captured with a possibly lower-confidence later guess.
    @Query("UPDATE downloads SET artist = :artist WHERE id = :id AND artist IS NULL")
    suspend fun setArtistIfAbsent(id: String, artist: String)

    // Unconditional, unlike setArtistIfAbsent above — for the song preview sheet's own editable
    // artist field (SongPreviewCard/DownloadEntity.overrideArtist): a user's explicit edit should
    // always win over whatever *IfAbsent already captured from the source, not silently no-op
    // because a value happened to land first.
    @Query("UPDATE downloads SET artist = :artist WHERE id = :id")
    suspend fun setArtist(id: String, artist: String)

    @Query("UPDATE downloads SET album = :album WHERE id = :id AND album IS NULL")
    suspend fun setAlbumIfAbsent(id: String, album: String)

    @Query("UPDATE downloads SET track = :track WHERE id = :id AND track IS NULL")
    suspend fun setTrackIfAbsent(id: String, track: String)

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

    /** Used by "Prevent duplicate downloads" (Settings > Downloads) — an existing entry for the
     * exact same URL *and item selection* that's still meaningful to the user: actively queued/
     * running/paused, or already finished. CANCELLED/DELETED/ERRORED rows don't count as a
     * duplicate — those represent a download the user explicitly gave up on or that never
     * produced anything, so a fresh attempt at the same URL is a deliberate retry, not an
     * accidental re-submission.
     *
     * [itemFilter] (SharePickerScreen's own `"num in {...}"` string, null for "the whole gallery")
     * matters here, not just [url]: without it, picking a *different* subset of items from the
     * same multi-item gallery post than a previous download used would be wrongly flagged as a
     * duplicate of that unrelated selection and silently skipped — a real correctness bug, not
     * just a missing label, since it could block genuinely new content the user explicitly asked
     * for. `itemFilter IS :itemFilter` (not `=`) is SQLite's own null-safe equality — true when
     * both sides are null (two "whole gallery" downloads) as well as when both are the same
     * non-null string, false for anything else (including one null, one not) — exactly the
     * "same selection" semantics this needs. Every caller downloading a single, non-gallery url
     * (DownloadPreviewSheet, Instant Share, a plain shared link) always has itemFilter null on
     * both sides here regardless, so this is a strict refinement for them, not a behavior change —
     * only SharePickerScreen's own partial-selection case is actually affected. */
    @Query("SELECT * FROM downloads WHERE url = :url AND itemFilter IS :itemFilter AND status IN ('QUEUED', 'SCHEDULED', 'RUNNING', 'PAUSED', 'FINISHED') LIMIT 1")
    suspend fun findActiveOrFinishedByUrl(url: String, itemFilter: String? = null): DownloadEntity?

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

    /** Used by the periodic leftover-downloads cleanup sweep (Settings > Downloads) — cancelled
     * or errored downloads whose staging directory may still be sitting in cache (either because
     * "Delete leftover on failure" was off at the time, or the process was killed before that
     * cleanup code ever ran). QUEUED/SCHEDULED/RUNNING/PAUSED are deliberately excluded since
     * their staging dir is still in active use. */
    @Query("SELECT id FROM downloads WHERE status IN ('CANCELLED', 'ERRORED')")
    suspend fun getCancelledOrErroredIdsOnce(): List<String>

    /** Records that [filename] has been moved into the gallery for [downloadId]. Returns -1 if it
     * was already recorded (a duplicate announcement from gallery-dl) or the new row id otherwise
     * — callers use that to tell "genuinely new file" apart from "already handled, don't recount". */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordDownloadedFile(record: DownloadedFileRecord): Long

    @Query("DELETE FROM downloaded_files WHERE downloadId = :downloadId")
    suspend fun clearDownloadedFileRecords(downloadId: String)

    @Query("SELECT * FROM duplicate_attempts ORDER BY dateAdded DESC")
    fun getDuplicateAttemptsFlow(): Flow<List<DuplicateAttempt>>

    @Insert
    suspend fun insertDuplicateAttempt(attempt: DuplicateAttempt)

    @Query("DELETE FROM duplicate_attempts WHERE id = :id")
    suspend fun deleteDuplicateAttempt(id: String)

    @Query("DELETE FROM duplicate_attempts")
    suspend fun clearDuplicateAttempts()
}
