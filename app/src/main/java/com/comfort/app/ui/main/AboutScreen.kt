package com.comfort.app.ui.main

// Settings › About: the app, its credits and the issue forms.

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import compose.icons.FeatherIcons
import compose.icons.feathericons.Instagram
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.VideoSiteRouter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

@Composable
internal fun AboutScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    SettingsSubScaffold(title = stringResource(R.string.about), topicIcon = Icons.Outlined.Info, onBack = onBack, highlightKey = highlightKey) {
        item {
            SettingsSection(
                title = stringResource(R.string.app), 
                icon = Icons.Outlined.Info,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/d-gibr1l/comfort"))
                    runCatching { context.startActivity(intent) }
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = context.getString(R.string.comfort_version_x_open_project_page, versionName)
                    }
                ) {
                    AppIconTile(size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Comfort", style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.version_x, versionName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // The project's issue forms, one row each, opening that form with this version filled in.
        item {
            SettingsSection(title = stringResource(R.string.report_an_issue), icon = Icons.Outlined.BugReport) {
                Text(
                    stringResource(R.string.report_an_issue_subtitle, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IssueFormRow(Icons.Outlined.BugReport, stringResource(R.string.issue_bug), stringResource(R.string.issue_bug_subtitle), "bug_report.yml", versionName)
                    IssueFormRow(Icons.Outlined.LinkOff, stringResource(R.string.issue_site), stringResource(R.string.issue_site_subtitle), "site_not_working.yml", versionName)
                    IssueFormRow(Icons.Outlined.Lightbulb, stringResource(R.string.issue_idea), stringResource(R.string.issue_idea_subtitle), "feature_request.yml", versionName)
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
        item {
            SettingsSection(title = stringResource(R.string.credits), icon = Icons.Outlined.Link) {
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
                        CreditEntry(iconRes = com.comfort.app.R.drawable.ic_credit_python, title = context.getString(R.string.python_runtime), url = "https://github.com/deniscerri/ytdlnis-packages"),
                        CreditEntry(icon = Icons.Outlined.Bolt, title = "aria2", url = "https://aria2.github.io/"),
                        // The word-by-word lyrics (music.lyrics) are ported from it.
                        CreditEntry(icon = Icons.Outlined.MusicNote, title = "Mixify", url = "https://github.com/MixifyTeam/Mixify"),
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
}

/** One of the project's issue forms, opened on GitHub with the app's version filled in. */
@Composable
private fun IssueFormRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, template: String, version: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            // The form's version field, filled in (the site form names it "versions").
            val field = when (template) { "bug_report.yml" -> "version"; "site_not_working.yml" -> "versions"; else -> null }
            val url = "https://github.com/d-gibr1l/comfort/issues/new?template=$template" + (field?.let { "&$it=" + Uri.encode(if (it == "versions") "Comfort $version" else version) } ?: "")
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
            .semantics {
                role = Role.Button
                contentDescription = context.getString(R.string.open_x_website, entry.title)
            }
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
