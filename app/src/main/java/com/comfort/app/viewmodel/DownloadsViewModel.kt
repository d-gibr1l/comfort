package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.util.MediaStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).downloadDao()

    private val rawHistoryFlow: StateFlow<List<DownloadEntity>> = dao.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawDeletedFlow: StateFlow<List<DownloadEntity>> = dao.getDeletedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawQueueFlow: StateFlow<List<DownloadEntity>> = dao.getQueueFlow()
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

    val deletedFlow: StateFlow<List<DownloadEntity>> = combine(rawDeletedFlow, _deletingIds) { deleted, deleting ->
        if (deleting.isEmpty()) deleted else deleted.filterNot { it.id in deleting }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queueFlow: StateFlow<List<DownloadEntity>> = combine(rawQueueFlow, _deletingIds) { queue, deleting ->
        if (deleting.isEmpty()) queue else queue.filterNot { it.id in deleting }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGloballyPaused = MutableStateFlow(GalleryDlPreferences.isGloballyPaused(application))
    val isGloballyPaused: StateFlow<Boolean> = _isGloballyPaused.asStateFlow()

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

    fun dismissDuplicateAttempt(id: String) {
        viewModelScope.launch { dao.deleteDuplicateAttempt(id) }
    }

    fun enqueueDownload(
        url: String,
        title: String,
        itemFilter: String? = null,
        totalItems: Int = 0,
        videoQuality: VideoQuality? = null,
        clipRange: String? = null,
        extraCommands: String? = null,
        outputFormat: OutputFormat? = null,
        filenameTemplate: String? = null,
        saveThumbnail: Boolean? = null,
        // Pass true from a caller whose own "Download" button already checked
        // DownloadDispatcher.isDuplicate and relabeled itself "Redownload" (DownloadPreviewSheet
        // does this internally) — the button already told the user, so tapping it is the
        // confirmation; nothing here should silently no-op a tap that says "Redownload" on its face.
        forceDuplicate: Boolean = false,
    ) {
        viewModelScope.launch {
            DownloadDispatcher.enqueueDownload(
                getApplication(), url, title, itemFilter, totalItems,
                videoQuality = videoQuality,
                clipRange = clipRange,
                extraCommands = extraCommands,
                outputFormat = outputFormat,
                filenameTemplate = filenameTemplate,
                saveThumbnail = saveThumbnail,
                forceDuplicate = forceDuplicate,
            )
        }
    }

    /** Pauses every currently running/queued download and holds any added afterwards until resumed. */
    fun pauseAll() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            // Set *before* the loop below, not after — pauseDownload() calls repairOrphanedQueue()
            // internally, which only fast-exits once GalleryDlPreferences.isGloballyPaused(context)
            // is already true. Setting the flag after the loop meant every single pauseDownload()
            // call in a large selection ran repairOrphanedQueue()'s full DB+WorkManager inspection
            // instead of skipping it — real thrashing reproduced against a 50-item queue.
            _isGloballyPaused.value = true
            GalleryDlPreferences.setGloballyPaused(context, true)
            queueFlow.value
                .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED }
                // notify = false — a per-item "Paused" notification for every download in a large
                // queue would turn one "Pause All" tap into a stack of individual notifications.
                .forEach { entity -> DownloadDispatcher.pauseDownload(context, entity.id, notify = false) }
        }
    }

    /** Resumes everything held by pauseAll(), including downloads added while paused. */
    fun resumeAll() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _isGloballyPaused.value = false
            GalleryDlPreferences.setGloballyPaused(context, false)
            queueFlow.value
                .filter { it.status == DownloadStatus.PAUSED || (it.status == DownloadStatus.QUEUED && it.workRequestId == null) }
                .forEach { entity ->
                    // launch, not a plain suspend call — enqueueWork() is a suspend function that
                    // hits the database and acquires a per-id Mutex (see DownloadDispatcher.
                    // withDownloadLock), so awaiting each one in turn here forced a large "Resume
                    // All" to re-enqueue its downloads one at a time for no reason: different ids
                    // never contend on the same lock, so there's nothing to serialize between them.
                    launch {
                        // enqueueWork() itself sets the correct QUEUED/SCHEDULED status once it
                        // knows the actual delay — no need to guess QUEUED here first.
                        DownloadDispatcher.enqueueWork(context, entity.id, entity.url)
                    }
                }
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch {
            DownloadDispatcher.pauseDownload(getApplication(), id)
        }
    }

    fun cancelDownload(id: String) {
        viewModelScope.launch {
            DownloadDispatcher.cancelDownload(getApplication(), id)
        }
    }

    fun retryDownload(id: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val entity = dao.getById(id) ?: return@launch
            DownloadDispatcher.enqueueWork(context, entity.id, entity.url)
        }
    }

    /** Jumps a QUEUED/SCHEDULED download to the front of the line and past any schedule-window
     * wait — the "Start now" button on a waiting download. */
    fun startNow(id: String) {
        viewModelScope.launch {
            DownloadDispatcher.startNow(getApplication(), id)
        }
    }

    /** The "Retry All" FAB on the Queue screen's Errored/Cancelled filter tabs. */
    fun retryAll(status: DownloadStatus) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            queueFlow.value
                .filter { it.status == status }
                .forEach { entity -> DownloadDispatcher.enqueueWork(context, entity.id, entity.url) }
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
        viewModelScope.launch {
            val context = getApplication<Application>()
            ids.map { id -> async { DownloadDispatcher.deleteDownload(context, id) } }.awaitAll()
            _deletingIds.update { it - ids }
        }
    }

    /** Reverses [hideForDeletion] — the user tapped Undo before [confirmDelete] ever ran, so
     * nothing was actually deleted and this just un-hides the ids. */
    fun cancelDeletion(ids: Set<String>) {
        _deletingIds.update { it - ids }
    }

    fun setFavorite(id: String, isFavorite: Boolean) {
        viewModelScope.launch {
            dao.setFavorite(id, isFavorite)
        }
    }

    fun renameDownload(id: String, newTitle: String) {
        viewModelScope.launch {
            dao.updateTitle(id, newTitle)
        }
    }

    /** Checks every finished download's thumbnail Uri against the actual MediaStore and moves any
     * that no longer resolve — i.e. the user deleted the image from their gallery outside the app
     * — into the Deleted section, instead of leaving a permanently broken thumbnail in Library. */
    fun scanForDeletedMedia() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val candidates = dao.getHistoryWithThumbnailOnce()
            withContext(Dispatchers.IO) {
                candidates.forEach { entity ->
                    val uri = entity.thumbnailPath ?: return@forEach
                    if (!MediaStoreHelper.exists(context, uri)) {
                        dao.updateStatus(entity.id, DownloadStatus.DELETED)
                    }
                }
            }
        }
    }
}
