package com.comfort.app.ui.main

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.data.GestureSettings
import com.comfort.app.data.LibrarySettings
import com.comfort.app.data.PlayerChoice

// Settings › Library: what Delete does, the Deleted and Duplicates sections, the Library's swipe,
// the players it opens things in, and the folders downloads are saved to (once a page of its own).
@Composable
internal fun LibrarySettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deleteFromPhone by LibrarySettings.deleteFromPhone.collectAsState()
    val showDeleted by LibrarySettings.showDeleted.collectAsState()
    val showDuplicates by LibrarySettings.showDuplicates.collectAsState()
    val clearAfter by LibrarySettings.clearDeletedAfterDays.collectAsState()
    val swipeRemove by GestureSettings.swipeToRemove.collectAsState()
    val externalVideo by PlayerChoice.externalVideo.collectAsState()
    val externalMusic by PlayerChoice.externalMusic.collectAsState()
    val folders = foldersSettingsItems()

    SettingsSubScaffold(title = stringResource(R.string.library_settings), topicIcon = Icons.Outlined.VideoLibrary, onBack = onBack, highlightKey = highlightKey) {
        item {
            SettingsSection(title = stringResource(R.string.deleting), icon = Icons.Outlined.Delete) {
                ChoiceRow(
                    title = stringResource(R.string.when_you_delete),
                    subtitle = stringResource(R.string.when_you_delete_subtitle),
                    options = listOf(true to stringResource(R.string.delete_option_phone), false to stringResource(R.string.delete_option_library)),
                    selected = deleteFromPhone,
                    onSelect = { LibrarySettings.setDeleteFromPhone(context, it) },
                )
                Spacer(Modifier.height(12.dp))
                IconToggleRow(
                    icon = Icons.Outlined.SwipeLeft,
                    title = stringResource(R.string.swipe_to_remove),
                    subtitle = stringResource(R.string.swipe_to_remove_subtitle),
                    checked = swipeRemove,
                    onCheckedChange = { GestureSettings.setSwipeToRemove(context, it) },
                )
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.deleted_and_duplicates), icon = Icons.Outlined.ContentCopy) {
                IconToggleRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = stringResource(R.string.show_deleted_section),
                    subtitle = stringResource(R.string.show_deleted_section_subtitle),
                    checked = showDeleted,
                    onCheckedChange = { LibrarySettings.setShowDeleted(context, it) },
                )
                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    title = stringResource(R.string.clear_deleted_after),
                    subtitle = stringResource(R.string.clear_deleted_after_subtitle),
                    options = LibrarySettings.CLEAR_DELETED_CHOICES.map { days ->
                        days to if (days == 0) stringResource(R.string.never) else pluralStringResource(R.plurals.days_count, days, days)
                    },
                    selected = clearAfter,
                    onSelect = { LibrarySettings.setClearDeletedAfterDays(context, it) },
                )
                Spacer(Modifier.height(12.dp))
                IconToggleRow(
                    icon = Icons.Outlined.ContentCopy,
                    title = stringResource(R.string.show_duplicates_section),
                    subtitle = stringResource(R.string.show_duplicates_section_subtitle),
                    checked = showDuplicates,
                    onCheckedChange = { LibrarySettings.setShowDuplicates(context, it) },
                )
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.players), icon = Icons.Outlined.PlayCircle) {
                IconToggleRow(
                    icon = Icons.Outlined.OndemandVideo,
                    title = stringResource(R.string.videos_in_phone_player),
                    subtitle = stringResource(R.string.videos_in_phone_player_subtitle),
                    checked = externalVideo,
                    onCheckedChange = { PlayerChoice.setExternalVideo(context, it) },
                )
                Spacer(Modifier.height(12.dp))
                IconToggleRow(
                    icon = Icons.Outlined.LibraryMusic,
                    title = stringResource(R.string.music_in_phone_player),
                    subtitle = stringResource(R.string.music_in_phone_player_subtitle),
                    checked = externalMusic,
                    onCheckedChange = { PlayerChoice.setExternalMusic(context, it) },
                )
            }
        }
        folders()
    }
}

/** A setting picked from a few connected chips, as Sharing mode's (Settings › Downloads). */
@Composable
private fun <T> ChoiceRow(title: String, subtitle: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().then(highlightRowModifier(title)).padding(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { index, (value, label) ->
                val interactionSource = remember { MutableInteractionSource() }
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.RadioButton },
                    interactionSource = interactionSource,
                    shape = rememberMorphingChipShape(index, options.size, selected = value == selected, interactionSource = interactionSource, height = 40.dp),
                    label = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
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
