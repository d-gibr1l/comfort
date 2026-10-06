package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.util.AppUpdater
import com.comfort.app.util.EngineUpdater
import com.comfort.app.util.PythonRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Engine and app update state for the whole app — one instance per activity, shared by Home's
 * launch check, the Settings root's update teasers, the Updates page and the nav-bar dot.
 *
 * This used to live in four composables, each with its own copy of the statuses and its own check,
 * kept loosely in sync through two global flags (EngineUpdateSignal / AppUpdateSignal). Now each of
 * those screens just reads [engineStatuses] / [appStatus] and calls the actions below. */
class UpdatesViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    private val _engineStatuses = MutableStateFlow<List<EngineUpdater.VersionStatus>?>(null)
    /** Null until the first check finishes. */
    val engineStatuses: StateFlow<List<EngineUpdater.VersionStatus>?> = _engineStatuses.asStateFlow()

    private val _checkingEngines = MutableStateFlow(false)
    val checkingEngines: StateFlow<Boolean> = _checkingEngines.asStateFlow()

    /** Engines (packageDirName) a tapped Update is installing — a set, since several can run. */
    private val _updatingEngines = MutableStateFlow(emptySet<String>())
    val updatingEngines: StateFlow<Set<String>> = _updatingEngines.asStateFlow()

    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

    private val _appStatus = MutableStateFlow<AppUpdater.UpdateStatus?>(null)
    val appStatus: StateFlow<AppUpdater.UpdateStatus?> = _appStatus.asStateFlow()

    private val _checkingApp = MutableStateFlow(false)
    val checkingApp: StateFlow<Boolean> = _checkingApp.asStateFlow()

    /** The app APK download's progress (0..1) while one runs, and its error — owned by AppUpdater. */
    val appDownloadProgress: StateFlow<Float?> = AppUpdater.downloadProgress
    val appDownloadError: StateFlow<String?> = AppUpdater.downloadError

    // Seeded from the persisted flags, so the nav-bar dot shows right away after a restart.
    private val engineUpdateAvailable = MutableStateFlow(GalleryDlPreferences.isEngineUpdateAvailable(application))
    private val appUpdateAvailable = MutableStateFlow(GalleryDlPreferences.isAppUpdateAvailable(application))

    /** Something in Settings > Updates needs attention — the nav-bar dot. */
    val anyUpdateAvailable: StateFlow<Boolean> = combine(engineUpdateAvailable, appUpdateAvailable) { e, a -> e || a }
        .stateIn(viewModelScope, SharingStarted.Eagerly, engineUpdateAvailable.value || appUpdateAvailable.value)

    private var launchChecked = false

    /** Home's once-per-launch checks, each rate-limited by its own interval so relaunching doesn't
     * spam PyPI/GitHub. Provisioning first: right after an app update it re-unpacks the runtime,
     * which puts the bundled engines back and resets the engine last-check time — so this launch
     * checks (and auto-updates) them now instead of trusting a check made against the old ones. */
    fun runLaunchChecks() {
        if (launchChecked) return
        launchChecked = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { PythonRuntime.ensureProvisioned(context) }
            val lastEngineCheck = GalleryDlPreferences.getEngineUpdateLastCheckMs(context)
            if (System.currentTimeMillis() - lastEngineCheck >= GalleryDlPreferences.ENGINE_UPDATE_CHECK_INTERVAL_MS) {
                publishEngines(EngineUpdater.checkAndAutoUpdate(context))
            }
        }
        viewModelScope.launch {
            val lastAppCheck = GalleryDlPreferences.getAppUpdateLastCheckMs(context)
            if (System.currentTimeMillis() - lastAppCheck >= GalleryDlPreferences.APP_UPDATE_CHECK_INTERVAL_MS) checkApp()
        }
    }

    /** A fresh engine check — installs what it finds when auto-update is on (EngineUpdater
     * serializes overlapping checks, so a second caller just finds everything current). */
    fun checkEngines() {
        if (_checkingEngines.value) return
        _checkingEngines.value = true
        viewModelScope.launch {
            try {
                publishEngines(EngineUpdater.checkAndAutoUpdate(context))
            } finally {
                _checkingEngines.value = false
            }
        }
    }

    /** Checks once per ViewModel unless [checkEngines] is called again (the Updates page's button). */
    fun ensureEnginesChecked() {
        if (_engineStatuses.value == null) checkEngines()
    }

    /** A tapped Update for one engine. */
    fun updateEngine(status: EngineUpdater.VersionStatus) {
        if (status.artifactUrl == null) return
        val key = status.engine.packageDirName
        if (key in _updatingEngines.value) return
        _updatingEngines.value = _updatingEngines.value + key
        _engineError.value = null
        viewModelScope.launch {
            val result = EngineUpdater.update(context, status)
            _updatingEngines.value = _updatingEngines.value - key
            result.onSuccess { newVersion ->
                val updated = _engineStatuses.value.orEmpty().map {
                    if (it.engine == status.engine) it.copy(installedVersion = newVersion) else it
                }
                GalleryDlPreferences.setEngineUpdateAvailable(context, updated.any { it.updateAvailable })
                publishEngines(updated)
            }
            result.onFailure { e ->
                _engineError.value = "Couldn't update ${status.engine.displayName}: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun checkApp() {
        if (_checkingApp.value) return
        _checkingApp.value = true
        viewModelScope.launch {
            try {
                val result = AppUpdater.check(context)
                _appStatus.value = result
                appUpdateAvailable.value = result.updateAvailable
                GalleryDlPreferences.setAppUpdateAvailable(context, result.updateAvailable)
                GalleryDlPreferences.setAppUpdateLastCheckMs(context, System.currentTimeMillis())
            } finally {
                _checkingApp.value = false
            }
        }
    }

    fun ensureAppChecked() {
        if (_appStatus.value == null) checkApp()
    }

    fun startAppDownload(status: AppUpdater.UpdateStatus) = AppUpdater.startDownload(context, status)

    private fun publishEngines(statuses: List<EngineUpdater.VersionStatus>) {
        _engineStatuses.value = statuses
        engineUpdateAvailable.value = statuses.any { it.updateAvailable }
    }
}
