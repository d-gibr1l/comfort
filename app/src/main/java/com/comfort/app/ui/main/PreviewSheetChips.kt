@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.comfort.app.R
import com.comfort.app.viewmodel.rememberSheetViewModelStoreOwner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfort.app.data.AudioFormat
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.PreviewInfo
import com.comfort.app.util.TrackPreview
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A chip row that scrolls horizontally rather than wrapping, per the sheet's own spec. */
@Composable
internal fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The audio format for this one download (Settings > Processing > Audio format is the default):
 * a chip showing the current pick that opens a small panel of all of them, each with what it's for. */
@Composable
internal fun AudioFormatChip(
    format: AudioFormat,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (AudioFormat) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PreviewChip(label = format.displayLabel, icon = Icons.Outlined.MusicNote, shape = shape, onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.width(264.dp),
        ) {
            Text(
                stringResource(R.string.save_audio_as),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            AudioFormat.entries.forEach { option ->
                val selected = option == format
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onChange(option)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            option.displayLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            audioFormatHint(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The video container: a chip naming it, opening a panel of both with what each is for. Same
 * panel as [AudioFormatChip]. */
@Composable
internal fun VideoFormatChip(
    format: OutputFormat,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (OutputFormat) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PreviewChip(label = format.label, icon = Icons.Outlined.Movie, shape = shape, onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(min = 160.dp, max = 216.dp),
        ) {
            Text(
                stringResource(R.string.save_video_as),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            OutputFormat.entries.forEach { option ->
                val selected = option == format
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onChange(option)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(if (option == OutputFormat.MKV) R.string.video_format_mkv_hint else R.string.video_format_mp4_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The subtitle tracks a video has and the ones this download gets ([selected]); [translated] is
 * the app's language when the site can translate captions into it and has no track of its own. */
internal data class SubtitleChoice(
    val tracks: List<com.comfort.app.data.SubtitleTrack>,
    val selected: List<com.comfort.app.data.SubtitleTrack>,
    val translated: com.comfort.app.data.SubtitleTrack?,
)

/** A video's subtitles: a chip naming the languages that download ("Off" for none), opening a
 * panel to tick any of them — uploaded tracks first, auto-generated ones marked, and the app's
 * language as a translation where the site offers one. Same panel shape as [AudioLanguageChip]. */
@Composable
internal fun SubtitleChip(
    choice: SubtitleChoice,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (List<com.comfort.app.data.SubtitleTrack>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = choice.tracks + listOfNotNull(choice.translated)
    Box {
        PreviewChip(
            label = if (choice.selected.isEmpty()) stringResource(R.string.subtitles_off)
            else choice.selected.joinToString(", ") { com.comfort.app.data.SubtitleLanguage.displayName(it.code) },
            selected = choice.selected.isNotEmpty(),
            icon = Icons.Outlined.Subtitles,
            shape = shape,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(min = 180.dp, max = 264.dp).heightIn(max = 420.dp),
        ) {
            Text(
                stringResource(R.string.subtitles),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            options.forEach { option ->
                val checked = choice.selected.any { it.code == option.code }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        // Several can be ticked, so the panel stays open.
                        .toggleable(value = checked, role = Role.Checkbox) { on ->
                            onChange(if (on) choice.selected + option else choice.selected.filter { it.code != option.code })
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            com.comfort.app.data.SubtitleLanguage.displayName(option.code),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        val note = when {
                            option === choice.translated -> stringResource(R.string.subtitles_translated)
                            option.auto -> stringResource(R.string.subtitles_auto_generated)
                            else -> null
                        }
                        note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (checked) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The audio track of a video with dubs: a chip naming the one that downloads, opening a panel of
 * them all with the original marked. Same panel shape as [AudioFormatChip]. */
@Composable
internal fun AudioLanguageChip(
    track: com.comfort.app.data.AudioTrack,
    tracks: List<com.comfort.app.data.AudioTrack>,
    shape: androidx.compose.ui.graphics.Shape,
    onChange: (com.comfort.app.data.AudioTrack) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PreviewChip(
            label = java.util.Locale.forLanguageTag(track.code).let { it.getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { track.code } }
                .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) },
            icon = Icons.Outlined.Language,
            shape = shape,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(min = 180.dp, max = 264.dp).heightIn(max = 420.dp),
        ) {
            Text(
                stringResource(R.string.audio_track),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            )
            tracks.forEach { option ->
                val selected = option == track
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onChange(option)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            com.comfort.app.data.AudioLanguage.displayName(option.code),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        if (option.original) {
                            Text(
                                stringResource(R.string.audio_track_original),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun audioFormatHint(format: AudioFormat): String = when (format) {
    AudioFormat.ORIGINAL -> stringResource(R.string.as_the_site_sends_it_not)
    AudioFormat.MP3 -> stringResource(R.string.plays_on_anything)
    AudioFormat.M4A -> stringResource(R.string.aac_small_and_good_quality)
    AudioFormat.OPUS -> stringResource(R.string.smallest_at_the_same_quality)
    AudioFormat.FLAC -> stringResource(R.string.lossless_biggest_file)
}

/** [icon] is the chip's own resting icon (what it *is*); the selected state replaces it with a
 * check, per the M3 chip spec, so a selected chip still reads as selected at a glance. */
@Composable
internal fun PreviewChip(
    label: String,
    selected: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.CircleShape,
    onClick: () -> Unit,
) {
    val leading = when {
        selected -> Icons.Outlined.Check
        else -> icon
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(31.dp),
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
                        borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp
        ),
        // 8dp rather than the pill the theme's shape scale would otherwise give a chip — the
        // sheet's own spec calls for squarer chips than the fully-rounded buttons around them.
        shape = shape,
        leadingIcon = leading?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(15.dp)) }
        },
    )
}

/** The sheet's own short quality labels ("1080", not the Settings screen's "1080p") — the chip row
 * has to fit five of them across a phone, so it uses tighter text than the full-width Settings
 * picker does for the same enum. */
internal val VideoQuality.chipLabel: String
    @Composable get() = when (this) {
        VideoQuality.BEST -> stringResource(R.string.best_available)
        VideoQuality.P2160 -> "4K"
        VideoQuality.P1440 -> "1440"
        VideoQuality.P1080 -> "1080"
        VideoQuality.P720 -> "720"
        VideoQuality.P480 -> "480"
        VideoQuality.AUDIO_ONLY -> stringResource(R.string.audio)
    }

internal val FIRST_CHIP_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = androidx.compose.foundation.shape.CornerSize(50),
    bottomStart = androidx.compose.foundation.shape.CornerSize(50),
    topEnd = androidx.compose.foundation.shape.CornerSize(8.dp),
    bottomEnd = androidx.compose.foundation.shape.CornerSize(8.dp)
)
internal val MIDDLE_CHIP_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
internal val LAST_CHIP_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = androidx.compose.foundation.shape.CornerSize(8.dp),
    bottomStart = androidx.compose.foundation.shape.CornerSize(8.dp),
    topEnd = androidx.compose.foundation.shape.CornerSize(50),
    bottomEnd = androidx.compose.foundation.shape.CornerSize(50)
)
