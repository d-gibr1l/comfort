package com.comfort.app.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.comfort.app.MainActivity
import com.comfort.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plays the Library's songs in the background: one ExoPlayer behind a MediaSession, so playback
 * goes on with the app closed or the phone locked, with Media3's own media notification and the
 * lock-screen, Bluetooth and headset controls that come with a session. The app's screens drive it
 * through [MusicPlayer] (a MediaController); the notification opens Now Playing.
 *
 * From Auxio's playback service: a shuffle that keeps the song on first ([BetterShuffleOrder]),
 * the queue and position saved and brought back when the service starts again (after the app was
 * closed or the phone restarted), a wake lock while playing, and the audio session opened to the
 * system's equalizer apps.
 */
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // Music: takes audio focus (pauses for a call, ducks under navigation), and pauses
            // when headphones come out.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU up while playing with the screen off (Auxio's setting).
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(listener)
        val openNowPlaying = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openNowPlaying)
            .setCallback(sessionCallback)
            .setBitmapLoader(CoverBitmapLoader(this))
            .build()
        // The app's own logo in the status bar and the notification's corner (Media3's is a play icon).
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(com.comfort.app.R.drawable.ic_notif_logo)
            }
        )
        openAudioEffectSession(player.audioSessionId)
        restore(player)
        // Where in the song, every few seconds while playing: what a killed process comes back to.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (session?.player?.isPlaying == true) save()
            }
        }
    }

    // A media key (the widget, headphones, a car) with nothing loaded: the saved queue, to resume.
    private val sessionCallback = object : MediaSession.Callback {
        /** The service is exported (MediaSessionService has to be: the system's media controls,
         * Bluetooth, cars and watches connect to it), and Media3's default lets any app that
         * connects replace the queue with whatever media items it likes — any installed app could
         * make the player load and play a URL of its choosing. Other apps get the transport
         * controls (play/pause, skip, seek, shuffle, repeat, volume) and no more; this app's own
         * controllers (MusicPlayer, the media notification, the widget) keep everything. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            if (controller.packageName == packageName || !result.isAccepted) return result
            val transportOnly = result.availablePlayerCommands.buildUpon()
                .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
            return MediaSession.ConnectionResult.accept(result.availableSessionCommands, transportOnly)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val saved = loadSaved()
                if (saved == null) {
                    result.setException(IllegalStateException("Nothing saved to resume"))
                } else {
                    result.set(MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.position))
                    session?.player?.let { p ->
                        p.repeatMode = saved.repeat
                        p.shuffleModeEnabled = saved.shuffle
                    }
                }
            }
            return result
        }
    }

    private val listener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled) reshuffle()
            save()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // A new queue played shuffled: the song picked plays first, the rest after it.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                val p = session?.player as? ExoPlayer
                if (p != null && p.shuffleModeEnabled && p.currentTimeline.getFirstWindowIndex(true) != p.currentMediaItemIndex &&
                    p.currentPosition < 1000 && !restoring
                ) reshuffle()
            }
            save()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            save()
            updateWidget()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            save()
            updateWidget()
        }
        override fun onRepeatModeChanged(repeatMode: Int) = save()
        override fun onAudioSessionIdChanged(audioSessionId: Int) = openAudioEffectSession(audioSessionId)
    }

    private fun reshuffle() {
        val p = session?.player as? ExoPlayer ?: return
        if (p.mediaItemCount > 0) p.setShuffleOrder(BetterShuffleOrder(p.mediaItemCount, p.currentMediaItemIndex))
    }

    // --- The queue saved and brought back -------------------------------------------------------

    private var restoring = false

    /** The queue (song ids), the song on, where in it, and shuffle/repeat, for [restore]. */
    private fun save() {
        val p = session?.player ?: return
        if (restoring) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.mediaItemCount == 0) {
            prefs.edit().remove(KEY_STATE).apply()
            return
        }
        val ids = JSONArray()
        for (i in 0 until p.mediaItemCount) ids.put(p.getMediaItemAt(i).mediaId)
        val state = JSONObject()
            .put("ids", ids)
            .put("index", p.currentMediaItemIndex)
            .put("position", p.currentPosition.coerceAtLeast(0))
            .put("shuffle", p.shuffleModeEnabled)
            .put("repeat", p.repeatMode)
        prefs.edit().putString(KEY_STATE, state.toString()).apply()
    }

    private class Saved(val items: List<MediaItem>, val index: Int, val position: Long, val shuffle: Boolean, val repeat: Int)

    /** The saved queue, songs since deleted left out (null: nothing saved, or none left). */
    private suspend fun loadSaved(): Saved? = runCatching {
        val json = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null) ?: return null
        val state = JSONObject(json)
        val ids = state.getJSONArray("ids").let { a -> List(a.length()) { a.getString(it) } }
        val dao = AppDatabase.getDatabase(applicationContext).downloadDao()
        val byId = withContext(Dispatchers.IO) { ids.distinct().mapNotNull { dao.getById(it) }.associateBy { it.id } }
        val kept = ids.mapIndexedNotNull { i, id -> byId[id]?.takeIf { it.isAudio }?.let(Song::from)?.let { i to it } }
        if (kept.isEmpty()) return null
        val oldIndex = state.optInt("index")
        val newIndex = kept.indexOfFirst { it.first >= oldIndex }.coerceAtLeast(0)
        val position = if (kept.getOrNull(newIndex)?.first == oldIndex) state.optLong("position") else 0L
        Saved(kept.map { it.second.toMediaItem() }, newIndex, position, state.optBoolean("shuffle"), state.optInt("repeat", Player.REPEAT_MODE_OFF))
    }.getOrNull()

    /** The saved queue, paused where it was (the app opened again: the mini player shows it). */
    private fun restore(player: ExoPlayer) {
        restoring = true
        scope.launch {
            val saved = loadSaved()
            if (saved != null && player.mediaItemCount == 0) {
                player.setMediaItems(saved.items, saved.index, saved.position)
                player.repeatMode = saved.repeat
                player.shuffleModeEnabled = saved.shuffle
                if (saved.shuffle) player.setShuffleOrder(BetterShuffleOrder(player.mediaItemCount, saved.index))
                player.prepare()
            }
            restoring = false
            updateWidget()
        }
    }

    // --- The home-screen widget -------------------------------------------------------------------

    private var widgetJob: kotlinx.coroutines.Job? = null

    /** Redraws [MusicWidget] with the song on (its cover loaded off the main thread). */
    private fun updateWidget() {
        val p = session?.player ?: return
        val item = p.currentMediaItem
        val playing = p.isPlaying
        if (item == null) {
            MusicWidget.update(this, null, null, false, null)
            return
        }
        val song = Song.from(item)
        widgetJob?.cancel()
        widgetJob = scope.launch {
            val cover = withContext(Dispatchers.IO) { coverBitmap(song) }
            MusicWidget.update(this@MusicService, song.title, song.artist, playing, cover)
        }
    }

    /** The song's cover, small (a widget's bitmap travels between processes): the pulled-out
     * cover file, else the one embedded in the song. */
    private fun coverBitmap(song: Song): android.graphics.Bitmap? = runCatching {
        val art = (song.cover as? com.comfort.app.util.AudioCover)
        val bytes = art?.storedArt?.let { a -> contentResolver.openInputStream(android.net.Uri.parse(a))?.use { it.readBytes() } }
            ?: android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(this@MusicService, song.uri)
                    embeddedPicture
                } finally {
                    release()
                }
            }
            ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 192) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    // --- Equalizers ------------------------------------------------------------------------------

    private var openSessionId = C.AUDIO_SESSION_ID_UNSET

    /** Tells the system's equalizer apps (Samsung's Sound quality and effects, and others) which
     * audio session to work on, as music players do (Auxio does the same). */
    private fun openAudioEffectSession(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == openSessionId) return
        closeAudioEffectSession()
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        )
        openSessionId = sessionId
    }

    private fun closeAudioEffectSession() {
        if (openSessionId == C.AUDIO_SESSION_ID_UNSET) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, openSessionId)
        )
        openSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiped away from Recents: keep playing if music is on, else there's nothing to keep.
    override fun onTaskRemoved(rootIntent: Intent?) {
        save()
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        save()
        closeAudioEffectSession()
        scope.cancel()
        session?.run {
            player.removeListener(listener)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "music_player"
        const val KEY_STATE = "saved_queue"
    }
}
