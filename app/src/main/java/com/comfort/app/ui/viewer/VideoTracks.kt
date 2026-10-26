package com.comfort.app.ui.viewer

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

/** One audio or subtitle track of the video playing, as the viewer's ⋮ menu lists it. */
data class VideoTrack(val key: String, val label: String, val selected: Boolean)

/** The tracks of the video on screen and how to switch them: [audio] only matters with more than
 * one (a download with dubs), [subtitles] whenever there are any. Subtitles start off — a
 * downloaded video's are only shown when picked here. */
class VideoTracks(
    val audio: List<VideoTrack>,
    val subtitles: List<VideoTrack>,
    private val player: Player,
    private val groups: Map<String, Pair<Tracks.Group, Int>>,
) {
    val subtitlesOff: Boolean get() = subtitles.none { it.selected }

    fun selectAudio(track: VideoTrack) = select(C.TRACK_TYPE_AUDIO, track)

    /** [track] null turns subtitles off. */
    fun selectSubtitles(track: VideoTrack?) {
        if (track == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            select(C.TRACK_TYPE_TEXT, track)
        }
    }

    private fun select(type: Int, track: VideoTrack) {
        val (group, index) = groups[track.key] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    companion object {
        /** The playable audio and subtitle tracks in [tracks]. Named by their language in the
         * app's language ("Japanese"), else the file's own label, else numbered. */
        fun from(player: Player, tracks: Tracks, untitled: (Int) -> String): VideoTracks {
            val groups = mutableMapOf<String, Pair<Tracks.Group, Int>>()
            fun list(type: Int): List<VideoTrack> {
                val out = mutableListOf<VideoTrack>()
                tracks.groups.filter { it.type == type }.forEachIndexed { g, group ->
                    for (i in 0 until group.length) {
                        if (!group.isTrackSupported(i)) continue
                        val format = group.getTrackFormat(i)
                        val key = "$type:$g:$i"
                        groups[key] = group to i
                        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                        val label = language?.let { languageName(it) } ?: format.label ?: untitled(out.size + 1)
                        out += VideoTrack(key, label, group.isTrackSelected(i))
                    }
                }
                return out
            }
            return VideoTracks(list(C.TRACK_TYPE_AUDIO), list(C.TRACK_TYPE_TEXT), player, groups)
        }

        private fun languageName(code: String): String {
            val locale = Locale.getDefault()
            return Locale.forLanguageTag(code).getDisplayName(locale).ifBlank { code }
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
}
