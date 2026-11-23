package com.comfort.app.ui.comic

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.theme.HeaderFontFamily
import com.comfort.app.ui.main.SheetHeader
import com.comfort.app.ui.main.rememberSturdySheetState

/** How a page fits the screen in the paged modes. */
enum class PageScale(val contentScale: ContentScale) {
    FIT(ContentScale.Fit), WIDTH(ContentScale.FillWidth), HEIGHT(ContentScale.FillHeight), ORIGINAL(ContentScale.None)
}

enum class ReaderBackground(val color: Color) { BLACK(Color.Black), GRAY(Color(0xFF303030)), WHITE(Color.White) }

enum class ReaderOrientation(val android: Int) {
    FREE(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
    LANDSCAPE(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
}

/** The comic reader's settings (in its "comic_reader" prefs), after TachiyomiJ2K's reader. */
data class ReaderSettings(
    val mode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
    val scale: PageScale = PageScale.FIT,
    val background: ReaderBackground = ReaderBackground.BLACK,
    /** Taps on a page's left or right third turn it (the middle shows and hides the bars). */
    val tapZones: Boolean = true,
    val invertTaps: Boolean = false,
    val volumeKeys: Boolean = false,
    /** Webtoon mode: the strip's margins, in percent of the width on each side. */
    val sidePadding: Int = 0,
    val animatePages: Boolean = true,
    val orientation: ReaderOrientation = ReaderOrientation.FREE,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = true,
    val fullscreen: Boolean = true,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", mode.name).putString("scale", scale.name).putString("background", background.name)
            .putBoolean("tap_zones", tapZones).putBoolean("invert_taps", invertTaps).putBoolean("volume_keys", volumeKeys)
            .putInt("side_padding", sidePadding).putBoolean("animate_pages", animatePages).putString("orientation", orientation.name)
            .putBoolean("keep_screen_on", keepScreenOn).putBoolean("show_page_number", showPageNumber).putBoolean("fullscreen", fullscreen)
            .apply()
    }

    companion object {
        const val PREFS = "comic_reader"

        fun load(context: Context): ReaderSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = ReaderSettings()
            fun <E : Enum<E>> e(key: String, values: Array<E>, default: E) = values.firstOrNull { it.name == p.getString(key, null) } ?: default
            return ReaderSettings(
                mode = e("mode", ReadingMode.entries.toTypedArray(), d.mode),
                scale = e("scale", PageScale.entries.toTypedArray(), d.scale),
                background = e("background", ReaderBackground.entries.toTypedArray(), d.background),
                tapZones = p.getBoolean("tap_zones", d.tapZones),
                invertTaps = p.getBoolean("invert_taps", d.invertTaps),
                volumeKeys = p.getBoolean("volume_keys", d.volumeKeys),
                sidePadding = p.getInt("side_padding", d.sidePadding),
                animatePages = p.getBoolean("animate_pages", d.animatePages),
                orientation = e("orientation", ReaderOrientation.entries.toTypedArray(), d.orientation),
                keepScreenOn = p.getBoolean("keep_screen_on", d.keepScreenOn),
                showPageNumber = p.getBoolean("show_page_number", d.showPageNumber),
                fullscreen = p.getBoolean("fullscreen", d.fullscreen),
            )
        }
    }
}

/** The reader's settings, in a sheet as the music player's: the reading mode at the top, then what
 * goes with it (page scale and taps for the paged modes, margins for the webtoon strip), then the
 * rest. Each change applies at once. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSettingsSheet(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberSturdySheetState(), contentWindowInsets = { WindowInsets(0) }) {
        // A sheet, not a page: at most 85% of the screen, the reader still showing above it (it
        // ran up under the status bar); its settings scroll inside it.
        val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp),
        ) {
            SheetHeader(icon = Icons.Outlined.Tune, title = stringResource(R.string.reader_settings), subtitle = null, modifier = Modifier.padding(bottom = 12.dp))
            Choices(
                stringResource(R.string.reading_mode),
                listOf(ReadingMode.RIGHT_TO_LEFT to R.string.reading_right_to_left, ReadingMode.LEFT_TO_RIGHT to R.string.reading_left_to_right, ReadingMode.VERTICAL to R.string.reading_vertical),
                settings.mode,
            ) { onChange(settings.copy(mode = it)) }
            if (settings.mode != ReadingMode.VERTICAL) {
                Choices(
                    stringResource(R.string.page_scale),
                    listOf(PageScale.FIT to R.string.scale_fit, PageScale.WIDTH to R.string.scale_fit_width, PageScale.HEIGHT to R.string.scale_fit_height, PageScale.ORIGINAL to R.string.scale_original),
                    settings.scale,
                ) { onChange(settings.copy(scale = it)) }
                Toggle(stringResource(R.string.tap_to_turn_pages), stringResource(R.string.tap_to_turn_pages_subtitle), settings.tapZones) { onChange(settings.copy(tapZones = it)) }
                if (settings.tapZones) Toggle(stringResource(R.string.invert_taps), null, settings.invertTaps) { onChange(settings.copy(invertTaps = it)) }
                Toggle(stringResource(R.string.animate_page_turns), null, settings.animatePages) { onChange(settings.copy(animatePages = it)) }
            } else {
                Text(stringResource(R.string.side_padding), style = MaterialTheme.typography.titleSmall.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = settings.sidePadding.toFloat(),
                        onValueChange = { onChange(settings.copy(sidePadding = (Math.round(it / 5f) * 5))) },
                        valueRange = 0f..25f,
                        steps = 4,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${settings.sidePadding}%", modifier = Modifier.width(48.dp).padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            Choices(
                stringResource(R.string.reader_background),
                listOf(ReaderBackground.BLACK to R.string.reader_bg_black, ReaderBackground.GRAY to R.string.reader_bg_gray, ReaderBackground.WHITE to R.string.reader_bg_white),
                settings.background,
            ) { onChange(settings.copy(background = it)) }
            Choices(
                stringResource(R.string.reader_orientation),
                listOf(ReaderOrientation.FREE to R.string.orientation_free, ReaderOrientation.PORTRAIT to R.string.orientation_portrait, ReaderOrientation.LANDSCAPE to R.string.orientation_landscape),
                settings.orientation,
            ) { onChange(settings.copy(orientation = it)) }
            Toggle(stringResource(R.string.volume_keys_turn_pages), null, settings.volumeKeys) { onChange(settings.copy(volumeKeys = it)) }
            Toggle(stringResource(R.string.reader_keep_screen_on), null, settings.keepScreenOn) { onChange(settings.copy(keepScreenOn = it)) }
            Toggle(stringResource(R.string.show_page_number), null, settings.showPageNumber) { onChange(settings.copy(showPageNumber = it)) }
            Toggle(stringResource(R.string.reader_fullscreen), null, settings.fullscreen) { onChange(settings.copy(fullscreen = it)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(title: String, options: List<Pair<T, Int>>, selected: T, onPick: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val round by androidx.compose.animation.core.animateFloatAsState(if (value == selected) 1f else 0f, label = "readerChip")
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                label = { Text(stringResource(label)) },
                shape = remember(round) { com.comfort.app.theme.CapsuleMorphShape(round, 12.dp) },
            )
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
