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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

        val sharedText = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)
            // Direct link taps (twitter.com, instagram.com, pixiv.net — see the manifest's
            // ACTION_VIEW intent-filter) arrive with the URL as the intent's data, not an extra.
            intent.action == Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        // while(find()), not a single if — used to stop at the first match, so sharing a block of
        // text with two separate links (e.g. a text message with two TikTok URLs) silently
        // discarded the second one. Every match is collected the same way, in the order they
        // appear in the text.
        val urls = mutableListOf<String>()
        if (sharedText != null) {
            val matcher = Patterns.WEB_URL.matcher(sharedText)
            while (matcher.find()) urls.add(sharedText.substring(matcher.start(), matcher.end()))
        }

        if (urls.isEmpty()) {
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
                // Shared by every path below so a duplicate detected *anywhere* — instant share, a
                // bare "Download now," the preview sheet, or a picked item — surfaces through the
                // same "Already downloaded — Redownload" Snackbar instead of each path needing its
                // own. Docked at the top: every one of this Activity's own sheets slides up from
                // the bottom, so this is the one place a Snackbar can sit without the two fighting
                // for the same screen region.
                val snackbarHostState = remember { SnackbarHostState() }
                Box(Modifier.fillMaxSize()) {
                    when {
                        // Multiple links: the picker/preview sheets below are built around
                        // reviewing/filtering exactly one link's own gallery, and stacking one per
                        // link would be terrible UX — so this bypasses them (and the "Instant
                        // download" preference, which only ever gated whether *one* link's sheet
                        // appears) and just enqueues every link found.
                        urls.size > 1 -> MultiLinkHandler(urls = urls, onFinished = { finish() })
                        GalleryDlPreferences.isInstantShareEnabled(context) -> InstantShareHandler(
                            url = urls[0],
                            snackbarHostState = snackbarHostState,
                            onFinished = { finish() },
                        )
                        else -> ShareRouter(url = urls[0], snackbarHostState = snackbarHostState, onFinished = { finish() })
                    }
                    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
                }
            }
        }
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

/** No sheet, no frame ever drawn for the common case — enqueues in the background and closes
 * immediately, same as before this existed. Only a genuine duplicate keeps the (fully transparent,
 * dimmed) window open long enough to show the Snackbar and its "Redownload" action. */
@Composable
private fun InstantShareHandler(url: String, snackbarHostState: SnackbarHostState, onFinished: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(url) {
        enqueueAndHandleDuplicate(context, snackbarHostState, url, "Downloading from ${VideoSiteRouter.siteName(url)}")
        onFinished()
    }
}

/** Runs the same listing pass SharePickerScreen itself would (see its own `preloadedResult` doc
 * comment) to decide, for *any* site — not a fixed host list — whether this share is a single
 * detected video: if so, DownloadPreviewSheet (quality/trim/format/commands/filename) is a much
 * more useful landing spot than a picker grid with nothing to pick between. Anything else (a real
 * multi-item gallery, an image-only post, an unlistable link, a genuine listing failure) falls
 * through to the existing SharePickerScreen item-picker unchanged, fed this same already-fetched
 * result so it isn't listed a second time. */
@Composable
private fun ShareRouter(url: String, snackbarHostState: SnackbarHostState, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var listingResult by remember { mutableStateOf<ListingResult?>(null) }

    LaunchedEffect(url) {
        listingResult = GalleryDlListing.listItems(context, url)
    }

    val result = listingResult
    val singleVideoItem = result != null && result.items.size == 1 &&
        result.items[0].filename?.let(VideoSiteRouter::isVideoFilename) == true

    when {
        result == null -> LoadingSheet(
            onDismiss = onFinished,
            // Detection (the listing pass above) can take a real few seconds on a slow/rate-
            // limited site — this lets an impatient share skip straight to a plain whole-URL
            // download instead of waiting it out, the same fire-and-forget shape Instant Share
            // itself already uses.
            onDownloadNow = {
                scope.launch {
                    enqueueAndHandleDuplicate(context, snackbarHostState, url, "Downloading from ${VideoSiteRouter.siteName(url)}")
                    onFinished()
                }
            },
        )
        singleVideoItem -> DownloadPreviewSheet(
            url = url,
            onDismiss = onFinished,
            onDownload = { options ->
                scope.launch {
                    enqueueAndHandleDuplicate(
                        context = context,
                        snackbarHostState = snackbarHostState,
                        url = url,
                        title = "Downloading from ${VideoSiteRouter.siteName(url)}",
                        videoQuality = options.quality,
                        clipRange = options.clipRange,
                        extraCommands = options.extraCommands,
                        outputFormat = options.outputFormat,
                        filenameTemplate = options.filenameTemplate,
                        saveThumbnail = options.saveThumbnail,
                    )
                    onFinished()
                }
            },
        )
        else -> SharePickerSheet(url = url, preloadedResult = result, snackbarHostState = snackbarHostState, onFinished = onFinished)
    }
}

/** Shared by every download-triggering action in this Activity (instant share, "Download now,"
 * the preview sheet, a picked share-sheet item) — enqueues, and if [DownloadDispatcher.
 * enqueueDownload] reports it's a duplicate (see EnqueueResult), shows a Snackbar with a
 * "Redownload" action that bypasses the check for just this one deliberate retry
 * (forceDuplicate = true) instead of silently doing nothing with no way back short of digging it
 * up in Library > Duplicates. A plain Toast covers the ordinary non-duplicate case, same as
 * before any of this existed. */
private suspend fun enqueueAndHandleDuplicate(
    context: android.content.Context,
    snackbarHostState: SnackbarHostState,
    url: String,
    title: String,
    itemFilter: String? = null,
    totalItems: Int = 0,
    videoQuality: com.comfort.app.data.VideoQuality? = null,
    clipRange: String? = null,
    extraCommands: String? = null,
    outputFormat: com.comfort.app.data.OutputFormat? = null,
    filenameTemplate: String? = null,
    saveThumbnail: Boolean? = null,
) {
    val result = DownloadDispatcher.enqueueDownload(
        context, url, title, itemFilter, totalItems, videoQuality, clipRange,
        extraCommands, outputFormat, filenameTemplate, saveThumbnail,
    )
    if (result is EnqueueResult.Duplicate) {
        val action = snackbarHostState.showSnackbar(
            message = "Already downloaded",
            actionLabel = "Redownload",
            duration = SnackbarDuration.Long,
        )
        if (action == SnackbarResult.ActionPerformed) {
            DownloadDispatcher.enqueueDownload(
                context, url, title, itemFilter, totalItems, videoQuality, clipRange,
                extraCommands, outputFormat, filenameTemplate, saveThumbnail,
                forceDuplicate = true,
            )
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
    }
}

/** The translucent bottom-sheet shell shared by every state this Activity can show (loading,
 * SharePickerScreen's picker) — pulled out so the brief "detecting whether this is a single
 * video" window before ShareRouter picks a real screen doesn't flash a bare spinner floating with
 * no sheet/scrim at all, which reads as broken rather than "still loading." */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingSheet(onDismiss: () -> Unit, onDownloadNow: () -> Unit) {
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
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(SHEET_ANIM_MS), initialOffsetY = { it }),
            exit = slideOutVertically(tween(SHEET_ANIM_MS), targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(280.dp),
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
                            Text("Download")
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
    snackbarHostState: SnackbarHostState,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    // Set the moment a download is actually kicked off — the exit-animation effect below awaits
    // it before finishing the Activity, so a duplicate's Snackbar+"Redownload" still gets to show
    // (and be interacted with) even though the sheet itself has already slid away.
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

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(SHEET_ANIM_MS), initialOffsetY = { it }),
            exit = slideOutVertically(tween(SHEET_ANIM_MS), targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(animatedHeight),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                SharePickerScreen(
                    url = url,
                    onDismiss = { visible = false },
                    onDownload = { downloadUrl, itemFilter, totalItems, videoQuality ->
                        downloadJob = scope.launch {
                            enqueueAndHandleDuplicate(
                                context, snackbarHostState, downloadUrl,
                                "Downloading from ${VideoSiteRouter.siteName(downloadUrl)}",
                                itemFilter, totalItems, videoQuality,
                            )
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
