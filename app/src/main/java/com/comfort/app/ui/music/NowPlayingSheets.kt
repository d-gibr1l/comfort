package com.comfort.app.ui.music

// The music player's own sheets: sleep timer, speed and pitch, music settings, a song's properties.

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.music.Song

/** 1.25 as "1.25×". */
internal fun formatFactor(f: Float): String = "%.2f×".format(java.util.Locale.ROOT, f).replace(".00×", "×").replace("0×", "×")

/** A sheet of the player's, titled, its content scrolling when it's taller than the screen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    InWindowSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** The sleep timer: off, a number of minutes, or the end of the song on. */
@Composable
internal fun SleepTimerSheet(onDismiss: () -> Unit) {
    val at by com.comfort.app.music.MusicSettings.sleepAt.collectAsState()
    val endOfSong by com.comfort.app.music.MusicSettings.sleepAtEndOfSong.collectAsState()
    // Which choice set the timer that's running (the minutes it was set for), for its tick.
    var chosen by remember { mutableStateOf<Int?>(null) }
    PlayerSheet(stringResource(R.string.sleep_timer), onDismiss) {
        sleepTimerLabel()?.let {
            Text(stringResource(R.string.sleep_stops_in, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
        }
        @Composable
        fun choice(label: String, selected: Boolean, onPick: () -> Unit) = Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onPick(); onDismiss() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily))
        }
        choice(stringResource(R.string.subtitles_off_short), at == null && !endOfSong) { com.comfort.app.music.MusicSettings.setSleepTimer(null); chosen = null }
        for (min in listOf(15, 30, 45, 60, 90)) {
            choice(stringResource(R.string.sleep_x_min, min), at != null && chosen == min) { com.comfort.app.music.MusicSettings.setSleepTimer(min); chosen = min }
        }
        choice(stringResource(R.string.sleep_end_of_song), endOfSong) { com.comfort.app.music.MusicSettings.setSleepAtEndOfSong(); chosen = null }
        // Any length, 5 minutes to 3 hours: a slider, then Start.
        var custom by remember { mutableStateOf(false) }
        var minutes by remember { mutableStateOf(20) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { custom = !custom }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(selected = custom || (at != null && chosen != null && chosen !in listOf(15, 30, 45, 60, 90)), onClick = null)
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.sleep_custom), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), modifier = Modifier.weight(1f))
            if (custom) Text(formatMinutes(minutes), style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.animation.AnimatedVisibility(visible = custom) {
            Column {
                androidx.compose.material3.Slider(
                    value = minutes.toFloat(),
                    onValueChange = {
                        val v = (Math.round(it / 5f) * 5).coerceIn(5, 180)
                        if (v != minutes) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            minutes = v
                        }
                    },
                    valueRange = 5f..180f,
                )
                androidx.compose.material3.FilledTonalButton(
                    onClick = { com.comfort.app.music.MusicSettings.setSleepTimer(minutes); chosen = minutes; onDismiss() },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.sleep_start), fontFamily = com.comfort.app.theme.HeaderFontFamily) }
            }
        }
    }
}

/** 95 as "1 h 35 min", 40 as "40 min". */
@Composable
private fun formatMinutes(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.sleep_x_min, minutes)
    else if (minutes % 60 == 0) stringResource(R.string.sleep_x_h, minutes / 60)
    else stringResource(R.string.sleep_x_h_y_min, minutes / 60, minutes % 60)

/** Playback speed and pitch, each from half to double, and back to normal. */
@Composable
internal fun SpeedPitchSheet(onDismiss: () -> Unit) {
    val speed by com.comfort.app.music.MusicSettings.speed.value.collectAsState()
    val pitch by com.comfort.app.music.MusicSettings.pitch.value.collectAsState()
    PlayerSheet(stringResource(R.string.speed_and_pitch), onDismiss) {
        FactorSlider(stringResource(R.string.speed), speed) { com.comfort.app.music.MusicSettings.speed.set(it) }
        FactorSlider(stringResource(R.string.pitch), pitch) { com.comfort.app.music.MusicSettings.pitch.set(it) }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.FilledTonalButton(
            onClick = { com.comfort.app.music.MusicSettings.speed.set(1f); com.comfort.app.music.MusicSettings.pitch.set(1f) },
            enabled = speed != 1f || pitch != 1f,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.reset_to_normal), fontFamily = com.comfort.app.theme.HeaderFontFamily) }
    }
}

/** A 0.5–2× slider in 0.05 steps, its value beside its name. */
@Composable
private fun FactorSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), modifier = Modifier.weight(1f))
            Text(formatFactor(value), style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = {
                val v = (Math.round(it * 20f) / 20f).coerceIn(0.5f, 2f)
                if (v != value) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                    onChange(v)
                }
            },
            valueRange = 0.5f..2f,
        )
    }
}

/** The player's settings (Now Playing › ⋮ › Settings): playback, sound, lyrics and the queue. */
@Composable
internal fun MusicSettingsSheet(lyricLine: Boolean, onLyricLineChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val m = com.comfort.app.music.MusicSettings
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // A tick each time a slider moves on a step.
    val tick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) }
    PlayerSheet(stringResource(R.string.music_settings), onDismiss) {
        SettingsGroup(stringResource(R.string.music_group_playback))
        SettingSwitch(stringResource(R.string.skip_silence), stringResource(R.string.skip_silence_subtitle), m.skipSilence)
        val fade by m.fadeSeconds.value.collectAsState()
        SettingRow(stringResource(R.string.fade_between), stringResource(R.string.fade_between_subtitle)) {
            Text(if (fade == 0) stringResource(R.string.subtitles_off_short) else stringResource(R.string.x_seconds_short, fade), style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Slider(
            value = fade.toFloat(),
            onValueChange = { val v = Math.round(it); if (v != fade) { tick(); m.fadeSeconds.set(v) } },
            valueRange = 0f..12f,
            steps = 11,
        )
        SettingSwitch(stringResource(R.string.resume_after_interruption), stringResource(R.string.resume_after_interruption_subtitle), m.resumeAfterInterruption)
        SettingSwitch(stringResource(R.string.pause_on_disconnect), stringResource(R.string.pause_on_disconnect_subtitle), m.pauseOnDisconnect)

        SettingsGroup(stringResource(R.string.music_group_sound))
        val sessionId by m.audioSessionId.collectAsState()
        SettingRow(
            stringResource(R.string.equalizer),
            stringResource(R.string.equalizer_subtitle),
            onClick = {
                val panel = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                val opened = runCatching { context.startActivity(panel) }.isSuccess
                if (!opened) android.widget.Toast.makeText(context, R.string.no_equalizer, android.widget.Toast.LENGTH_SHORT).show()
            },
        ) {}
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            SettingSwitch(stringResource(R.string.volume_levelling), stringResource(R.string.volume_levelling_subtitle), m.volumeLevelling)
        }

        SettingsGroup(stringResource(R.string.appearance))
        SettingSwitch(stringResource(R.string.player_background), stringResource(R.string.player_background_subtitle), m.coverBackground)

        SettingsGroup(stringResource(R.string.lyrics))
        SettingSwitch(stringResource(R.string.online_lyrics), stringResource(R.string.online_lyrics_subtitle), m.onlineLyrics)
        SettingRow(stringResource(R.string.lyric_lines), stringResource(R.string.lyric_lines_subtitle), onClick = {
            haptics.performHapticFeedback(if (lyricLine) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
            onLyricLineChange(!lyricLine)
        }) {
            androidx.compose.material3.Switch(checked = lyricLine, onCheckedChange = null)
        }
        // The full lyrics' size, as pinching them sets it (LyricsView's "lyrics_zoom").
        val zoomPrefs = remember { context.getSharedPreferences("music_player", android.content.Context.MODE_PRIVATE) }
        var zoom by remember { mutableStateOf(zoomPrefs.getFloat("lyrics_zoom", 1f).coerceIn(0.7f, 1.8f)) }
        SettingRow(stringResource(R.string.lyrics_text_size), null) {
            Text("${Math.round(zoom * 100)}%", style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Slider(
            value = zoom,
            onValueChange = { val v = (Math.round(it * 20f) / 20f).coerceIn(0.7f, 1.8f); if (v != zoom) { tick(); zoom = v } },
            onValueChangeFinished = { zoomPrefs.edit().putFloat("lyrics_zoom", zoom).apply() },
            valueRange = 0.7f..1.8f,
        )

        SettingsGroup(stringResource(R.string.queue))
        SettingSwitch(stringResource(R.string.previous_restarts), stringResource(R.string.previous_restarts_subtitle), m.previousRestarts)
        SettingSwitch(stringResource(R.string.play_tapped_only), stringResource(R.string.play_tapped_only_subtitle), m.playTappedOnly)
        SettingSwitch(stringResource(R.string.keep_queue), stringResource(R.string.keep_queue_subtitle), m.keepQueue)
    }
}

@Composable
private fun SettingsGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** A setting's name and what it does, with [trailing] at its end; a tap target when [onClick] is set. */
@Composable
private fun SettingRow(title: String, subtitle: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily))
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.size(12.dp))
        trailing()
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, setting: com.comfort.app.music.MusicSettings.Setting<Boolean>) {
    val on by setting.value.collectAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    SettingRow(title, subtitle, onClick = {
        haptics.performHapticFeedback(if (on) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
        setting.set(!on)
    }) {
        androidx.compose.material3.Switch(checked = on, onCheckedChange = null)
    }
}

/** What a song's file is: its name and where it's saved, format, length, bitrate, sample rate and
 * size (Auxio's "Song properties"). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SongPropertiesSheet(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val props by androidx.compose.runtime.produceState<List<Pair<Int, String>>?>(null, song.uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { songProperties(context, song.uri) }
    }
    InWindowSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.song_properties),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            props?.forEach { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** The song file's properties, labelled; those that can't be read are left out. */
private fun songProperties(context: android.content.Context, uri: android.net.Uri): List<Pair<Int, String>> {
    var name: String? = null
    var folder: String? = null
    var size: Long? = null
    runCatching {
        val cols = buildList {
            add(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
            add(android.provider.MediaStore.MediaColumns.SIZE)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) add(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = c.getLong(1).takeIf { it > 0 }
                if (cols.size > 2) folder = c.getString(2)
            }
        }
    }
    var duration: Long? = null
    var bitrate: Long? = null
    var sampleRate: Int? = null
    val retriever = android.media.MediaMetadataRetriever()
    runCatching {
        retriever.setDataSource(context, uri)
        duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            sampleRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
        }
    }
    runCatching { retriever.release() }
    val format = name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()
    return buildList {
        name?.let { add(R.string.prop_file to it) }
        folder?.let { add(R.string.path to it.trimEnd('/')) }
        format?.let { add(R.string.prop_format to it) }
        duration?.takeIf { it > 0 }?.let { ms ->
            val total = ms / 1000
            add(R.string.prop_length to if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%d:%02d".format(total / 60, total % 60))
        }
        bitrate?.takeIf { it > 0 }?.let { add(R.string.prop_bitrate to "${(it + 500) / 1000} kbps") }
        sampleRate?.takeIf { it > 0 }?.let { hz -> add(R.string.prop_sample_rate to (if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f))) }
        size?.let { add(R.string.sort_size to com.comfort.app.ui.main.formatFileSize(it)) }
    }
}

/** Where the song's lyrics come from (Mixify's sources sheet): its own, or a web source's. Each
 * row says how that source fared; a tap on one that has them shows them, on any other asks it. */
@Composable
internal fun LyricsSourceSheet(song: com.comfort.app.music.Song, onDismiss: () -> Unit) {
    val ol = com.comfort.app.music.lyrics.OnlineLyrics
    val status by ol.status.collectAsState()
    val shown by ol.shown.collectAsState()
    val own by ol.own.collectAsState()
    val state by com.comfort.app.music.MusicPlayer.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val songStatus = status.takeIf { it.songId == song.id }
    val showing = shown?.takeIf { it.songId == song.id }
    PlayerSheet(stringResource(R.string.lyrics_source), onDismiss) {
        val ownLyrics = own?.takeIf { it.first == song.id }?.second
        if (ownLyrics != null) {
            LyricsSourceRow(
                title = stringResource(R.string.lyrics_source_in_song),
                wordSynced = ownLyrics.wordSynced, synced = ownLyrics.synced,
                state = if (showing != null && showing.source == null) LyricsRowState.Showing else LyricsRowState.Found,
            ) { scope.launch { ol.pick(song, null, state.duration) } }
        }
        com.comfort.app.music.lyrics.LyricsSource.offered.forEach { source ->
            val found = songStatus?.found?.get(source)
            val rowState = when {
                showing?.source == source -> LyricsRowState.Showing
                found != null -> LyricsRowState.Found
                songStatus?.sources?.get(source) == com.comfort.app.music.lyrics.OnlineLyrics.State.Searching -> LyricsRowState.Searching
                songStatus?.sources?.get(source) == com.comfort.app.music.lyrics.OnlineLyrics.State.NotFound -> LyricsRowState.NotFound
                else -> LyricsRowState.NotAsked
            }
            LyricsSourceRow(
                title = source.label,
                wordSynced = found?.wordSynced ?: source.wordSynced,
                synced = found?.synced ?: true,
                state = rowState,
            ) {
                if (rowState != LyricsRowState.Searching) scope.launch { ol.pick(song, source, state.duration) }
            }
        }
    }
}

private enum class LyricsRowState { Showing, Found, Searching, NotFound, NotAsked }

@Composable
private fun LyricsSourceRow(title: String, wordSynced: Boolean, synced: Boolean, state: LyricsRowState, onClick: () -> Unit) {
    val showing = state == LyricsRowState.Showing
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (showing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (wordSynced) R.string.lyrics_word_by_word else if (synced) R.string.lyrics_by_line else R.string.lyrics_unsynced),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                stringResource(
                    when (state) {
                        LyricsRowState.Showing -> R.string.lyrics_source_showing
                        LyricsRowState.Found -> R.string.lyrics_source_found
                        LyricsRowState.Searching -> R.string.lyrics_source_searching
                        LyricsRowState.NotFound -> R.string.lyrics_source_not_found
                        LyricsRowState.NotAsked -> R.string.lyrics_source_not_asked
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            LyricsRowState.Searching -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            LyricsRowState.Showing, LyricsRowState.Found -> Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            LyricsRowState.NotFound -> Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            LyricsRowState.NotAsked -> Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
