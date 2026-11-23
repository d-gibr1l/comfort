package com.comfort.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.data.ComicSites

/** Settings › Processing › Comics: whether manga / comic chapters are saved as one .cbz, and on
 * which sites (a list like the Sites lists: the known ones, plus or minus the user's). */
@Composable
internal fun ComicsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ComicSites.isEnabled(context)) }
    var sitesOpen by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(ComicSites.sites(context).size) }
    SettingsSection(title = stringResource(R.string.comics), icon = Icons.Outlined.MenuBook) {
        IconToggleRow(
            icon = Icons.Outlined.FolderZip,
            title = stringResource(R.string.save_chapters_as_cbz),
            subtitle = stringResource(R.string.save_chapters_as_cbz_subtitle),
            checked = enabled,
            onCheckedChange = { enabled = it; ComicSites.setEnabled(context, it) },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) { sitesOpen = true }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.comic_sites), style = MaterialTheme.typography.titleSmall)
                Text(pluralStringResource(R.plurals.site_count_grouped, count, "%,d".format(count)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (sitesOpen) ComicSitesSheet(onDismiss = { sitesOpen = false; count = ComicSites.sites(context).size })
}

/** The comic sites, to add to (a site or a link to one) or take off; Reset brings back the known ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicSitesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sites by remember { mutableStateOf(ComicSites.sites(context)) }
    var input by remember { mutableStateOf("") }
    val host = com.comfort.app.data.VideoSiteRouter.siteFromInput(input)
    fun add() {
        val h = host ?: return
        ComicSites.add(context, h)
        sites = ComicSites.sites(context)
        input = ""
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 20.dp).imePadding()) {
            SheetHeader(
                icon = Icons.Outlined.MenuBook,
                title = stringResource(R.string.comic_sites),
                subtitle = stringResource(R.string.comic_sites_subtitle),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            ) {
                TextButton(onClick = { ComicSites.resetToDefaults(context); sites = ComicSites.sites(context) }) {
                    Text(stringResource(R.string.reset_to_default))
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.comic_site_add_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                trailingIcon = {
                    IconButton(onClick = { add() }, enabled = host != null) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(sites, key = { it }) { site ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(site.first().uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(site, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { ComicSites.remove(context, site); sites = ComicSites.sites(context) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
