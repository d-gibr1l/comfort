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
    // The player itself, for what only an ExoPlayer has (skip silence, its volume, the shuffle
    // order); the session plays it through [PreviousSetting].
    private var exo: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        MusicSettings.init(this)
        val player = ExoPlayer.Builder(this)
            // Music: takes audio focus (pauses for a call, ducks under navigation), and pauses
            // when headphones come out.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            // Music settings: Pause when headphones disconnect.
            .setHandleAudioBecomingNoisy(MusicSettings.pauseOnDisconnect.current)
            // Keeps the CPU up while playing with the screen off (Auxio's setting).
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(listener)
        exo = player
        val openNowPlaying = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, PreviousSetting(player))
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
        MusicSettings.setAudioSessionId(player.audioSessionId)
        applySettings(player)
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
                val saved = if (MusicSettings.keepQueue.current) loadSaved() else null
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
                val p = exo
                if (p != null && p.shuffleModeEnabled && p.currentTimeline.getFirstWindowIndex(true) != p.currentMediaItemIndex &&
                    p.currentPosition < 1000 && !restoring
                ) reshuffle()
            }
            save()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Into the next song by itself, or by the crossfade's own move on: it fades in. Anything
            // else (a skip, a pick in the queue) cuts the crossfade's tail short.
            if (crossfadeSkip) {
                crossfadeSkip = false
                fadingIn = false
            } else {
                fadingIn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                endTail()
            }
            save()
            updateWidget()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Paused mid-crossfade: the old song's tail stops with it (it isn't picked up again).
            // Not on isPlaying: that's false for a moment as the next song loads, the crossfade's own.
            if (!playWhenReady) endTail()
            // The sleep timer's "End of song" has stopped the music: it's done.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && MusicSettings.sleepAtEndOfSong.value) {
                MusicSettings.clearSleepTimer()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            save()
            updateWidget()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A seek within the song: a tail readied for where it was is dropped (one already playing
            // is the crossfade's own move on, or ended by the change of song).
            if (reason == Player.DISCONTINUITY_REASON_SEEK && tailStartedAt == 0L) endTail()
        }
        override fun onRepeatModeChanged(repeatMode: Int) = save()
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            openAudioEffectSession(audioSessionId)
            MusicSettings.setAudioSessionId(audioSessionId)
        }
    }

    private fun reshuffle() {
        val p = exo ?: return
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
            // Music settings: Keep the queue. Off, the app opens with nothing queued.
            val saved = if (MusicSettings.keepQueue.current) loadSaved() else null
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
     * cover file, else the system thumbnail of the song, else the one embedded in it. */
    private fun coverBitmap(song: Song): android.graphics.Bitmap? {
        val art = song.cover as? com.comfort.app.util.AudioCover
        // The cover pulled out when it was saved — kept in the cache folder, which Android clears
        // when space runs low (most songs' covers were gone on the phone, and the widget showed
        // none: this read threw and nothing else was tried). Then, like the Library's
        // AudioCoverFetcher, the system's thumbnail of the song, then its embedded cover.
        art?.storedArt?.let { a ->
            runCatching { contentResolver.openInputStream(android.net.Uri.parse(a))?.use { it.readBytes() } }
                .getOrNull()?.let { decodeSmall(it) }?.let { return it }
        }
        val audio = art?.audioUri?.let(android.net.Uri::parse) ?: song.uri
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && audio.authority == android.provider.MediaStore.AUTHORITY) {
            runCatching { contentResolver.loadThumbnail(audio, android.util.Size(256, 256), null) }.getOrNull()?.let { return it }
        }
        val embedded = runCatching {
            android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(this@MusicService, audio)
                    embeddedPicture
                } finally {
                    release()
                }
            }
        }.getOrNull() ?: return null
        return decodeSmall(embedded)
    }

    /** [bytes] decoded at about 192 px (a widget's bitmap travels between processes). */
    private fun decodeSmall(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 192) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    // --- The music settings (MusicSettings) --------------------------------------------------------

    /** Whether the song on came in by itself after the last one, so fades in. */
    private var fadingIn = false

    // --- Crossfade ---------------------------------------------------------------------------------
    // The player plays one song at a time, so the end of the one going out is played by a second
    // player ("the tail"): readied a few seconds before, paused at the point the crossfade starts;
    // there it plays on, fading out, while the main player moves to the next song and fades it in.
    private var tail: ExoPlayer? = null
    private var tailFor: String? = null
    // When the tail started playing (silent, a moment ahead of the crossfade) and when the
    // crossfade itself began; 0 before.
    private var tailRollingAt = 0L
    // Lining the silent tail up with the main player: when it was last moved, and how many times.
    private var tailAlignedAt = 0L
    private var tailAligns = 0
    private var tailLastLag = Long.MIN_VALUE
    // How far ahead of the main player the last seek put the tail, and what a seek costs (learnt).
    private var tailSeekAhead = Long.MIN_VALUE
    private var tailSeekCost = 160L
    private var tailStartedAt = 0L
    private var crossfadeSkip = false
    private var crossfade: kotlinx.coroutines.Job? = null

    /** Stops and lets go of the tail, whatever it was doing; the song on back at full volume. */
    private fun endTail() {
        val running = crossfade
        crossfade = null
        if (running != null) {
            running.cancel()
            exo?.volume = 1f
        }
        tail?.let { runCatching { it.release() } }
        tail = null
        tailFor = null
        tailRollingAt = 0L
        tailStartedAt = 0L
    }

    /** The crossfade itself: the next song (the main player, moved on) in, the tail out, along an
     * equal-power curve (sine in, cosine out) so the two together stay as loud as one song — a
     * straight line dips in the middle, two unrelated songs each at half adding up to less. Set
     * every ~16 ms by the clock, not by an animator: a ValueAnimator runs on the display's frames,
     * and with none coming (battery saver, the screen off) it never moved, the next song staying
     * silent at volume 0 for good (seen on the phone 2026-11-24). */
    private fun startCrossfade(main: ExoPlayer, t: ExoPlayer, fadeMs: Long) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        crossfade = scope.launch {
            while (true) {
                val v = ((android.os.SystemClock.elapsedRealtime() - startedAt).toFloat() / fadeMs).coerceIn(0f, 1f)
                val a = v * (Math.PI.toFloat() / 2f)
                main.volume = kotlin.math.sin(a)
                t.volume = kotlin.math.cos(a)
                if (v >= 1f) break
                kotlinx.coroutines.delay(16)
            }
            if (crossfade === coroutineContext[kotlinx.coroutines.Job]) {
                crossfade = null
                main.volume = 1f
                endTail()
            }
        }
    }

    /** A tail for [player]'s song, paused at [at] ms, on the same audio session (so the equalizer
     * and volume levelling apply to it too), speed, pitch and skip silence as the main player. */
    private fun readyTail(player: ExoPlayer, at: Long) {
        val item = player.currentMediaItem ?: return
        endTail()
        val t = ExoPlayer.Builder(this)
            // Not its own audio focus: it's the same music, and it would take it from the main player.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
        runCatching { if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) t.setAudioSessionId(player.audioSessionId) }
        t.playbackParameters = player.playbackParameters
        t.skipSilenceEnabled = player.skipSilenceEnabled
        t.setMediaItem(item, at)
        t.playWhenReady = false
        t.prepare()
        tail = t
        tailFor = item.mediaId
    }
    private var levelling: android.media.audiofx.DynamicsProcessing? = null

    /** Follows the music settings as they change, and runs the sleep timer and the fades. */
    private fun applySettings(player: ExoPlayer) {
        scope.launch { MusicSettings.skipSilence.value.collect { player.skipSilenceEnabled = it } }
        scope.launch { MusicSettings.pauseOnDisconnect.value.collect { player.setHandleAudioBecomingNoisy(it) } }
        scope.launch {
            kotlinx.coroutines.flow.combine(MusicSettings.speed.value, MusicSettings.pitch.value) { sp, pi -> sp to pi }
                .collect { (sp, pi) -> player.playbackParameters = androidx.media3.common.PlaybackParameters(sp, pi) }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(MusicSettings.volumeLevelling.value, MusicSettings.audioSessionId) { on, id -> on to id }
                .collect { (on, id) -> setLevelling(on, id) }
        }
        scope.launch { MusicSettings.sleepAtEndOfSong.collect { player.pauseAtEndOfMediaItems = it } }
        // The sleep timer and the fades: often while either has something to do, rarely otherwise.
        scope.launch {
            while (true) {
                val sleepAt = MusicSettings.sleepAt.value
                if (sleepAt != null && android.os.SystemClock.elapsedRealtime() >= sleepAt) {
                    player.pause()
                    MusicSettings.clearSleepTimer()
                }
                val fadeMs = MusicSettings.fadeSeconds.current * 1000L
                val fading = fadeMs > 0 && player.isPlaying
                if (!fading) {
                    player.volume = 1f
                    if (fadeMs == 0L) endTail()
                } else {
                    val pos = player.currentPosition
                    val dur = player.duration
                    // Crossfades into a next song (not past the last one, nor a song on repeat, nor
                    // when the sleep timer stops at the end of this one), and only a song longer
                    // than two fades.
                    val canCross = dur > fadeMs * 2 && player.hasNextMediaItem() &&
                        player.repeatMode != Player.REPEAT_MODE_ONE && !MusicSettings.sleepAtEndOfSong.value
                    val crossAt = dur - fadeMs
                    val id = player.currentMediaItem?.mediaId
                    if (canCross && tailStartedAt == 0L) {
                        // Readied a few seconds ahead, paused a moment before the crossfade point;
                        // played from there, silent, so it's already sounding when the crossfade
                        // starts (an audio track takes a moment to start: started at the crossfade
                        // itself, the old song went quiet for it). Starting, it falls ~90-160 ms
                        // behind the main player (measured on a Galaxy S24), and at the switch that
                        // stretch played twice: a stutter. While it's still silent, it's moved on by
                        // what it lags, until it's within ~25 ms (at most three tries).
                        val preroll = 1500L
                        if (pos >= crossAt - 4000 && pos < crossAt - preroll && tailFor != id) readyTail(player, crossAt - preroll)
                        val t = tail
                        if (t != null && tailFor == id) {
                            if (tailRollingAt == 0L && pos >= crossAt - preroll && t.playbackState == Player.STATE_READY) {
                                t.volume = 0f
                                t.play()
                                tailRollingAt = android.os.SystemClock.elapsedRealtime()
                                tailAlignedAt = tailRollingAt
                                tailAligns = 0
                                tailLastLag = Long.MIN_VALUE
                                tailSeekAhead = Long.MIN_VALUE
                                tailSeekCost = 160L
                            }
                            val lag = pos - t.currentPosition
                            val nowMs = android.os.SystemClock.elapsedRealtime()
                            // Only once the lag has settled (it reads off for ~200 ms after a start or a
                            // seek), and moved past it by what a seek costs: playing again after one, the
                            // tail falls back by about that much (~160 ms measured), each time.
                            val settled = tailLastLag != Long.MIN_VALUE && kotlin.math.abs(lag - tailLastLag) <= 10
                            tailLastLag = lag
                            if (tailRollingAt != 0L && t.isPlaying && settled && nowMs - tailAlignedAt >= 200 && tailAligns < 3 &&
                                kotlin.math.abs(lag) > 20 && pos < crossAt - 400
                            ) {
                                // What the last seek cost, now it has settled: what it was moved ahead
                                // of the main player by, plus the lag left.
                                if (tailSeekAhead != Long.MIN_VALUE) tailSeekCost = tailSeekAhead + lag
                                tailSeekAhead = tailSeekCost
                                t.seekTo(t.currentPosition + lag + tailSeekCost)
                                tailAligns++
                                tailAlignedAt = nowMs
                                tailLastLag = Long.MIN_VALUE
                            }
                            if (tailRollingAt != 0L && pos >= crossAt && t.isPlaying) {
                                tailStartedAt = android.os.SystemClock.elapsedRealtime()
                                crossfadeSkip = true
                                player.volume = 0f
                                t.volume = 1f
                                player.seekToNextMediaItem()
                                startCrossfade(player, t, fadeMs)
                            } else if (pos >= crossAt + 300) {
                                // Not sounding in time: no crossfade this once (it fades out below).
                                endTail()
                                tailFor = id
                            }
                        }
                    }
                    // Not crossfading (the last song, or the tail not ready in time): faded out at
                    // its end only when another song follows.
                    val out = if (tailRollingAt == 0L && dur > 0 && pos >= crossAt && (player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE)) {
                        ((dur - pos).toFloat() / fadeMs).coerceIn(0f, 1f)
                    } else 1f
                    // The position from before any move on just now: the song that came in starts at 0.
                    val now = if (fadingIn) player.currentPosition else pos
                    val inn = if (fadingIn) (now.toFloat() / fadeMs).coerceIn(0f, 1f) else 1f
                    if (fadingIn && now >= fadeMs) fadingIn = false
                    // During the crossfade its own loop sets the volumes.
                    if (crossfade == null) player.volume = minOf(out, inn)
                }
                kotlinx.coroutines.delay(if (fading) 50 else if (sleepAt != null) 1000 else 500)
            }
        }
    }

    /** Volume levelling: a gentle compressor on the music's audio session, then a limiter so the
     * gain it adds back never clips. Android 9 and up (DynamicsProcessing). */
    private fun setLevelling(on: Boolean, sessionId: Int) {
        levelling?.let { runCatching { it.release() } }
        levelling = null
        if (!on || sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0 || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        levelling = runCatching {
            val dp = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, false, 0, true, 1, false, 0, true,
            ).build().let { android.media.audiofx.DynamicsProcessing(0, sessionId, it) }
            // One band over everything: above -28 dB squeezed 3:1 (slow enough not to pump), the
            // level lost given back after it.
            dp.setMbcBandAllChannelsTo(0, android.media.audiofx.DynamicsProcessing.MbcBand(true, 20000f, 20f, 400f, 3f, -28f, 8f, -90f, 1f, 0f, 9f))
            dp.setLimiterAllChannelsTo(android.media.audiofx.DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -1.5f, 0f))
            dp.enabled = true
            dp
        }.getOrNull()
    }

    /** The player as the session (the notification, headphones, a car, the app) sees it: Previous
     * goes straight to the song before when Music settings › Previous restarts the song is off. */
    private class PreviousSetting(player: Player) : androidx.media3.common.ForwardingPlayer(player) {
        override fun seekToPrevious() {
            if (!MusicSettings.previousRestarts.current && hasPreviousMediaItem()) seekToPreviousMediaItem() else super.seekToPrevious()
        }
    }

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
        endTail()
        save()
        closeAudioEffectSession()
        levelling?.let { runCatching { it.release() } }
        levelling = null
        scope.cancel()
        session?.run {
            player.removeListener(listener)
            player.release()
            release()
        }
        session = null
        exo = null
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "music_player"
        const val KEY_STATE = "saved_queue"
    }
}
