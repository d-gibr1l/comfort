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
    override fun onCreate() {
        super.onCreate()
        VideoSiteRouter.loadSiteRules(this)
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
