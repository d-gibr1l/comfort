/*

// The viewer's info: the details card for a picture or video, and the audio and subtitle track picker.
 * Ported from Photon Gallery (https://github.com/Bn5prS/Photon_Gallery), GPL-3.0, like Comfort.
 * Adapted for Comfort: package, imports and the pieces Photon's app provides that Comfort doesn't
 * (see the notes where they were changed).
 */
package com.comfort.app.ui.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.verticalScroll
import android.content.Context

import android.net.Uri
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.comfort.app.R

data class ExifData(
    val model: String?,
    val aperture: String?,
    val iso: String?,
    val shutterSpeed: String?,
    val focalLength: String?,
    val resolution: String?
)

suspend fun extractExif(context: android.content.Context, uri: Uri): ExifData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    var model: String? = null
    var aperture: String? = null
    var iso: String? = null
    var shutterSpeed: String? = null
    var focalLength: String? = null
    var resolution: String? = null

    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val exif = androidx.exifinterface.media.ExifInterface(inputStream)
            model = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)
            aperture = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
            iso = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }
            shutterSpeed = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)?.let { "${it}s" }
            focalLength = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it}mm" }
            
            val width = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH)
            val length = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH)
            if (width != null && length != null) {
                resolution = "${width}x${length}"
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    ExifData(model, aperture, iso, shutterSpeed, focalLength, resolution)
}

fun formatExifDate(timestamp: Long): String {
    return com.comfort.app.util.LocalFormat.date(timestamp, "yMMddHHmm")
}

fun formatSizeToMB(sizeBytes: Long): String {
    return com.comfort.app.util.LocalFormat.measure(sizeBytes / (1024.0 * 1024.0), android.icu.util.MeasureUnit.MEGABYTE, 2)
}

@Composable
fun ExifInfoCard(galleryItem: GalleryItem?, exifData: ExifData?, modifier: Modifier = Modifier) {
    if (galleryItem == null) return
    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 340.dp),
        shape = ShapeLargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // File Name — full wrap
            InfoBlock(label = stringResource(R.string.command_template_name), value = galleryItem.name)

            // Time + Size row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoBlock(
                    label = stringResource(R.string.modified),
                    value = formatExifDate(galleryItem.dateModified * 1000L),
                    modifier = Modifier.weight(1f)
                )
                InfoBlock(
                    label = stringResource(R.string.sort_size),
                    value = formatSizeToMB(galleryItem.size),
                    modifier = Modifier.weight(1f)
                )
            }

            // Resolution + Device row
            val resolution = exifData?.resolution
            val device = exifData?.model
            if (resolution != null || device != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (resolution != null) {
                        InfoBlock(label = stringResource(R.string.resolution), value = resolution, modifier = Modifier.weight(1f))
                    }
                    if (device != null) {
                        InfoBlock(label = stringResource(R.string.device), value = device, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Camera parameters
            val paramsParts = listOfNotNull(exifData?.focalLength, exifData?.shutterSpeed, exifData?.iso, exifData?.aperture).filter { it.isNotBlank() }
            if (paramsParts.isNotEmpty()) {
                InfoBlock(label = stringResource(R.string.parameters), value = paramsParts.joinToString("  •  "))
            }

            // Path — full wrap
            InfoBlock(label = stringResource(R.string.path), value = galleryItem.path)
        }
    }
}

@Composable
private fun InfoBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(

                letterSpacing = 0.8.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** A video's subtitle tracks ("Off" first) or audio tracks, as a radio list; picking one switches
 * the playing video to it. */
@Composable
internal fun TrackPickerDialog(tracks: VideoTracks, subtitles: Boolean, onDismiss: () -> Unit) {
    val options: List<VideoTrack?> = if (subtitles) listOf<VideoTrack?>(null) + tracks.subtitles else tracks.audio
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (subtitles) R.string.subtitles else R.string.audio_track)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                options.forEach { option ->
                    val selected = if (option == null) tracks.subtitlesOff else option.selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                if (subtitles) tracks.selectSubtitles(option) else option?.let(tracks::selectAudio)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(option?.label ?: stringResource(R.string.subtitles_off_short), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
