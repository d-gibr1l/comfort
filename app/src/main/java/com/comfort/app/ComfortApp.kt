package com.comfort.app

import android.app.Application
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.MediaStoreHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process start-up: loads the user's Sites lists into VideoSiteRouter before anything routes a
 * link — the worker can start the process on its own, without any screen — and, once, sorts
 * downloads saved under the old folders into Download/Comfort's. */
class ComfortApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VideoSiteRouter.loadSiteRules(this)
        if (!GalleryDlPreferences.isSavedFilesOrganized(this)) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                MediaStoreHelper.organizeSavedFiles(this@ComfortApp)
                GalleryDlPreferences.setSavedFilesOrganized(this@ComfortApp)
            }
        }
    }
}
