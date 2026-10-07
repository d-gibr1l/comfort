package com.comfort.app

import android.app.Application
import com.comfort.app.data.VideoSiteRouter

/** Process start-up: loads the user's Sites lists into VideoSiteRouter before anything routes a
 * link — the worker can start the process on its own, without any screen. */
class ComfortApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VideoSiteRouter.loadSiteRules(this)
    }
}
