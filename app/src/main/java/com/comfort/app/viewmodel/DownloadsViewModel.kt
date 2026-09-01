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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).downloadDao()

    val historyFlow: StateFlow<List<DownloadEntity>> = dao.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deletedFlow: StateFlow<List<DownloadEntity>> = dao.getDeletedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queueFlow: StateFlow<List<DownloadEntity>> = dao.getQueueFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            queueFlow.value
                .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED }
                .forEach { entity -> DownloadDispatcher.pauseDownload(context, entity.id) }
            _isGloballyPaused.value = true
            GalleryDlPreferences.setGloballyPaused(context, true)
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

    /** Bulk delete for the Queue screen's multi-select mode — one coroutine handling every id
     * sequentially rather than a separate launch per item, so a large selection doesn't fire a
     * burst of concurrent deletes racing each other. */
    fun deleteDownloads(ids: Set<String>) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            ids.forEach { id -> DownloadDispatcher.deleteDownload(context, id) }
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
