package com.comfort.app.ui.main

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import compose.icons.FeatherIcons
import compose.icons.feathericons.Instagram
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import com.comfort.app.theme.SuccessGreen40
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.EngineUpdateChannel
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.theme.LocalThemeState
import com.comfort.app.theme.ThemeMode
import com.comfort.app.util.AppUpdater
import com.comfort.app.util.EngineUpdater
import dev.darkokoa.datetimewheelpicker.WheelTimePicker
import dev.darkokoa.datetimewheelpicker.core.format.TimeFormat
import dev.darkokoa.datetimewheelpicker.core.format.timeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

enum class SettingsRoute { ROOT, APPEARANCE, FOLDERS, DOWNLOADS, PROCESSING, ADVANCED, COOKIES, UPDATES, ABOUT }

private fun SettingsRoute.displayName(): String = when (this) {
    SettingsRoute.ROOT -> "Settings"
    SettingsRoute.APPEARANCE -> "Appearance"
    SettingsRoute.FOLDERS -> "Folders"
    SettingsRoute.DOWNLOADS -> "Downloads"
    SettingsRoute.PROCESSING -> "Processing"
    SettingsRoute.ADVANCED -> "Advanced"
    SettingsRoute.COOKIES -> "Cookies & Login"
    SettingsRoute.UPDATES -> "Updates"
    SettingsRoute.ABOUT -> "About"
}

private fun SettingsRoute.icon(): ImageVector = when (this) {
    SettingsRoute.ROOT -> Icons.Outlined.Settings
    SettingsRoute.APPEARANCE -> Icons.Outlined.WbSunny
    SettingsRoute.FOLDERS -> Icons.Outlined.Folder
    SettingsRoute.DOWNLOADS -> Icons.Outlined.Download
    SettingsRoute.PROCESSING -> Icons.Outlined.Movie
    SettingsRoute.ADVANCED -> Icons.Outlined.Terminal
    SettingsRoute.COOKIES -> Icons.Outlined.Lock
    SettingsRoute.UPDATES -> Icons.Outlined.Update
    SettingsRoute.ABOUT -> Icons.Outlined.Info
}

/** One individual row from inside a settings sub-screen — as opposed to SettingsItemSpec below,
 * which only covers the handful of top-level nav entries on the Settings root. Lets the search bar
 * match something like "TLS" or "aria2c" against a row buried three screens deep without that
 * screen's own composable ever running. */
private data class SubpageSearchEntry(val title: String, val subtitle: String, val route: SettingsRoute) {
    fun matches(query: String): Boolean =
        title.contains(query, ignoreCase = true) || subtitle.contains(query, ignoreCase = true)
}

// Hand-maintained index of every toggle/field across the settings sub-screens — title/subtitle
// text copied verbatim from each row's own composable further down (and AppearanceScreen.kt).
// Nothing generates this automatically (the screens are built ad hoc, not off one shared model
// those rows could be collected from), so keep it in sync by hand: add an entry here whenever a
// new settings row is added elsewhere, and update the text here if a row's own title/subtitle
// changes. Order doesn't matter — this is only ever filtered, never displayed as-is.
private val SUBPAGE_SEARCH_INDEX = listOf(
    // Downloads
    SubpageSearchEntry("Sharing mode", "Configure, Instant, or Always ask — what the Sharesheet's default entry does with a shared link.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Multiple concurrent downloads", "Run more than one download at the same time. Off means exactly one at a time, regardless of the slider below.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Wi-Fi only", "Queued downloads wait for a Wi-Fi connection instead of using mobile data.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Speed limit", "Caps download bandwidth for all future downloads.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Retries", "How many times a failed request is retried before giving up. Off uses the engine's built-in default.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Fragment retries", "How many times a failed video/audio fragment is retried. Off shares the main Retries budget.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Proxy", "Routes all future downloads through this proxy. Supports http://, https:// and socks5://.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Force IPv4", "Forces connections over IPv4. Try this if downloads fail due to broken IPv6 routes.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Skip certificate checks", "Disables security certificate checks. Only enable this if a server is misconfigured.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Concurrent fragments", "How many fragments of a single video to download in parallel.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Sleep interval", "Adds a random delay before requests to avoid triggering rate limits and bot bans.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Socket timeout", "How long to wait on a stalled connection before retrying. Off uses the engine's default.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Buffer size", "The size of each read chunk (yt-dlp only). Rarely worth changing from the default.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Multi-connection downloads (aria2c)", "Downloads files faster by splitting them into multiple parts (yt-dlp only). Best for slow connections.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Restrict to time window", "New downloads wait in the queue until the window opens.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Use alarm for scheduling", "Ensures scheduled downloads start exactly on time by bypassing Android's battery-saving delays.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Limit max file size", "Files larger than this are skipped instead of downloaded.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Download delay", "Adds a delay between a finished download and the next one in the queue.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Incognito by default", "Downloads are still saved to your device, but won't appear in the app's History or Library.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Prevent duplicate downloads", "Skips downloading a link if it's already queued, running, or finished.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Remember last quality", "Makes the quality chosen on the download sheet the new default for future downloads.", SettingsRoute.DOWNLOADS),
    SubpageSearchEntry("Clean up leftover downloads", "Automatically deletes partial files when a download is cancelled or fails.", SettingsRoute.DOWNLOADS),

    // Folders
    SubpageSearchEntry("Filename format", "How downloaded files are named.", SettingsRoute.FOLDERS),
    SubpageSearchEntry("Restrict filenames", "Removes special characters and replaces spaces with underscores. Safer for sharing and older file systems.", SettingsRoute.FOLDERS),
    SubpageSearchEntry("Trim filenames", "Caps long titles at 150 characters (only applies to the default filename format).", SettingsRoute.FOLDERS),
    SubpageSearchEntry("Download location", "Where downloaded files are saved.", SettingsRoute.FOLDERS),
    SubpageSearchEntry("Storage", "How much space downloads are using.", SettingsRoute.FOLDERS),

    // Processing
    SubpageSearchEntry("Single video only", "Only downloads the specific video from a link, even if it belongs to a larger playlist or channel.", SettingsRoute.PROCESSING),
    SubpageSearchEntry("Live streams from the start", "Downloads live streams from the beginning instead of the current moment.", SettingsRoute.PROCESSING),
    SubpageSearchEntry("Embed thumbnail", "Save the video's thumbnail as cover art inside the file.", SettingsRoute.PROCESSING),
    SubpageSearchEntry("Embed metadata", "Tag the file with its title, uploader, and other details.", SettingsRoute.PROCESSING),
    SubpageSearchEntry("Embed chapters", "Saves chapter markers inside the video file.", SettingsRoute.PROCESSING),
    SubpageSearchEntry("Write description / info.json files", "Save a separate JSON metadata file alongside each download.", SettingsRoute.PROCESSING),
    SubpageSearchEntry("Download subtitles", "Fetch and embed subtitles when they're available.", SettingsRoute.PROCESSING),
    SubpageSearchEntry("Save subtitle files", "Saves subtitles as a separate file (.srt/.vtt) next to the video instead of only embedding them.", SettingsRoute.PROCESSING),

    // Advanced
    SubpageSearchEntry("Extra arguments", "Raw gallery-dl command-line arguments.", SettingsRoute.ADVANCED),
    SubpageSearchEntry("Rotate player clients", "Automatically switches between Android, iOS, and Web clients if YouTube blocks or slows down a download.", SettingsRoute.ADVANCED),
    SubpageSearchEntry("Use Instaloader for Instagram", "Downloads Instagram posts and reels with Instaloader, falling back to gallery-dl and yt-dlp.", SettingsRoute.ADVANCED),
    SubpageSearchEntry("Impersonate a browser", "Makes the app look like a real web browser to bypass bot detection on strict websites.", SettingsRoute.ADVANCED),
    SubpageSearchEntry("yt-dlp extractor arguments", "Site-specific extractor options passed straight to yt-dlp.", SettingsRoute.ADVANCED),
    SubpageSearchEntry("Format sort", "Custom yt-dlp format-selection priority.", SettingsRoute.ADVANCED),
    SubpageSearchEntry("Custom headers", "Extra HTTP headers sent with every request.", SettingsRoute.ADVANCED),
    SubpageSearchEntry("Verbose logging", "Logs all internal yt-dlp debug output for advanced troubleshooting.", SettingsRoute.ADVANCED),

    // Cookies & Login
    SubpageSearchEntry("Cookies", "Sign in to sites that require it, via a real embedded browser.", SettingsRoute.COOKIES),
    SubpageSearchEntry("Saved cookies", "Sites you've already signed in to.", SettingsRoute.COOKIES),

    // Appearance
    SubpageSearchEntry("Follow system theme", "Switch between your light and dark theme automatically.", SettingsRoute.APPEARANCE),
    SubpageSearchEntry("Pure black dark mode", "Use true black backgrounds in dark theme.", SettingsRoute.APPEARANCE),
    SubpageSearchEntry("Light theme", "Pick this app's light-mode color scheme.", SettingsRoute.APPEARANCE),
    SubpageSearchEntry("Dark theme", "Pick this app's dark-mode color scheme.", SettingsRoute.APPEARANCE),

    // About
    SubpageSearchEntry("App update", "Check for a newer version of this app.", SettingsRoute.UPDATES),
    SubpageSearchEntry("Engines", "Update yt-dlp, gallery-dl and Instaloader independently of an app update.", SettingsRoute.UPDATES),
    SubpageSearchEntry("Credits", "gallery-dl, yt-dlp, Instaloader, FFmpeg, QuickJS, aria2, and the bundled Python runtime.", SettingsRoute.ABOUT),
)

/** [route]/[onNavigate] are hoisted up to MainScreen rather than owned here — this composable
 * itself gets torn down and rebuilt every time the Settings tab is switched away from and back
 * (MainScreen's `when(selectedTab)` only composes the selected tab's screen at all), so a plain
 * local `remember` here used to reset to ROOT on every tab switch instead of staying wherever the
 * user actually was (reported live: drill into Downloads, tap Library, tap Settings again — lands
 * back on the root list instead of Downloads). Hoisting to MainScreen (which stays composed for
 * the app's whole lifetime) is what actually survives a tab switch. */
@Composable
fun MoreScreen(route: SettingsRoute, highlightKey: String?, onNavigate: (SettingsRoute, String?) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        // The root settings list is what a sub-screen's back gesture reveals — kept composed
        // underneath whenever we're not already on it, same reasoning as MainScreen's Home-behind-
        // a-tab treatment, purely so there's something real to peek at mid-swipe.
        if (route != SettingsRoute.ROOT) {
            SettingsRootScreen(onNavigate = onNavigate)
        }

        val backProgress = rememberPredictiveBackProgress(enabled = route != SettingsRoute.ROOT) {
            onNavigate(SettingsRoute.ROOT, null)
        }
        Box(modifier = Modifier.fillMaxSize().predictiveBackReveal(backProgress)) {
            when (route) {
                SettingsRoute.ROOT -> SettingsRootScreen(onNavigate = onNavigate)
                SettingsRoute.APPEARANCE -> AppearanceScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
                SettingsRoute.FOLDERS -> FoldersSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
                SettingsRoute.DOWNLOADS -> DownloadsSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
                SettingsRoute.PROCESSING -> ProcessingSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
                SettingsRoute.ADVANCED -> AdvancedSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
                SettingsRoute.COOKIES -> CookiesSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
                SettingsRoute.UPDATES -> UpdatesSettingsScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
                SettingsRoute.ABOUT -> AboutScreen(onBack = { onNavigate(SettingsRoute.ROOT, null) }, highlightKey = highlightKey)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRootScreen(onNavigate: (SettingsRoute, String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val themeState = LocalThemeState.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val effectiveDark = when (themeState.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    val themeSummary = if (effectiveDark) themeState.darkTheme.darkLabel else themeState.lightTheme.lightLabel
    val filenameFormat = remember { GalleryDlPreferences.getFilenameFormat(context) }
    val hasCookies = remember { GalleryDlPreferences.getCookies(context).isNotBlank() }

    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var maxHeaderHeightPx by remember { mutableStateOf(0) }
    // 4dp under the header (not the sub-pages' 20dp) plus the list's own 8dp top padding below —
    // the combined ~32dp gap between the title and the first card read as too loose (reported live).
    val headerState = rememberCollapsingHeaderState(scrollState, expandedTopPadding = 76.dp, expandedBottomPadding = 4.dp)
    // Split across Folders/Downloads/Processing (previously all one "Downloads" page) to match
    // YTDLnis's own settings shape — see gallery-dl.md's "Break up the Downloads settings page"
    // entry for why: one page covering filenames+folders+network+scheduling+quality+embedding all
    // at once had grown too long to scan.
    val mainItems = remember(themeSummary, filenameFormat, hasCookies) {
        listOf(
            SettingsItemSpec(Icons.Outlined.WbSunny, "Appearance", themeSummary, SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.APPEARANCE, null) },
            SettingsItemSpec(Icons.Outlined.Folder, "Folders", filenameFormat, SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.FOLDERS, null) },
            SettingsItemSpec(Icons.Outlined.Download, "Downloads", "Network, scheduling, and queue behavior", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.DOWNLOADS, null) },
            SettingsItemSpec(Icons.Outlined.Movie, "Processing", "Quality, format, and embedding", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.PROCESSING, null) },
            SettingsItemSpec(Icons.Outlined.Terminal, "Advanced", "Extra gallery-dl arguments", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.ADVANCED, null) },
            SettingsItemSpec(Icons.Outlined.Lock, "Cookies & Login", if (hasCookies) "Configured" else "Not set", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.COOKIES, null) },
            SettingsItemSpec(Icons.Outlined.Update, "Updates", "App, yt-dlp, gallery-dl & Instaloader", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.UPDATES, null) },
        )
    }
    val aboutItems = remember {
        listOf(SettingsItemSpec(Icons.Outlined.Info, "About", "Version, credits & source", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.ABOUT, null) })
    }
    val filteredMainItems = mainItems.filter { it.matches(searchQuery) }
    val filteredAboutItems = aboutItems.filter { it.matches(searchQuery) }
    val isSearching = searchQuery.isNotBlank()
    // Only computed while actually searching — SUBPAGE_SEARCH_INDEX.filter() over ~50 static
    // entries is cheap enough to just do inline (no remember needed), but there's no reason to pay
    // even that when the search bar is empty and this can never show anything anyway.
    val filteredSubpageResults = if (isSearching) SUBPAGE_SEARCH_INDEX.filter { it.matches(searchQuery) } else emptyList()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = with(density) { maxHeaderHeightPx.toDp() })
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ExpressiveSettingsList(
                items = filteredMainItems,
                emptyMessage = "No settings match \"$searchQuery\"".takeIf {
                    isSearching && filteredMainItems.isEmpty() && filteredAboutItems.isEmpty() && filteredSubpageResults.isEmpty()
                },
            )

            // Individual rows from inside a sub-screen (e.g. searching "TLS" surfaces Advanced's
            // own "Impersonate a browser" toggle) rather than just the 6 top-level nav entries
            // above — each one navigates straight to its own sub-screen, same as tapping that
            // sub-screen's own nav row would, just skipping the trip through its own list first.
            // Only ever shown while actually searching: unlike the nav entries above, these aren't
            // a real navigation surface on their own (no menu ever lists them directly), so there's
            // nothing sensible to show here once the query's cleared.
            if (isSearching && filteredSubpageResults.isNotEmpty()) {
                ExpressiveSettingsList(
                    items = filteredSubpageResults.map { entry ->
                        SettingsItemSpec(entry.route.icon(), entry.title, "In ${entry.route.displayName()}", SettingsItemColor.SURFACE_HIGH) {
                            onNavigate(entry.route, entry.title)
                        }
                    },
                )
            }

            if (!isSearching) {
                QuickAppUpdateSection()
                QuickEngineUpdateSection()
            }

            if (filteredAboutItems.isNotEmpty()) {
                ExpressiveSettingsList(items = filteredAboutItems)
            }

            // Clears the floating nav pill overlaying this screen (see MainScreen's own comment
            // on why it overlays instead of reserving Scaffold space) so this list can scroll
            // fully clear of it instead of ending up hidden behind.
            Spacer(Modifier.height(navBarClearance()))
        }

        // Compact single-row bar (leading page icon, title, trailing search toggle) at rest;
        // tapping search morphs this same slot into the full search field (crossfade via
        // AnimatedContent) instead of pushing a second bar into the scrolling content below — the
        // field visually covers the icon+title exactly where they sat. Same scroll-driven
        // collapse/expand as every sub-page's own header (see SettingsSubScaffold): scrolls away
        // like ordinary content, reappears compact on reverse-scroll, and grows back into this
        // full size as scroll nears the top — search still works in either register since both
        // branches below live inside the same alpha/padding-driven overlay.
        // Only shows while the header itself is hidden (the inverse of how far it's slid away).
        StatusBarScrim(alpha = { 1f - headerState.reveal.fraction }, modifier = Modifier.align(Alignment.TopStart))
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .compactHeaderReveal(headerState.reveal)
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { maxHeaderHeightPx = maxOf(maxHeaderHeightPx, it.height) }
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp)
                .padding(top = headerState.topPadding, bottom = headerState.bottomPadding),
        ) {
            androidx.compose.animation.AnimatedContent(targetState = searchExpanded, label = "settings-top-bar") { expanded ->
                if (expanded) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        PillSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                searchExpanded = false
                                searchQuery = ""
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        // 40dp box around the (non-clickable) leading icon, not just the bare
                        // 32dp icon — matches the 40dp IconButton every sub-page's back arrow
                        // sits in (see SettingsSubScaffold), so the title text next to it starts
                        // at the exact same x position on every Settings page, root included.
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.SettingsApplications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.displayMedium,
                            fontFamily = com.comfort.app.theme.HeaderFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { searchExpanded = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// A pill-shaped 56dp search bar filtering whatever's below it in real time — shared with the
// Library page (DownloadsHistoryScreen.kt), which imports this same composable rather than
// keeping its own separate copy of the same look.
@Composable
fun PillSearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String = "Search", modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(56.dp),
        // A percentage shape, not a fixed 28dp — 28dp only reads as a true pill (MD3's "full"
        // token) because it happens to equal exactly half of this bar's own 56dp height; tied to
        // a literal dp value, it'd stop being a pill the moment this bar's height ever changed.
        // RoundedCornerShape(50) is always exactly half of whatever height it's actually given.
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            // Same clear affordance as the home screen's own "Paste a link" field: only shown once
            // there's something to clear.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private enum class SettingsItemColor { PRIMARY, TERTIARY, SURFACE_HIGH }

private class SettingsItemSpec(
    val icon: ImageVector,
    val title: String,
    val summary: String,
    val color: SettingsItemColor,
    val onClick: () -> Unit,
) {
    fun matches(query: String): Boolean =
        query.isBlank() || title.contains(query, ignoreCase = true) || summary.contains(query, ignoreCase = true)
}

// The M3 Expressive "list group" shape treatment: 3dp gaps between items, extra-large (28dp) on
// the group's outer top/bottom corners, small (8dp) on the corners items share with their
// neighbor. These match MaterialTheme.shapes.extraLarge/.small's own corner values exactly (not
// arbitrary numbers) — kept as literals rather than pulled from those Shape objects since
// RoundedCornerShape's own per-corner constructor takes plain Dp, not a CornerSize/Shape, and
// there's no clean way to mix "this corner from shapes.extraLarge, that one from shapes.small"
// otherwise.
private fun expressiveListItemShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 28.dp
    val inner = 8.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun ExpressiveSettingsList(items: List<SettingsItemSpec>, emptyMessage: String? = null) {
    if (items.isEmpty()) {
        if (emptyMessage != null) {
            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { index, item ->
            val (containerColor, onContainerColor) = when (item.color) {
                SettingsItemColor.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                SettingsItemColor.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                SettingsItemColor.SURFACE_HIGH -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            }
            SettingsListRow(
                icon = item.icon,
                title = item.title,
                summary = item.summary,
                containerColor = containerColor,
                onContainerColor = onContainerColor,
                shape = expressiveListItemShape(index, items.size),
                onClick = item.onClick,
            )
        }
    }
}

@Composable
private fun SettingsListRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    containerColor: Color,
    onContainerColor: Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = shape,
        color = containerColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No tinted circle behind this any more — onContainerColor was already chosen to
            // read correctly against this row's own Surface color (containerColor), so it stays
            // the right tint for the bare icon too, not just for a badge drawn on top of it.
            Icon(icon, contentDescription = null, tint = onContainerColor, modifier = Modifier.size(28.dp))
            // 16dp, not 14dp — MD3's spacing system is built on an 8dp grid. Fixed once here
            // rather than everywhere it recurs across this file: every settings row on every
            // subpage renders through this one shared row, so this is the single highest-leverage
            // spacing fix available.
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = onContainerColor, fontWeight = FontWeight.SemiBold)
                if (summary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainerColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = onContainerColor, modifier = Modifier.size(18.dp))
        }
    }
}

// Carries a sub-screen's "scroll to and flash this row" request down to whichever row actually
// matches it, without every row composable needing an explicit parameter threaded all the way
// down from its own screen's top — SettingsSection/IconToggleRow/ThemeToggleRow (AppearanceScreen)
// all just read this directly via highlightRowModifier() below. Not `private`: AppearanceScreen.kt
// (a separate file, same package) needs it too, and Kotlin's own same-package visibility means no
// import is needed either way.
class HighlightController(val targetKey: String?, val scrollState: ScrollState) {
    // Set once by the sub-screen's own root Column right after it's laid out — every row's own
    // target-scroll math below is relative to *this*, not the row's raw on-screen position, so it
    // stays correct regardless of how deep the row is nested (inside a SettingsSection's own Card,
    // itself inside the scrolling Column).
    var containerWindowY = 0f
}

val LocalHighlightState = compositionLocalOf<HighlightController?> { null }

/** Applied to a settings row's own outer Modifier — a no-op Modifier unless this row is the
 * ambient [LocalHighlightState]'s current target, in which case it scrolls the sub-screen's own
 * ScrollState to bring this row on-screen and flashes a brief background tint behind it. [title]
 * is matched with startsWith (not equals) since a couple of real row titles carry a dynamic suffix
 * the search index's own copy of that title can't predict (e.g. "Saved cookies (3)" for a target
 * key of "Saved cookies") — every actual title in SUBPAGE_SEARCH_INDEX is still specific enough
 * that this doesn't risk matching the wrong row. */
@Composable
fun highlightRowModifier(title: String): Modifier {
    val highlight = LocalHighlightState.current
    val targetKey = highlight?.targetKey
    if (highlight == null || targetKey == null || !title.startsWith(targetKey, ignoreCase = true)) return Modifier

    var rowWindowY by remember(highlight) { mutableStateOf<Float?>(null) }
    val flash = remember(highlight) { Animatable(0f) }

    LaunchedEffect(rowWindowY) {
        val y = rowWindowY ?: return@LaunchedEffect
        // A beat for the sub-screen's own enter transition (slide-in from Settings root) to finish
        // before scrolling — animating scroll position mid-transition read as a jarring double
        // motion when tested without this.
        delay(300)
        val target = (highlight.scrollState.value + (y - highlight.containerWindowY)).roundToInt().coerceAtLeast(0)
        highlight.scrollState.animateScrollTo(target)
        flash.animateTo(1f, tween(150))
        delay(450)
        flash.animateTo(0f, tween(600))
    }

    return Modifier
        .onGloballyPositioned { if (rowWindowY == null) rowWindowY = it.positionInWindow().y }
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = flash.value * 0.6f), RoundedCornerShape(14.dp))
}

// Shared by SettingsSubScaffold's pinned topBar, its own inline/collapsing variant, and the pinned
// "reappears once you scroll back up" overlay of that variant — all three want the exact same
// icon+title look, just with different top padding (a tall 40dp at rest, a tight 8dp once it's
// re-pinned after having scrolled away).
// Callers that AREN'T already inside a horizontally-padded container (the pinned topBar, and the
// floating re-pinned overlay) pass includeHorizontalPadding = true for their own 20dp side margin;
// the inline variant, living inside SettingsSubScaffold's own 20dp-padded Column, passes false so
// it doesn't end up with 40dp on each side.
// State for the "header scrolls away like ordinary content, then reappears compact and morphs back
// into its full size as you scroll the last collapseRangePx back to the top" behavior — shared by
// every Settings page's header (root included) so they all collapse/expand identically. See
// SettingsSubScaffold's own doc comment for why this is one continuously-interpolated instance
// rather than two separate composables crossfading against each other.
internal data class CollapsingHeaderState(
    val reveal: CompactHeaderReveal,
    val topPadding: androidx.compose.ui.unit.Dp,
    val bottomPadding: androidx.compose.ui.unit.Dp,
    val collapseFraction: Float,
    // Lazy lists only (see rememberLazyCollapsingHeaderState) — attach to an ancestor of the list.
    val nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection? = null,
)

/** How far a pinned compact header has slid up out of view, driven 1:1 by scroll distance instead
 * of a timed show/hide: scrolling toward the end pushes it up by exactly as many pixels as the
 * content moved, scrolling back pulls it down by the same amount, and it can rest part-way if the
 * finger stops there — so it "shows itself" at the pace of the scroll rather than snapping in.
 * Hidden amounts are clamped to the header's own measured height ([heightPx], fed by
 * [compactHeaderReveal]); [hide] parks it fully hidden even before that height is known. */
@androidx.compose.runtime.Stable
internal class CompactHeaderReveal {
    var heightPx by androidx.compose.runtime.mutableFloatStateOf(0f)
    private var rawHiddenPx by androidx.compose.runtime.mutableFloatStateOf(0f)
    val hiddenPx: Float get() = rawHiddenPx.coerceIn(0f, heightPx)
    /** 1 = fully shown, 0 = fully hidden. */
    val fraction: Float get() = when {
        heightPx > 0f -> 1f - hiddenPx / heightPx
        rawHiddenPx > 0f -> 0f
        else -> 1f
    }
    /** [deltaPx] > 0 = content scrolled toward its end (hides), < 0 = back toward the start (reveals). */
    fun scrollBy(deltaPx: Float) { rawHiddenPx = (hiddenPx + deltaPx).coerceIn(0f, heightPx) }
    fun show() { rawHiddenPx = 0f }
    fun hide() { rawHiddenPx = Float.POSITIVE_INFINITY }
}

/** Slides the header up by [reveal]'s hidden amount and reports its full size back to it. Place it
 * before any status-bar inset padding so the measured height includes the inset. Deliberately not
 * clipped at the status bar: while part-way, the header slides straight in over it (chosen live
 * over an emerge-from-under-the-status-bar variant). */
internal fun Modifier.compactHeaderReveal(reveal: CompactHeaderReveal): Modifier = this
    .graphicsLayer { translationY = -reveal.hiddenPx }
    .onSizeChanged { reveal.heightPx = it.height.toFloat() }

@Composable
internal fun rememberCollapsingHeaderState(
    scrollState: ScrollState,
    expandedTopPadding: androidx.compose.ui.unit.Dp,
    collapsedTopPadding: androidx.compose.ui.unit.Dp = 8.dp,
    expandedBottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    collapsedBottomPadding: androidx.compose.ui.unit.Dp = 8.dp,
): CollapsingHeaderState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 120dp, not the original 32dp — that was fine back when collapsing only meant a padding
    // change, but now that it also merges the back-button row into the title row and crossfades
    // the icon, 32dp of scroll (much less than a single normal scroll gesture) made that whole
    // transformation happen almost the instant a finger touched the list, reproduced live as an
    // abrupt collapse after barely any scroll at all. 120dp asks for a more deliberate scroll.
    val collapseRangePx = remember(density) { with(density) { 120.dp.toPx() } }
    val reveal = remember { CompactHeaderReveal() }
    LaunchedEffect(scrollState) {
        var previous = scrollState.value
        snapshotFlow { scrollState.value }.collect { current ->
            if (current <= collapseRangePx) reveal.show() else reveal.scrollBy((current - previous).toFloat())
            previous = current
        }
    }
    val collapseFraction = (scrollState.value / collapseRangePx).coerceIn(0f, 1f)
    return CollapsingHeaderState(
        reveal = reveal,
        topPadding = androidx.compose.ui.unit.lerp(expandedTopPadding, collapsedTopPadding, collapseFraction),
        bottomPadding = androidx.compose.ui.unit.lerp(expandedBottomPadding, collapsedBottomPadding, collapseFraction),
        collapseFraction = collapseFraction,
    )
}

/** [rememberCollapsingHeaderState] for a LazyColumn (the Download Queue) instead of a
 * verticalScroll Column. A LazyListState has no single running scroll total the way
 * ScrollState.value does, so this combines index and offset into one monotonic value (the index
 * weighted far above any single item's height) for the direction comparison, and reads the offset
 * alone while still on item 0 — exact there, which is the only range the collapse itself spans,
 * *provided item 0 is taller than the 120dp collapse range* (the Queue makes its header's reserved
 * space item 0 for exactly this; a shorter item 0 makes the collapse snap shut once it scrolls off).
 * That combined value jumps by ~1,000,000 whenever the index changes, though, so it can't supply
 * the pixel deltas the scroll-linked reveal needs; those come from [CollapsingHeaderState.nestedScrollConnection]
 * instead, which the caller attaches to an ancestor of the list. */
@Composable
internal fun rememberLazyCollapsingHeaderState(
    listState: androidx.compose.foundation.lazy.LazyListState,
    expandedTopPadding: androidx.compose.ui.unit.Dp,
    collapsedTopPadding: androidx.compose.ui.unit.Dp = 8.dp,
    expandedBottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    collapsedBottomPadding: androidx.compose.ui.unit.Dp = 8.dp,
): CollapsingHeaderState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapseRangePx = remember(density) { with(density) { 120.dp.toPx() } }
    val reveal = remember { CompactHeaderReveal() }
    fun nearTop() = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= collapseRangePx
    val connection = remember(listState, collapseRangePx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                // consumed.y < 0 means the content moved up, i.e. scrolled toward its end.
                if (nearTop()) reveal.show() else reveal.scrollBy(-consumed.y)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    // Programmatic jumps (e.g. scrollToItem(0) on a tab switch) never pass through nested scroll.
    LaunchedEffect(listState) {
        snapshotFlow { nearTop() }.collect { if (it) reveal.show() }
    }
    val collapseFraction = if (listState.firstVisibleItemIndex > 0) 1f
        else (listState.firstVisibleItemScrollOffset / collapseRangePx).coerceIn(0f, 1f)
    return CollapsingHeaderState(
        reveal = reveal,
        topPadding = androidx.compose.ui.unit.lerp(expandedTopPadding, collapsedTopPadding, collapseFraction),
        bottomPadding = androidx.compose.ui.unit.lerp(expandedBottomPadding, collapsedBottomPadding, collapseFraction),
        collapseFraction = collapseFraction,
        nestedScrollConnection = connection,
    )
}

/** The extra controls a Settings toggle reveals under itself (a slider, a size field, ...), expanding
 * open / collapsing shut instead of popping in and out. Collapses toward the toggle above it so the
 * content visibly folds back into it. With the OS reduce-motion setting on, it only fades, since the
 * height change is exactly the kind of movement that setting asks to avoid. */
@Composable
private fun ColumnScope.ToggleReveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) fadeIn(tween(200)) else
            androidx.compose.animation.expandVertically(tween(250), expandFrom = Alignment.Top) + fadeIn(tween(250)),
        exit = if (reducedMotion) fadeOut(tween(150)) else
            androidx.compose.animation.shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(150)),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsSubPageHeader(
    title: String,
    topicIcon: ImageVector,
    onBack: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    includeHorizontalPadding: Boolean,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 8.dp,
    // 0 at rest (fully expanded), 1 once fully scrolled/collapsed — see SettingsSubScaffold's own
    // doc comment on CollapsingHeaderState. Continuous, not a discrete swap at some threshold: the
    // standalone back-button row above the title shrinks away exactly in step with this, and the
    // icon beside the title crossfades from the topic icon to the same back icon, so by the time
    // it's fully collapsed the compact bar is a single row — back icon, title, no space above it,
    // like every other Android app's compact app bar — with no separate "collapsed layout" to
    // jump-cut into (that's what caused the double-header ghosting fixed earlier).
    collapseFraction: Float = 0f,
) {
    val undoIcon = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_undo)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (includeHorizontalPadding) Modifier.padding(horizontal = 20.dp) else Modifier)
            // Shrinks toward 0 as it collapses too, same reasoning as the back-button row below —
            // 8dp is what puts it right at the top like other apps at rest; a collapsed compact bar
            // shouldn't keep even that much air above it.
            .padding(top = androidx.compose.ui.unit.lerp(8.dp, 0.dp, collapseFraction), bottom = bottomPadding),
    ) {
        // Its own height (not just alpha) shrinks to 0 as collapseFraction approaches 1, so the
        // compact bar reclaims the space entirely instead of leaving it empty-but-reserved.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.ui.unit.lerp(40.dp, 0.dp, collapseFraction))
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { alpha = 1f - collapseFraction }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    undoIcon,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(topPadding))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    // Only clickable once it's visually closer to the back icon than the topic
                    // icon — the topic icon itself stays purely decorative at rest, same as before;
                    // this is a plain on/off flip on an already-invisible property (hit-testing),
                    // not a visual change, so there's no jump to smooth out here.
                    .then(
                        if (collapseFraction > 0.5f) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.foundation.LocalIndication.current,
                                onClick = onBack,
                            )
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Both icons occupy the exact same box, just crossfaded by the same collapseFraction
                // driving everything else here — unlike the double-header bug, there's no risk of
                // the two disagreeing on position, since they're literally stacked in one Box.
                Icon(
                    topicIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp).graphicsLayer { alpha = 1f - collapseFraction },
                )
                Icon(
                    undoIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp).graphicsLayer { alpha = collapseFraction },
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = com.comfort.app.theme.HeaderFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsSubScaffold(
    title: String,
    topicIcon: ImageVector,
    onBack: () -> Unit,
    // Non-null only when this screen was opened from a Settings-search result (see
    // SettingsRootScreen's subpage results list) — see HighlightController's own doc comment for
    // how a row actually consumes this.
    highlightKey: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val highlight = remember(highlightKey) { HighlightController(highlightKey, scrollState) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var maxHeaderHeightPx by remember { mutableStateOf(0) }
    // There's only ever one header composable here, not an inline copy plus a separate pinned one
    // that crossfades against it — that two-composable version was tried first and reliably showed
    // both at once for a moment (reproduced live as a double title ghost) because one was fading
    // out on its own timer while the other was simultaneously scrolling into view underneath it.
    // Instead this single instance is always pinned, and its own top/bottom padding is continuously
    // interpolated from scroll position while within the last collapseRangePx of the top — the
    // compact bar doesn't get replaced by the real header, it *grows into* it, exactly in step with
    // the finger, which is what "becomes the header" means here (same behavior on every sub-page).
    val headerState = rememberCollapsingHeaderState(scrollState, expandedTopPadding = 76.dp)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalHighlightState provides highlight) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // Reserves exactly the header's own resting (fully expanded) height so the
                    // first section starts right where it visually ends at scroll = 0 — the header
                    // itself is a pinned overlay below, entirely outside this Column, not one of
                    // its children.
                    .padding(top = with(density) { maxHeaderHeightPx.toDp() })
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarClearance())
                    .onGloballyPositioned { highlight.containerWindowY = it.positionInWindow().y },
                verticalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
        }
        StatusBarScrim(alpha = { 1f - headerState.reveal.fraction }, modifier = Modifier.align(Alignment.TopStart))
        SettingsSubPageHeader(
            title = title,
            topicIcon = topicIcon,
            onBack = onBack,
            topPadding = headerState.topPadding,
            bottomPadding = headerState.bottomPadding,
            collapseFraction = headerState.collapseFraction,
            includeHorizontalPadding = true,
            modifier = Modifier
                .align(Alignment.TopStart)
                .compactHeaderReveal(headerState.reveal)
                .background(MaterialTheme.colorScheme.background)
                // Measuring outside windowInsetsPadding, not inside it — inside, onSizeChanged only
                // sees the header's own topPadding+row+bottomPadding and never learns about the
                // status bar inset windowInsetsPadding adds beyond that, so the reserved space below
                // undercounted by exactly the status bar's height. Reproduced live: the first
                // section's own label (e.g. "SHARING") rendered a status-bar's-worth of pixels too
                // high, right underneath the opaque header.
                .onSizeChanged { maxHeaderHeightPx = maxOf(maxHeaderHeightPx, it.height) }
                .windowInsetsPadding(WindowInsets.statusBars),
        )
    }
}

@Composable
private fun DownloadsSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var concurrentDownloads by remember { mutableStateOf(GalleryDlPreferences.getConcurrentDownloads(context)) }
    var concurrentDownloadsEnabled by remember { mutableStateOf(GalleryDlPreferences.isConcurrentDownloadsEnabled(context)) }
    var wifiOnly by remember { mutableStateOf(GalleryDlPreferences.isWifiOnly(context)) }
    var scheduleEnabled by remember { mutableStateOf(GalleryDlPreferences.isScheduleEnabled(context)) }
    var alarmSchedulingEnabled by remember { mutableStateOf(GalleryDlPreferences.isAlarmSchedulingEnabled(context)) }
    var scheduleStartMin by remember { mutableStateOf(GalleryDlPreferences.getScheduleStartMinutes(context)) }
    var scheduleEndMin by remember { mutableStateOf(GalleryDlPreferences.getScheduleEndMinutes(context)) }
    var speedLimit by remember { mutableStateOf(GalleryDlPreferences.getSpeedLimit(context)) }
    var speedLimitEnabled by remember { mutableStateOf(GalleryDlPreferences.isSpeedLimitEnabled(context)) }
    var proxyUrl by remember { mutableStateOf(GalleryDlPreferences.getProxyUrl(context)) }
    var proxyEnabled by remember { mutableStateOf(GalleryDlPreferences.isProxyEnabled(context)) }
    var maxFilesizeEnabled by remember { mutableStateOf(GalleryDlPreferences.isMaxFilesizeEnabled(context)) }
    var maxFilesize by remember { mutableStateOf(GalleryDlPreferences.getMaxFilesize(context)) }
    var shareMode by remember { mutableStateOf(GalleryDlPreferences.getShareMode(context)) }
    var deleteLeftoverOnFailure by remember { mutableStateOf(GalleryDlPreferences.isDeleteLeftoverOnFailure(context)) }
    var cleanupLeftoverInterval by remember { mutableStateOf(GalleryDlPreferences.getCleanupLeftoverInterval(context)) }
    var preventDuplicateDownloads by remember { mutableStateOf(GalleryDlPreferences.isPreventDuplicateDownloads(context)) }
    var rememberDownloadType by remember { mutableStateOf(GalleryDlPreferences.isRememberDownloadType(context)) }
    var networkRetries by remember { mutableStateOf(GalleryDlPreferences.getNetworkRetries(context)) }
    var networkRetriesEnabled by remember { mutableStateOf(GalleryDlPreferences.isNetworkRetriesEnabled(context)) }
    var fragmentRetries by remember { mutableStateOf(GalleryDlPreferences.getFragmentRetries(context)) }
    var fragmentRetriesEnabled by remember { mutableStateOf(GalleryDlPreferences.isFragmentRetriesEnabled(context)) }
    // Imported from YTDLnis's own downloading_preferences.xml (see GalleryDlPreferences' own doc
    // comments on each of these).
    var forceIpv4 by remember { mutableStateOf(GalleryDlPreferences.isForceIpv4(context)) }
    var concurrentFragments by remember { mutableStateOf(GalleryDlPreferences.getConcurrentFragments(context)) }
    var concurrentFragmentsEnabled by remember { mutableStateOf(GalleryDlPreferences.isConcurrentFragmentsEnabled(context)) }
    var noCheckCertificates by remember { mutableStateOf(GalleryDlPreferences.isNoCheckCertificates(context)) }
    var sleepIntervalSeconds by remember { mutableStateOf(GalleryDlPreferences.getSleepIntervalSeconds(context)) }
    var sleepIntervalEnabled by remember { mutableStateOf(GalleryDlPreferences.isSleepIntervalEnabled(context)) }
    var socketTimeoutSeconds by remember { mutableStateOf(GalleryDlPreferences.getSocketTimeoutSeconds(context)) }
    var socketTimeoutEnabled by remember { mutableStateOf(GalleryDlPreferences.isSocketTimeoutEnabled(context)) }
    var bufferSizeKb by remember { mutableStateOf(GalleryDlPreferences.getBufferSizeKb(context)) }
    var bufferSizeEnabled by remember { mutableStateOf(GalleryDlPreferences.isBufferSizeEnabled(context)) }
    var aria2Enabled by remember { mutableStateOf(GalleryDlPreferences.isAria2Enabled(context)) }
    var downloadDelayEnabled by remember { mutableStateOf(GalleryDlPreferences.isDownloadDelayEnabled(context)) }
    var downloadDelaySeconds by remember { mutableStateOf(GalleryDlPreferences.getDownloadDelaySeconds(context)) }
    var incognitoDefault by remember { mutableStateOf(GalleryDlPreferences.isIncognitoDefault(context)) }

    // A download forced past the schedule window (Start Now) requests setExpedited(), but Android
    // grants each app only a limited expedited-job quota — once a burst of Start Now taps burns
    // through it, the next one silently falls back to a plain background job instead of erroring.
    // A plain background job is exactly the kind of work Battery Saver + the app being backgrounded
    // is allowed to hold indefinitely (reproduced live: one sat QUEUED with its own real
    // workRequestId assigned, but DownloadWorker.doWork() was never actually invoked for it).
    // Whitelisting the app from battery optimization exempts it from that background-network block
    // entirely, the same fix standard Android download managers point users to for this exact class
    // of stall. Re-read (not just set once) via the launcher's callback below, since the user grants
    // or denies this from a system dialog this screen has no other way to observe returning from.
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryUnrestricted = isIgnoringBatteryOptimizations() }

    SettingsSubScaffold(title = "Downloads", topicIcon = Icons.Outlined.Download, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Sharing", icon = Icons.Outlined.Share) {
            ShareModeRow(
                mode = shareMode,
                onModeChange = {
                    shareMode = it
                    GalleryDlPreferences.setShareMode(context, it)
                },
            )
        }

        SettingsSection(title = "Concurrent downloads", icon = Icons.Outlined.Layers) {
            IconToggleRow(
                icon = if (concurrentDownloadsEnabled) Icons.Outlined.Layers else Icons.Outlined.LayersClear,
                title = "Multiple concurrent downloads",
                subtitle = "Run more than one download at the same time. Off means exactly one at a time, regardless of the slider below.",
                checked = concurrentDownloadsEnabled,
                onCheckedChange = {
                    concurrentDownloadsEnabled = it
                    GalleryDlPreferences.setConcurrentDownloadsEnabled(context, it)
                    scope.launch { DownloadDispatcher.rescheduleQueuedDownloads(context) }
                },
            )
            ToggleReveal(concurrentDownloadsEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = concurrentDownloads,
                    // Starts at 2, not 1 — 1 is exactly what the toggle above already means when
                    // off, so the slider (only shown while it's on) has nothing meaningful to say
                    // at that value; every position on it should actually mean "more than one."
                    valueRange = 2..GalleryDlPreferences.MAX_CONCURRENT_DOWNLOADS,
                    label = { "$it at once" },
                    onValueChange = {
                        concurrentDownloads = it
                        sharedPreferences.edit().putInt(GalleryDlPreferences.KEY_CONCURRENT_DOWNLOADS, it).apply()
                        // Without this, everything already queued stays chained in whatever
                        // round-robin lane(s) it was originally assigned to (e.g. all in
                        // gallery_dl_queue_0 from when the setting was 1) and keeps running
                        // exactly that concurrently regardless of the new setting — it only
                        // ever applied to downloads added *after* this tap. Redistributes the
                        // existing backlog across the new lane count immediately instead of
                        // leaving the user's current queue stuck on the old concurrency.
                        scope.launch { DownloadDispatcher.rescheduleQueuedDownloads(context) }
                    },
                )
            }
        }

        SettingsSection(title = "Network", icon = Icons.Outlined.Wifi) {
            IconToggleRow(
                icon = if (wifiOnly) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                title = "Wi-Fi only",
                subtitle = "Queued downloads wait for a Wi-Fi connection instead of using mobile data.",
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    sharedPreferences.edit().putBoolean(GalleryDlPreferences.KEY_WIFI_ONLY, it).apply()
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (speedLimitEnabled) {
                    ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_speed_2)
                } else {
                    Icons.Outlined.Speed
                },
                title = "Speed limit",
                subtitle = "Caps download bandwidth for all future downloads.",
                checked = speedLimitEnabled,
                onCheckedChange = {
                    speedLimitEnabled = it
                    GalleryDlPreferences.setSpeedLimitEnabled(context, it)
                    scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                },
            )
            ToggleReveal(speedLimitEnabled) {
                Spacer(Modifier.height(16.dp))
                SizeSheetField(
                    currentValue = speedLimit,
                    label = "Speed limit",
                    units = SPEED_UNITS,
                    onValueChange = {
                        speedLimit = it
                        GalleryDlPreferences.setSpeedLimit(context, it)
                        // A speed limit is only ever read fresh when a new subprocess is spawned —
                        // no IPC channel reaches an already-running one, so changing it here used
                        // to do nothing for whatever's downloading right now, only the next thing
                        // queued.
                        scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Refresh,
                title = "Retries",
                subtitle = "How many times a failed request is retried before giving up. Off uses the engine's built-in default.",
                checked = networkRetriesEnabled,
                onCheckedChange = {
                    networkRetriesEnabled = it
                    GalleryDlPreferences.setNetworkRetriesEnabled(context, it)
                },
            )
            ToggleReveal(networkRetriesEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = networkRetries,
                    valueRange = 1..GalleryDlPreferences.MAX_NETWORK_RETRIES,
                    label = { "$it retries" },
                    onValueChange = {
                        networkRetries = it
                        GalleryDlPreferences.setNetworkRetries(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Refresh,
                title = "Fragment retries",
                subtitle = "How many times a failed video/audio fragment is retried. Off shares the main Retries budget.",
                checked = fragmentRetriesEnabled,
                onCheckedChange = {
                    fragmentRetriesEnabled = it
                    GalleryDlPreferences.setFragmentRetriesEnabled(context, it)
                },
            )
            ToggleReveal(fragmentRetriesEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = fragmentRetries,
                    valueRange = 1..GalleryDlPreferences.MAX_FRAGMENT_RETRIES,
                    label = { "$it retries" },
                    onValueChange = {
                        fragmentRetries = it
                        GalleryDlPreferences.setFragmentRetries(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Lock,
                title = "Proxy",
                subtitle = "Routes all future downloads through this proxy. Supports http://, https:// and socks5://.",
                checked = proxyEnabled,
                onCheckedChange = {
                    proxyEnabled = it
                    GalleryDlPreferences.setProxyEnabled(context, it)
                    scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                },
            )
            ToggleReveal(proxyEnabled) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = proxyUrl,
                    onValueChange = {
                        proxyUrl = it
                        GalleryDlPreferences.setProxyUrl(context, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Proxy") },
                    placeholder = { Text("None") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                // Same reasoning as the speed-limit toggle above — a proxy is only ever read
                // fresh when a new subprocess is spawned, so changing it here otherwise does
                // nothing for whatever's downloading right now. Debounced so typing a new URL
                // doesn't restart every currently running download on every keystroke.
                var proxyUrlSettled by remember { mutableStateOf(proxyUrl) }
                LaunchedEffect(proxyUrl) {
                    delay(800)
                    if (proxyUrl != proxyUrlSettled) {
                        proxyUrlSettled = proxyUrl
                        DownloadDispatcher.restartRunningDownloads(context)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Public,
                title = "Force IPv4",
                subtitle = "Forces connections over IPv4. Try this if downloads fail due to broken IPv6 routes.",
                checked = forceIpv4,
                onCheckedChange = {
                    forceIpv4 = it
                    GalleryDlPreferences.setForceIpv4(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.GppBad,
                title = "Skip certificate checks",
                subtitle = "Disables security certificate checks. Only enable this if a server is misconfigured.",
                checked = noCheckCertificates,
                onCheckedChange = {
                    noCheckCertificates = it
                    GalleryDlPreferences.setNoCheckCertificates(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.CallSplit,
                title = "Concurrent fragments",
                subtitle = "How many fragments of a single video to download in parallel.",
                checked = concurrentFragmentsEnabled,
                onCheckedChange = {
                    concurrentFragmentsEnabled = it
                    GalleryDlPreferences.setConcurrentFragmentsEnabled(context, it)
                },
            )
            ToggleReveal(concurrentFragmentsEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = concurrentFragments,
                    // Same reasoning as Concurrent downloads' own slider above — 1 is already what
                    // the toggle above means when off.
                    valueRange = 2..GalleryDlPreferences.MAX_CONCURRENT_FRAGMENTS,
                    label = { "$it at once" },
                    onValueChange = {
                        concurrentFragments = it
                        GalleryDlPreferences.setConcurrentFragments(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (sleepIntervalEnabled) Icons.Outlined.AvTimer else Icons.Outlined.TimerOff,
                title = "Sleep interval",
                subtitle = "Adds a random delay before requests to avoid triggering rate limits and bot bans.",
                checked = sleepIntervalEnabled,
                onCheckedChange = {
                    sleepIntervalEnabled = it
                    GalleryDlPreferences.setSleepIntervalEnabled(context, it)
                },
            )
            ToggleReveal(sleepIntervalEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = sleepIntervalSeconds,
                    // Starts at 2, not the toggle's own off-value (0) — same off-value-redundancy
                    // reasoning as Concurrent downloads/fragments' sliders — and the floor
                    // yt_dlp_wrapper.py itself hardcodes for the random range's lower end.
                    valueRange = 2..GalleryDlPreferences.MAX_SLEEP_INTERVAL_SECONDS,
                    label = { "2-${it}s" },
                    onValueChange = {
                        sleepIntervalSeconds = it
                        GalleryDlPreferences.setSleepIntervalSeconds(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = "Socket timeout",
                subtitle = "How long to wait on a stalled connection before retrying. Off uses the engine's default.",
                checked = socketTimeoutEnabled,
                onCheckedChange = {
                    socketTimeoutEnabled = it
                    GalleryDlPreferences.setSocketTimeoutEnabled(context, it)
                },
            )
            ToggleReveal(socketTimeoutEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = socketTimeoutSeconds,
                    valueRange = 5..120,
                    label = { "${it}s" },
                    onValueChange = {
                        socketTimeoutSeconds = it
                        GalleryDlPreferences.setSocketTimeoutSeconds(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Storage,
                title = "Buffer size",
                subtitle = "The size of each read chunk (yt-dlp only). Rarely worth changing from the default.",
                checked = bufferSizeEnabled,
                onCheckedChange = {
                    bufferSizeEnabled = it
                    GalleryDlPreferences.setBufferSizeEnabled(context, it)
                },
            )
            ToggleReveal(bufferSizeEnabled) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (bufferSizeKb > 0) bufferSizeKb.toString() else "",
                    onValueChange = { input ->
                        val kb = input.filter { it.isDigit() }.toIntOrNull() ?: 0
                        bufferSizeKb = kb
                        GalleryDlPreferences.setBufferSizeKb(context, kb)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("KB") },
                    placeholder = { Text("1024") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (aria2Enabled) {
                    ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_bolt_boost)
                } else {
                    Icons.Outlined.FlashOff
                },
                title = "Multi-connection downloads (aria2c)",
                subtitle = "Downloads files faster by splitting them into multiple parts (yt-dlp only). Best for slow connections.",
                checked = aria2Enabled,
                onCheckedChange = {
                    aria2Enabled = it
                    GalleryDlPreferences.setAria2Enabled(context, it)
                },
            )
        }

        SettingsSection(title = "Reliability", icon = Icons.Outlined.Bolt) {
            if (batteryUnrestricted) {
                StatusRow(Icons.Outlined.CheckCircle, "Unrestricted — downloads can keep running in the background.", MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    "Unrestricted background activity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Without this, Android's Battery Saver can silently freeze a download that's " +
                        "waiting to start once the app is backgrounded — especially after \"Start now\" " +
                        "is tapped several times in a row. Recommended if downloads seem to get stuck.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        // Some OEM skins ship no activity for this action at all despite declaring
                        // the permission — falls back to the general battery-settings screen rather
                        // than crashing on startActivity() with no handler.
                        runCatching { batteryOptimizationLauncher.launch(intent) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Allow unrestricted background activity")
                }
            }
        }

        SettingsSection(title = "Schedule", icon = Icons.Outlined.Schedule) {
            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = "Restrict to time window",
                subtitle = "New downloads wait in the queue until the window opens.",
                checked = scheduleEnabled,
                onCheckedChange = {
                    scheduleEnabled = it
                    sharedPreferences.edit().putBoolean(GalleryDlPreferences.KEY_SCHEDULE_ENABLED, it).apply()
                    // Otherwise a download already queued under the old setting just sits
                    // there until its stale delay elapses — see rescheduleQueuedDownloads().
                    scope.launch { com.comfort.app.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                    com.comfort.app.data.DownloadDispatcher.scheduleWindowAlarm(context)
                },
            )

            ToggleReveal(scheduleEnabled) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = "Start",
                        minutesSinceMidnight = scheduleStartMin,
                        onPicked = { minutes ->
                            scheduleStartMin = minutes
                            sharedPreferences.edit().putInt(GalleryDlPreferences.KEY_SCHEDULE_START_MIN, minutes).apply()
                            scope.launch { com.comfort.app.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                            com.comfort.app.data.DownloadDispatcher.scheduleWindowAlarm(context)
                        },
                    )
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = "End",
                        minutesSinceMidnight = scheduleEndMin,
                        onPicked = { minutes ->
                            scheduleEndMin = minutes
                            sharedPreferences.edit().putInt(GalleryDlPreferences.KEY_SCHEDULE_END_MIN, minutes).apply()
                            scope.launch { com.comfort.app.data.DownloadDispatcher.rescheduleQueuedDownloads(context) }
                            com.comfort.app.data.DownloadDispatcher.scheduleWindowAlarm(context)
                        },
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))

                IconToggleRow(
                    icon = Icons.Outlined.Refresh,
                    title = "Use alarm for scheduling",
                    subtitle = "Ensures scheduled downloads start exactly on time by bypassing Android's battery-saving delays.",
                    checked = alarmSchedulingEnabled,
                    onCheckedChange = {
                        alarmSchedulingEnabled = it
                        GalleryDlPreferences.setAlarmSchedulingEnabled(context, it)
                        com.comfort.app.data.DownloadDispatcher.scheduleWindowAlarm(context)
                    },
                )
            }
        }

        SettingsSection(title = "Max file size", icon = Icons.Outlined.SdStorage) {
            IconToggleRow(
                icon = Icons.Outlined.SdStorage,
                title = "Limit max file size",
                subtitle = "Files larger than this are skipped instead of downloaded.",
                checked = maxFilesizeEnabled,
                onCheckedChange = {
                    maxFilesizeEnabled = it
                    GalleryDlPreferences.setMaxFilesizeEnabled(context, it)
                    scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                },
            )

            ToggleReveal(maxFilesizeEnabled) {
                Spacer(Modifier.height(16.dp))
                SizeSheetField(
                    currentValue = maxFilesize,
                    label = "Max file size",
                    units = FILESIZE_UNITS,
                    onValueChange = {
                        maxFilesize = it
                        GalleryDlPreferences.setMaxFilesize(context, it)
                        scope.launch { DownloadDispatcher.restartRunningDownloads(context) }
                    },
                )
            }
        }

        // Imported from YTDLnis's own "Download Delay" and "Incognito" settings.
        SettingsSection(title = "Pacing & privacy", icon = Icons.Outlined.VisibilityOff) {
            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = "Download delay",
                subtitle = "Adds a delay between a finished download and the next one in the queue.",
                checked = downloadDelayEnabled,
                onCheckedChange = {
                    downloadDelayEnabled = it
                    GalleryDlPreferences.setDownloadDelayEnabled(context, it)
                },
            )

            ToggleReveal(downloadDelayEnabled) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (downloadDelaySeconds > 0) downloadDelaySeconds.toString() else "",
                    onValueChange = { input ->
                        val seconds = input.filter { it.isDigit() }.toIntOrNull() ?: 0
                        downloadDelaySeconds = seconds
                        GalleryDlPreferences.setDownloadDelaySeconds(context, seconds)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Seconds") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_domino_mask),
                title = "Incognito by default",
                subtitle = "Downloads are still saved to your device, but won't appear in the app's History or Library.",
                checked = incognitoDefault,
                onCheckedChange = {
                    incognitoDefault = it
                    GalleryDlPreferences.setIncognitoDefault(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.ContentCopy,
                title = "Prevent duplicate downloads",
                subtitle = "Skips downloading a link if it's already queued, running, or finished.",
                checked = preventDuplicateDownloads,
                onCheckedChange = {
                    preventDuplicateDownloads = it
                    GalleryDlPreferences.setPreventDuplicateDownloads(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (rememberDownloadType) {
                    Icons.Outlined.HighQuality
                } else {
                    ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_high_quality_off)
                },
                title = "Remember last quality",
                subtitle = "Makes the quality chosen on the download sheet the new default for future downloads.",
                checked = rememberDownloadType,
                onCheckedChange = {
                    rememberDownloadType = it
                    GalleryDlPreferences.setRememberDownloadType(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.DeleteForever,
                title = "Clean up leftover downloads",
                subtitle = "Automatically deletes partial files when a download is cancelled or fails.",
                checked = deleteLeftoverOnFailure,
                onCheckedChange = {
                    deleteLeftoverOnFailure = it
                    GalleryDlPreferences.setDeleteLeftoverOnFailure(context, it)
                    DownloadDispatcher.rescheduleStagingCleanup(context)
                },
            )
            if (deleteLeftoverOnFailure) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "\"On failure\" only runs from inside the download itself, so it can't catch the app being killed outright — the periodic options add that as a backstop, on top of the same on-failure cleanup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // Real FilterChips, not a hand-rolled Surface+onClick row — same conversion, and
                // same reasoning, as the Sharing mode row above: the chip API's own accessibility
                // semantics, minimum touch target, and selected-state contract, for free.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // "Never" isn't one of the options here — the toggle above already covers
                    // that state, so (same off-value-redundancy fix as Concurrent downloads/
                    // fragments' sliders) it's deliberately excluded from what's reachable once
                    // the toggle reveals this row, rather than being selectable two different ways.
                    val cleanupOptions = listOf("" to "On failure", "daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly")
                    cleanupOptions.forEachIndexed { index, (value, label) ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val selected = cleanupLeftoverInterval == value
                        FilterChip(
                            selected = selected,
                            onClick = {
                                cleanupLeftoverInterval = value
                                GalleryDlPreferences.setCleanupLeftoverInterval(context, value)
                                DownloadDispatcher.rescheduleStagingCleanup(context)
                            },
                            modifier = Modifier.weight(1f).height(40.dp),
                            interactionSource = interactionSource,
                            shape = rememberMorphingChipShape(index, cleanupOptions.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                            label = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            border = null,
                        )
                    }
                }
            }
        }
    }
}

/** Paths, filenames, and local storage — split out of what used to be one long "Downloads" page,
 * matching YTDLnis's own Folders/Downloads/Processing split (see gallery-dl.md's "Break up the
 * Downloads settings page" entry) instead of one screen covering everything. */
@Composable
private fun FoldersSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var filenameFormat by remember { mutableStateOf(GalleryDlPreferences.getFilenameFormat(context)) }
    var filenameFormatSaved by remember { mutableStateOf(false) }
    var restrictFilenames by remember { mutableStateOf(GalleryDlPreferences.isRestrictFilenames(context)) }
    var trimFilenames by remember { mutableStateOf(GalleryDlPreferences.isTrimFilenames(context)) }
    var downloadLocationUri by remember { mutableStateOf(GalleryDlPreferences.getDownloadLocationUri(context)) }
    val downloadLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            downloadLocationUri = uri
            GalleryDlPreferences.setDownloadLocationUri(context, uri)
        }
    }

    // Imported from YTDLnis's own separate music/video folder settings — each independently
    // optional, falling back to the shared downloadLocationUri above (then the built-in default)
    // when unset. Same take-persistable-permission dance as the shared picker.
    var audioLocationUri by remember { mutableStateOf(GalleryDlPreferences.getAudioLocationUri(context)) }
    val audioLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            audioLocationUri = uri
            GalleryDlPreferences.setAudioLocationUri(context, uri)
        }
    }
    var videoLocationUri by remember { mutableStateOf(GalleryDlPreferences.getVideoLocationUri(context)) }
    val videoLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            videoLocationUri = uri
            GalleryDlPreferences.setVideoLocationUri(context, uri)
        }
    }

    SettingsSubScaffold(title = "Folders", topicIcon = Icons.Outlined.Folder, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Filename format", icon = Icons.Outlined.TextFields) {
            Text(
                "Filename format applied to every downloaded file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = filenameFormat,
                onValueChange = { filenameFormat = it; filenameFormatSaved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filename format") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Placeholders: {uploader} {title} {id} {extension} and more — see gallery-dl's format string docs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val toSave = filenameFormat.ifBlank { GalleryDlPreferences.DEFAULT_FILENAME_FORMAT }
                    filenameFormat = toSave
                    sharedPreferences.edit().putString(GalleryDlPreferences.KEY_FILENAME_FORMAT, toSave).apply()
                    filenameFormatSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save format")
            }

            if (filenameFormatSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Filename format saved", tint = SuccessGreen40)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.TextFields,
                title = "Restrict filenames",
                subtitle = "Removes special characters and replaces spaces with underscores. Safer for sharing and older file systems.",
                checked = restrictFilenames,
                onCheckedChange = {
                    restrictFilenames = it
                    GalleryDlPreferences.setRestrictFilenames(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.ContentCut,
                title = "Trim filenames",
                subtitle = "Caps long titles at 150 characters (only applies to the default filename format).",
                checked = trimFilenames,
                onCheckedChange = {
                    trimFilenames = it
                    GalleryDlPreferences.setTrimFilenames(context, it)
                },
            )
        }

        SettingsSection(title = "Download location", icon = Icons.Outlined.Folder) {
            val locationName = remember(downloadLocationUri) {
                downloadLocationUri?.let { uri ->
                    runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                }
            }
            Text(
                if (locationName != null) "Saving to \"$locationName\"." else "Saving to the default Pictures/Comfort folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locationName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Custom folders won't automatically appear in Photos/Gallery apps — only the default location is indexed as media.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { downloadLocationLauncher.launch(null) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose folder")
                }
                if (locationName != null) {
                    OutlinedButton(
                        onClick = {
                            downloadLocationUri = null
                            GalleryDlPreferences.setDownloadLocationUri(context, null)
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Use default")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            FolderOverrideRow(
                label = "Audio folder",
                fallbackDescription = "audio downloads land in the folder above (or the default) unless this is set.",
                uri = audioLocationUri,
                onChoose = { audioLocationLauncher.launch(null) },
                onUseDefault = { audioLocationUri = null; GalleryDlPreferences.setAudioLocationUri(context, null) },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            FolderOverrideRow(
                label = "Video folder",
                fallbackDescription = "video downloads land in the folder above (or the default) unless this is set.",
                uri = videoLocationUri,
                onChoose = { videoLocationLauncher.launch(null) },
                onUseDefault = { videoLocationUri = null; GalleryDlPreferences.setVideoLocationUri(context, null) },
            )
        }

        SettingsSection(title = "Storage", icon = Icons.Outlined.SdStorage) {
            var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
            var cacheCleared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                cacheSizeBytes = withContext(Dispatchers.IO) {
                    runCatching { context.cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } }.getOrNull() ?: 0L
                }
            }
            Text(
                cacheSizeBytes?.let { bytes ->
                    val mb = bytes / (1024f * 1024f)
                    if (mb >= 1f) "Cache: %.1f MB".format(mb) else "Cache: ${bytes / 1024} KB"
                } ?: "Calculating cache size…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Leftover temp files from interrupted downloads and preview thumbnails. Safe to clear — nothing here is a finished download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
                        }
                        cacheSizeBytes = 0L
                        cacheCleared = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clear cache")
            }
            if (cacheCleared) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cache cleared", tint = SuccessGreen40)
            }
        }
    }
}

/** Quality/format and embed-into-the-file choices — the other half of what used to be one long
 * "Downloads" page, split out to match YTDLnis's own Processing screen. */
@Composable
private fun ProcessingSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videoQuality by remember { mutableStateOf(GalleryDlPreferences.getVideoQuality(context)) }
    var outputFormat by remember { mutableStateOf(GalleryDlPreferences.getOutputFormat(context)) }
    var noPlaylist by remember { mutableStateOf(GalleryDlPreferences.isNoPlaylist(context)) }
    var liveFromStart by remember { mutableStateOf(GalleryDlPreferences.isLiveFromStart(context)) }
    var embedThumbnail by remember { mutableStateOf(GalleryDlPreferences.isEmbedThumbnail(context)) }
    var embedMetadata by remember { mutableStateOf(GalleryDlPreferences.isEmbedMetadata(context)) }
    var embedChapters by remember { mutableStateOf(GalleryDlPreferences.isEmbedChapters(context)) }
    var writeInfoFiles by remember { mutableStateOf(GalleryDlPreferences.isWriteInfoFiles(context)) }
    var downloadSubtitles by remember { mutableStateOf(GalleryDlPreferences.isDownloadSubtitles(context)) }
    var subtitleLanguages by remember { mutableStateOf(GalleryDlPreferences.getSubtitleLanguages(context)) }
    var saveSubtitleFiles by remember { mutableStateOf(GalleryDlPreferences.isSaveSubtitleFiles(context)) }
    var formatIdOverride by remember { mutableStateOf(GalleryDlPreferences.getFormatIdOverride(context)) }

    SettingsSubScaffold(title = "Processing", topicIcon = Icons.Outlined.Movie, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Video downloads", icon = Icons.Outlined.Movie) {
            Text("Quality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Applies to video links handled by yt-dlp (YouTube, Twitter/X, and similar).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                // Bleeds past the settings page's own 20dp side margin so the row's scrollable
                // viewport spans the full screen width, then re-adds that 20dp as inner padding so
                // the resting position still looks inset like the rest of the page — chips can now
                // scroll flush to the true screen edge instead of getting clipped mid-chip right at
                // the page margin, which read as "cut off." Widens via a custom layout rather than
                // Modifier.padding with a negative value — Compose's padding() throws at runtime on
                // negative dp, it isn't a supported way to do this.
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val bleed = 20.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                        layout(placeable.width - bleed * 2, placeable.height) {
                            placeable.placeRelative(-bleed, 0)
                        }
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val qualities = VideoQuality.entries
                qualities.forEachIndexed { index, quality ->
                    val selected = videoQuality == quality
                    val interactionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            videoQuality = quality
                            GalleryDlPreferences.setVideoQuality(context, quality)
                        },
                        modifier = Modifier.height(40.dp),
                        interactionSource = interactionSource,
                        shape = rememberMorphingChipShape(index, qualities.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                        label = { Text(quality.label, style = MaterialTheme.typography.labelLarge) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = null,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("Output format", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Only applies when video and audio need merging (most yt-dlp sources). A single already-muxed file, or anything gallery-dl fetches directly, keeps its own format regardless. If MP4 is picked but a source's video can't actually go in an MP4 (Instagram Reels are usually like this), that one download saves as MKV instead rather than an unplayable MP4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val formats = OutputFormat.entries
                formats.forEachIndexed { index, format ->
                    val selected = outputFormat == format
                    val interactionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            outputFormat = format
                            GalleryDlPreferences.setOutputFormat(context, format)
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        interactionSource = interactionSource,
                        shape = rememberMorphingChipShape(index, formats.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(format.label, style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = null,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.List,
                title = "Single video only",
                subtitle = "Only downloads the specific video from a link, even if it belongs to a larger playlist or channel.",
                checked = noPlaylist,
                onCheckedChange = {
                    noPlaylist = it
                    GalleryDlPreferences.setNoPlaylist(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.FastRewind,
                title = "Live streams from the start",
                subtitle = "Downloads live streams from the beginning instead of the current moment.",
                checked = liveFromStart,
                onCheckedChange = {
                    liveFromStart = it
                    GalleryDlPreferences.setLiveFromStart(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Image,
                title = "Embed thumbnail",
                subtitle = "Save the video's thumbnail as cover art inside the file.",
                checked = embedThumbnail,
                onCheckedChange = {
                    embedThumbnail = it
                    GalleryDlPreferences.setEmbedThumbnail(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Tag,
                title = "Embed metadata",
                subtitle = "Tag the file with its title, uploader, and other details.",
                checked = embedMetadata,
                onCheckedChange = {
                    embedMetadata = it
                    GalleryDlPreferences.setEmbedMetadata(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.List,
                title = "Embed chapters",
                subtitle = "Saves chapter markers inside the video file.",
                checked = embedChapters,
                onCheckedChange = {
                    embedChapters = it
                    GalleryDlPreferences.setEmbedChapters(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Description,
                title = "Write description / info.json files",
                subtitle = "Save a separate JSON metadata file alongside each download.",
                checked = writeInfoFiles,
                onCheckedChange = {
                    writeInfoFiles = it
                    GalleryDlPreferences.setWriteInfoFiles(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (downloadSubtitles) Icons.Outlined.Subtitles else Icons.Outlined.SubtitlesOff,
                title = "Download subtitles",
                subtitle = "Fetch and embed subtitles when they're available.",
                checked = downloadSubtitles,
                onCheckedChange = {
                    downloadSubtitles = it
                    GalleryDlPreferences.setDownloadSubtitles(context, it)
                },
            )
            if (downloadSubtitles) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = subtitleLanguages,
                    onValueChange = {
                        subtitleLanguages = it
                        GalleryDlPreferences.setSubtitleLanguages(context, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Subtitle languages") },
                    placeholder = { Text("en") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Comma-separated language codes, e.g. \"en,es\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (saveSubtitleFiles) Icons.Outlined.Subtitles else Icons.Outlined.SubtitlesOff,
                title = "Save subtitle files",
                subtitle = "Saves subtitles as a separate file (.srt/.vtt) next to the video instead of only embedding them.",
                checked = saveSubtitleFiles,
                onCheckedChange = {
                    saveSubtitleFiles = it
                    GalleryDlPreferences.setSaveSubtitleFiles(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("Format ID override", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "yt-dlp only — a raw format selector (e.g. \"137+140\", or any of yt-dlp's own -f expression syntax) that fully replaces Video quality above for every download. Leave blank to let the quality picker choose as usual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = formatIdOverride,
                onValueChange = {
                    formatIdOverride = it
                    GalleryDlPreferences.setFormatIdOverride(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Format selector") },
                placeholder = { Text("bv+ba/b") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}

@Composable
private fun TimePickerButton(
    modifier: Modifier = Modifier,
    label: String,
    minutesSinceMidnight: Int,
    onPicked: (Int) -> Unit,
) {
    val hour = minutesSinceMidnight / 60
    val minute = minutesSinceMidnight % 60
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        onClick = { showPicker = true },
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%d:%02d %s".format(displayHour, minute, amPm), style = MaterialTheme.typography.titleSmall)
        }
    }

    if (showPicker) {
        // Seeded once from the committed value when the dialog opens, then only ever written by
        // the wheel's own onSnappedTime — re-deriving it from minutesSinceMidnight on every
        // recomposition would fight the wheel's scroll position every time it snaps.
        var pendingMinutes by remember { mutableStateOf(minutesSinceMidnight) }
        val initialTime = remember { LocalTime(hour = hour, minute = minute) }
        Dialog(onDismissRequest = { showPicker = false }) {
            Card(shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    WheelTimePicker(
                        modifier = Modifier.size(280.dp, 160.dp),
                        startTime = initialTime,
                        timeFormatter = timeFormatter(timeFormat = TimeFormat.AM_PM),
                        textStyle = MaterialTheme.typography.headlineSmall,
                        selectedTextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        onSnappedTime = { snapped -> pendingMinutes = snapped.hour * 60 + snapped.minute },
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPicker = false }) { Text("Cancel") }
                        TextButton(onClick = { onPicked(pendingMinutes); showPicker = false }) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var extraArgs by remember { mutableStateOf(GalleryDlPreferences.getExtraArgs(context)) }
    var saved by remember { mutableStateOf(false) }
    var extractorArgs by remember { mutableStateOf(GalleryDlPreferences.getExtractorArgs(context)) }
    var extractorArgsSaved by remember { mutableStateOf(false) }
    var formatSort by remember { mutableStateOf(GalleryDlPreferences.getFormatSort(context)) }
    var formatSortSaved by remember { mutableStateOf(false) }
    var customHeaders by remember { mutableStateOf(GalleryDlPreferences.getCustomHeaders(context)) }
    var customHeadersSaved by remember { mutableStateOf(false) }
    var verboseLogging by remember { mutableStateOf(GalleryDlPreferences.isVerboseLogging(context)) }
    var youtubeClientRotation by remember { mutableStateOf(GalleryDlPreferences.isYoutubeClientRotationEnabled(context)) }
    var impersonateEnabled by remember { mutableStateOf(GalleryDlPreferences.isImpersonateEnabled(context)) }
    var instaloaderForInstagram by remember { mutableStateOf(GalleryDlPreferences.isInstaloaderForInstagram(context)) }

    SettingsSubScaffold(title = "Advanced", topicIcon = Icons.Outlined.Terminal, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Extra arguments", icon = Icons.Outlined.Terminal) {
            Text(
                "Extra command-line arguments passed to gallery-dl on every download. For advanced users.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = extraArgs,
                onValueChange = { extraArgs = it; saved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("e.g. --write-metadata --no-mtime") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    sharedPreferences.edit().putString(GalleryDlPreferences.KEY_EXTRA_ARGS, extraArgs).apply()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save arguments")
            }

            if (saved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Extra arguments saved", tint = SuccessGreen40)
            }
        }

        SettingsSection(title = "YouTube", icon = Icons.Outlined.Memory) {
            IconToggleRow(
                icon = Icons.Outlined.Refresh,
                title = "Rotate player clients",
                subtitle = "Automatically switches between Android, iOS, and Web clients if YouTube blocks or slows down a download.",
                checked = youtubeClientRotation,
                onCheckedChange = {
                    youtubeClientRotation = it
                    GalleryDlPreferences.setYoutubeClientRotationEnabled(context, it)
                },
            )
        }

        SettingsSection(title = "Instagram", icon = FeatherIcons.Instagram) {
            IconToggleRow(
                icon = FeatherIcons.Instagram,
                title = "Use Instaloader for Instagram",
                subtitle = "Downloads Instagram posts and reels with Instaloader, which handles carousels and captions and works on public posts without cookies. Falls back to gallery-dl and yt-dlp if it can't get a post. Off uses gallery-dl and yt-dlp only.",
                checked = instaloaderForInstagram,
                onCheckedChange = {
                    instaloaderForInstagram = it
                    GalleryDlPreferences.setInstaloaderForInstagram(context, it)
                },
            )
        }

        SettingsSection(title = "Bot detection", icon = Icons.Outlined.GppBad) {
            IconToggleRow(
                icon = Icons.Outlined.GppBad,
                title = "Impersonate a browser",
                subtitle = "Makes the app look like a real web browser to bypass bot detection on strict websites.",
                checked = impersonateEnabled,
                onCheckedChange = {
                    impersonateEnabled = it
                    GalleryDlPreferences.setImpersonateEnabled(context, it)
                },
            )
        }

        SettingsSection(title = "yt-dlp extractor arguments", icon = Icons.Outlined.Terminal) {
            Text(
                "Site-specific yt-dlp tuning (throttling workarounds, player client selection, etc). " +
                    "CLI syntax, e.g. \"youtube:player_client=android,web\". Separate multiple " +
                    "extractors with whitespace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = extractorArgs,
                onValueChange = { extractorArgs = it; extractorArgsSaved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("e.g. youtube:player_client=android") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    GalleryDlPreferences.setExtractorArgs(context, extractorArgs)
                    extractorArgsSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save extractor arguments")
            }

            if (extractorArgsSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Extractor arguments saved", tint = SuccessGreen40)
            }
        }

        // Imported from YTDLnis's own advanced_preferences.xml (Format Sorting, User Agent
        // Header) — yt-dlp only, same reasoning as this screen's other two fields above.
        SettingsSection(title = "Format sort", icon = Icons.Outlined.FilterAlt) {
            Text(
                "Raw yt-dlp --format-sort syntax (e.g. \"codec:vp9,fps\"), applied after this app's own " +
                    "quality-cap and MP4-compatibility bias so it can still reorder or override them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formatSort,
                onValueChange = { formatSort = it; formatSortSaved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("e.g. codec:vp9,fps") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    GalleryDlPreferences.setFormatSort(context, formatSort)
                    formatSortSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save format sort")
            }

            if (formatSortSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Format sort saved", tint = SuccessGreen40)
            }
        }

        SettingsSection(title = "Custom headers", icon = Icons.Outlined.Terminal) {
            Text(
                "One \"Header-Name: value\" per line, sent on every yt-dlp download — overrides that " +
                    "header's own default (including User-Agent/Referer) rather than only adding new ones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = customHeaders,
                onValueChange = { customHeaders = it; customHeadersSaved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("e.g. Referer: https://example.com") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    GalleryDlPreferences.setCustomHeaders(context, customHeaders)
                    customHeadersSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save headers")
            }

            if (customHeadersSaved) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Headers saved", tint = SuccessGreen40)
            }
        }

        SettingsSection(title = "Debugging", icon = Icons.Outlined.Terminal) {
            IconToggleRow(
                icon = Icons.Outlined.Terminal,
                title = "Verbose logging",
                subtitle = "Logs all internal yt-dlp debug output for advanced troubleshooting.",
                checked = verboseLogging,
                onCheckedChange = {
                    verboseLogging = it
                    GalleryDlPreferences.setVerboseLogging(context, it)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookiesSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val cookiesFile = remember { java.io.File(context.filesDir, "cookies.txt") }

    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Same fix as IconToggleRow's own Switch — Compose's Switch doesn't call
    // performHapticFeedback internally, so this per-site toggle was the one Switch in Settings
    // still silent on tap.
    val haptics = LocalHapticFeedback.current
    var showBrowser by remember { mutableStateOf(false) }
    var extractedCookies by remember { mutableStateOf("") }
    // Deliberately empty, not seeded from whatever's already saved — this box is for pasting a
    // *new* cookies.txt in, not for displaying/re-editing what's already saved (that's what the
    // per-site table below is for). Reported live: pre-filling it with the current save meant this
    // one field alone could end up showing every cookie for every site as one giant wall of raw
    // text, duplicating what the table already shows more usefully.
    var pastedCookies by remember { mutableStateOf("") }
    var savedConfirmation by remember { mutableStateOf(false) }
    // Distinct from savedConfirmation, not just its negation — persist()/clearing pastedCookies
    // only happens on an actual successful parse now (see the Save cookies button below), so this
    // and savedConfirmation are never both true from the same click.
    var pasteError by remember { mutableStateOf<String?>(null) }
    // The real, on-disk cookies.txt is the one source of truth gallery-dl/yt-dlp actually read
    // (see GalleryDlListing.kt/DownloadWorker.kt) — SharedPreferences' own KEY_COOKIES is only ever
    // a same-content mirror written alongside it, kept for the raw-paste textbox's own persistence.
    // Reading the file fresh (not the mirror) for the parsed table below means it can never drift
    // out of sync with what a download actually uses, the way two independently-updated copies of
    // the same data always eventually can.
    var savedCookiesContent by remember {
        mutableStateOf(runCatching { cookiesFile.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty())
    }
    val parsedCookies = remember(savedCookiesContent) { parseCookiesFile(savedCookiesContent) }
    // Hoisted here (not local to the "Saved cookies" SettingsSection below, where this used to
    // live) since the confirm-delete sheet further down — a sibling of the SettingsSubScaffold call
    // this whole screen is built from, not nested inside it — needs it too, to describe what a
    // "Clear all" is about to remove.
    val cookieSites = remember(parsedCookies) { groupCookiesBySite(parsedCookies) }
    // Non-null while the confirm-delete sheet is up — both delete paths below (a single site's
    // Trash2 button, and the "Clear all" button) now go through this instead of calling persist()
    // straight from their own onClick, so neither can wipe a saved login from one stray tap with no
    // way back.
    var pendingDelete by remember { mutableStateOf<PendingCookieDelete?>(null) }
    // Non-null while a site's cookie viewer/editor sheet is open (tapping its row).
    var viewingSite by remember { mutableStateOf<SiteCookies?>(null) }
    // The per-site "use these cookies" toggle's own state — kept and used just like the real
    // save file above (read once, mutated in place, never re-read from disk mid-screen) since
    // this is the only place in the app that changes it.
    var disabledDomains by remember { mutableStateOf(GalleryDlPreferences.getDisabledCookieDomains(context)) }

    fun persist(content: String) {
        sharedPreferences.edit().putString(GalleryDlPreferences.KEY_COOKIES, content).apply()
        cookiesFile.writeText(content)
        savedCookiesContent = content
    }

    if (showBrowser) {
        CookieLoginDialog(
            loginUrl = "https://instagram.com",
            onDismiss = { showBrowser = false },
            onCookiesSaved = { merged ->
                extractedCookies = merged
                savedCookiesContent = merged
                showBrowser = false
            },
        )
    }

    SettingsSubScaffold(title = "Cookies & Login", topicIcon = Icons.Outlined.Lock, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Cookies", icon = Icons.Outlined.Lock) {
            Text(
                "Sign in through the built-in browser to unlock private/age-restricted content, or paste a cookies.txt below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // A site's own rate-limiting/anti-bot systems associate a session with the account
            // behind it, not just the device — a heavy download session with real personal cookies
            // risks a site-level restriction on that actual account, not just this app or a fresh
            // IP the way an unauthenticated 429 does (see the rate-limit tip on the Queue's own
            // ERRORED cards). A throwaway account sidesteps that entirely.
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Consider using a secondary/throwaway account rather than your primary one — heavy download activity risks a site-level restriction on the account behind the cookies, not just this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { showBrowser = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log in via built-in browser")
            }

            if (extractedCookies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cookies extracted successfully", tint = SuccessGreen40)
            }

            Spacer(Modifier.height(20.dp))
            Text("Or paste raw cookies manually", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = pastedCookies,
                onValueChange = { pastedCookies = it; pasteError = null },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                label = { Text("cookies.txt contents") },
                maxLines = 10,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    // Merges into whatever's already saved rather than replacing it wholesale — a
                    // manual paste is almost always meant to *add* a site's cookies (or refresh
                    // one), not wipe out every other site's already-saved login in the process.
                    // Works on the already-parsed rawLine form (not mergeNetscapeCookies, which
                    // rebuilds each line from scratch with a fixed 5-year expiry and TRUE/TRUE
                    // flags — fine for a browser-extracted cookie header with no real field values
                    // of its own, but would silently overwrite a *pasted* file's own genuine
                    // expiry/secure/subdomain flags) — only the touched registrable domains get
                    // replaced, everything else already saved is left exactly as it was.
                    val pastedParsed = parseCookiesFile(pastedCookies)
                    // parseCookiesFile() silently drops any line that isn't real tab-separated
                    // Netscape format (fewer than 7 fields) rather than throwing — the right call
                    // for skipping a comment/header line, but it means a paste in the wrong format
                    // entirely (an HTTP-header-style "name=value; name2=value2" string, a browser
                    // cookie-editor's own space-aligned export, ...) used to parse to an empty list
                    // and this button would still merge that into (no-op) the existing file and
                    // claim "Cookies saved and applied" regardless — reproduced live: pasted
                    // Instagram cookies that weren't real tab-separated Netscape rows disappeared
                    // with no error, and the very next thing the user saw was a false success
                    // message. Refusing to persist or clear the textbox (so the original paste is
                    // still there to fix/copy elsewhere) when nothing actually parsed turns that
                    // silent no-op into a real, actionable error instead.
                    if (pastedCookies.isNotBlank() && pastedParsed.isEmpty()) {
                        pasteError = "Couldn't find any valid cookies in that text — it needs to be real tab-separated Netscape format (domain, includeSubdomains, path, secure, expiry, name, value per line), not just \"name=value\" pairs."
                        savedConfirmation = false
                    } else {
                        val touchedDomains = pastedParsed.map { it.domain.removePrefix(".") }.toSet()
                        val keptExisting = parseCookiesFile(savedCookiesContent)
                            .filterNot { it.domain.removePrefix(".") in touchedDomains }
                        // parseCookiesFile() deliberately drops comment/header lines when parsing (so
                        // they don't get double-counted as fake cookies) — rebuilding purely from
                        // rawLine values without adding this back means the result can never carry the
                        // "# Netscape HTTP Cookie File" header gallery-dl/yt-dlp's own cookie-jar parser
                        // requires as the file's literal first line. Reproduced live: a save through
                        // this exact path produced a header-less file that both engines flatly rejected
                        // as "does not look like a Netscape format cookies file", failing every
                        // download outright regardless of whether that site even needed cookies.
                        val mergedText = (listOf("# Netscape HTTP Cookie File") + (keptExisting + pastedParsed).map { it.rawLine })
                            .joinToString("\n")
                        persist(mergedText)
                        pastedCookies = ""
                        pasteError = null
                        savedConfirmation = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save cookies")
            }

            if (savedConfirmation) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cookies saved and applied", tint = SuccessGreen40)
            }
            pasteError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // One row per *site*, not per individual cookie — a real login legitimately saves a dozen-
        // plus individual cookies together (sessionid, csrftoken, a device id, ...; see
        // groupCookiesBySite's own doc comment for why), and showing every single one as its own
        // row read as "the app is duplicating my cookies" rather than "this is what one login
        // actually consists of" (reported live). Grouped by registrable domain instead — Instagram
        // shows as one row regardless of how many individual cookies back that session, same for
        // Reddit, etc. — with a per-site delete that removes that whole group's cookies at once.
        // Still sourced from the real cookies.txt (see savedCookiesContent's own comment), so it
        // always reflects exactly what a download would actually send. (cookieSites itself is
        // declared up with parsedCookies, not here — see that declaration's own comment for why.)
        SettingsSection(title = "Saved cookies (${cookieSites.size})", icon = Icons.Outlined.List) {
            if (cookieSites.isEmpty()) {
                Text(
                    "No cookies saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                cookieSites.forEachIndexed { index, site ->
                    val enabled = site.rootDomain !in disabledDomains
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { viewingSite = site }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Delete stays at the lead position; Copy moved to sit right beside the
                        // toggle at the trailing end instead, next to the one other cookie-related
                        // action a user might reach for around the same time as flipping the switch.
                        IconButton(onClick = { pendingDelete = PendingCookieDelete.Site(site) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove ${site.label}'s cookies", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                site.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${site.cookies.size} cookie${if (site.cookies.size == 1) "" else "s"} · ${formatCookieExpiry(site.soonestExpiryEpochSeconds)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            val text = site.cookies.joinToString("\n") { it.rawLine }
                            scope.launch {
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("${site.label} cookies", text)))
                            }
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ${site.label}'s cookies", modifier = Modifier.size(18.dp))
                        }
                        // Kept, not deleted — a saved login the user just doesn't want *sent* right
                        // now (a stale account, testing anonymous behavior, ...) without losing it
                        // outright. Filters this site's cookies out of every download/preview from
                        // here on (see GalleryDlPreferences.filterCookiesByDisabledDomains and its
                        // call sites in DownloadWorker.kt/GalleryDlListing.kt) until switched back.
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                GalleryDlPreferences.setCookieDomainEnabled(context, site.rootDomain, checked)
                                disabledDomains = GalleryDlPreferences.getDisabledCookieDomains(context)
                            },
                            // Same fix as IconToggleRow's own Switch: the default unchecked thumb
                            // color is nearly invisible against the unchecked track in this theme
                            // — an off site (e.g. Instagram/Reddit above) read as a dead, unlabeled
                            // gray blob instead of a working control resting in its off position.
                            colors = SwitchDefaults.colors(
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                    if (index != cookieSites.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("cookies.txt", savedCookiesContent)))
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy all")
                    }
                    OutlinedButton(
                        onClick = { pendingDelete = PendingCookieDelete.All },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear all")
                    }
                }
            }
        }
    }

    viewingSite?.let { site ->
        SiteCookiesSheet(
            site = site,
            onCopy = { label, text ->
                scope.launch {
                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(label, text)))
                }
            },
            onSave = { editedValues ->
                // Only the edited cookies' lines change, and only their value field — everything
                // else in the file (other sites, flags, expiry) is written back exactly as it was.
                // Header re-added for the same reason as the Save/delete paths above.
                val updated = parsedCookies.map { cookie ->
                    editedValues[cookie]?.let { cookie.withValue(it) } ?: cookie
                }
                persist((listOf("# Netscape HTTP Cookie File") + updated.map { it.rawLine }).joinToString("\n"))
                viewingSite = null
            },
            onDismiss = { viewingSite = null },
        )
    }

    pendingDelete?.let { pending ->
        val (title, message) = when (pending) {
            is PendingCookieDelete.Site -> "Remove ${pending.site.label}?" to
                "This deletes ${pending.site.cookies.size} saved cookie${if (pending.site.cookies.size == 1) "" else "s"} for ${pending.site.label}. You'll need to sign in there again next time."
            PendingCookieDelete.All -> "Clear all cookies?" to
                "This deletes all ${parsedCookies.size} saved cookie${if (parsedCookies.size == 1) "" else "s"} across ${cookieSites.size} site${if (cookieSites.size == 1) "" else "s"}. You'll need to sign in again everywhere."
        }
        ConfirmDeleteSheet(
            title = title,
            message = message,
            confirmLabel = "Delete",
            onConfirm = {
                when (pending) {
                    is PendingCookieDelete.Site -> {
                        val toRemove = pending.site.cookies.toSet()
                        // Same header requirement as the Save button's own merge logic above —
                        // parseCookiesFile() strips comment/header lines when parsing, so
                        // rebuilding purely from the surviving cookies' rawLine values needs the
                        // "# Netscape HTTP Cookie File" header added back explicitly, or the
                        // result fails gallery-dl/yt-dlp's strict format check the same way.
                        val remaining = parsedCookies.filter { it !in toRemove }
                        val updated = (listOf("# Netscape HTTP Cookie File") + remaining.map { it.rawLine })
                            .joinToString("\n")
                        persist(updated)
                    }
                    PendingCookieDelete.All -> persist("")
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// Which delete action the confirm sheet is confirming — a single site's cookies (the per-row
// Trash2 button) or every saved cookie at once (the "Clear all" button). Both otherwise silently
// discarded a real signed-in session with no way back before this existed.
private sealed class PendingCookieDelete {
    data class Site(val site: SiteCookies) : PendingCookieDelete()
    data object All : PendingCookieDelete()
}

/** Real ModalBottomSheet (not an AlertDialog) so this matches the rest of the app's own sheet-first
 * interaction language (DownloadPreviewSheet, the size-limit picker above, ...) instead of
 * introducing the one dialog-shaped confirmation in an app that otherwise never uses one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmDeleteSheet(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(confirmLabel) }
            }
        }
    }
}

/** One row of a Netscape-format cookies.txt (the format gallery-dl/yt-dlp's --cookies flag reads —
 * see mergeNetscapeCookies' own doc comment for the field layout). [rawLine] is kept verbatim
 * (rather than reconstructed from the parsed fields) so deleting a cookie can just drop its exact
 * original line instead of risking a lossy round-trip through re-serialization. */
private data class ParsedCookie(
    val domain: String,
    val name: String,
    val expiryEpochSeconds: Long,
    val rawLine: String,
) {
    /** The value field — the 7th tab-separated field. Splitting the whole raw line (a leading
     * "#HttpOnly_" stays attached to the first field) with limit = 7 keeps a value that itself
     * contains tabs intact. */
    val value: String get() = rawLine.split("\t", limit = 7).getOrElse(6) { "" }

    /** Same line with only the value replaced. Tabs and line breaks are stripped from the new value
     * since either would corrupt the one-cookie-per-line, tab-separated format. */
    fun withValue(newValue: String): ParsedCookie {
        val fields = rawLine.split("\t", limit = 7).toMutableList()
        if (fields.size < 7) return this
        fields[6] = newValue.replace(Regex("[\\t\\r\\n]"), "")
        return copy(rawLine = fields.joinToString("\t"))
    }
}

/** Viewer/editor for one site's saved cookies: each cookie's name with its value in an editable
 * field and its own copy button, plus Copy all and Save. Save only reports the cookies whose value
 * actually changed; the caller rewrites just those lines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteCookiesSheet(
    site: SiteCookies,
    onCopy: (label: String, text: String) -> Unit,
    onSave: (Map<ParsedCookie, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val edits = remember(site) { mutableStateMapOf<ParsedCookie, String>() }
    fun currentValue(cookie: ParsedCookie) = edits[cookie] ?: cookie.value
    val changed = edits.filter { (cookie, value) -> value != cookie.value }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding()) {
            Text(site.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${site.cookies.size} cookie${if (site.cookies.size == 1) "" else "s"} · ${formatCookieExpiry(site.soonestExpiryEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                site.cookies.forEach { cookie ->
                    OutlinedTextField(
                        value = currentValue(cookie),
                        onValueChange = { edits[cookie] = it },
                        label = { Text(cookie.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium,
                        trailingIcon = {
                            IconButton(onClick = { onCopy("${cookie.name} cookie", currentValue(cookie)) }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ${cookie.name}", modifier = Modifier.size(18.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val text = site.cookies.joinToString("\n") { cookie ->
                            (edits[cookie]?.let { cookie.withValue(it) } ?: cookie).rawLine
                        }
                        onCopy("${site.label} cookies", text)
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy all")
                }
                Button(
                    onClick = { onSave(changed) },
                    enabled = changed.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

/** Real comment lines start with "#" and nothing else meaningful follows on that line; a
 * "#HttpOnly_"-prefixed line (a real convention plenty of cookies.txt exports — Chrome's own
 * cookie-export extensions among them — actually use) is NOT a comment despite the leading "#": the
 * rest of the line past that exact prefix is a genuine tab-separated cookie row, just one flagged
 * httpOnly. Reproduced live in this app's own "paste raw cookies" flow: without stripping that
 * prefix first, every httpOnly cookie in a real exported file silently vanished from the parsed
 * list, showing as if the file were mostly empty.
 *
 * Falls back to splitting on any run of whitespace (not just a literal tab) when a line doesn't
 * split into 7 real tab-separated fields — reproduced live with a genuine Cookie-Editor export
 * whose tabs had been silently collapsed to plain spaces somewhere in the copy/paste chain (a
 * viewer or app re-rendering the text, not something gallery-dl's own export ever did). Visually
 * indistinguishable from a real tab-separated file, and previously failed this parser entirely —
 * `limit = 7` keeps the 7th field (the cookie's value) intact as everything remaining, in case it
 * legitimately contains its own internal whitespace, rather than truncating it. The corresponding
 * ParsedCookie.rawLine is rebuilt with real tabs in this fallback branch — a big difference from
 * gallery-dl/yt-dlp's own `--cookies` parser (Python's http.cookiejar), which is strict and only
 * ever splits on literal tabs: persisting the original, space-only line as-is would have let this
 * *display* correctly in the app's own cookie list while still silently failing to actually
 * authenticate a single real download — worse than never fixing it, since it would look fixed. */
private fun parseCookiesFile(content: String): List<ParsedCookie> {
    return content.lineSequence().mapNotNull { rawLine ->
        val trimmed = rawLine.trimEnd('\r')
        if (trimmed.isBlank()) return@mapNotNull null
        val hadHttpOnlyPrefix = trimmed.startsWith("#HttpOnly_")
        val dataLine = trimmed.removePrefix("#HttpOnly_")
        if (dataLine.startsWith("#")) return@mapNotNull null
        var fields = dataLine.split("\t")
        var normalizedLine = rawLine
        if (fields.size < 7) {
            val whitespaceFields = dataLine.trim().split(Regex("\\s+"), limit = 7)
            if (whitespaceFields.size == 7) {
                fields = whitespaceFields
                normalizedLine = (if (hadHttpOnlyPrefix) "#HttpOnly_" else "") + whitespaceFields.joinToString("\t")
            }
        }
        if (fields.size < 7) return@mapNotNull null
        ParsedCookie(
            domain = fields[0],
            expiryEpochSeconds = fields[4].toLongOrNull() ?: 0L,
            name = fields[5],
            rawLine = normalizedLine,
        )
    }.toList()
}

/** One "site" worth of cookies for the grouped summary row — [cookies] keeps every individual
 * [ParsedCookie] that belongs to it (needed to actually delete them, and to compute
 * [soonestExpiryEpochSeconds]), while the row itself only ever shows [label] and a count. */
private data class SiteCookies(
    val label: String,
    // The raw registrable domain (e.g. "reddit.com"), distinct from [label] ("Reddit") — used as
    // the stable key for the per-site "use these cookies" toggle (GalleryDlPreferences' own
    // disabled-domains set), since a human-readable label is derived/cosmetic and shouldn't be
    // relied on as a persisted identity.
    val rootDomain: String,
    val cookies: List<ParsedCookie>,
) {
    // The soonest of the group's own expiries is what actually determines when this login first
    // needs refreshing — showing the *latest* one instead would understate how soon a session
    // might already be partly stale (a real login session's individual cookies don't all share one
    // expiry; some non-essential ones (display prefs, A/B-test bucketing) are often set to expire
    // far sooner than the actual session token itself, without meaning the session as a whole
    // isn't still good).
    val soonestExpiryEpochSeconds: Long = cookies
        .map { it.expiryEpochSeconds }
        .filter { it > 0L }
        .minOrNull() ?: 0L
}

/** Groups by *registrable* domain (the last two dot-separated labels — "www.reddit.com" and
 * ".reddit.com" both collapse to the same "reddit.com" group, same simplification VideoSiteRouter
 * itself already makes) rather than by the exact domain string each cookie's own line happens to
 * carry, since a single real login often spans a mix of exact-domain and subdomain-inclusive
 * ("TRUE" in the Netscape format's own includeSubdomains column) cookies for what's really one
 * site as far as a user setting up a login is concerned. Sorted by site label for a stable,
 * predictable display order rather than whatever order cookies.txt's own lines happen to be in. */
private fun groupCookiesBySite(cookies: List<ParsedCookie>): List<SiteCookies> {
    return cookies
        .groupBy { cookie ->
            val bare = cookie.domain.removePrefix(".")
            val labelParts = bare.split(".")
            if (labelParts.size <= 2) bare else labelParts.takeLast(2).joinToString(".")
        }
        .map { (rootDomain, group) -> SiteCookies(label = VideoSiteRouter.siteName("https://$rootDomain"), rootDomain = rootDomain, cookies = group) }
        .sortedBy { it.label.lowercase() }
}

private fun formatCookieExpiry(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Session"
    val millis = epochSeconds * 1000
    if (millis < System.currentTimeMillis()) return "Expired"
    return java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
}

/** Shared by the Cookies & Login settings screen and the Queue's per-download "Add cookies"
 * error-card action — a real navigable browser (URL bar, back/forward/refresh) starting at
 * [loginUrl] so the user can sign in normally, or browse anywhere else the site sends them
 * (an OAuth redirect, a "verify it's you" subdomain, ...) without getting stuck on one fixed
 * page. The single FAB extracts whatever CookieManager captured for the page currently on screen
 * and merges it into the saved cookies.txt (replacing only that site's prior lines, not the whole
 * file), then closes. */
@Composable
fun CookieLoginDialog(
    loginUrl: String,
    onDismiss: () -> Unit,
    onCookiesSaved: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var addressBarText by remember { mutableStateOf(loginUrl) }
    // The single source of truth for what's actually loaded — addressBarText tracks the user's
    // in-progress edits separately so typing a new URL doesn't fight with the WebView's own
    // onPageStarted/onPageFinished updates overwriting the field mid-edit.
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        // Dialog's default width policy caps the window well short of the screen (platform
        // "dialog" sizing), which is what was leaving the WebView inset with visible margins —
        // this makes the window itself the full screen instead of just the content inside it.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Forward")
                    }
                    OutlinedTextField(
                        value = addressBarText,
                        onValueChange = { addressBarText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        // shapes.small (8dp) — the spec's own text-field token; 24dp matched
                        // nothing on the shape scale.
                        shape = MaterialTheme.shapes.small,
                        trailingIcon = {
                            IconButton(onClick = {
                                val target = normalizeBrowserAddress(addressBarText)
                                currentUrl = target
                                webView?.loadUrl(target)
                            }) {
                                Icon(Icons.Outlined.ArrowCircleRight, contentDescription = "Go")
                            }
                        },
                    )
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload")
                    }
                }
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(4.dp))
                }

                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Force a Desktop Chrome User-Agent. Instagram's mobile site often sends intent:// redirects 
                                // to force opening their native app, which causes WebViews to go completely blank.
                                // The desktop site works flawlessly and doesn't try to deep-link you away.
                                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        // Block Android app intents (intent://) which crash the WebView into a blank screen
                                        if (url.startsWith("intent://") || url.startsWith("android-app://")) {
                                            return true
                                        }
                                        return super.shouldOverrideUrlLoading(view, request)
                                    }

                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
                                        if (url != null) {
                                            currentUrl = url
                                            addressBarText = url
                                        }
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        isLoading = false
                                        if (url != null) {
                                            currentUrl = url
                                            addressBarText = url
                                        }
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }
                                }
                                loadUrl(loginUrl)
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    ExtendedFloatingActionButton(
                        onClick = {
                            val host = runCatching { java.net.URI(currentUrl).host }.getOrNull()
                            val cookieHeader = CookieManager.getInstance().getCookie(currentUrl)
                            if (host != null && !cookieHeader.isNullOrBlank()) {
                                val sharedPreferences = context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                                val existing = sharedPreferences.getString(GalleryDlPreferences.KEY_COOKIES, "") ?: ""
                                val merged = mergeNetscapeCookies(existing, host, cookieHeader)
                                sharedPreferences.edit().putString(GalleryDlPreferences.KEY_COOKIES, merged).apply()
                                java.io.File(context.filesDir, "cookies.txt").writeText(merged)
                                onCookiesSaved(merged)
                            } else {
                                onDismiss()
                            }
                        },
                        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        text = { Text("Extract cookies") },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Turns whatever's typed in the address bar into a real URL to load — a bare host/domain gets
 * "https://" prefixed, anything else (no dot, contains a space, ...) is treated as a search query
 * instead of a broken navigation attempt. */
private fun normalizeBrowserAddress(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isBlank() -> "https://www.google.com"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        !trimmed.contains(' ') && trimmed.contains('.') && !trimmed.contains("://") ->
            "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}

/** CookieManager.getCookie() returns an HTTP-header-style string ("name1=value1; name2=value2"),
 * not the Netscape cookies.txt format gallery-dl/yt-dlp's --cookies flag actually parses (tab-
 * separated: domain, includeSubdomains, path, secure, expiry, name, value). Converts and merges
 * into [existing], dropping any prior lines for [host] first so re-extracting replaces rather than
 * duplicates/conflicts with them. */
// Not private — BrowserScreen's own "Extract cookies" action reuses this same conversion.
fun mergeNetscapeCookies(existing: String, host: String, cookieHeader: String): String {
    val domain = if (host.startsWith(".")) host else ".$host"
    val bareDomain = domain.removePrefix(".")
    // Five years out — CookieManager doesn't expose each cookie's real expiry, and a long-lived
    // session cookie being treated as farther in the future than it really is just means it stops
    // working when the site itself expires it, same as any other stale-cookie failure.
    val expiry = (System.currentTimeMillis() / 1000L) + 60L * 60 * 24 * 365 * 5
    val newLines = cookieHeader.split(";").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val name = pair.substring(0, idx).trim()
        val value = pair.substring(idx + 1).trim()
        if (name.isEmpty()) return@mapNotNull null
        "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
    }
    val keptExisting = existing.lineSequence()
        .filter { line -> line.isBlank() || line.startsWith("#") || !(line.startsWith(domain) || line.startsWith(bareDomain)) }
        .toList()
    val header = if (keptExisting.any { it.startsWith("# Netscape") }) emptyList() else listOf("# Netscape HTTP Cookie File")
    return (header + keptExisting + newLines).joinToString("\n").trim()
}

/** The app's own update check plus the download engines' (yt-dlp, gallery-dl, Instaloader) —
 * moved out of About into their own page so updating isn't buried under version info and credits. */
@Composable
private fun UpdatesSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    SettingsSubScaffold(title = "Updates", topicIcon = Icons.Outlined.Update, onBack = onBack, highlightKey = highlightKey) {
        AppUpdateSection()
        EnginesSection()
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    SettingsSubScaffold(title = "About", topicIcon = Icons.Outlined.Info, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "App", icon = Icons.Outlined.Info) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // painterResource() can't load mipmap-anydpi-v26/ic_launcher.xml directly (an
                // AdaptiveIconDrawable, not a plain vector/raster) — composed by hand here from
                // the same two layers instead: ic_launcher_background.xml (a plain white rect,
                // approximated directly rather than parsed) behind ic_launcher_foreground.xml,
                // the actual wordmark. This used to be a separate static ic_app_logo.png export
                // that silently drifted out of sync with the real launcher icon once it changed
                // (reported live: "still using the old app icon") — rendering the *same* vector
                // the launcher itself uses guarantees they can't drift again.
                // Colors come from the in-app theme, not @color/splash_icon (which the foreground
                // vector's own fill references): resources resolve against the *system* night
                // mode, so with the app's own Light/Dark setting overriding it the wordmark could
                // end up black-on-dark or the tile a stray white block. Light keeps the launcher's
                // own white tile and black wordmark; dark inverts to a raised dark tile.
                val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val tileColor = if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
                val logoColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color.Black
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(tileColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        imageVector = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(logoColor),
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Comfort", style = MaterialTheme.typography.titleSmall)
                    Text("Version $versionName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // gallery-dl was the only credit here before — a real gap for an app that's really built on
        // six separately-licensed open-source projects, not one: yt-dlp does just as much of the
        // actual downloading (see VideoSiteRouter), and PythonRuntime's own doc comment describes
        // running YTDLnis's published interpreter/curl_cffi build as a subprocess, FFmpeg
        // (FfmpegRuntime) merging video/audio, QuickJS (QuickJsRuntime) solving yt-dlp's JS
        // challenges, and aria2 (Aria2Runtime) doing real multi-connection downloads — none of them
        // previously credited or linked anywhere in the app.
        SettingsSection(title = "Credits", icon = Icons.Outlined.Link) {
            // gallery-dl, QuickJS, and aria2 have no usable real-logo asset (gallery-dl and QuickJS
            // ship no logo at all; aria2's only asset is a 16x16 favicon too small to read as
            // anything but a blob at chip size) — those three keep a generic Material icon.
            // FFmpeg's favicon and the Python community logo (credited project is literally
            // CPython, via YTDLnis's published interpreter build — see PythonRuntime's own doc
            // comment) are real, recognizable marks, so those two use their actual artwork instead.
            val credits = remember {
                listOf(
                    CreditEntry(icon = Icons.Outlined.Code, title = "gallery-dl", url = "https://github.com/mikf/gallery-dl"),
                    CreditEntry(icon = Icons.Outlined.Terminal, title = "yt-dlp", url = "https://github.com/yt-dlp/yt-dlp"),
                    CreditEntry(icon = FeatherIcons.Instagram, title = "Instaloader", url = "https://github.com/instaloader/instaloader"),
                    CreditEntry(iconRes = com.comfort.app.R.drawable.ic_credit_ffmpeg, title = "FFmpeg", url = "https://ffmpeg.org"),
                    CreditEntry(icon = Icons.Outlined.Memory, title = "QuickJS", url = "https://bellard.org/quickjs/"),
                    CreditEntry(iconRes = com.comfort.app.R.drawable.ic_credit_python, title = "Python runtime", url = "https://github.com/deniscerri/ytdlnis-packages"),
                    CreditEntry(icon = Icons.Outlined.Bolt, title = "aria2", url = "https://aria2.github.io/"),
                )
            }
            credits.chunked(2).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { entry -> CreditChip(entry, modifier = Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** App-itself equivalent of QuickEngineUpdateSection() just below — same "only ever appears as
 * news, not a permanent fixture" reasoning (renders nothing once there's nothing to report), just
 * checking AppUpdater's GitHub Releases instead of PyPI. Listed first (a whole app release is
 * arguably bigger news than an extractor-fix engine bump) but otherwise fully independent of it —
 * both can be visible at once if both happen to have something new. */
@Composable
private fun QuickAppUpdateSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AppUpdater.UpdateStatus?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val result = AppUpdater.check(context)
        status = result
        GalleryDlPreferences.setAppUpdateAvailable(context, result.updateAvailable)
        GalleryDlPreferences.setAppUpdateLastCheckMs(context, System.currentTimeMillis())
        AppUpdateSignal.hasUpdate = result.updateAvailable
    }

    val current = status
    if (current == null || !current.updateAvailable) return

    SettingsSection(title = "App update available", icon = Icons.Outlined.Download) {
        AppUpdateRow(
            status = current,
            downloading = downloading,
            onUpdate = {
                val url = current.downloadUrl ?: return@AppUpdateRow
                downloading = true
                errorText = null
                scope.launch {
                    val result = AppUpdater.downloadApk(context, url)
                    downloading = false
                    result.onSuccess { apk ->
                        if (AppUpdater.canInstall(context)) {
                            AppUpdater.installApk(context, apk)
                        } else {
                            errorText = "Allow installing from this app in the settings screen that just opened, then tap Update again."
                            AppUpdater.requestInstallPermission(context)
                        }
                    }
                    result.onFailure { e -> errorText = "Couldn't download update: ${e.message ?: "unknown error"}" }
                }
            },
        )
        if (errorText != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Persistent panel for Updates > this app's own update check — mirrors EnginesSection() below (a
 * persistent "Checking…"/"Up to date" utility panel, unlike QuickAppUpdateSection above which only
 * ever shows up as news) but for one thing, not a list. Always does its own fresh check regardless
 * of the cached flag/interval MainScreen's periodic one respects, same as EnginesSection. */
@Composable
private fun AppUpdateSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AppUpdater.UpdateStatus?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun runCheck() {
        checking = true
        errorText = null
        scope.launch {
            val result = AppUpdater.check(context)
            status = result
            checking = false
            GalleryDlPreferences.setAppUpdateAvailable(context, result.updateAvailable)
            GalleryDlPreferences.setAppUpdateLastCheckMs(context, System.currentTimeMillis())
            AppUpdateSignal.hasUpdate = result.updateAvailable
        }
    }

    LaunchedEffect(Unit) { runCheck() }

    SettingsSection(title = "App Update", icon = Icons.Outlined.Download) {
        val current = status
        if (current == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking for updates…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            AppUpdateRow(
                status = current,
                downloading = downloading,
                onUpdate = {
                    val url = current.downloadUrl ?: return@AppUpdateRow
                    downloading = true
                    errorText = null
                    scope.launch {
                        val result = AppUpdater.downloadApk(context, url)
                        downloading = false
                        result.onSuccess { apk ->
                            if (AppUpdater.canInstall(context)) {
                                AppUpdater.installApk(context, apk)
                            } else {
                                errorText = "Allow installing from this app in the settings screen that just opened, then tap Update again."
                                AppUpdater.requestInstallPermission(context)
                            }
                        }
                        result.onFailure { e -> errorText = "Couldn't download update: ${e.message ?: "unknown error"}" }
                    }
                },
            )
        }
        if (errorText != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { runCheck() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check for updates")
            }
        }
    }
}

@Composable
private fun AppUpdateRow(status: AppUpdater.UpdateStatus, downloading: Boolean, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Comfort", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "v${status.installedVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            downloading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            status.updateAvailable -> Button(
                onClick = onUpdate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Update to v${status.latestVersion}", style = MaterialTheme.typography.labelMedium) }
            status.latestVersion != null -> Text(
                "Up to date",
                style = MaterialTheme.typography.labelMedium,
                color = SuccessGreen40,
            )
            else -> Text(
                "Couldn't check",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact teaser on the Settings root list, so an available engine update is both visible and
 * actionable without drilling into Updates > Engines first — mirrors that section's own check/update
 * logic (see EnginesSection() below) but renders nothing at all when everything's already current,
 * rather than always showing a "Checking…"/"Up to date" panel the way the About page's version
 * does (appropriate there as a persistent utility panel; here it should only ever appear as news,
 * not a permanent fixture). */
@Composable
private fun QuickEngineUpdateSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<EngineUpdater.VersionStatus>?>(null) }
    // A set, not one engine: updates run concurrently, and a single slot made tapping a second
    // engine's Update look like it stopped the first (its spinner reverted to an Update button),
    // then the first to finish cleared the other's spinner too.
    var updatingEngines by remember { mutableStateOf(emptySet<String>()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        var result = EngineUpdater.checkAll(context)
        // Same auto-update behavior as MainScreen's own periodic check (Settings > Updates > Engines
        // > Auto-update, on by default) — opening Settings shouldn't need a manual tap either when
        // it's enabled.
        if (GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) {
            result = result.map { status ->
                if (status.updateAvailable && status.artifactUrl != null) {
                    val update = EngineUpdater.update(context, status)
                    update.getOrNull()?.let { newVersion -> status.copy(installedVersion = newVersion) } ?: status
                } else {
                    status
                }
            }
        }
        statuses = result
        val available = result.any { it.updateAvailable }
        GalleryDlPreferences.setEngineUpdateAvailable(context, available)
        GalleryDlPreferences.setEngineUpdateLastCheckMs(context, System.currentTimeMillis())
        EngineUpdateSignal.hasUpdate = available
    }

    val outdated = statuses?.filter { it.updateAvailable } ?: return
    if (outdated.isEmpty()) return

    SettingsSection(title = "Updates available", icon = Icons.Outlined.Refresh) {
        outdated.forEachIndexed { index, status ->
            EngineUpdateRow(
                status = status,
                updating = status.engine.packageDirName in updatingEngines,
                onUpdate = {
                    if (status.artifactUrl == null) return@EngineUpdateRow
                    updatingEngines = updatingEngines + status.engine.packageDirName
                    errorText = null
                    scope.launch {
                        val result = EngineUpdater.update(context, status)
                        updatingEngines = updatingEngines - status.engine.packageDirName
                        result.onSuccess { newVersion ->
                            val updated = statuses.orEmpty().map {
                                if (it.engine == status.engine) it.copy(installedVersion = newVersion) else it
                            }
                            statuses = updated
                            val available = updated.any { it.updateAvailable }
                            GalleryDlPreferences.setEngineUpdateAvailable(context, available)
                            EngineUpdateSignal.hasUpdate = available
                        }
                        result.onFailure { e ->
                            errorText = "Couldn't update ${status.engine.displayName}: ${e.message ?: "unknown error"}"
                        }
                    }
                },
            )
            if (index != outdated.lastIndex) Spacer(Modifier.height(12.dp))
        }
        if (errorText != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** yt-dlp and gallery-dl are bundled as static wheels (see PythonRuntime's own doc comment) that
 * only get refreshed when this app itself ships a new APK — but site extractors break against the
 * live site far more often than that. This lets either engine be updated independently, straight
 * from PyPI, without waiting on an app release — the in-app equivalent of yt-dlp's own `-U` flag. */
@Composable
private fun EnginesSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<EngineUpdater.VersionStatus>?>(null) }
    var checking by remember { mutableStateOf(false) }
    // A set, not one engine: updates run concurrently, and a single slot made tapping a second
    // engine's Update look like it stopped the first (its spinner reverted to an Update button),
    // then the first to finish cleared the other's spinner too.
    var updatingEngines by remember { mutableStateOf(emptySet<String>()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var autoUpdate by remember { mutableStateOf(GalleryDlPreferences.isAutoUpdateEnginesEnabled(context)) }
    var ytDlpChannel by remember { mutableStateOf(GalleryDlPreferences.getYtDlpUpdateChannel(context)) }
    var galleryDlChannel by remember { mutableStateOf(GalleryDlPreferences.getGalleryDlUpdateChannel(context)) }
    var instaloaderChannel by remember { mutableStateOf(GalleryDlPreferences.getInstaloaderUpdateChannel(context)) }

    fun runCheck() {
        checking = true
        errorText = null
        scope.launch {
            val result = EngineUpdater.checkAll(context)
            statuses = result
            checking = false
            // Refreshes both the persisted flag (survives process restart) and the live in-session
            // signal MainScreen's nav-bar badge reads directly — opening this screen and checking
            // here is itself a fresh signal, no reason to wait for the next auto-check interval, or
            // a tab switch, to clear/set it.
            val available = result.any { it.updateAvailable }
            GalleryDlPreferences.setEngineUpdateAvailable(context, available)
            GalleryDlPreferences.setEngineUpdateLastCheckMs(context, System.currentTimeMillis())
            EngineUpdateSignal.hasUpdate = available
        }
    }

    // Re-checks whenever a channel picker below flips — EngineUpdater.checkAll() reads the
    // channel preference itself, so switching from Stable to Nightly/Master needs a fresh check
    // against that new source before the row/button below reflect it, same as opening this screen
    // for the first time does.
    LaunchedEffect(ytDlpChannel, galleryDlChannel, instaloaderChannel) { runCheck() }

    SettingsSection(title = "Engines", icon = Icons.Outlined.Refresh) {
        IconToggleRow(
            icon = Icons.Outlined.SystemUpdateAlt,
            title = "Auto-update",
            subtitle = "Automatically installs newer yt-dlp, gallery-dl and Instaloader updates when found.",
            checked = autoUpdate,
            onCheckedChange = {
                autoUpdate = it
                GalleryDlPreferences.setAutoUpdateEnginesEnabled(context, it)
            },
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        val currentStatuses = statuses
        if (currentStatuses == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking for updates…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            currentStatuses.forEachIndexed { index, status ->
                EngineCard(
                    status = status,
                    channel = when (status.engine) {
                        EngineUpdater.YT_DLP -> ytDlpChannel
                        EngineUpdater.INSTALOADER -> instaloaderChannel
                        else -> galleryDlChannel
                    },
                    onChannelChange = { newChannel ->
                        when (status.engine) {
                            EngineUpdater.YT_DLP -> {
                                ytDlpChannel = newChannel
                                GalleryDlPreferences.setYtDlpUpdateChannel(context, newChannel)
                            }
                            EngineUpdater.INSTALOADER -> {
                                instaloaderChannel = newChannel
                                GalleryDlPreferences.setInstaloaderUpdateChannel(context, newChannel)
                            }
                            else -> {
                                galleryDlChannel = newChannel
                                GalleryDlPreferences.setGalleryDlUpdateChannel(context, newChannel)
                            }
                        }
                    },
                    updating = status.engine.packageDirName in updatingEngines,
                    onUpdate = {
                        if (status.artifactUrl == null) return@EngineCard
                        updatingEngines = updatingEngines + status.engine.packageDirName
                        errorText = null
                        scope.launch {
                            val result = EngineUpdater.update(context, status)
                            updatingEngines = updatingEngines - status.engine.packageDirName
                            result.onSuccess { newVersion ->
                                // The live list, not the currentStatuses snapshot from when Update was
                                // tapped — with two updates in flight, writing back that stale copy let
                                // whichever finished second erase the first one's new version.
                                val updated = statuses.orEmpty().map {
                                    if (it.engine == status.engine) it.copy(installedVersion = newVersion) else it
                                }
                                statuses = updated
                                val available = updated.any { it.updateAvailable }
                                GalleryDlPreferences.setEngineUpdateAvailable(context, available)
                                EngineUpdateSignal.hasUpdate = available
                            }
                            result.onFailure { e ->
                                errorText = "Couldn't update ${status.engine.displayName}: ${e.message ?: "unknown error"}"
                            }
                        }
                    },
                )
                if (index != currentStatuses.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        if (errorText != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { runCheck() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check for updates")
            }
        }
    }
}

/** Plain single-row status, no card/channel-picker — used only by QuickEngineUpdateSection's
 * compact "Updates available" notice elsewhere in Settings, which lists just the outdated engines
 * with nothing else to configure. EngineCard below is the richer version for the About page's own
 * Engines section, which needs the channel picker too. */
@Composable
private fun EngineUpdateRow(status: EngineUpdater.VersionStatus, updating: Boolean, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(status.engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                status.installedVersion?.let { "v$it" } ?: "Version unknown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            updating -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            status.updateAvailable -> Button(
                onClick = onUpdate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Update to ${status.latestVersion}", style = MaterialTheme.typography.labelMedium) }
            status.latestVersion != null -> Text(
                "Up to date",
                style = MaterialTheme.typography.labelMedium,
                color = SuccessGreen40,
            )
            else -> Text(
                "Couldn't check",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One engine's own card inside the Engines section — previously a single flat row shared by both
 * engines with the channel picker chips stacked loosely above it, easy to misread as one control
 * block rather than two separate engines. Giving each its own icon+name+version header (plus the
 * channel picker underneath, now clearly scoped to *this* card) makes the yt-dlp/gallery-dl split
 * visually obvious at a glance instead of requiring reading the row text to tell them apart. */
@Composable
private fun EngineCard(
    status: EngineUpdater.VersionStatus,
    channel: EngineUpdateChannel,
    onChannelChange: (EngineUpdateChannel) -> Unit,
    updating: Boolean,
    onUpdate: () -> Unit,
) {
    val icon = when (status.engine) {
        EngineUpdater.YT_DLP -> Icons.Outlined.Terminal
        EngineUpdater.INSTALOADER -> FeatherIcons.Instagram
        else -> Icons.Outlined.Image
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.engine.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        status.installedVersion?.let { "v$it" } ?: "Version unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    updating -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    status.updateAvailable -> Button(
                        onClick = onUpdate,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("Update to ${status.latestVersion}", style = MaterialTheme.typography.labelMedium) }
                    status.latestVersion != null -> Surface(
                        shape = MaterialTheme.shapes.small,
                        color = SuccessGreen40.copy(alpha = 0.15f),
                    ) {
                        Text(
                            "Up to date",
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen40,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    else -> Text(
                        "Couldn't check",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            EngineChannelPicker(engine = status.engine, channel = channel, onChannelChange = onChannelChange)
        }
    }
}

/** Same 2-option Surface-chip-row picker as ProcessingSettingsScreen's own Output format picker
 * (see that screen's own comment on the pattern) — one per engine, its label for the non-Stable
 * option differing per engine (see EngineUpdater's own doc comment on why gallery-dl's is "Master"
 * rather than "Nightly": it has no nightly build, only its live Master branch source). */
@Composable
private fun EngineChannelPicker(
    engine: EngineUpdater.EngineInfo,
    channel: EngineUpdateChannel,
    onChannelChange: (EngineUpdateChannel) -> Unit,
) {
    val bleedingEdgeLabel = if (engine == EngineUpdater.YT_DLP) "Nightly" else "Master"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            EngineUpdateChannel.STABLE to "Stable",
            EngineUpdateChannel.BLEEDING_EDGE to bleedingEdgeLabel,
        ).forEach { (value, label) ->
            val selected = channel == value
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { onChannelChange(value) },
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Exactly one of icon/iconRes is set per entry — a plain Material icon for projects with no real
// logo asset, or a bundled drawable (see the Credits section's own comment) for the ones that have one.
private data class CreditEntry(
    val title: String,
    val url: String,
    val icon: ImageVector? = null,
    @androidx.annotation.DrawableRes val iconRes: Int? = null,
)

// 2-column chip grid for the About screen's Credits section — replaced a plain vertical list of
// icon+title+raw-URL rows (one credit per line, URL spelled out underneath) with a denser card
// grid: the URL itself was never useful at a glance (nobody reads out a github.com URL), just an
// affordance to tap through, so it's dropped from the visible chip and only used as the tap target.
@Composable
private fun CreditChip(entry: CreditEntry, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = modifier
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.iconRes != null) {
                Image(
                    androidx.compose.ui.res.painterResource(entry.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    entry.icon!!,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().then(highlightRowModifier(title)),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

// A toggle row with a leading icon-in-a-circle, matching SettingsListRow's top-level style —
// used for every individual switch setting within a section so the per-row icon convention holds
// at both levels, not just the top-level Appearance/Downloads/Advanced/... list.
@Composable
private fun IconToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Compose's own Switch (unlike the platform's View-based SwitchCompat) doesn't call
    // performHapticFeedback internally at all — checked directly against this app's bundled
    // material3 1.5.0-alpha18 SwitchKt.class, no HapticFeedback reference anywhere in it. Every
    // toggle in Settings felt inert on tap without this.
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().then(highlightRowModifier(title)).padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No tinted circle behind this any more. onSurfaceVariant, not the bare onSurface the
        // circle version used — full onSurface is meant for primary content (titles/body text),
        // not a supporting row icon with nothing behind it to soften the contrast.
        // Several call sites pass a different icon depending on `checked` (Wifi/WifiOff, Speed/
        // Speed2, ...) — crossfade + scale pop between the two instead of a hard cut, so flipping
        // the switch reads as the icon itself changing state, not a jarring swap.
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(initialScale = 0.6f, animationSpec = tween(200)))
                    .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 0.6f, animationSpec = tween(150)))
            },
            label = "toggle-row-icon",
        ) { animatedIcon ->
            Icon(animatedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onCheckedChange(it)
            },
            // Default unchecked thumb color reads as near-invisible against the unchecked track
            // in this theme — an off toggle looked like a flat, dead pill rather than a working
            // control resting in its off position. onSurfaceVariant/surfaceVariant is M3's own
            // "always contrasts against its matching surface" pairing, so the thumb stays clearly
            // visible against the track regardless of light/dark theme — a plain alpha-dimmed
            // color wasn't enough contrast in this app's dark theme specifically.
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** What tapping the Sharesheet's default "Configure" entry does — mirrors the three ways of
 * sharing into the app: "Configure" (open the picker/preview sheet), "Instant" (download right
 * away, same as picking the Sharesheet's own separate "Instant" entry), or "Always ask" (a small
 * sheet asking which, per share). A 3-segment chip row (same connected-group shape as the Queue
 * card's Pause/Cancel chips) rather than a Switch — this replaced a plain on/off toggle once a
 * genuine third option (Always ask) existed that a boolean couldn't represent. */
@Composable
private fun ShareModeRow(mode: com.comfort.app.data.ShareMode, onModeChange: (com.comfort.app.data.ShareMode) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Text("Sharing mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "What the Sharesheet's default \"Configure\" entry does. Its separate \"Instant\" entry always downloads right away.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val options = com.comfort.app.data.ShareMode.entries
        // Same connected-group treatment as the Library toolbar row's chips (Sort/Deleted/
        // Duplicates/Audio) — near-touching (2dp) with rounded outer ends/square inner corners,
        // not the wider 8dp-gap/uniform-shape look tried first.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { index, option ->
                val interactionSource = remember { MutableInteractionSource() }
                FilterChip(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    interactionSource = interactionSource,
                    shape = rememberMorphingChipShape(index, options.size, selected = mode == option, interactionSource = interactionSource, height = 40.dp),
                    label = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(option.label, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    border = null,
                )
            }
        }
    }
}

/** An integer-valued Material 3 slider for a Settings row — [label] renders the current value
 * above the track (e.g. "4 at once"), snapping to whole numbers only (one step per integer in
 * [valueRange]). Used for Concurrent downloads/fragments and Retries, replacing what used to be a
 * fixed row of preset chips — a slider covers the whole range continuously instead of only the
 * handful of values a chip row could fit. */
@Composable
private fun SettingsSlider(
    value: Int,
    valueRange: IntRange,
    label: (Int) -> String,
    onValueChange: (Int) -> Unit,
) {
    // A stored value can sit below valueRange.first — e.g. a fresh install's concurrentFragments
    // defaults to 1, but this slider (shown only while its own toggle is on) starts at 2, see the
    // Concurrent downloads/fragments call sites' own comments. Corrected immediately (not just
    // displayed clamped) so the actually-applied setting always matches what the slider shows —
    // without this, the label could read "2 at once" while the real stored value stayed 1, same
    // as toggle-off, silently doing nothing.
    LaunchedEffect(value, valueRange) {
        if (value !in valueRange) onValueChange(valueRange.first)
    }
    val displayValue = value.coerceIn(valueRange.first, valueRange.last)
    Text(
        label(displayValue),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    // Same gap as the Switch/toggle fixes above — Slider doesn't call performHapticFeedback
    // internally either. SegmentTick (not ToggleOn/Off) is the fitting one here: this is a
    // stepped, snap-to-integer slider, not a two-state control. Fired only on an actual step
    // change (tracked via lastTick), not on every pixel of drag the way a naive onValueChange
    // hook would — a continuous drag across a wide range would otherwise buzz constantly instead
    // of ticking once per whole number.
    val haptics = LocalHapticFeedback.current
    var lastTick by remember { mutableStateOf(displayValue) }
    Slider(
        value = displayValue.toFloat(),
        onValueChange = {
            val rounded = it.roundToInt().coerceIn(valueRange.first, valueRange.last)
            if (rounded != lastTick) {
                lastTick = rounded
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            onValueChange(rounded)
        },
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A compact "override the shared download location for just this media type" row — Audio
 * folder/Video folder in Settings > Downloads. Unset (the default) means whatever
 * [saveMediaToGallery]'s own fallback chain resolves to for that type; only shown as a name once
 * actually set, matching the shared Download location row's own "only show Use default once
 * there's something to reset" pattern. */
@Composable
private fun FolderOverrideRow(
    label: String,
    fallbackDescription: String,
    uri: android.net.Uri?,
    onChoose: () -> Unit,
    onUseDefault: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val name = remember(uri) {
        uri?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() }
    }
    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        if (name != null) "Saving to \"$name\"." else "Not set — $fallbackDescription",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onChoose,
            modifier = Modifier.weight(1f).height(50.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose folder")
        }
        if (name != null) {
            OutlinedButton(
                onClick = onUseDefault,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Use default")
            }
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodySmall)
    }
}


// (label, suffix) pairs, e.g. "KB/s" -> "k". Order determines the trailing toggle's cycle order.
private val SPEED_UNITS = listOf("KB/s" to "k", "MB/s" to "m")
private val FILESIZE_UNITS = listOf("KB" to "k", "MB" to "m", "GB" to "g")

/** A field that opens a bottom sheet to edit a "number + suffix letter" value (e.g. "500k",
 * "2M") — the string format both Speed limit and Max file size share, and that both engines'
 * own config parsers accept directly. Blank/zero numeric input means unlimited, matching both
 * preferences' own convention. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizeSheetField(
    modifier: Modifier = Modifier,
    label: String,
    currentValue: String,
    units: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    val displayValue = if (currentValue.isBlank()) "Unlimited" else {
        val num = currentValue.filter { it.isDigit() || it == '.' }
        val suffix = currentValue.filter { it.isLetter() }.lowercase()
        val unitLabel = units.firstOrNull { it.second == suffix }?.first ?: units[0].first
        "$num $unitLabel"
    }

    OutlinedButton(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = { showSheet = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayValue, style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showSheet) {
        // Seeded once from the committed value when the sheet opens, then only ever written by
        // the fields below — re-deriving from currentValue on every recomposition would fight
        // whatever the user is mid-typing.
        var numberText by remember { mutableStateOf(currentValue.filter { it.isDigit() || it == '.' }) }
        var unitIndex by remember {
            mutableStateOf(
                units.indexOfFirst { (_, suffix) -> currentValue.trim().endsWith(suffix, ignoreCase = true) }
                    .takeIf { it >= 0 } ?: 0
            )
        }
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = numberText,
                    onValueChange = { numberText = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Unlimited") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(12.dp))
                // A plain chip row instead of a DropdownMenu — a popup nested inside a
                // ModalBottomSheet's own popup dismisses BOTH on tap (reproduced live: tapping
                // the unit dropdown closed the whole sheet instead of opening the menu), so this
                // sidesteps that Compose nested-popup bug entirely rather than working around it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    units.forEachIndexed { index, (unitLabel, _) ->
                        val selected = unitIndex == index
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            onClick = { unitIndex = index },
                        ) {
                            Box(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(unitLabel)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onValueChange(if (numberText.isBlank()) "" else "$numberText${units[unitIndex].second}")
                        showSheet = false
                    },
                ) {
                    Text("Done")
                }
            }
        }
    }
}


