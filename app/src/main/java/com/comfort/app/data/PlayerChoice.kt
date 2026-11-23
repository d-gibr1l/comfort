package com.comfort.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which player opens a download (Settings › Look & feel › Players): Comfort's own (the media viewer,
 * the music player) or the phone's, the app Android picks for the file (or asks which, the first
 * time). Pictures always open in Comfort's viewer.
 */
object PlayerChoice {
    private const val PREFS = "player_choice"
    private const val KEY_EXTERNAL_VIDEO = "external_video"
    private const val KEY_EXTERNAL_MUSIC = "external_music"

    private val _externalVideo = MutableStateFlow(false)
    val externalVideo: StateFlow<Boolean> = _externalVideo.asStateFlow()
    private val _externalMusic = MutableStateFlow(false)
    val externalMusic: StateFlow<Boolean> = _externalMusic.asStateFlow()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _externalVideo.value = p.getBoolean(KEY_EXTERNAL_VIDEO, false)
        _externalMusic.value = p.getBoolean(KEY_EXTERNAL_MUSIC, false)
        loaded = true
    }

    fun setExternalVideo(context: Context, on: Boolean) {
        _externalVideo.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EXTERNAL_VIDEO, on).apply()
    }

    fun setExternalMusic(context: Context, on: Boolean) {
        _externalMusic.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EXTERNAL_MUSIC, on).apply()
    }

    /** Opens [download] in the phone's player when the setting for its kind says so. True if it
     * did (or tried: no app for it gives a toast and true, so Comfort's player doesn't open
     * instead of what was asked for); false to open it in Comfort's own. */
    fun openExternallyIfChosen(context: Context, download: DownloadEntity): Boolean {
        val file = (download.mediaUri ?: download.thumbnailPath)?.takeIf { it.isNotBlank() } ?: return false
        val uri = Uri.parse(file)
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val external = when {
            download.isAudio -> _externalMusic.value
            type?.startsWith("video/") == true -> _externalVideo.value
            else -> false
        }
        if (!external) return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type ?: if (download.isAudio) "audio/*" else "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, com.comfort.app.R.string.no_app_to_open_this, android.widget.Toast.LENGTH_SHORT).show()
        }
        return true
    }
}
