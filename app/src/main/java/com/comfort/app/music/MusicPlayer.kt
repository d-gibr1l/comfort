package com.comfort.app.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.comfort.app.data.DownloadEntity
import com.comfort.app.util.AudioCover
import com.comfort.app.util.thumbnailCacheKey
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Library song, as the player and its screens need it. */
data class Song(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    /** For Coil: the cover pulled out of the file, else the file's own (AudioCover). */
    val cover: Any?,
    /** The memory-cache key of its Library thumbnail (the cover), shown at once. */
    val placeholderKey: String,
    /** The cover as a file Uri, for the notification (it reads the song's own embedded cover otherwise). */
    val artworkUri: Uri?,
) {
    /** For the player: plays the file, shows its details, and carries what [Song.from] needs back. */
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .setExtras(android.os.Bundle().apply {
                    putString("comfort.file", uri.toString())
                    (cover as? AudioCover)?.storedArt?.let { putString("comfort.art", it) }
                })
                .build()
        )
        .build()

    val subtitle: String? get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() })
        .joinToString(" · ").ifBlank { null }

    companion object {
        /** The song's name without the artist in front ("Artist - Song" is how downloads are
         * titled): the artist is shown under it already. */
        fun trackTitle(title: String, artist: String?): String {
            val t = title.trim()
            val a = artist?.trim().orEmpty()
            if (a.isNotEmpty()) {
                for (sep in listOf(" - ", " – ", " — ")) {
                    val prefix = a + sep
                    if (t.startsWith(prefix, ignoreCase = true) && t.length > prefix.length) return t.substring(prefix.length).trim()
                }
            }
            return t.ifBlank { "Unknown" }
        }

        private const val EXTRA_FILE = "comfort.file"
        private const val EXTRA_STORED_ART = "comfort.art"

        /** A song back from a queued item (see [toMediaItem]). */
        fun from(item: MediaItem): Song {
            val md = item.mediaMetadata
            val file = md.extras?.getString(EXTRA_FILE)
            val storedArt = md.extras?.getString(EXTRA_STORED_ART)
            return Song(
                id = item.mediaId,
                uri = file?.let(Uri::parse) ?: Uri.EMPTY,
                title = md.title?.toString() ?: "Unknown",
                artist = md.artist?.toString(),
                album = md.albumTitle?.toString(),
                cover = file?.let { AudioCover(it, storedArt) } ?: md.artworkUri,
                placeholderKey = thumbnailCacheKey(item.mediaId),
                artworkUri = md.artworkUri,
            )
        }

        fun from(download: DownloadEntity): Song? {
            val file = (download.mediaUri ?: download.thumbnailPath)?.takeIf { it.isNotBlank() } ?: return null
            // mediaUri is set only when a cover was pulled out (thumbnailPath is then that cover).
            val storedArt = download.thumbnailPath.takeIf { download.mediaUri != null }
            return Song(
                id = download.id,
                uri = Uri.parse(file),
                title = trackTitle(download.title, download.artist),
                artist = download.artist,
                album = download.album,
                cover = AudioCover(file, storedArt),
                placeholderKey = thumbnailCacheKey(download.id),
                // The pulled-out cover, else the song itself: MusicService's bitmap loader reads
                // the cover embedded in it (the notification showed none for such a song).
                artworkUri = Uri.parse(storedArt ?: file),
            )
        }
    }
}

/** What the screens show: the song on, and the player's state. */
data class MusicState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val index: Int = 0,
    val count: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val duration: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** The queue in play order (shuffled when shuffle is on): each song with its queue index. */
    val queue: List<Pair<Int, Song>> = emptyList(),
    /** Whether the last change of song went forward (next, or a song ending) or back: which way
     * the screens slide the new song in. */
    val forward: Boolean = true,
    /** Goes up by one each time a song starts (also from one queued copy to another of the same
     * song): what the screens key their change of song on. */
    val playId: Int = 0,
    /** Goes up by one each time the position jumps (a seek, Previous restarting the song): the
     * screens read the position only while playing, so a jump while paused needs telling. */
    val seekId: Int = 0,
)

/**
 * The app's side of [MusicService]: a MediaController the screens use to play a queue of Library
 * songs and drive it (play/pause, next/previous, seek, shuffle, repeat), and [state] for them to
 * show. Commands sent before the controller has connected wait for it.
 */
object MusicPlayer {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val waiting = ArrayList<(MediaController) -> Unit>()
    // The queue's songs by id (the controller's items carry the id and metadata, not the cover).
    private val songs = HashMap<String, Song>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playId++
            // Which way: a command of ours says; a song ending goes forward; anything else (the
            // notification, a headset) from where the two sit in play order, wraps included.
            // A command's direction counts only just after it: Previous partway into a song restarts
            // it (no change of song), and its direction mustn't wait for a later, unrelated one.
            val pending = pendingForward.takeIf { android.os.SystemClock.uptimeMillis() - pendingAt < 1500 }
            transitionForward = pending ?: if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) true else null
            pendingForward = null
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            seekId++
        }

        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    private var playId = 0

    private var seekId = 0
    private var pendingForward: Boolean? = null
        set(value) {
            field = value
            pendingAt = android.os.SystemClock.uptimeMillis()
        }
    private var pendingAt = 0L
    private var transitionForward: Boolean? = null

    private fun withController(context: Context, action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        waiting += action
        if (future != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, MusicService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                waiting.clear()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            publish(c)
            val pending = waiting.toList()
            waiting.clear()
            pending.forEach { it(c) }
        }, ContextCompat.getMainExecutor(app))
    }

    /** Connects, so the state shows music already playing (the app reopened while it played). */
    fun connect(context: Context) {
        if (controller == null && future == null) withController(context) {}
    }

    /** Plays [downloads]' songs as the queue, from [startId] (the first one if null), shuffled if asked. */
    fun play(context: Context, downloads: List<DownloadEntity>, startId: String? = null, shuffle: Boolean = false) {
        val queue = downloads.filter { it.isAudio }.mapNotNull(Song::from)
        if (queue.isEmpty()) return
        queue.forEach { songs[it.id] = it }
        val start = if (shuffle && startId == null) queue.indices.random() else queue.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        val items = queue.map { it.toMediaItem() }
        withController(context) { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(c.currentMediaItemIndex)
            c.play()
        }
    }

    fun next() {
        pendingForward = true
        controller?.seekToNextMediaItem()
    }

    /** To the previous song, or back to the start of this one once it's a few seconds in. */
    fun previous() {
        pendingForward = false
        controller?.seekToPrevious()
    }

    /** Straight to the previous song (a swipe), never back to the start of this one. */
    fun previousSong() {
        pendingForward = false
        controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    /** Off → all → one → off. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Stops and clears the queue (the mini player goes away). */
    fun stop() {
        controller?.run { stop(); clearMediaItems() }
    }

    /** Where the song is (ms); read on a timer by the screens that show it. */
    fun position(): Long = controller?.currentPosition ?: 0L

    /** Moves the queue's song at [from] to [to] (queue indices; a drag in the queue). */
    fun move(from: Int, to: Int) {
        if (from != to) controller?.moveMediaItem(from, to)
    }

    /** Takes the queue's song at [index] out (a swipe in the queue). */
    fun removeAt(index: Int) {
        controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    }

    /** Clears the queue but for the song on. */
    fun clearQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }

    /** Plays the queue's song at [index] (a tap in the queue). */
    fun playAt(index: Int) {
        val c = controller ?: return
        val order = _state.value.queue.map { it.first }
        pendingForward = order.indexOf(index) >= order.indexOf(c.currentMediaItemIndex)
        c.seekToDefaultPosition(index)
        c.play()
    }

    // Not one this process queued (the app reopened while it played, or a restored queue): from
    // the item, which carries all of it (Song.toMediaItem).
    private fun songOf(item: MediaItem): Song = songs[item.mediaId] ?: Song.from(item).also { songs[it.id] = it }

    private fun publish(player: Player) {
        val song = player.currentMediaItem?.let(::songOf)
        // Play order: the timeline walked with shuffle as it is, so a shuffled queue lists what plays next.
        val timeline = player.currentTimeline
        val order = ArrayList<Int>()
        if (!timeline.isEmpty) {
            var i = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
            while (i != androidx.media3.common.C.INDEX_UNSET && order.size < timeline.windowCount) {
                order += i
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
        // Forward unless the new song comes before the old one in play order (a wrap from the last
        // song to the first, with repeat all, is forward too).
        val previous = _state.value
        val forward = when {
            playId == previous.playId -> previous.forward
            transitionForward != null -> transitionForward!!
            else -> {
                // From play order: the shorter way round, so a wrap either way reads right.
                val from = order.indexOf(previous.index)
                val to = order.indexOf(player.currentMediaItemIndex)
                if (from < 0 || to < 0) true else {
                    val ahead = (to - from + order.size) % order.size
                    ahead <= order.size - ahead
                }
            }
        }
        _state.value = MusicState(
            song = song,
            isPlaying = player.isPlaying,
            index = player.currentMediaItemIndex,
            count = player.mediaItemCount,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            duration = player.duration.takeIf { it > 0 } ?: 0L,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queue = order.map { it to songOf(player.getMediaItemAt(it)) },
            forward = forward,
            playId = playId,
            seekId = seekId,
        )
    }
}
