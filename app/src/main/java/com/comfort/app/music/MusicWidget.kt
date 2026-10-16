package com.comfort.app.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.RemoteViews
import com.comfort.app.MainActivity
import com.comfort.app.R

/**
 * The home-screen music widget (Auxio has one too): the song on, its cover, and previous /
 * play-pause / next. [MusicService] redraws it as the music changes; its buttons are media keys
 * sent to the music session (through Media3's MediaButtonReceiver), so they work with the app
 * closed — a play with nothing loaded picks the saved queue back up (MusicService's resumption).
 */
class MusicWidget : AppWidgetProvider() {
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
                setOnClickPendingIntent(R.id.widget_previous, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_play, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT))
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

        /** A media key for the music session, through Media3's MediaButtonReceiver. */
        private fun mediaKey(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, androidx.media3.session.MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
