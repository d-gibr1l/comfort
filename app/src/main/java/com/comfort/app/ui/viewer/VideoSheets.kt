package com.comfort.app.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.theme.HeaderFontFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The video player's settings (its ⋮ › Settings), as flows the playing video follows at once:
 * [loop] plays a video again from the start when it ends; [speed] is its playback speed. (Whether
 * videos start with sound is GalleryDlPreferences.isViewerSound, as before.) */
object VideoSettings {
    private const val PREFS = "video_settings"
    private val _loop = MutableStateFlow(false)
    val loop: StateFlow<Boolean> = _loop.asStateFlow()
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _loop.value = p.getBoolean("loop", false)
        _speed.value = p.getFloat("speed", 1f).coerceIn(0.25f, 2f)
        loaded = true
    }

    fun setLoop(context: Context, on: Boolean) {
        _loop.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("loop", on).apply()
    }

    fun setSpeed(context: Context, value: Float) {
        _speed.value = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("speed", value).apply()
    }
}

/** One option of the viewer's ⋮ sheet: its icon, its name, and a [value] at its end (what it's
 * set to), as the music player's song menu has them. */
@Composable
internal fun ViewerMenuOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    value: String? = null,
    color: Color = Color.Unspecified,
) {
    val tint = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.38f
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint.copy(alpha = alpha), modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The viewer's ⋮: a sheet, as the music player's song menu, headed by the item (its picture and
 * name), its options under it. [options] are the rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerMenuSheet(item: GalleryItem, title: String, onDismiss: () -> Unit, options: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = com.comfort.app.ui.main.rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) {
                    coil.compose.AsyncImage(
                        model = item.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (item.isVideo) R.string.video else R.string.picture),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = HeaderFontFamily),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            options()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** The viewer's ⋮ › Settings: what the music player's settings sheet is for songs, for videos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSettingsSheet(soundOn: Boolean, onSoundChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val loop by VideoSettings.loop.collectAsState()
    val speed by VideoSettings.speed.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = com.comfort.app.ui.main.rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.video_settings),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = HeaderFontFamily),
                fontWeight = FontWeight.Bold,
            )
            Group(stringResource(R.string.music_group_playback))
            SwitchRow(stringResource(R.string.play_videos_with_sound), stringResource(R.string.play_videos_with_sound_subtitle), soundOn) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onSoundChange(it)
            }
            SwitchRow(stringResource(R.string.loop_videos), stringResource(R.string.loop_videos_subtitle), loop) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                VideoSettings.setLoop(context, it)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.playback_speed), style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily))
                    Text(stringResource(R.string.playback_speed_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${formatSpeed(speed)}×",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = speed,
                onValueChange = {
                    val v = (Math.round(it * 4f) / 4f).coerceIn(0.25f, 2f)
                    if (v != speed) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        VideoSettings.setSpeed(context, v)
                    }
                },
                valueRange = 0.25f..2f,
                steps = 6,
            )
        }
    }
}

private fun formatSpeed(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

@Composable
private fun Group(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = HeaderFontFamily),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A video file's details: what a viewer wants to know, not camera EXIF (a downloaded video has
 * none) — its name, resolution, frame rate, codecs, bitrate, length, size and format. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoDetailsSheet(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rows by produceState<List<Pair<Int, String>>?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { videoDetails(context, uri) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = com.comfort.app.ui.main.rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.video_details),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = HeaderFontFamily),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            val shown = rows
            if (shown == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                shown.forEach { (label, value) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge.copy(fontFamily = HeaderFontFamily), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** The details [VideoDetailsSheet] lists, labelled; those that can't be read are left out. */
private fun videoDetails(context: Context, uri: Uri): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    val resolver = context.contentResolver
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { out += R.string.prop_file to it }
                if (!c.isNull(1)) out += R.string.prop_size to android.text.format.Formatter.formatFileSize(context, c.getLong(1))
            }
        }
    }
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w != null && h != null && w > 0 && h > 0) {
            val (sw, sh) = if (rotation == 90 || rotation == 270) h to w else w to h
            out += R.string.resolution to "$sw × $sh (${minOf(sw, sh)}p)"
        }
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ms ->
            out += R.string.prop_length to android.text.format.DateUtils.formatElapsedTime(ms / 1000)
        }
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }?.let { bps ->
            out += R.string.prop_bitrate to if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1_000_000.0) else "${bps / 1000} kbps"
        }
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { out += R.string.prop_format to containerName(it) }
    } catch (_: Exception) {
    } finally {
        runCatching { r.release() }
    }
    // Codecs, frame rate and the audio's sample rate, from the file's tracks.
    val extractor = android.media.MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                out += R.string.video_codec to codecName(mime)
                if (f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                    val fps = runCatching { f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { runCatching { f.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) }
                    if (fps > 0f) out += R.string.frame_rate to "${Math.round(fps)} fps"
                }
            } else if (mime.startsWith("audio/") && out.none { it.first == R.string.audio_codec }) {
                val rate = if (f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) else 0
                val channels = if (f.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) else 0
                out += R.string.audio_codec to listOfNotNull(
                    codecName(mime),
                    rate.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000.0) },
                    channels.takeIf { it > 0 }?.let { if (it == 1) "mono" else if (it == 2) "stereo" else "$it ch" },
                ).joinToString(" · ")
            }
        }
    } catch (_: Exception) {
    } finally {
        runCatching { extractor.release() }
    }
    // In a sensible order: name, size, then the picture, the sound, the file.
    val order = listOf(R.string.prop_file, R.string.resolution, R.string.frame_rate, R.string.video_codec, R.string.audio_codec, R.string.prop_bitrate, R.string.prop_length, R.string.prop_size, R.string.prop_format)
    return out.distinctBy { it.first }.sortedBy { order.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
}

private fun codecName(mime: String): String = when (mime.lowercase()) {
    "video/avc" -> "H.264 (AVC)"
    "video/hevc" -> "H.265 (HEVC)"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/av01" -> "AV1"
    "video/mp4v-es" -> "MPEG-4"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/mpeg" -> "MP3"
    "audio/flac" -> "FLAC"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    else -> mime.substringAfter('/').uppercase()
}

private fun containerName(mime: String): String = when (mime.lowercase()) {
    "video/mp4" -> "MP4"
    "video/x-matroska" -> "MKV (Matroska)"
    "video/webm" -> "WebM"
    "video/quicktime" -> "MOV"
    "video/3gpp" -> "3GP"
    else -> mime.substringAfter('/').uppercase()
}
