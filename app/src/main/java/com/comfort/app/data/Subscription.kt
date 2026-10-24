package com.comfort.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A YouTube channel or playlist the app watches for new uploads (Settings › Subscriptions), and
 * the download choices its new uploads are queued with — the same per-download overrides the
 * preview sheet hands DownloadDispatcher.enqueueDownload, saved from the sheet it was subscribed
 * from. Null option columns mean "whatever Settings says at download time", as for a download. */
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    /** 6, 12 or 24 — see [SubscriptionChecks.INTERVAL_CHOICES]. */
    val intervalHours: Int = 24,
    val paused: Boolean = false,
    /** A [VideoQuality] name. */
    val videoQuality: String? = null,
    /** An [OutputFormat] name. */
    val outputFormat: String? = null,
    /** An [AudioFormat] name. */
    val audioFormat: String? = null,
    val saveThumbnail: Boolean? = null,
    val extraCommands: String? = null,
    val filenameTemplate: String? = null,
    val createdAt: Long,
    /** When the last check ran, whether it worked or not. */
    val lastCheckedAt: Long? = null,
    /** The last check's error, or null when it worked. */
    val lastError: String? = null,
    /** Set once a check has succeeded and recorded what the source already had. Not inferred from
     * [lastCheckedAt]: a first check that fails still sets that, and the next check must then
     * still only record, not download the whole listing. */
    val initialized: Boolean = false,
)

/** One upload a subscription has already seen — queued, or there before it was subscribed to. */
// No separate index: the composite primary key is itself an index led by subscriptionId, which is
// what every query here (a source's seen ids, deleting a source's rows) looks up by.
@Entity(tableName = "subscription_seen", primaryKeys = ["subscriptionId", "entryId"])
data class SubscriptionSeenEntry(
    val subscriptionId: String,
    /** yt-dlp's id for the entry (a YouTube video id). */
    val entryId: String,
)

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscriptions ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<Subscription>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: String): Subscription?

    @Query("SELECT * FROM subscriptions WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): Subscription?

    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subscription: Subscription)

    @Query("UPDATE subscriptions SET paused = :paused WHERE id = :id")
    suspend fun setPaused(id: String, paused: Boolean)

    @Query("UPDATE subscriptions SET intervalHours = :hours WHERE id = :id")
    suspend fun setInterval(id: String, hours: Int)

    @Query("UPDATE subscriptions SET lastCheckedAt = :at, lastError = :error WHERE id = :id")
    suspend fun setCheckResult(id: String, at: Long, error: String?)

    /** A successful check: also refreshes the title/thumbnail, since a channel can rename itself. */
    @Query("UPDATE subscriptions SET lastCheckedAt = :at, lastError = NULL, initialized = 1, title = :title, thumbnail = COALESCE(:thumbnail, thumbnail) WHERE id = :id")
    suspend fun setCheckSucceeded(id: String, at: Long, title: String, thumbnail: String?)

    @Query("SELECT entryId FROM subscription_seen WHERE subscriptionId = :id")
    suspend fun getSeenIds(id: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeen(entries: List<SubscriptionSeenEntry>)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscriptionRow(id: String)

    @Query("DELETE FROM subscription_seen WHERE subscriptionId = :id")
    suspend fun deleteSeen(id: String)

    /** Removes a source and everything it had seen. */
    @Transaction
    suspend fun delete(id: String) {
        deleteSeen(id)
        deleteSubscriptionRow(id)
    }
}
