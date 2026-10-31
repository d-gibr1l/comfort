package com.comfort.app.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.comfort.app.MainActivity
import com.comfort.app.R

/**
 * The home-screen music widget (Auxio has one too): the song on, its cover, and previous /
 * play-pause / next. [MusicService] redraws it as the music changes. Its buttons come back here
 * and are carried out through a MediaController on [MusicService], so they work with the app
 * closed: binding starts the service, which restores the saved queue, and play plays it.
 *
 * They used to be media keys sent to Media3's MediaButtonReceiver, which drops everything but
 * play on Android 8+ (it can only start the service for a play), so previous/next did nothing;
 * and play with the app closed didn't start the saved queue either (both seen on the phone).
 */
class MusicWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_PREVIOUS && action != ACTION_PLAY_PAUSE && action != ACTION_NEXT) {
            super.onReceive(context, intent)
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        val token = androidx.media3.session.SessionToken(app, ComponentName(app, MusicService::class.java))
        val future = androidx.media3.session.MediaController.Builder(app, token).buildAsync()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var done = false
        fun finish(controller: androidx.media3.session.MediaController?) {
            if (done) return
            done = true
            // Kept a moment, so a play has started (and put the service in the foreground)
            // before the controller lets go of it.
            main.postDelayed({ controller?.release(); pending.finish() }, 1_500)
        }
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: run { finish(null); return@addListener }
            fun act() {
                when (action) {
                    ACTION_PREVIOUS -> controller.seekToPrevious()
                    ACTION_NEXT -> controller.seekToNextMediaItem()
                    else -> if (controller.isPlaying) controller.pause() else {
                        if (controller.playbackState == androidx.media3.common.Player.STATE_IDLE) controller.prepare()
                        if (controller.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                            controller.seekToDefaultPosition(controller.currentMediaItemIndex)
                        }
                        controller.play()
                    }
                }
                finish(controller)
            }
            if (controller.mediaItemCount > 0) { act(); return@addListener }
            // Just started: the saved queue is still being restored (MusicService.restore).
            var waited = 0
            val poll = object : Runnable {
                override fun run() {
                    if (controller.mediaItemCount > 0 || waited >= 3_000) act()
                    else { waited += 100; main.postDelayed(this, 100) }
                }
            }
            main.post(poll)
        }, androidx.core.content.ContextCompat.getMainExecutor(app))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Placed or the launcher asked: what was last shown (the service redraws it when it runs).
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        render(context, last.getString(KEY_TITLE, null), last.getString(KEY_ARTIST, null), last.getBoolean(KEY_PLAYING, false), null)
    }

    companion object {
        private const val PREFS = "music_widget"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_PLAYING = "playing"

        /** Redraws every placed widget with this song (null: nothing playing). */
        fun update(context: Context, title: String?, artist: String?, playing: Boolean, cover: Bitmap?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TITLE, title).putString(KEY_ARTIST, artist).putBoolean(KEY_PLAYING, playing).apply()
            render(context, title, artist, playing, cover)
        }

        private fun render(context: Context, title: String?, artist: String?, playing: Boolean, cover: Bitmap?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_music).apply {
                setTextViewText(R.id.widget_title, title ?: context.getString(R.string.widget_music_idle))
                setTextViewText(R.id.widget_artist, artist.orEmpty())
                if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_music)
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause_dark else R.drawable.ic_widget_play_dark)
                setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.widget_music_pause else R.string.widget_music_play))
                setOnClickPendingIntent(R.id.widget_previous, button(context, ACTION_PREVIOUS, 1))
                setOnClickPendingIntent(R.id.widget_play, button(context, ACTION_PLAY_PAUSE, 2))
                setOnClickPendingIntent(R.id.widget_next, button(context, ACTION_NEXT, 3))
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            manager.updateAppWidget(ids, views)
        }

        private const val ACTION_PREVIOUS = "com.comfort.app.widget.PREVIOUS"
        private const val ACTION_PLAY_PAUSE = "com.comfort.app.widget.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.comfort.app.widget.NEXT"

        /** A widget button: comes back to [onReceive]. */
        private fun button(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, MusicWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
