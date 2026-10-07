package com.comfort.app.ui.main

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import compose.icons.feathericons.Instagram
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.theme.LocalThemeState
import com.comfort.app.theme.ThemeMode
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings: the root list, its search, and the route each subpage is reached by.

enum class SettingsRoute { ROOT, APPEARANCE, FOLDERS, DOWNLOADS, PROCESSING, ADVANCED, COOKIES, UPDATES, ABOUT }

private fun SettingsRoute.displayName(): String = when (this) {
    SettingsRoute.ROOT -> "Settings"
    SettingsRoute.APPEARANCE -> "Appearance"
    SettingsRoute.FOLDERS -> "Folders"
    SettingsRoute.DOWNLOADS -> "Downloads"
    SettingsRoute.PROCESSING -> "Processing"
    SettingsRoute.ADVANCED -> "Advanced"
    SettingsRoute.COOKIES -> "Cookies & Login"
    SettingsRoute.UPDATES -> "Updates and engines"
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
internal data class SubpageSearchEntry(val title: String, val subtitle: String, val route: SettingsRoute) {
    fun matches(query: String): Boolean =
        title.contains(query, ignoreCase = true) || subtitle.contains(query, ignoreCase = true)
}

// Hand-maintained index of every toggle/field across the settings sub-screens — title/subtitle
// text copied verbatim from each row's own composable further down (and AppearanceScreen.kt).
// Nothing generates this automatically (the screens are built ad hoc, not off one shared model
// those rows could be collected from), so keep it in sync by hand: add an entry here whenever a
// new settings row is added elsewhere, and update the text here if a row's own title/subtitle
// changes. Order doesn't matter — this is only ever filtered, never displayed as-is.
internal val SUBPAGE_SEARCH_INDEX = listOf(
    // Downloads
    SubpageSearchEntry("Sharing mode", "Configure, Instant, or Always ask — what Comfort does with a link shared to it.", SettingsRoute.DOWNLOADS),
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
    SubpageSearchEntry("Which engines run", "Turn yt-dlp, gallery-dl or Instaloader off, and choose which tries a link first.", SettingsRoute.UPDATES),
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
fun MoreScreen(
    route: SettingsRoute,
    highlightKey: String?,
    onNavigate: (SettingsRoute, String?) -> Unit,
    // False while MainScreen keeps this composed but hidden (see its keptTabs): the back gesture
    // must then belong to whatever is on screen, not a sub-page nobody can see.
    isVisible: Boolean = true,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // The one and only root settings list, always composed underneath: it's what a
        // sub-screen's back reveals, and simply the visible page on ROOT. A second copy used to
        // live inside the animated box below for ROOT, so every back threw this one away and built
        // a fresh list mid-transition (header not measured yet, so it jumped for a frame) — the
        // flicker at the end of the back animation (reported live). Same fix as MainScreen's Home.
        val back = rememberBackRevealState(enabled = isVisible && route != SettingsRoute.ROOT) {
            onNavigate(SettingsRoute.ROOT, null)
        }
        // Opening a sub-page from the root list plays the push (see animateEnter).
        val navigate: (SettingsRoute, String?) -> Unit = { target, key ->
            if (route == SettingsRoute.ROOT && target != SettingsRoute.ROOT) {
                back.animateEnter { onNavigate(target, key) }
            } else {
                onNavigate(target, key)
            }
        }
        Box(modifier = Modifier.fillMaxSize().predictiveBackBehind(back, active = route != SettingsRoute.ROOT)) {
            SettingsRootScreen(onNavigate = navigate)
        }
        // Each sub-page's own back button plays the same peel-away as the gesture, then returns.
        val onBack = { back.animateBack() }
        if (route != SettingsRoute.ROOT) Box(modifier = Modifier.fillMaxSize().predictiveBackReveal(back)) {
            when (route) {
                SettingsRoute.ROOT -> Unit
                SettingsRoute.APPEARANCE -> AppearanceScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.FOLDERS -> FoldersSettingsScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.DOWNLOADS -> DownloadsSettingsScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.PROCESSING -> ProcessingSettingsScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.ADVANCED -> AdvancedSettingsScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.COOKIES -> CookiesSettingsScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.UPDATES -> UpdatesSettingsScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.ABOUT -> AboutScreen(onBack = onBack, highlightKey = highlightKey)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRootScreen(onNavigate: (SettingsRoute, String?) -> Unit) {
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
            SettingsItemSpec(Icons.Outlined.Terminal, "Advanced", "Extra arguments for each engine", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.ADVANCED, null) },
            SettingsItemSpec(Icons.Outlined.Lock, "Cookies & Login", if (hasCookies) "Configured" else "Not set", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.COOKIES, null) },
            SettingsItemSpec(Icons.Outlined.Update, "Updates and engines", "App, yt-dlp, gallery-dl & Instaloader", SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.UPDATES, null) },
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
                            autoFocus = true,
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
fun PillSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search",
    modifier: Modifier = Modifier,
    // Focus the field and bring up the keyboard as soon as the bar appears — for a bar opened by
    // tapping a search icon, which otherwise needed a second tap on the field itself.
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (autoFocus) {
        LaunchedEffect(Unit) {
            // A frame first: the bar may still be entering (AnimatedContent), and focusing a node
            // that isn't attached yet throws.
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
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
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                // bodyLarge (16sp), MD3's own search-bar text size — bodyMedium read as too small
                // in a 56dp bar (reported live).
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).clearFocusOnKeyboardDismiss(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
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
internal fun SettingsListRow(
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
