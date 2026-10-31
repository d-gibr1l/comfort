package com.comfort.app.viewmodel

import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.util.MediaShape
import com.comfort.app.util.MediaShapes
import com.comfort.app.util.MediaStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).downloadDao()
    private val shapeCache = ConcurrentHashMap<String, MediaShape>()
    private val isScanning = AtomicBoolean(false)

    // Both lists at most every LIST_UPDATE_MS (the first change at once): a batch — 200 scheduled
    // downloads let go when their window opens, a Retry All, many finishing together — is one
    // status write per download, and each used to rebuild the Queue/Library and recount its tabs.
    // Progress lines only arrive about once a second anyway.
    private val rawHistoryFlow: StateFlow<List<DownloadEntity>> = dao.getHistoryFlow()
        .throttleLatest(LIST_UPDATE_MS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawDeletedFlow: StateFlow<List<DownloadEntity>> = dao.getDeletedFlow()
        .throttleLatest(LIST_UPDATE_MS) // a deleted-files scan or bulk delete is a burst too
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawQueueFlow: StateFlow<List<DownloadEntity>> = dao.getQueueFlow()
        .throttleLatest(LIST_UPDATE_MS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Optimistic-hide set shared by every screen's undoable delete (see hideForDeletion() below)
    // — an id sits here for the window between the user tapping delete and either the Undo
    // Snackbar timing out (confirmDelete() actually removes it) or the user tapping Undo
    // (cancelDeletion() un-hides it), *and* for the brief window bulk deletes need between
    // tapping and each sequential disk I/O finishing. Every list below filters through it, not
    // just the queue — the Library screen's better-interface review found the exact same
    // no-confirmation/no-undo HIGH finding already fixed here for the Queue screen.
    private val _deletingIds = MutableStateFlow<Set<String>>(emptySet())

    val historyFlow: StateFlow<List<DownloadEntity>> = combine(rawHistoryFlow, _deletingIds) { history, deleting ->
        if (deleting.isEmpty()) history else history.filterNot { it.id in deleting }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Each Library item's shape and length (MediaShapes), by download id — what the grid sizes
     * its tiles and duration badges from. Read from MediaStore off the main thread; an audio
     * download's cover art gives the shape and its real file (mediaUri) the length. Cached to
     * avoid re-parsing MediaStore for existing items across emissions. */
    val mediaShapes: StateFlow<Map<String, MediaShape>> = combine(rawHistoryFlow, rawDeletedFlow) { history, deleted -> history + deleted }
        .map { items ->
            withContext(Dispatchers.IO) {
                val currentIds = items.map { it.id }.toSet()
                shapeCache.keys.retainAll(currentIds)
                items.mapNotNull { item ->
                    val cached = shapeCache[item.id]
                    if (cached != null) return@mapNotNull item.id to cached
                    val shape = item.thumbnailPath?.let { MediaShapes.of(getApplication(), it) } ?: return@mapNotNull null
                    val duration = item.mediaUri?.let { MediaShapes.of(getApplication(), it)?.durationMs } ?: shape.durationMs
                    val resolved = shape.copy(durationMs = duration)
                    shapeCache[item.id] = resolved
                    item.id to resolved
                }.toMap()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val deletedFlow: StateFlow<List<DownloadEntity>> = combine(rawDeletedFlow, _deletingIds) { deleted, deleting ->
        if (deleting.isEmpty()) deleted else deleted.filterNot { it.id in deleting }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A change the user just asked for, shown before the database has it: [apply] turns the row
     * into what it will be, for as long as the row is still in status [from]. */
    private class PendingMove(val from: DownloadStatus, val apply: (DownloadEntity) -> DownloadEntity)

    // What a bulk action looks like from the moment it's tapped. Even in one pass, Retry All on 100
    // downloads takes WorkManager a moment, and the Queue used to show it as it happened: cards
    // leaving a few at a time, the list shuffling under them, the tab counts ticking over. With
    // these, every card moves at once, in one animation, and the database catching up changes
    // nothing on screen. An entry ends once its row has left [PendingMove.from] (see init) — the
    // database has it now, or something else happened to the download — or when its action didn't
    // apply to it, or after PENDING_MOVE_TIMEOUT_MS whatever happened.
    private val _pendingMoves = MutableStateFlow<Map<String, PendingMove>>(emptyMap())

    // With the running downloads' bytes and speed from memory (LiveProgress), not the database.
    val queueFlow: StateFlow<List<DownloadEntity>> = combine(rawQueueFlow, _deletingIds, _pendingMoves, com.comfort.app.data.LiveProgress.live) { queue, deleting, pending, live ->
        val shown = if (deleting.isEmpty()) queue else queue.filterNot { it.id in deleting }
        val moved = if (pending.isEmpty()) shown
        else shown.map { item -> pending[item.id]?.takeIf { it.from == item.status }?.apply?.invoke(item) ?: item }
            .sortedWith(QUEUE_ORDER)
        com.comfort.app.data.LiveProgress.overlay(moved, live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Ends each pending move once its row has left the status it was made from. Only watches
        // the queue while there are any.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _pendingMoves
                .flatMapLatest { pending -> if (pending.isEmpty()) emptyFlow() else rawQueueFlow }
                .collect { queue ->
                    val status = queue.associate { it.id to it.status }
                    _pendingMoves.update { pending -> pending.filter { (id, move) -> status[id] == move.from } }
                }
        }
    }

    /** Shows [moves] on the Queue from now on, until they end (see [_pendingMoves]). */
    private fun showPendingMoves(moves: Map<String, PendingMove>) {
        if (moves.isEmpty()) return
        _pendingMoves.update { it + moves }
        viewModelScope.launch {
            kotlinx.coroutines.delay(PENDING_MOVE_TIMEOUT_MS)
            endPendingMoves(moves)
        }
    }

    /** Runs the bulk [action] behind [moves]; it returns the ids it changed, and the moves for the
     * rest end there (all of them if it fails). The changed ones end once the Queue shows them. */
    private suspend fun settlePendingMoves(moves: Map<String, PendingMove>, action: suspend () -> Set<String>) {
        val changed = try {
            action()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("DownloadsViewModel", "Bulk action failed", e)
            emptySet()
        }
        endPendingMoves(moves.filterKeys { it !in changed })
    }

    /** Ends these moves, unless a later action has already replaced one. */
    private fun endPendingMoves(moves: Map<String, PendingMove>) {
        if (moves.isEmpty()) return
        _pendingMoves.update { pending -> pending.filterNot { (id, move) -> moves[id] === move } }
    }

    /** The downloads [ids] as the Queue shows them, in its order. */
    private fun queuedRows(ids: Collection<String>): List<DownloadEntity> {
        val wanted = ids.toSet()
        return queueFlow.value.filter { it.id in wanted }
    }

    private val globalPauseMutex = kotlinx.coroutines.sync.Mutex()

    private val _isGloballyPaused = MutableStateFlow(GalleryDlPreferences.isGloballyPaused(application))
    val isGloballyPaused: StateFlow<Boolean> = _isGloballyPaused.asStateFlow()

    // Keeps _isGloballyPaused in step with the stored flag when something other than
    // pauseAll()/resumeAll() changes it — DownloadDispatcher.enqueueWork() clears it when the
    // user starts a single download (a card's Continue, or the paused notification's Resume with
    // no Activity around). Held in a field: SharedPreferences only keeps listeners weakly.
    private val globalPauseListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _isGloballyPaused.value = GalleryDlPreferences.isGloballyPaused(application)
    }
    private val globalPausePrefs = application.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .also { it.registerOnSharedPreferenceChangeListener(globalPauseListener) }

    override fun onCleared() {
        globalPausePrefs.unregisterOnSharedPreferenceChangeListener(globalPauseListener)
        super.onCleared()
    }

    /** Drives the "ongoing download" badge on the Library tab and the Queue icon. */
    val hasActiveDownloads: StateFlow<Boolean> = queueFlow
        .map { list -> list.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val activeDownloadsCount: StateFlow<Int> = queueFlow
        .map { list -> list.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Library's own "Duplicates" filter — see DuplicateAttempt's doc comment for what these
     * actually are (a link that "Prevent duplicate downloads" recognized as already queued/
     * running/finished, so nothing was actually re-downloaded). */
    val duplicateAttemptsFlow: StateFlow<List<com.comfort.app.data.DuplicateAttempt>> = dao.getDuplicateAttemptsFlow()
        .throttleLatest(LIST_UPDATE_MS) // a deleted-files scan or bulk delete is a burst too
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The "Redownload" action on a Duplicates entry — same forceDuplicate escape hatch the share
     * sheet's own Snackbar action uses, then clears this specific log entry since it's been acted
     * on (a fresh, real download now exists for this URL; there's nothing left to "resolve"). */
    fun redownloadDuplicate(attempt: com.comfort.app.data.DuplicateAttempt) {
        viewModelScope.launch {
            DownloadDispatcher.enqueueDownload(getApplication(), attempt.url, attempt.title, forceDuplicate = true)
            dao.deleteDuplicateAttempt(attempt.id)
        }
    }

    /** "Download again" on a download whose file was deleted from the phone: a fresh download of
     * the same link (past the duplicate check, which would otherwise stop it), replacing the old
     * entry. */
    fun redownloadDeleted(id: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val entity = dao.getById(id) ?: return@launch
            DownloadDispatcher.enqueueDownload(context, entity.url, entity.title, forceDuplicate = true)
            DownloadDispatcher.deleteDownload(context, id)
        }
    }

    fun dismissDuplicateAttempt(id: String) {
        viewModelScope.launch { dao.deleteDuplicateAttempt(id) }
    }

    /** Pauses every currently running/queued download and holds any added afterwards until resumed. */
    fun pauseAll() {
        // pauseAllLocked's frozen order too, so nothing moves again once the database has it.
        val active = queueFlow.value.filter { it.status in PAUSABLE }
        val frozen = frozenFront(active)
        val front = frozen.withIndex().associate { (index, entity) -> entity.id to index - frozen.size }
        showPendingMoves(pauseMoves(active).mapValues { (id, move) ->
            val order = front[id] ?: return@mapValues move
            PendingMove(move.from) { move.apply(it).copy(queueOrder = order) }
        })
        viewModelScope.launch {
            // One at a time with resumeAll(): the FAB flips the instant the flag changes, so a quick
            // Pause→Resume used to run both loops interleaved — Resume re-enqueued the rows Pause
            // had already handled while Pause went on to pause the rest, leaving a mix of running
            // and paused rows with the button saying "Pause".
            globalPauseMutex.withLock { pauseAllLocked() }
        }
    }

    /** The block of [active] (in queue order) that Pause All renumbers to the front: what's
     * running, then the "Up next" ones — see pauseAllLocked. */
    private fun frozenFront(active: List<DownloadEntity>): List<DownloadEntity> =
        active.filter { it.status == DownloadStatus.RUNNING } +
            active.filter { it.status != DownloadStatus.RUNNING && it.queueOrder < 0 }

    private suspend fun pauseAllLocked() {
        val context = getApplication<Application>()
        // Set *before* the loop below, not after — pauseDownload() calls repairOrphanedQueue()
        // internally, which only fast-exits once GalleryDlPreferences.isGloballyPaused(context)
        // is already true. Setting the flag after the loop meant every single pauseDownload()
        // call in a large selection ran repairOrphanedQueue()'s full DB+WorkManager inspection
        // instead of skipping it — real thrashing reproduced against a 50-item queue.
        _isGloballyPaused.value = true
        GalleryDlPreferences.setGloballyPaused(context, true)
        val active = dao.getActiveInQueueOrderOnce()
            .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED }
        // Freeze the current order so Resume All brings everything back exactly like this: the
        // download(s) running right now first, then the "Up next" ones, then everything else.
        // Only that front block needs renumbering (into -n..-1, ahead of every plain queued
        // download's 0); the rest is already ordered by dateAdded and is left untouched. This
        // only fixes the *order* — whether a row bypasses the schedule window is its separate
        // forceStart flag, which Pause All leaves alone, so a download that was running before the
        // pause still waits for the window on resume if it's closed by then.
        val front = frozenFront(active)
        front.forEachIndexed { index, entity -> dao.setQueueOrder(entity.id, index - front.size) }
        // notify = false — a per-item "Paused" notification for every download in a large
        // queue would turn one "Pause All" tap into a stack of individual notifications.
        DownloadDispatcher.pauseDownloads(context, active.map { it.id }, notify = false)
    }

    /** Resumes everything held by pauseAll(), including downloads added while paused. */
    fun resumeAll() {
        viewModelScope.launch {
            globalPauseMutex.withLock {
                val context = getApplication<Application>()
                _isGloballyPaused.value = false
                GalleryDlPreferences.setGloballyPaused(context, false)
                val held = dao.getActiveInQueueOrderOnce()
                    .filter { it.status == DownloadStatus.PAUSED || (it.status == DownloadStatus.QUEUED && it.workRequestId == null) }
                // In queue order (see pauseAll's frozen order) — these used to be launched
                // concurrently, which appended them to WorkManager's per-slot chains in whatever
                // order the coroutines happened to finish, so downloads came back in a shuffled
                // order (reported live). enqueueWorkMany() sets the actual QUEUED/SCHEDULED status
                // once it knows the delay. Only an explicit "Up next" (forceStart) skips the schedule
                // window, same as DownloadDispatcher.repairIfJobDead treats it.
                val moves = held.filter { it.status == DownloadStatus.PAUSED }.associate { entity ->
                    val status = DownloadDispatcher.predictedDispatchStatus(context, forceImmediate = entity.forceStart)
                    entity.id to PendingMove(entity.status) { it.copy(status = status) }
                }
                showPendingMoves(moves)
                settlePendingMoves(moves) {
                    DownloadDispatcher.enqueueWorkMany(context, held.map { entity ->
                        DownloadDispatcher.Dispatch(
                            entity.id, entity.url,
                            forceImmediate = entity.forceStart,
                            expectedStatus = setOf(DownloadStatus.PAUSED, DownloadStatus.QUEUED),
                        )
                    })
                }
            }
        }
    }

    fun pauseDownload(id: String) = pauseDownloads(listOf(id))

    /** A card's Pause, or the selection bar's for every selected download. */
    fun pauseDownloads(ids: Collection<String>) {
        val rows = queuedRows(ids)
        val moves = pauseMoves(rows)
        showPendingMoves(moves)
        viewModelScope.launch {
            settlePendingMoves(moves) { DownloadDispatcher.pauseDownloads(getApplication(), rows.map { it.id }) }
        }
    }

    private fun pauseMoves(rows: List<DownloadEntity>): Map<String, PendingMove> =
        rows.filter { it.status in PAUSABLE }
            .associate { entity -> entity.id to PendingMove(entity.status) { it.copy(status = DownloadStatus.PAUSED, speedMbs = 0f) } }

    fun cancelDownload(id: String) = cancelDownloads(listOf(id))

    /** A card's Cancel, or the selection bar's for every selected download. */
    fun cancelDownloads(ids: Collection<String>) {
        val rows = queuedRows(ids)
        val moves = rows.filter { it.status in PAUSABLE || it.status == DownloadStatus.PAUSED }
            .associate { entity -> entity.id to PendingMove(entity.status) { it.copy(status = DownloadStatus.CANCELLED, speedMbs = 0f) } }
        showPendingMoves(moves)
        viewModelScope.launch {
            settlePendingMoves(moves) { DownloadDispatcher.cancelDownloads(getApplication(), rows.map { it.id }) }
        }
    }

    /** A card's Retry/Continue, or "Download the missing items" on a finished one. */
    fun retryDownload(id: String) = retryDownloads(listOf(id))

    /** The selection bar's Retry/Resume: each selected card's own, as one dispatch in the order
     * they're listed. A tap on these cards — starts them even while "Pause All" holds everything
     * else. */
    fun retryDownloads(ids: Collection<String>) {
        val listed = queuedRows(ids)
        val missing = ids.toSet() - listed.map { it.id }.toSet()
        if (missing.isEmpty()) {
            dispatchRetries(listed, userInitiated = true)
            return
        }
        // A finished download ("Download the missing items") isn't on the Queue.
        viewModelScope.launch {
            dispatchRetries(listed + missing.mapNotNull { dao.getById(it) }, userInitiated = true)
        }
    }

    /** Jumps a QUEUED/SCHEDULED download to the front of the line and past any schedule-window
     * wait — the "Start now" button on a waiting download. */
    fun startNow(id: String) {
        viewModelScope.launch {
            DownloadDispatcher.startNow(getApplication(), id)
        }
    }

    /** The Queue's "Start at…": [id] starts at [at] (epoch millis), or with null waits for the
     * schedule window again — see DownloadDispatcher.scheduleAt. */
    fun scheduleAt(id: String, at: Long?) {
        viewModelScope.launch {
            DownloadDispatcher.scheduleAt(getApplication(), id, at)
        }
    }

    /** The "Retry All" FAB on the Queue screen's Errored/Cancelled filter tabs. */
    fun retryAll(status: DownloadStatus) {
        dispatchRetries(queueFlow.value.filter { it.status == status }, userInitiated = false)
    }

    /** Retries/resumes [rows] as one dispatch, in this order. A retried failure goes to the back of
     * the queue — listed last and run last (see DownloadDao.moveToQueueEnd) — keeping the order
     * they're listed in relative to each other (1 ms apart). A paused/cancelled card's resume keeps
     * its place. */
    private fun dispatchRetries(rows: List<DownloadEntity>, userInitiated: Boolean) {
        if (rows.isEmpty()) return
        val context = getApplication<Application>()
        val now = System.currentTimeMillis()
        // A retried failure loses any "Up next" mark (moveToQueueEnd clears it), and so does a
        // pause (DownloadDispatcher.pauseDownloads): resumed, it waits for a closed window again.
        fun forced(entity: DownloadEntity) = entity.forceStart && entity.status != DownloadStatus.ERRORED
        val dispatches = rows.mapIndexed { index, entity ->
            DownloadDispatcher.Dispatch(
                entity.id, entity.url,
                forceImmediate = forced(entity),
                // Each as it was listed: one that has changed since (finished, failed again,
                // deleted) is left as it is.
                expectedStatus = setOf(entity.status),
                moveToQueueEndAt = if (entity.status == DownloadStatus.ERRORED) now + index else null,
            )
        }
        val moves = rows.zip(dispatches).associate { (entity, dispatch) ->
            val queueEnd = dispatch.moveToQueueEndAt
            val status = DownloadDispatcher.predictedDispatchStatus(context, forceImmediate = dispatch.forceImmediate, userInitiated = userInitiated)
            entity.id to PendingMove(entity.status) { row ->
                if (queueEnd == null) row.copy(status = status)
                else row.copy(status = status, queueOrder = 0, forceStart = false, dateAdded = queueEnd)
            }
        }
        showPendingMoves(moves)
        viewModelScope.launch {
            settlePendingMoves(moves) { DownloadDispatcher.enqueueWorkMany(context, dispatches, userInitiated) }
        }
    }

    /** First half of an undoable delete, shared by every screen with a delete action (Queue,
     * Library) (code review: a destructive delete with no confirmation or undo anywhere was a
     * HIGH finding on both). Hides the given ids from [queueFlow]/[historyFlow]/[deletedFlow]
     * immediately via [_deletingIds], but — unlike [confirmDelete]/[deleteDownloads] — touches
     * nothing in the database. The caller (a Snackbar's own "Undo" window) decides afterward
     * whether to actually go through with [confirmDelete] or reverse this with [cancelDeletion];
     * nothing here is irreversible on its own. */
    fun hideForDeletion(ids: Set<String>) {
        if (ids.isEmpty()) return
        _deletingIds.update { it + ids }
    }

    /** Second half of an undoable delete: performs the real, irreversible
     * [DownloadDispatcher.deleteDownload] for every id, concurrently since they're independent
     * per-id work (separate rows, separate files) — also what keeps a bulk selection's real
     * deletes from running one at a time and stuttering/reflowing the list over a second or two,
     * the way a naive sequential loop did before this existed. Call only once the Undo window has
     * genuinely passed; [hideForDeletion] must have already hidden these ids or they never
     * visually leave their list in the first place. */
    fun confirmDelete(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                ids.map { id -> async { DownloadDispatcher.deleteDownload(context, id) } }.awaitAll()
            } finally {
                _deletingIds.update { it - ids }
            }
        }
    }

    /** Reverses [hideForDeletion] — the user tapped Undo before [confirmDelete] ever ran, so
     * nothing was actually deleted and this just un-hides the ids. */
    fun cancelDeletion(ids: Set<String>) {
        if (ids.isEmpty()) return
        _deletingIds.update { it - ids }
    }

    fun setFavorite(id: String, isFavorite: Boolean) {
        viewModelScope.launch {
            dao.setFavorite(id, isFavorite)
        }
    }

    fun renameDownload(id: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            dao.updateTitle(id, trimmed)
        }
    }

    /** Checks every finished download's file against the actual MediaStore and moves any that no
     * longer resolve — i.e. the user deleted it from their gallery outside the app — into the
     * Deleted section, instead of leaving a permanently broken thumbnail in Library. The file is
     * mediaUri when there is one: a song's thumbnailPath is a copy of its cover art, which
     * outlives the song (and can be cleared while the song is still there). A Deleted download
     * whose file is back (restored from the gallery's trash) returns to the Library. */
    fun scanForDeletedMedia() {
        if (!isScanning.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val candidates = dao.getHistoryWithThumbnailOnce()
                val deleted = dao.getDeletedOnce()
                withContext(Dispatchers.IO) {
                    candidates.forEach { entity ->
                        val uri = entity.mediaUri ?: entity.thumbnailPath ?: return@forEach
                        if (!MediaStoreHelper.exists(context, uri)) {
                            dao.updateStatus(entity.id, DownloadStatus.DELETED)
                        }
                    }
                    deleted.forEach { entity ->
                        val uri = entity.mediaUri ?: entity.thumbnailPath ?: return@forEach
                        if (uri.startsWith("content://media/") && MediaStoreHelper.exists(context, uri, failOpen = false)) {
                            dao.updateStatus(entity.id, DownloadStatus.FINISHED)
                        }
                    }
                }
            } finally {
                isScanning.set(false)
            }
        }
    }
}

/** How often (at most) the Queue's and Library's lists update while their downloads change fast. */
private const val LIST_UPDATE_MS = 150L

private const val PENDING_MOVE_TIMEOUT_MS = 15_000L

private val PAUSABLE = setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.SCHEDULED)

/** DownloadDao.getQueueFlow's ORDER BY, for the Queue while pending moves change rows the database
 * hasn't yet: failed downloads first, newest failure first; then queue order, then oldest added. */
private val QUEUE_ORDER = compareBy<DownloadEntity>(
    { if (it.status == DownloadStatus.ERRORED) 0 else 1 },
    { if (it.status == DownloadStatus.ERRORED) -(it.erroredAt ?: it.dateAdded) else it.queueOrder.toLong() },
    { it.dateAdded },
)

/** Each value at once if nothing came for [periodMs], else the latest one at the end of the period:
 * a burst of N values becomes about one per period, never delaying the first or dropping the last. */
private fun <T> kotlinx.coroutines.flow.Flow<T>.throttleLatest(periodMs: Long): kotlinx.coroutines.flow.Flow<T> =
    conflate().transform {
        emit(it)
        kotlinx.coroutines.delay(periodMs)
    }
