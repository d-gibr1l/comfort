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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /** How the engine updates that just ran went, once the last of them has ended (several at
     * once, as auto-update does, give one summary, not a message each). MainScreen shows it as a
     * snackbar on whichever tab is open, replacing its "Updating …" one. */
    data class EngineUpdateSummary(val updated: List<Pair<String, String>>, val failed: List<String>)
    private val _engineUpdated = kotlinx.coroutines.flow.MutableSharedFlow<EngineUpdateSummary>(extraBufferCapacity = 4)
    val engineUpdated: kotlinx.coroutines.flow.SharedFlow<EngineUpdateSummary> = _engineUpdated
    // The updates of the run still going: name and new version, or the names that failed.
    private val runUpdated = mutableListOf<Pair<String, String>>()
    private val runFailed = mutableListOf<String>()

    private fun announceUpdate(status: EngineUpdater.VersionStatus, newVersion: String) {
        synchronized(runUpdated) { if (newVersion.isNotEmpty()) runUpdated += status.engine.displayName to newVersion }
    }

    private fun announceFailure(status: EngineUpdater.VersionStatus) {
        synchronized(runUpdated) { runFailed += status.engine.displayName }
    }

    /** The summary of the run, once nothing is updating any more. */
    private fun summarizeIfDone() {
        if (_updatingEngines.value.isNotEmpty() || _updatingEngineNames.value.isNotEmpty()) return
        val summary = synchronized(runUpdated) {
            EngineUpdateSummary(runUpdated.toList(), runFailed.toList()).also { runUpdated.clear(); runFailed.clear() }
        }
        if (summary.updated.isNotEmpty() || summary.failed.isNotEmpty()) _engineUpdated.tryEmit(summary)
    }

    /** An automatic update (EngineUpdater.checkAndAutoUpdate), reported as a tapped one is. */
    private suspend fun checkAndAutoUpdate(): List<EngineUpdater.VersionStatus> {
        val statuses = EngineUpdater.checkAndAutoUpdate(
            context,
            onUpdated = { status, version ->
                announceUpdate(status, version)
                _updatingEngineNames.update { it - status.engine.displayName }
            },
            onStarting = { status -> _updatingEngineNames.update { it + status.engine.displayName } },
            onFailed = { status ->
                announceFailure(status)
                _updatingEngineNames.update { it - status.engine.displayName }
            },
        )
        summarizeIfDone()
        return statuses
    }

    /** The engines updating now, by name (for "Updating yt-dlp and gallery-dl…"). */
    val updatingEngineNames: StateFlow<List<String>> get() = _updatingEngineNames
    private val _updatingEngineNames = MutableStateFlow<List<String>>(emptyList())

    /** What the launch check found, to show as a popup (MainScreen): a new Comfort, or engine
     * updates waiting because auto-update is off. Each shown once per version, so "Later" isn't
     * asked again until a newer one comes out; Settings > Updates keeps offering it. */
    sealed interface UpdatePrompt {
        data class App(val status: AppUpdater.UpdateStatus) : UpdatePrompt
        data class Engines(val statuses: List<EngineUpdater.VersionStatus>) : UpdatePrompt
    }

    private val _prompts = MutableStateFlow<List<UpdatePrompt>>(emptyList())
    /** The popup to show now (the first; the app's before the engines'). */
    val prompt: StateFlow<UpdatePrompt?> = _prompts
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissPrompt() {
        _prompts.update { it.drop(1) }
    }

    private fun offerApp(status: AppUpdater.UpdateStatus) {
        val version = status.latestVersion ?: return
        if (!status.updateAvailable || status.downloadUrl == null) return
        if (GalleryDlPreferences.getPromptedAppVersion(context) == version) return
        GalleryDlPreferences.setPromptedAppVersion(context, version)
        // Ahead of an engines popup: the app's update may bring newer engines itself.
        _prompts.update { listOf(UpdatePrompt.App(status)) + it.filter { p -> p !is UpdatePrompt.App } }
    }

    private fun offerEngines(statuses: List<EngineUpdater.VersionStatus>) {
        // With auto-update on, the check installed them (or will, after a running download).
        if (GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) return
        val waiting = statuses.filter { it.updateAvailable && it.artifactUrl != null && EngineUpdater.isOn(context, it.engine) }
        if (waiting.isEmpty()) return
        val key = waiting.joinToString(",") { "${it.engine.packageDirName}=${it.latestVersion}" }
        if (GalleryDlPreferences.getPromptedEngineVersions(context) == key) return
        GalleryDlPreferences.setPromptedEngineVersions(context, key)
        _prompts.update { it.filter { p -> p !is UpdatePrompt.Engines } + UpdatePrompt.Engines(waiting) }
    }

    /** The app update's sheet on request (Settings' "App update available" card), shown again
     * whether or not it was offered before. */
    fun showAppPrompt(status: AppUpdater.UpdateStatus) {
        if (!status.updateAvailable) return
        _prompts.update { listOf(UpdatePrompt.App(status)) + it.filter { p -> p !is UpdatePrompt.App } }
    }

    /** The popup's Update for engines: each one in turn, as a tapped Update on the Updates page. */
    fun updateEngines(statuses: List<EngineUpdater.VersionStatus>) = statuses.forEach(::updateEngine)

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
                val statuses = checkAndAutoUpdate()
                publishEngines(statuses)
                offerEngines(statuses)
            }
        }
        viewModelScope.launch {
            val lastAppCheck = GalleryDlPreferences.getAppUpdateLastCheckMs(context)
            if (System.currentTimeMillis() - lastAppCheck >= GalleryDlPreferences.APP_UPDATE_CHECK_INTERVAL_MS) {
                runAppCheck()?.let(::offerApp)
            }
        }
    }

    /** A fresh engine check — installs what it finds when auto-update is on (EngineUpdater
     * serializes overlapping checks, so a second caller just finds everything current). [offer]:
     * a check nobody asked for (not the Updates page's) offers what it finds as a popup. */
    fun checkEngines(offer: Boolean = false) {
        // Asked again while one runs (a channel switched right after the Updates page opened, which
        // starts a check): one more check once it's done, or the page kept the old channel's result.
        if (_checkingEngines.value) { recheckEngines = true; return }
        _checkingEngines.value = true
        viewModelScope.launch {
            try {
                do {
                    recheckEngines = false
                    val statuses = checkAndAutoUpdate()
                    publishEngines(statuses)
                    if (offer) offerEngines(statuses)
                } while (recheckEngines)
            } finally {
                _checkingEngines.value = false
            }
        }
    }

    @Volatile private var recheckEngines = false

    /** Checks once per ViewModel unless [checkEngines] is called again (the Updates page's button). */
    // The Settings page's check, at every launch (it's built in the background a second in): it
    // offers the popup too, or it took the launch check's place without one (UpdatePrompt).
    fun ensureEnginesChecked() {
        if (_engineStatuses.value == null) checkEngines(offer = true)
    }

    /** A tapped Update for one engine. */
    fun updateEngine(status: EngineUpdater.VersionStatus) {
        if (status.artifactUrl == null) return
        val key = status.engine.packageDirName
        if (key in _updatingEngines.value) return
        _updatingEngines.update { it + key }
        _updatingEngineNames.update { it + status.engine.displayName }
        _engineError.value = null
        viewModelScope.launch {
            try {
                val result = EngineUpdater.update(context, status)
                result.onSuccess { newVersion ->
                    announceUpdate(status, newVersion)
                    val updated = _engineStatuses.value.orEmpty().map {
                        if (it.engine == status.engine) it.copy(installedVersion = newVersion) else it
                    }
                    GalleryDlPreferences.setEngineUpdateAvailable(context, updated.any { it.updateAvailable && EngineUpdater.isOn(context, it.engine) })
                    publishEngines(updated)
                }
                result.onFailure { e ->
                    announceFailure(status)
                    _engineError.value = getApplication<Application>().getString(com.comfort.app.R.string.couldn_t_update_engine, status.engine.displayName, e.message ?: getApplication<Application>().getString(com.comfort.app.R.string.unknown_error))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                announceFailure(status)
                _engineError.value = getApplication<Application>().getString(com.comfort.app.R.string.couldn_t_update_engine, status.engine.displayName, e.message ?: getApplication<Application>().getString(com.comfort.app.R.string.unknown_error))
            } finally {
                _updatingEngines.update { it - key }
                _updatingEngineNames.update { it - status.engine.displayName }
                // The last one of the run: one summary for all of them.
                summarizeIfDone()
            }
        }
    }

    fun checkApp() {
        viewModelScope.launch { runAppCheck() }
    }

    /** The app check itself; null if one was already running. */
    private suspend fun runAppCheck(): AppUpdater.UpdateStatus? {
        if (_checkingApp.value) return null
        _checkingApp.value = true
        try {
            val result = AppUpdater.check(context)
            _appStatus.value = result
            appUpdateAvailable.value = result.updateAvailable
            GalleryDlPreferences.setAppUpdateAvailable(context, result.updateAvailable)
            GalleryDlPreferences.setAppUpdateLastCheckMs(context, System.currentTimeMillis())
            return result
        } finally {
            _checkingApp.value = false
        }
    }

    /** The Settings page's check, at every launch: offers the popup too (see ensureEnginesChecked). */
    fun ensureAppChecked() {
        if (_appStatus.value == null) viewModelScope.launch { runAppCheck()?.let(::offerApp) }
    }

    fun startAppDownload(status: AppUpdater.UpdateStatus) = AppUpdater.startDownload(context, status)

    fun cancelAppDownload() = AppUpdater.cancelDownload()

    private fun publishEngines(statuses: List<EngineUpdater.VersionStatus>) {
        _engineStatuses.value = statuses
        engineUpdateAvailable.value = statuses.any { it.updateAvailable && EngineUpdater.isOn(context, it.engine) }
    }
}
