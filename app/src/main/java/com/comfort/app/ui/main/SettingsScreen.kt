package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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

enum class SettingsRoute { ROOT, APPEARANCE, FOLDERS, DOWNLOADS, PROCESSING, ADVANCED, COOKIES, UPDATES, BACKUP, SUBSCRIPTIONS, ABOUT }

/** The page's name, as a string resource. */
@androidx.annotation.StringRes
private fun SettingsRoute.displayName(): Int = when (this) {
    SettingsRoute.ROOT -> R.string.backup_part_settings
    SettingsRoute.APPEARANCE -> R.string.appearance
    SettingsRoute.FOLDERS -> R.string.folders
    SettingsRoute.DOWNLOADS -> R.string.downloads
    SettingsRoute.PROCESSING -> R.string.processing
    SettingsRoute.ADVANCED -> R.string.advanced
    SettingsRoute.COOKIES -> R.string.cookies_login
    SettingsRoute.UPDATES -> R.string.updates_and_engines
    SettingsRoute.BACKUP -> R.string.backup_title
    SettingsRoute.SUBSCRIPTIONS -> R.string.subscriptions_title
    SettingsRoute.ABOUT -> R.string.about
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
    SettingsRoute.BACKUP -> Icons.Outlined.SettingsBackupRestore
    SettingsRoute.SUBSCRIPTIONS -> Icons.Outlined.Subscriptions
    SettingsRoute.ABOUT -> Icons.Outlined.Info
}

/** One individual row from inside a settings sub-screen — as opposed to SettingsItemSpec below,
 * which only covers the handful of top-level nav entries on the Settings root. Lets the search bar
 * match something like "TLS" or "aria2c" against a row buried three screens deep without that
 * screen's own composable ever running. */
internal data class SubpageSearchEntry(
    @androidx.annotation.StringRes val title: Int,
    @androidx.annotation.StringRes val subtitle: Int,
    val route: SettingsRoute,
) {
    /** Matched in the language shown: the same strings the rows themselves use. */
    fun matches(resources: android.content.res.Resources, query: String): Boolean =
        resources.getString(title).contains(query, ignoreCase = true) ||
            resources.getString(subtitle).contains(query, ignoreCase = true)
}

// Hand-maintained index of every toggle/field across the settings sub-screens — title/subtitle
// text copied verbatim from each row's own composable further down (and AppearanceScreen.kt).
// Nothing generates this automatically (the screens are built ad hoc, not off one shared model
// those rows could be collected from), so keep it in sync by hand: add an entry here whenever a
// new settings row is added elsewhere, and update the text here if a row's own title/subtitle
// changes. Order doesn't matter — this is only ever filtered, never displayed as-is.
internal val SUBPAGE_SEARCH_INDEX = listOf(
    // Downloads
    SubpageSearchEntry(R.string.sharing_mode, R.string.configure_instant_or_always_ask_what, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.multiple_concurrent_downloads, R.string.run_more_than_one_download_at, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.wi_fi_only, R.string.queued_downloads_wait_for_a_wi, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.speed_limit, R.string.caps_download_bandwidth_for_all_future, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.retries, R.string.how_many_times_a_failed_request, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.fragment_retries, R.string.how_many_times_a_failed_video, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.proxy, R.string.routes_all_future_downloads_through_this, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.force_ipv4, R.string.forces_connections_over_ipv4_try_this, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.skip_certificate_checks, R.string.disables_security_certificate_checks_only_enable, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.concurrent_fragments, R.string.how_many_fragments_of_a_single, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.sleep_interval, R.string.adds_a_random_delay_before_requests, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.socket_timeout, R.string.how_long_to_wait_on_a, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.buffer_size, R.string.the_size_of_each_read_chunk, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.multi_connection_downloads_aria2c, R.string.downloads_files_faster_by_splitting_them, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.restrict_to_time_window, R.string.new_downloads_wait_in_the_queue, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.use_alarm_for_scheduling, R.string.ensures_scheduled_downloads_start_exactly_on, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.limit_max_file_size, R.string.files_larger_than_this_are_skipped, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.download_delay, R.string.adds_a_delay_between_a_finished, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.incognito_by_default, R.string.downloads_are_still_saved_to_your, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.prevent_duplicate_downloads, R.string.skips_downloading_a_link_if_it, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.remember_last_quality, R.string.makes_the_quality_chosen_on_the, SettingsRoute.DOWNLOADS),
    SubpageSearchEntry(R.string.clean_up_leftover_downloads, R.string.automatically_deletes_partial_files_when_a, SettingsRoute.DOWNLOADS),

    // Folders
    SubpageSearchEntry(R.string.filename_format, R.string.how_downloaded_files_are_named, SettingsRoute.FOLDERS),
    SubpageSearchEntry(R.string.restrict_filenames, R.string.removes_special_characters_and_replaces_spaces, SettingsRoute.FOLDERS),
    SubpageSearchEntry(R.string.trim_filenames, R.string.caps_long_titles_at_150_characters, SettingsRoute.FOLDERS),
    SubpageSearchEntry(R.string.download_location, R.string.where_downloaded_files_are_saved, SettingsRoute.FOLDERS),
    SubpageSearchEntry(R.string.storage, R.string.how_much_space_downloads_are_using, SettingsRoute.FOLDERS),

    // Processing
    SubpageSearchEntry(R.string.single_video_only, R.string.only_downloads_the_specific_video_from, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.live_streams_from_the_start, R.string.downloads_live_streams_from_the_beginning, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.embed_thumbnail, R.string.save_the_video_s_thumbnail_as, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.embed_metadata, R.string.tag_the_file_with_its_title, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.embed_chapters, R.string.saves_chapter_markers_inside_the_video, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.split_chapters_title, R.string.saves_a_video_that_has_chapters, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.backup_section, R.string.save_your_settings_library_history_and, SettingsRoute.BACKUP),
    SubpageSearchEntry(R.string.restore_section, R.string.bring_back_settings_history_and_cookies, SettingsRoute.BACKUP),
    SubpageSearchEntry(R.string.command_templates_section, R.string.saved_sets_of_arguments_to_add, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.sponsorblock_section, R.string.marks_or_removes_sponsored_segments_in, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.audio_format, R.string.save_songs_and_audio_as_mp3, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.save_webp_pictures_as_jpg, R.string.converts_webp_pictures_to_jpg_as, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.even_out_volume, R.string.makes_every_song_play_at_the_2, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.lyrics, R.string.adds_synced_or_plain_lyrics_to, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.save_lrc_file, R.string.also_saves_synced_lyrics_as_a_2, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.write_description_info_json_files, R.string.save_a_separate_json_metadata_file, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.download_subtitles, R.string.fetch_and_embed_subtitles_when_they, SettingsRoute.PROCESSING),
    SubpageSearchEntry(R.string.save_subtitle_files, R.string.saves_subtitles_as_a_separate_file, SettingsRoute.PROCESSING),

    // Advanced
    SubpageSearchEntry(R.string.extra_arguments, R.string.raw_gallery_dl_command_line_arguments, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.rotate_player_clients, R.string.automatically_switches_between_android_ios_and, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.which_engines_run, R.string.turn_yt_dlp_gallery_dl_or, SettingsRoute.UPDATES),
    SubpageSearchEntry(R.string.impersonate_a_browser, R.string.makes_the_app_look_like_a, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.yt_dlp_extractor_arguments, R.string.site_specific_extractor_options_passed_straight, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.format_sort, R.string.custom_yt_dlp_format_selection_priority, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.custom_headers, R.string.extra_http_headers_sent_with_every, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.verbose_logging, R.string.logs_all_internal_yt_dlp_debug, SettingsRoute.ADVANCED),
    SubpageSearchEntry(R.string.logs_title, R.string.logs_description, SettingsRoute.ADVANCED),

    // Subscriptions
    SubpageSearchEntry(R.string.subscriptions_title, R.string.subscriptions_search_subtitle, SettingsRoute.SUBSCRIPTIONS),

    // Cookies & Login
    SubpageSearchEntry(R.string.cookies, R.string.sign_in_to_sites_that_require, SettingsRoute.COOKIES),
    SubpageSearchEntry(R.string.saved_cookies, R.string.sites_you_ve_already_signed_in, SettingsRoute.COOKIES),

    // Appearance
    SubpageSearchEntry(R.string.follow_system_theme, R.string.switch_between_your_light_and_dark, SettingsRoute.APPEARANCE),
    SubpageSearchEntry(R.string.pure_black_dark_mode, R.string.use_true_black_backgrounds_in_dark, SettingsRoute.APPEARANCE),
    SubpageSearchEntry(R.string.light_theme, R.string.pick_this_app_s_light_mode, SettingsRoute.APPEARANCE),
    SubpageSearchEntry(R.string.dark_theme, R.string.pick_this_app_s_dark_mode, SettingsRoute.APPEARANCE),

    // About
    SubpageSearchEntry(R.string.app_update_2, R.string.check_for_a_newer_version_of, SettingsRoute.UPDATES),
    SubpageSearchEntry(R.string.engines, R.string.update_yt_dlp_gallery_dl_and, SettingsRoute.UPDATES),
    SubpageSearchEntry(R.string.credits, R.string.gallery_dl_yt_dlp_instaloader_ffmpeg, SettingsRoute.ABOUT),
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
                SettingsRoute.BACKUP -> BackupSettingsScreen(onBack = onBack, highlightKey = highlightKey)
                SettingsRoute.SUBSCRIPTIONS -> SubscriptionsSettingsScreen(onBack = onBack, highlightKey = highlightKey)
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
    val themeSummary = stringResource(if (effectiveDark) themeState.darkTheme.darkLabel else themeState.lightTheme.lightLabel)
    var filenameFormat by remember { mutableStateOf(GalleryDlPreferences.getFilenameFormat(context)) }
    val cookieVersion by com.comfort.app.data.CookieStore.version.collectAsState()
    val hasCookies = remember(cookieVersion) { GalleryDlPreferences.getCookies(context).isNotBlank() }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == GalleryDlPreferences.KEY_FILENAME_FORMAT || key == null) {
                filenameFormat = GalleryDlPreferences.getFilenameFormat(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    BackHandler(enabled = searchExpanded) {
        searchExpanded = false
        searchQuery = ""
        keyboard?.hide()
    }
    val scrollState = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var maxHeaderHeightPx by remember(configuration.screenWidthDp, configuration.screenHeightDp, configuration.fontScale) { mutableStateOf(0) }
    // 4dp under the header (not the sub-pages' 20dp) plus the list's own 8dp top padding below —
    // the combined ~32dp gap between the title and the first card read as too loose (reported live).
    val headerState = rememberCollapsingHeaderState(scrollState, expandedTopPadding = 76.dp, expandedBottomPadding = 4.dp)
    // Split across Folders/Downloads/Processing (previously all one "Downloads" page) to match
    // YTDLnis's own settings shape — see gallery-dl.md's "Break up the Downloads settings page"
    // entry for why: one page covering filenames+folders+network+scheduling+quality+embedding all
    // at once had grown too long to scan.
    val backupTitle = androidx.compose.ui.res.stringResource(com.comfort.app.R.string.backup_title)
    val backupSubtitle = androidx.compose.ui.res.stringResource(com.comfort.app.R.string.backup_root_subtitle)
    // Not remembered: their text comes from resources, read while composing (a cheap list).
    val mainItems = run {
        listOf(
            SettingsItemSpec(Icons.Outlined.WbSunny, stringResource(R.string.appearance), themeSummary, SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.APPEARANCE, null) },
            SettingsItemSpec(Icons.Outlined.Folder, stringResource(R.string.folders), filenameFormat, SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.FOLDERS, null) },
            SettingsItemSpec(Icons.Outlined.Download, stringResource(R.string.downloads), stringResource(R.string.network_scheduling_and_queue_behavior), SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.DOWNLOADS, null) },
            SettingsItemSpec(Icons.Outlined.Movie, stringResource(R.string.processing), stringResource(R.string.quality_format_and_embedding), SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.PROCESSING, null) },
            SettingsItemSpec(Icons.Outlined.Terminal, stringResource(R.string.advanced), stringResource(R.string.extra_arguments_for_each_engine), SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.ADVANCED, null) },
            SettingsItemSpec(Icons.Outlined.Lock, stringResource(R.string.cookies_login), if (hasCookies) stringResource(R.string.configured) else stringResource(R.string.not_set), SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.COOKIES, null) },
            SettingsItemSpec(Icons.Outlined.Update, stringResource(R.string.updates_and_engines), stringResource(R.string.app_yt_dlp_gallery_dl_instaloader), SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.UPDATES, null) },
            SettingsItemSpec(Icons.Outlined.SettingsBackupRestore, backupTitle, backupSubtitle, SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.BACKUP, null) },
            SettingsItemSpec(Icons.Outlined.Subscriptions, stringResource(R.string.subscriptions_title), stringResource(R.string.subscriptions_root_subtitle), SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.SUBSCRIPTIONS, null) },
        )
    }
    val aboutItems = run {
        listOf(SettingsItemSpec(Icons.Outlined.Info, stringResource(R.string.about), stringResource(R.string.version_credits_source), SettingsItemColor.SURFACE_HIGH) { onNavigate(SettingsRoute.ABOUT, null) })
    }
    val filteredMainItems = mainItems.filter { it.matches(searchQuery) }
    val filteredAboutItems = aboutItems.filter { it.matches(searchQuery) }
    val isSearching = searchQuery.isNotBlank()
    val searchResources = androidx.compose.ui.platform.LocalContext.current.resources
    // Only computed while actually searching — SUBPAGE_SEARCH_INDEX.filter() over ~50 static
    // entries is cheap enough to just do inline (no remember needed), but there's no reason to pay
    // even that when the search bar is empty and this can never show anything anyway.
    val filteredSubpageResults = if (isSearching) SUBPAGE_SEARCH_INDEX.filter { it.matches(searchResources, searchQuery) } else emptyList()

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
                emptyMessage = stringResource(R.string.no_settings_match_x, searchQuery).takeIf {
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
                        val title = stringResource(entry.title)
                        SettingsItemSpec(entry.route.icon(), title, stringResource(R.string.in_x, stringResource(entry.route.displayName())), SettingsItemColor.SURFACE_HIGH) {
                            // The search field's keyboard would otherwise stay up over the page.
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onNavigate(entry.route, title)
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
                                keyboard?.hide()
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.close_search),
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
                            stringResource(R.string.backup_part_settings),
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
                                contentDescription = stringResource(R.string.search),
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
    placeholder: String = stringResource(R.string.search),
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
            // Same clear affordance as the home screen's own "Paste a link" field: only shown once
            // there's something to clear.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics { role = Role.Button },
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
