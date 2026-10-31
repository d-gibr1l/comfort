package com.comfort.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** A running download's bytes so far (of the file it's on) and its speed, kept in memory rather
 * than written to the database each time they move.
 *
 * They used to be: about once a second for every running download, and every write to the
 * downloads table makes Room re-run every query watching it — the Library's whole history, the
 * deleted list, the Queue — whichever rows changed. With a long history and a few downloads
 * going, that's thousands of rows read and turned into objects a second, for a Library that
 * never shows either value. Now only the Queue (and Home's running card) pick them up, through
 * [overlay], from this map.
 *
 * The worker and the screens share one process (no android:process anywhere), so this is seen by
 * both. The bytes still reach the database every [PERSIST_EVERY_MS] and when the run ends
 * ([flush]): a paused card shows how far it got from there, and the next run's notification
 * starts from it. The speed doesn't need to — a download that isn't running has none. */
object LiveProgress {
    /** [bytes] null: only the speed is known yet this run (the card keeps the database's bytes). */
    data class Live(val bytes: Long?, val speedMbs: Float)

    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())
    val live: StateFlow<Map<String, Live>> = _live.asStateFlow()

    private val persistedAt = ConcurrentHashMap<String, Long>()
    private const val PERSIST_EVERY_MS = 15_000L

    /** The current file's [bytes] so far and the [speedMbs]. 0 bytes (a file just saved, its
     * bytes now counted in totalBytes) goes to the database at once, so it never adds them twice. */
    suspend fun bytes(dao: DownloadDao, id: String, bytes: Long, speedMbs: Float) {
        _live.update { it + (id to Live(bytes, speedMbs)) }
        val now = System.currentTimeMillis()
        val last = persistedAt[id]
        if (bytes == 0L || last == null || now - last >= PERSIST_EVERY_MS) {
            persistedAt[id] = now
            dao.setLiveBytes(id, bytes)
        }
    }

    fun speed(id: String, speedMbs: Float) {
        _live.update { it + (id to Live(it[id]?.bytes, speedMbs)) }
    }

    /** Writes the latest bytes to the database: the run is ending. */
    suspend fun flush(dao: DownloadDao, id: String) {
        _live.value[id]?.bytes?.let { dao.setLiveBytes(id, it) }
    }

    fun clear(id: String) {
        _live.update { it - id }
        persistedAt.remove(id)
    }

    /** [items] with the running ones' live bytes and speed. Only RUNNING rows: a card just paused
     * or cancelled (shown before the database has it, see DownloadsViewModel's pending moves)
     * shows no speed. */
    fun overlay(items: List<DownloadEntity>, live: Map<String, Live>): List<DownloadEntity> {
        if (live.isEmpty()) return items
        return items.map { item ->
            val l = live[item.id]
            if (l == null || item.status != DownloadStatus.RUNNING) item
            else item.copy(liveBytes = l.bytes ?: item.liveBytes, speedMbs = l.speedMbs)
        }
    }
}
