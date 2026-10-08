package com.comfort.app

import android.app.Application
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.MediaStoreHelper
import com.comfort.app.util.OldNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process start-up: loads the user's Sites lists into VideoSiteRouter before anything routes a
 * link — the worker can start the process on its own, without any screen — and, once, sorts
 * downloads saved under the old folders into Download/Comfort's and renames old-style names. */
class ComfortApp : Application() {
    /** The launcher shortcuts are all in res/xml/shortcuts.xml. An older build registered a
     * long-lived "Quick download" one at run time, which Android kept across updates — blank icon,
     * and it pushed one of the real four out of the launcher's four slots. Clears any such leftover. */
    private fun removeStaleShortcuts() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N_MR1) return
        runCatching {
            val manager = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val ids = manager.getShortcuts(android.content.pm.ShortcutManager.FLAG_MATCH_DYNAMIC).map { it.id }
                if (ids.isNotEmpty()) manager.removeLongLivedShortcuts(ids)
            }
            manager.removeAllDynamicShortcuts()
        }
    }

    override fun onCreate() {
        super.onCreate()
        VideoSiteRouter.loadSiteRules(this)
        com.comfort.app.theme.ThemePreferences.applyNightMode(this)
        removeStaleShortcuts()
        // Every process start, not just the main screen's: the app can run for days on
        // downloads and shares alone.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { com.comfort.app.util.GalleryDlListing.sweepStaleCache(this@ComfortApp) }
        }
        val organize = !GalleryDlPreferences.isSavedFilesOrganized(this)
        val rename = !GalleryDlPreferences.isOldNamesTidied(this)
        if (organize || rename) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                if (organize) {
                    MediaStoreHelper.organizeSavedFiles(this@ComfortApp)
                    GalleryDlPreferences.setSavedFilesOrganized(this@ComfortApp)
                }
                if (rename) {
                    val dao = AppDatabase.getDatabase(this@ComfortApp).downloadDao()
                    MediaStoreHelper.tidyOldNames(this@ComfortApp) { dao.urlForSavedFile(it) }
                    // The Library's titles came from the same old naming.
                    runCatching {
                        for (download in dao.getAllOnce()) {
                            OldNames.tidyTitle(download.title, OldNames.xPoster(download.url))
                                ?.let { dao.updateTitle(download.id, it) }
                        }
                    }
                    GalleryDlPreferences.setOldNamesTidied(this@ComfortApp)
                }
            }
        }
    }
}
