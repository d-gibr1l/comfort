package com.example.gallerydl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gallerydl.data.AppDatabase
import com.example.gallerydl.data.DownloadDispatcher
import com.example.gallerydl.data.DownloadEntity
import com.example.gallerydl.data.DownloadStatus
import com.example.gallerydl.data.GalleryDlPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).downloadDao()

    val historyFlow: StateFlow<List<DownloadEntity>> = dao.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queueFlow: StateFlow<List<DownloadEntity>> = dao.getQueueFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGloballyPaused = MutableStateFlow(GalleryDlPreferences.isGloballyPaused(application))
    val isGloballyPaused: StateFlow<Boolean> = _isGloballyPaused.asStateFlow()

    /** Drives the "ongoing download" badge on the Library tab and the Queue icon. */
    val hasActiveDownloads: StateFlow<Boolean> = queueFlow
        .map { list -> list.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun enqueueDownload(url: String, title: String, itemFilter: String? = null) {
        viewModelScope.launch {
            DownloadDispatcher.enqueueDownload(getApplication(), url, title, itemFilter)
        }
    }

    /** Pauses every currently running/queued download and holds any added afterwards until resumed. */
    fun pauseAll() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            queueFlow.value
                .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
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
                .filter { it.status == DownloadStatus.CANCELLED || (it.status == DownloadStatus.QUEUED && it.workRequestId == null) }
                .forEach { entity ->
                    dao.updateStatus(entity.id, DownloadStatus.QUEUED)
                    DownloadDispatcher.enqueueWork(context, entity.id, entity.url)
                }
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch {
            DownloadDispatcher.pauseDownload(getApplication(), id)
        }
    }

    fun retryDownload(id: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val entity = dao.getById(id) ?: return@launch
            dao.updateStatus(id, DownloadStatus.QUEUED)
            DownloadDispatcher.enqueueWork(context, entity.id, entity.url)
        }
    }

    fun deleteDownload(id: String) {
        viewModelScope.launch {
            DownloadDispatcher.deleteDownload(getApplication(), id)
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
}
