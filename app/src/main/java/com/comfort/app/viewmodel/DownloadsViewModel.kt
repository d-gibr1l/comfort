package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.GalleryDlPreferences
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

    val historyFlow: StateFlow<List<DownloadEntity>> = dao.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deletedFlow: StateFlow<List<DownloadEntity>> = dao.getDeletedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawQueueFlow: StateFlow<List<DownloadEntity>> = dao.getQueueFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Bulk-delete's own optimistic-hide set (see deleteDownloads()) — an id sits here for the
    // brief window between the user tapping delete and its real DB row actually being gone, so
    // queueFlow below can hide it immediately instead of waiting for each sequential disk I/O to
    // finish and Room to re-emit one row at a time.
    private val _deletingIds = MutableStateFlow<Set<String>>(emptySet())

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

    fun enqueueDownload(url: String, title: String, itemFilter: String? = null, totalItems: Int = 0) {
        viewModelScope.launch {
            DownloadDispatcher.enqueueDownload(getApplication(), url, title, itemFilter, totalItems)
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
                .forEach { entity -> DownloadDispatcher.pauseDownload(context, entity.id) }
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
                    // enqueueWork() itself sets the correct QUEUED/SCHEDULED status once it knows
                    // the actual delay — no need to guess QUEUED here first.
                    DownloadDispatcher.enqueueWork(context, entity.id, entity.url)
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

    fun deleteDownload(id: String) {
        viewModelScope.launch {
            DownloadDispatcher.deleteDownload(getApplication(), id)
        }
    }

    /** Bulk delete for the Queue screen's multi-select mode. Every selected id is hidden from
     * [queueFlow] immediately (see [_deletingIds]) so Compose animates the whole selection
     * disappearing as one batch, instead of a slow "waterfall" — each real DB delete is disk I/O,
     * so without this the list only lost one row at a time as Room re-emitted between each
     * sequential suspend, visibly stuttering/reflowing over a second or two for a large selection.
     * The real deletes themselves now run concurrently (they're independent per-id work — separate
     * rows, separate files) rather than one-at-a-time, so the optimistic hide above doesn't sit
     * ahead of reality for any longer than it has to. */
    fun deleteDownloads(ids: Set<String>) {
        _deletingIds.update { it + ids }
        viewModelScope.launch {
            val context = getApplication<Application>()
            ids.map { id -> async { DownloadDispatcher.deleteDownload(context, id) } }.awaitAll()
            _deletingIds.update { it - ids }
        }
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
