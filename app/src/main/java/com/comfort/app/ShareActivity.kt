package com.comfort.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.EnqueueResult
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.theme.GalleryDLTheme
import com.comfort.app.theme.ThemePreferences
import com.comfort.app.ui.main.DownloadPreviewSheet
import com.comfort.app.ui.main.SharePickerScreen
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.ListingResult
import com.comfort.app.util.shouldUsePreviewSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHEET_ANIM_MS = 280

/** Handles a shared link as a translucent floating sheet instead of a normal Activity: to the
 * user it never looks like they left Twitter/Reddit/the browser they shared from — technically
 * this is still an Activity, but its window is fully transparent (see Theme.GalleryDL.Transparent)
 * so the calling app stays visible behind the bottom sheet.
 *
 * The dim-behind scrim is done at the native Android window level (FLAG_DIM_BEHIND + dimAmount)
 * rather than by drawing a semi-transparent Box in Compose — this is the same mechanism a normal
 * Android Dialog uses for its own scrim, so it's guaranteed to composite correctly regardless of
 * Compose internals. (Note: `adb shell screencap` renders this whole effect as solid black even
 * when it's genuinely working — verify visually on-device, not from an adb screenshot.) */
class ShareActivity : ComponentActivity() {
    // Own mutableStateOf, not a local val inside onCreate — onNewIntent (see its own override
    // below, and the manifest's launchMode="singleTask" that makes it actually fire) needs a way
    // to feed a fresh share into this same already-running instance so its Compose tree reacts to
    // it, rather than the new Intent just sitting unread against whatever was parsed the first
    // time onCreate ran.
    private var sharedUrls by mutableStateOf<List<String>>(emptyList())

    /** while(find()), not a single if — used to stop at the first match, so sharing a block of
     * text with two separate links (e.g. a text message with two TikTok URLs) silently discarded
     * the second one. Every match is collected the same way, in the order they appear in the
     * text. Shared by onCreate and onNewIntent (below) — a second share arriving while this
     * Activity is already open needs the exact same parse, not a copy that's quietly drifted. */
    private fun parseUrls(intent: Intent): List<String> {
        val sharedText = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)
            // Direct link taps (twitter.com, instagram.com, pixiv.net — see the manifest's
            // ACTION_VIEW intent-filter) arrive with the URL as the intent's data, not an extra.
            intent.action == Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val urls = mutableListOf<String>()
        if (sharedText != null) {
            val matcher = Patterns.WEB_URL.matcher(sharedText)
            while (matcher.find()) urls.add(sharedText.substring(matcher.start(), matcher.end()))
        }
        return urls
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Without this the window still defaults to decorFitsSystemWindows=true, meaning the
        // system reserves its own space for the nav bar regardless of this Activity's transparent
        // theme — the sheet's dim scrim only happened to reach the real nav bar by that automatic
        // reservation, not by this window actually drawing there. SharePickerScreen's own Scaffold
        // (inside the sheet below) already computes its bottom content padding from real
        // WindowInsets via its own default contentWindowInsets — it just had nothing to measure
        // while this window wasn't edge-to-edge, so its Download button sat right instead of
        // properly inset purely by luck. Nothing else needs to change for that button to stay
        // reachable once this is on; Scaffold's default already reserves the right space now that
        // there's a real inset for it to see.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        com.comfort.app.util.AppImageLoader.install(applicationContext)

        sharedUrls = parseUrls(intent)
        if (sharedUrls.isEmpty()) {
            finish()
            return
        }

        // Compose's root view sets its own opaque window background by default, which silently
        // overrides the theme's windowIsTranslucent/windowBackground=transparent — without this,
        // the sheet renders correctly but everything behind it is solid black instead of the
        // calling app showing through.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.4f)

        setContent {
            val context = LocalContext.current
            // Reads the same named theme/dynamic-color/pure-black selections MainActivity's own
            // GalleryDLTheme call does — this used to build its own plain MaterialTheme here with
            // only the hardcoded default light/dark ColorScheme, silently ignoring whichever named
            // theme (Expressive Purple, ...), dynamic color, or pure-black setting the user had
            // actually picked in Settings. Reproduced live: the share sheet's colors didn't match
            // the rest of the app at all once a non-default theme was selected.
            val themeMode = remember { ThemePreferences.getThemeMode(context) }
            val lightTheme = remember { ThemePreferences.getLightTheme(context) }
            val darkTheme = remember { ThemePreferences.getDarkTheme(context) }
            val pureBlack = remember { ThemePreferences.isPureBlack(context) }

            GalleryDLTheme(themeMode = themeMode, lightTheme = lightTheme, darkTheme = darkTheme, pureBlack = pureBlack) {
                val urls = sharedUrls
                Box(Modifier.fillMaxSize()) {
                    // Keyed on the current share itself — a second share landing on this same
                    // singleTask instance (see onNewIntent below) should start completely fresh,
                    // not resume whatever ShareRouter's own remembered listing state (or
                    // MultiLinkHandler/InstantShareHandler's own in-flight work) happened to be
                    // mid-way through for the *previous* share when this Activity was reused
                    // instead of recreated.
                    key(urls) {
                        when {
                            // Multiple links: the picker/preview sheets below are built around
                            // reviewing/filtering exactly one link's own gallery, and stacking one
                            // per link would be terrible UX — so this bypasses them (and the
                            // "Instant download" preference, which only ever gated whether *one*
                            // link's sheet appears) and just enqueues every link found.
                            urls.size > 1 -> MultiLinkHandler(urls = urls, onFinished = { finish() })
                            GalleryDlPreferences.isInstantShareEnabled(context) -> InstantShareHandler(
                                url = urls[0],
                                onFinished = { finish() },
                            )
                            else -> ShareRouter(url = urls[0], onFinished = { finish() })
                        }
                    }
                }
            }
        }
    }

    /** Fires because the manifest declares this Activity launchMode="singleTask" — without both
     * of those, a second share while this Activity is already open (or hasn't finished yet)
     * always creates a brand new instance instead of reusing this one, so this override would
     * simply never run. Reproduced live without it (well, without singleTask — this override
     * didn't exist yet to matter): a run of Library > Duplicates confirmation-sheet testing left
     * an instance of this Activity open and never dismissed, its own translucent/dimmed window
     * quietly compositing on top of whatever the user did next in a *completely different app*
     * (Instagram, then even a different Android user profile/Secure Folder) until the process was
     * force-stopped — a real, visible bug, not just a theoretical one. singleTask makes that class
     * of stray instance impossible in the first place (there's only ever one), and this override
     * is what makes a *repeat* share actually go somewhere instead of silently doing nothing once
     * that one instance already exists: parses the new Intent the exact same way onCreate did for
     * the first one, and feeds it into the same already-composed Compose tree via [sharedUrls] —
     * see its own key(urls) wrapper further up for why that safely starts fresh rather than
     * resuming whatever the *previous* share's own screen was mid-way through. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val urls = parseUrls(intent)
        if (urls.isEmpty()) {
            // Only close if nothing else is in flight — a URL-less intent (e.g. sharing a photo
            // with no link right after a real share) used to call finish() unconditionally here,
            // which tears down this Activity's composition and cancels whatever coroutine a
            // still-in-progress previous share (ShareRouter/InstantShareHandler/MultiLinkHandler)
            // was mid-suspend in — including its own enqueueDownload() call, silently dropping
            // that earlier, valid download. Only finish when there's genuinely nothing to protect.
            if (sharedUrls.isEmpty()) finish()
            return
        }
        sharedUrls = urls
    }
}

/** Enqueues every one of [urls] the same direct way Instant Share already handles a single link —
 * a duplicate here just folds into the summary Toast rather than getting its own Snackbar+action;
 * with several links at once there's no single obvious one to offer "Redownload" for, and the
 * Library > Duplicates entry each one still gets recorded into (see DownloadDispatcher.
 * enqueueDownload) is there for exactly this case. */
@Composable
private fun MultiLinkHandler(urls: List<String>, onFinished: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(urls) {
        var duplicateCount = 0
        urls.forEach { sharedUrl ->
            val result = DownloadDispatcher.enqueueDownload(context, sharedUrl, "Downloading from ${VideoSiteRouter.siteName(sharedUrl)}")
            if (result is EnqueueResult.Duplicate) duplicateCount++
        }
        val startedCount = urls.size - duplicateCount
        val message = when {
            duplicateCount == 0 -> "${urls.size} downloads started"
            startedCount == 0 -> "Already downloaded — see Library > Duplicates"
            else -> "$startedCount downloads started, $duplicateCount already downloaded"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onFinished()
    }
}

/** No sheet, no frame ever drawn — enqueues in the background and closes immediately, always,
 * duplicate or not. Instant Share's whole premise is "no interaction at all," so unlike the
 * screens below (which have a real Download button that can just say "Redownload" up front) a
 * duplicate here has nowhere to surface a choice to — it silently folds into
 * DownloadDispatcher.enqueueDownload's own skip-and-record (see EnqueueResult.Duplicate; Library
 * > Duplicates is the audit trail), same as MultiLinkHandler already does for several links at
 * once. */
@Composable
private fun InstantShareHandler(url: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(url) {
        val result = DownloadDispatcher.enqueueDownload(context, url, "Downloading from ${VideoSiteRouter.siteName(url)}")
        Toast.makeText(
            context,
            if (result is EnqueueResult.Duplicate) "Already downloaded — see Library > Duplicates" else "Download started",
            Toast.LENGTH_SHORT,
        ).show()
        onFinished()
    }
}

/** Runs the same listing pass SharePickerScreen itself would (see its own `preloadedResult` doc
 * comment) to decide, for *any* site — not a fixed host list — whether this share is a single
 * detected video, or a listing that contains multiple videos even mixed in with photos (an
 * Instagram carousel with two reels and a photo, say): either way DownloadPreviewSheet's
 * quality/trim/format/commands/filename controls are a much more useful landing spot than a
 * picker grid with nothing meaningful to pick between video items on. Downloading still proceeds
 * exactly like it already does for a single video — every item in the post, not just the videos,
 * still gets downloaded; this only changes which screen shows up, not what gets fetched. An
 * image-only listing, an unlistable link, or a genuine listing failure still falls through to the
 * existing SharePickerScreen item-picker unchanged, fed this same already-fetched result so it
 * isn't listed a second time. */
@Composable
private fun ShareRouter(url: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var listingResult by remember { mutableStateOf<ListingResult?>(null) }
    // Checked purely off the url — unlike the listing pass below, doesn't need to wait on it, so
    // LoadingSheet's own "Download now" button can already read "Redownload" the moment this
    // screen opens rather than only DownloadPreviewSheet (once listing resolves) being able to.
    var isDuplicate by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        listingResult = GalleryDlListing.listItems(context, url)
    }
    LaunchedEffect(url) {
        isDuplicate = DownloadDispatcher.isDuplicate(context, url)
    }

    val result = listingResult
    // See ListingResult.shouldUsePreviewSheet's own doc comment — shared with MainScreen's own
    // paste-a-link flow so the two never quietly drift into deciding this differently.
    val usePreviewSheet = result?.shouldUsePreviewSheet() == true

    when {
        result == null -> LoadingSheet(
            onDismiss = onFinished,
            isDuplicate = isDuplicate,
            // Detection (the listing pass above) can take a real few seconds on a slow/rate-
            // limited site — this lets an impatient share skip straight to a plain whole-URL
            // download instead of waiting it out, the same fire-and-forget shape Instant Share
            // itself already uses. forceDuplicate unconditionally true: the button already told
            // the user "Redownload" when isDuplicate is true, so tapping it is the confirmation —
            // no separate Snackbar+action needed to ask again.
            onDownloadNow = {
                scope.launch {
                    DownloadDispatcher.enqueueDownload(context, url, "Downloading from ${VideoSiteRouter.siteName(url)}", forceDuplicate = true)
                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                    onFinished()
                }
            },
        )
        usePreviewSheet -> DownloadPreviewSheet(
            url = url,
            onDismiss = onFinished,
            // Same forceDuplicate = true reasoning as LoadingSheet above — DownloadPreviewSheet's
            // own Download button already says "Redownload" when this url is a duplicate (its own
            // independent isDuplicate check), so this is never a surprise skip-past.
            onDownload = { options ->
                scope.launch {
                    DownloadDispatcher.enqueueDownload(
                        context = context,
                        url = url,
                        title = "Downloading from ${VideoSiteRouter.siteName(url)}",
                        videoQuality = options.quality,
                        clipRange = options.clipRange,
                        extraCommands = options.extraCommands,
                        outputFormat = options.outputFormat,
                        filenameTemplate = options.filenameTemplate,
                        saveThumbnail = options.saveThumbnail,
                        forceDuplicate = true,
                    )
                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                    onFinished()
                }
            },
        )
        else -> SharePickerSheet(url = url, preloadedResult = result, onFinished = onFinished)
    }
}

/** The translucent bottom-sheet shell shared by every state this Activity can show (loading,
 * SharePickerScreen's picker) — pulled out so the brief "detecting whether this is a single
 * video" window before ShareRouter picks a real screen doesn't flash a bare spinner floating with
 * no sheet/scrim at all, which reads as broken rather than "still loading." */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingSheet(onDismiss: () -> Unit, isDuplicate: Boolean, onDownloadNow: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    BackHandler { visible = false }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(SHEET_ANIM_MS.toLong())
            onDismiss()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { visible = false }
        )
        var dragOffsetPx by remember { mutableStateOf(0f) }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(SHEET_ANIM_MS), initialOffsetY = { it }),
            exit = slideOutVertically(tween(SHEET_ANIM_MS), targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, dragOffsetPx.roundToInt()) },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(280.dp).pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (dragOffsetPx > 300f) {
                                visible = false
                            } else {
                                dragOffsetPx = 0f
                            }
                        }
                    )
                },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Same wavy indicator + copy as SharePickerScreen's own LOADING state (see its
                    // ListingState.LOADING branch) — this sheet covers the exact same listing
                    // fetch, just before ShareRouter knows which real screen to hand off to, so it
                    // should read as a continuation of that same "looking at what's there" moment,
                    // not a different, unlabeled spinner.
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularWavyProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Looking at what's there…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Detection can take a real few seconds on a slow/rate-limited site — this
                    // skips straight to a plain whole-URL download instead of waiting it out.
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            onClick = onDownloadNow,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(FeatherIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isDuplicate) "Redownload" else "Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharePickerSheet(
    url: String,
    preloadedResult: ListingResult? = null,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    // Set the moment a download is actually kicked off — the exit-animation effect below awaits
    // it before finishing the Activity, so the "Download started"/"Already downloaded" Toast still
    // gets a moment to show even though the sheet itself has already slid away.
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) { visible = true }
    BackHandler { visible = false }

    // Waits for the exit animation to actually play before finishing the Activity, instead of
    // cutting the sheet away mid-slide.
    LaunchedEffect(visible) {
        if (!visible) {
            delay(SHEET_ANIM_MS.toLong())
            downloadJob?.join()
            onFinished()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Invisible tap-to-dismiss catcher over the dimmed (window-level) area above the sheet.
        // No .background() here — drawing anything here is exactly what broke transparency.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { visible = false }
        )

        var sheetHeight by remember { mutableStateOf(280.dp) }
        val animatedHeight by animateDpAsState(targetValue = sheetHeight, label = "sheetHeight")

        var dragOffsetPx by remember { mutableStateOf(0f) }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(SHEET_ANIM_MS), initialOffsetY = { it }),
            exit = slideOutVertically(tween(SHEET_ANIM_MS), targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, dragOffsetPx.roundToInt()) },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(animatedHeight).pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (dragOffsetPx > 300f) {
                                visible = false
                            } else {
                                dragOffsetPx = 0f
                            }
                        }
                    )
                },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                SharePickerScreen(
                    url = url,
                    onDismiss = { visible = false },
                    onDownload = { downloadUrl, itemFilter, totalItems, videoQuality, forceDuplicate ->
                        // SharePickerScreen's own "Download N"/"Redownload N" button now checks
                        // duplicate status against the exact (url, itemFilter) pair for the
                        // currently-selected items itself (DownloadDao.findActiveOrFinishedByUrl
                        // matches on both together, not url alone — a different item selection
                        // from the same gallery post is never treated as the same download) and
                        // relabels accordingly, so forceDuplicate here is just that same
                        // already-shown confirmation carried through, same reasoning as
                        // DownloadPreviewSheet/LoadingSheet's own Download buttons.
                        downloadJob = scope.launch {
                            DownloadDispatcher.enqueueDownload(
                                context, downloadUrl, "Downloading from ${VideoSiteRouter.siteName(downloadUrl)}",
                                itemFilter, totalItems, videoQuality, forceDuplicate = forceDuplicate,
                            )
                            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                        }
                        visible = false
                    },
                    onHeightChange = { sheetHeight = it },
                    preloadedResult = preloadedResult,
                )
            }
        }
    }
}
