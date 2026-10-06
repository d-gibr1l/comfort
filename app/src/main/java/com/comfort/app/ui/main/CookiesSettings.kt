package com.comfort.app.ui.main

import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import compose.icons.feathericons.Instagram
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Cookies & Login: saved cookies per site, and the in-app login browser.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookiesSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
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
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cookies extracted successfully", tint = MaterialTheme.colorScheme.primary)
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
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cookies saved and applied", tint = MaterialTheme.colorScheme.primary)
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
            // Copy all stays its own button on the left; Save / Cancel is the same split button
            // as Settings' other edit sheets, on the right.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val text = site.cookies.joinToString("\n") { cookie ->
                            (edits[cookie]?.let { cookie.withValue(it) } ?: cookie).rawLine
                        }
                        onCopy("${site.label} cookies", text)
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy all")
                }
                Spacer(Modifier.weight(1f))
                ConfirmCancelSplitButton(
                    label = "Save",
                    icon = Icons.Outlined.Save,
                    onConfirm = { onSave(changed) },
                    onCancel = onDismiss,
                    confirmEnabled = changed.isNotEmpty(),
                )
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

/** Shared by the Cookies & Login settings screen, the Queue's per-download "Add cookies" error-card
 * action and the share picker — a real navigable browser starting at [loginUrl] so the user can
 * sign in normally, or browse anywhere else the site sends them (an OAuth redirect, a "verify it's
 * you" subdomain, ...) without getting stuck on one fixed page.
 *
 * Layout (redesigned): close + a pill address bar on top (lock + site name at rest, the full URL
 * selected for editing once tapped, reload/stop inside it) and a real-progress bar under it. The
 * system back gesture walks the page history before closing. The "Extract cookies" FAB can be
 * dragged anywhere on the page (it snaps to the nearest side on release) so it never
 * has to sit on top of the one button a login page needs; a plain tap still extracts whatever
 * CookieManager captured for the page on screen and merges it into cookies.txt (replacing only
 * that site's prior lines), then closes. */
@Composable
fun CookieLoginDialog(
    loginUrl: String,
    onDismiss: () -> Unit,
    onCookiesSaved: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // The single source of truth for what's actually loaded — the address field keeps the user's
    // in-progress edit separately, so typing a new URL doesn't fight the WebView's own page
    // callbacks overwriting the field mid-edit.
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var addressField by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(loginUrl)) }
    var editingAddress by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun navigateTo(input: String) {
        val target = normalizeBrowserAddress(input)
        currentUrl = target
        webView?.loadUrl(target)
        focusManager.clearFocus()
    }

    fun extractCookies() {
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
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Dialog's default width policy caps the window well short of the screen (platform
        // "dialog" sizing), which is what was leaving the WebView inset with visible margins —
        // this makes the window itself the full screen instead of just the content inside it.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Back walks the page history first; only an empty history closes the browser.
        androidx.activity.compose.BackHandler(enabled = canGoBack) { webView?.goBack() }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close browser")
                    }
                    val secure = currentUrl.startsWith("https://")
                    val host = remember(currentUrl) {
                        runCatching { java.net.URI(currentUrl).host }.getOrNull()?.removePrefix("www.") ?: currentUrl
                    }
                    Surface(
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (secure) Icons.Outlined.Lock else Icons.Outlined.Public,
                                contentDescription = if (secure) "Secure connection" else null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                BasicTextField(
                                    // At rest the pill reads as just the site; once focused it
                                    // holds the full URL, all selected, ready to type over.
                                    value = if (editingAddress) addressField else androidx.compose.ui.text.input.TextFieldValue(host),
                                    onValueChange = { addressField = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { navigateTo(addressField.text) }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { state ->
                                            if (state.isFocused && !editingAddress) {
                                                addressField = androidx.compose.ui.text.input.TextFieldValue(
                                                    currentUrl, selection = androidx.compose.ui.text.TextRange(0, currentUrl.length),
                                                )
                                            }
                                            editingAddress = state.isFocused
                                        },
                                )
                            }
                            IconButton(onClick = { if (isLoading) webView?.stopLoading() else webView?.reload() }) {
                                Icon(
                                    if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                                    contentDescription = if (isLoading) "Stop loading" else "Reload",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // Real page progress rather than an endless spinner; reserves its 3dp either way
                // so the page below doesn't jump when loading starts/stops.
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (isLoading) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize())
                    }
                }

                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // MATCH_PARENT, not AndroidView's default WRAP_CONTENT: with a
                                // wrap-content height the WebView can't resolve CSS viewport units,
                                // so 100vh/dvh/svh all computed to 0px. Instagram sizes its "Log into
                                // your Meta Account" sheet as calc(100vh - padding), so it collapsed
                                // to zero height and only its dark backdrop showed (found live via the
                                // WebView devtools: max-height 0px, 100vh = 0).
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Force a Desktop Chrome User-Agent. Instagram's mobile site often sends intent:// redirects
                                // to force opening their native app, which causes WebViews to go completely blank.
                                // The desktop site works flawlessly and doesn't try to deep-link you away.
                                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        progress = newProgress / 100f
                                    }
                                }
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
                                        if (url != null) currentUrl = url
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        isLoading = false
                                        if (url != null) currentUrl = url
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

                    // Draggable "Extract cookies" FAB. Positioned by its own top-left offset
                    // within this box; starts bottom-right above the system nav bar, can't be
                    // dragged off-screen, and snaps to the nearer side on release.
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)
                    val marginPx = with(density) { 16.dp.toPx() }
                    val toolbarReservePx = marginPx + navBarBottomPx
                    val boxWidthPx = constraints.maxWidth.toFloat()
                    val boxHeightPx = constraints.maxHeight.toFloat()
                    var fabSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                    val fabX = remember { Animatable(0f) }
                    val fabY = remember { Animatable(0f) }
                    var fabPlaced by remember { mutableStateOf(false) }
                    val maxX = (boxWidthPx - fabSize.width - marginPx).coerceAtLeast(marginPx)
                    val maxY = (boxHeightPx - fabSize.height - toolbarReservePx).coerceAtLeast(marginPx)
                    LaunchedEffect(fabSize, boxWidthPx, boxHeightPx) {
                        if (fabSize == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
                        if (!fabPlaced) {
                            fabX.snapTo(maxX)
                            fabY.snapTo(maxY)
                            fabPlaced = true
                        } else {
                            // Keep it on screen if the space changes (keyboard, rotation).
                            fabX.snapTo(fabX.value.coerceIn(marginPx, maxX))
                            fabY.snapTo(fabY.value.coerceIn(marginPx, maxY))
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = { extractCookies() },
                        icon = { Icon(Icons.Outlined.Cookie, contentDescription = null) },
                        text = { Text("Extract cookies") },
                        modifier = Modifier
                            .onSizeChanged { fabSize = it }
                            .offset { androidx.compose.ui.unit.IntOffset(fabX.value.roundToInt(), fabY.value.roundToInt()) }
                            .graphicsLayer { alpha = if (fabPlaced) 1f else 0f }
                            .pointerInput(maxX, maxY) {
                                detectDragGestures(
                                    onDrag = { change, drag ->
                                        change.consume()
                                        scope.launch {
                                            fabX.snapTo((fabX.value + drag.x).coerceIn(marginPx, maxX))
                                            fabY.snapTo((fabY.value + drag.y).coerceIn(marginPx, maxY))
                                        }
                                    },
                                    onDragEnd = {
                                        val snapLeft = fabX.value + fabSize.width / 2f < boxWidthPx / 2f
                                        scope.launch {
                                            fabX.animateTo(
                                                if (snapLeft) marginPx else maxX,
                                                androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 400f),
                                            )
                                        }
                                    },
                                )
                            },
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
