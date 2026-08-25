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

    @Query("SELECT * FROM downloads WHERE status NOT IN ('FINISHED', 'SAVED') ORDER BY dateAdded ASC")
    fun getQueueFlow(): Flow<List<DownloadEntity>>

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

    @Query("UPDATE downloads SET thumbnailPath = :thumbnailUri WHERE id = :id AND thumbnailPath IS NULL")
    suspend fun setThumbnailIfAbsent(id: String, thumbnailUri: String)

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
     * otherwise notice the setting changed. */
    @Query("SELECT * FROM downloads WHERE status = 'QUEUED'")
    suspend fun getQueuedOnce(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}
