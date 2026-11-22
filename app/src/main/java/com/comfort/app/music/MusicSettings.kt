package com.comfort.app.music

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The music player's settings (Now Playing › ⋮ › Settings, and its Sleep timer and Speed and pitch):
 * kept in the player's own preferences ("music_player", beside the saved queue), read by
 * [MusicService] as they change. The app and the service share one process, so these are plain
 * flows. The sleep timer isn't kept: it's for this listening only.
 */
object MusicSettings {
    private const val PREFS = "music_player"
    private var prefs: SharedPreferences? = null

    private fun bool(key: String, default: Boolean) = Setting(key, default, { p, k, d -> p.getBoolean(k, d) }, { e, k, v -> e.putBoolean(k, v) })
    private fun int(key: String, default: Int) = Setting(key, default, { p, k, d -> p.getInt(k, d) }, { e, k, v -> e.putInt(k, v) })
    private fun float(key: String, default: Float) = Setting(key, default, { p, k, d -> p.getFloat(k, d) }, { e, k, v -> e.putFloat(k, v) })

    /** Jumps over silent stretches (ExoPlayer's skip silence). */
    val skipSilence = bool("skip_silence", false)
    /** Seconds the end of each song and the start of the next play over each other, one fading
     * out as the other fades in (a crossfade); 0 is off. */
    val fadeSeconds = int("fade_seconds", 0)
    /** Plays on after a video with sound or a call paused the music. */
    val resumeAfterInterruption = bool("resume_after_interruption", true)
    /** Pauses when headphones (wired or Bluetooth) disconnect. */
    val pauseOnDisconnect = bool("pause_on_disconnect", true)
    /** Brings quiet and loud songs closer in volume (a compressor on the music's audio). */
    val volumeLevelling = bool("volume_levelling", false)
    /** Previous goes back to the start of the song once it's a few seconds in, before the one before. */
    val previousRestarts = bool("previous_restarts", true)
    /** A song tapped in the Library plays alone, not with the Library's other songs after it. */
    val playTappedOnly = bool("play_tapped_only", false)
    /** The last queue comes back, paused, when the app opens again. */
    val keepQueue = bool("keep_queue", true)
    /** The player's backdrop is the song's cover, blurred; off, a plain dark one. */
    val coverBackground = bool("cover_background", true)
    /** Playback speed and pitch, 1 being normal. */
    val speed = float("speed", 1f)
    val pitch = float("pitch", 1f)

    /** When the sleep timer stops the music (elapsedRealtime ms), or null. */
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    /** The sleep timer stops the music at the end of the song on. */
    private val _sleepAtEndOfSong = MutableStateFlow(false)
    val sleepAtEndOfSong: StateFlow<Boolean> = _sleepAtEndOfSong.asStateFlow()

    fun setSleepTimer(minutes: Int?) {
        _sleepAtEndOfSong.value = false
        _sleepAt.value = minutes?.let { android.os.SystemClock.elapsedRealtime() + it * 60_000L }
    }

    fun setSleepAtEndOfSong() {
        _sleepAt.value = null
        _sleepAtEndOfSong.value = true
    }

    internal fun clearSleepTimer() {
        _sleepAt.value = null
        _sleepAtEndOfSong.value = false
    }

    /** The music's audio session (for the system equalizer's panel), once the service has one. */
    private val _audioSessionId = MutableStateFlow(0)
    val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()
    internal fun setAudioSessionId(id: Int) { _audioSessionId.value = id }

    /** Reads the saved values; called by the app and the service as they start. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        all.forEach { it.load(p) }
    }

    private val all: List<Setting<*>> by lazy {
        listOf(coverBackground, skipSilence, fadeSeconds, resumeAfterInterruption, pauseOnDisconnect, volumeLevelling, previousRestarts, playTappedOnly, keepQueue, speed, pitch)
    }

    /** One setting: its value as a flow, set (and kept) with [set]. */
    class Setting<T> internal constructor(
        private val key: String,
        private val default: T,
        private val read: (SharedPreferences, String, T) -> T,
        private val write: (SharedPreferences.Editor, String, T) -> Unit,
    ) {
        private val _value = MutableStateFlow(default)
        val value: StateFlow<T> = _value.asStateFlow()
        val current: T get() = _value.value

        internal fun load(p: SharedPreferences) { _value.value = read(p, key, default) }

        fun set(v: T) {
            _value.value = v
            prefs?.edit()?.also { write(it, key, v) }?.apply()
        }
    }
}
