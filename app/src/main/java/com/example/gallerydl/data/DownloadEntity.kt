package com.example.gallerydl.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

enum class DownloadStatus {
    RUNNING, QUEUED, SCHEDULED, CANCELLED, ERRORED, FINISHED, SAVED
}

@Entity(tableName = "downloads")
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
    // gallery-dl `--filter "num in {...}"` expression, set when the user picked specific items
    // in the share sheet instead of the whole gallery. Persisted so retry/resume re-applies the
    // same selection instead of re-fetching everything.
    val itemFilter: String? = null,
)
