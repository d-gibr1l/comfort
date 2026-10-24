package com.comfort.app.ui.main

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.data.BackupRestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Settings > Backup & restore.

/** Saves the settings, cookies and Library history to a file, and restores them from one. */
@Composable
internal fun BackupSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSettings by remember { mutableStateOf(true) }
    var includeHistory by remember { mutableStateOf(true) }
    var includeSubscriptions by remember { mutableStateOf(true) }
    // Off by default: cookies are logins, and a backup file is easy to share by mistake.
    var includeCookies by remember { mutableStateOf(false) }
    // The last result, shown under the buttons.
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A backup picked to restore, waiting for the user to choose what to take from it.
    var pending by remember { mutableStateOf<BackupRestore.Backup?>(null) }
    // Settings were restored: the app restarts to use them.
    var askRestart by remember { mutableStateOf(false) }

    val res = context.resources
    fun describe(c: BackupRestore.Contents): String = listOfNotNull(
        res.getQuantityString(R.plurals.backup_settings_count, c.settings, c.settings).takeIf { c.settings > 0 },
        res.getString(R.string.backup_cookies_included).takeIf { c.hasCookies },
        res.getQuantityString(R.plurals.backup_history_count, c.history, c.history).takeIf { c.history > 0 },
        res.getQuantityString(R.plurals.backup_subscriptions_count, c.subscriptions, c.subscriptions).takeIf { c.subscriptions > 0 },
    ).joinToString(", ").ifEmpty { res.getString(R.string.backup_nothing) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching {
                val saved = BackupRestore.export(context, uri, BackupRestore.Parts(includeSettings, includeCookies, includeHistory, includeSubscriptions))
                res.getString(R.string.backup_saved, describe(saved))
            }.getOrElse { res.getString(R.string.backup_failed, it.message ?: it.javaClass.simpleName) }
            busy = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { BackupRestore.read(context, uri) }
                .onSuccess { pending = it; message = null }
                .onFailure { message = res.getString(R.string.restore_not_backup) }
            busy = false
        }
    }

    SettingsSubScaffold(
        title = stringResource(R.string.backup_title),
        topicIcon = Icons.Outlined.SettingsBackupRestore,
        onBack = onBack,
        highlightKey = highlightKey,
    ) {
        SettingsSection(title = stringResource(R.string.backup_section), icon = Icons.Outlined.Backup) {
            IconToggleRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.backup_part_settings),
                subtitle = stringResource(R.string.backup_part_settings_subtitle),
                checked = includeSettings,
                onCheckedChange = { includeSettings = it },
            )
            Spacer(Modifier.height(16.dp))
            IconToggleRow(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.backup_part_history),
                subtitle = stringResource(R.string.backup_part_history_subtitle),
                checked = includeHistory,
                onCheckedChange = { includeHistory = it },
            )
            Spacer(Modifier.height(16.dp))
            IconToggleRow(
                icon = Icons.Outlined.Subscriptions,
                title = stringResource(R.string.backup_part_subscriptions),
                subtitle = stringResource(R.string.backup_part_subscriptions_subtitle),
                checked = includeSubscriptions,
                onCheckedChange = { includeSubscriptions = it },
            )
            Spacer(Modifier.height(16.dp))
            IconToggleRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.backup_part_cookies),
                subtitle = stringResource(R.string.backup_part_cookies_subtitle),
                checked = includeCookies,
                onCheckedChange = { includeCookies = it },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("comfort-backup-$date.json")
                },
                enabled = !busy && (includeSettings || includeHistory || includeSubscriptions || includeCookies),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.backup_action))
            }
        }

        SettingsSection(title = stringResource(R.string.restore_section), icon = Icons.Outlined.Restore) {
            Text(
                stringResource(R.string.restore_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.restore_action))
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }

    pending?.let { backup ->
        val contents = backup.contents
        var takeSettings by remember(backup) { mutableStateOf(contents.settings > 0) }
        var takeHistory by remember(backup) { mutableStateOf(contents.history > 0) }
        var takeSubscriptions by remember(backup) { mutableStateOf(contents.subscriptions > 0) }
        var takeCookies by remember(backup) { mutableStateOf(contents.hasCookies) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.restore_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.restore_dialog_contains, describe(contents)))
                    Spacer(Modifier.height(4.dp))
                    if (contents.settings > 0) RestoreChoice(stringResource(R.string.backup_part_settings), takeSettings) { takeSettings = it }
                    if (contents.history > 0) RestoreChoice(stringResource(R.string.backup_part_history), takeHistory) { takeHistory = it }
                    if (contents.subscriptions > 0) RestoreChoice(stringResource(R.string.backup_part_subscriptions), takeSubscriptions) { takeSubscriptions = it }
                    if (contents.hasCookies) RestoreChoice(stringResource(R.string.restore_cookies_replace), takeCookies) { takeCookies = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = takeSettings || takeHistory || takeSubscriptions || takeCookies,
                    onClick = {
                        pending = null
                        busy = true
                        scope.launch {
                            message = runCatching {
                                val restored = BackupRestore.restore(context, backup, BackupRestore.Parts(takeSettings, takeCookies, takeHistory, takeSubscriptions))
                                if (restored.settings > 0) askRestart = true
                                res.getString(R.string.restore_done, describe(restored))
                            }.getOrElse { res.getString(R.string.restore_failed, it.message ?: it.javaClass.simpleName) }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (askRestart) {
        AlertDialog(
            onDismissRequest = { askRestart = false },
            title = { Text(stringResource(R.string.restore_restart_title)) },
            text = { Text(stringResource(R.string.restore_restart_text)) },
            confirmButton = {
                TextButton(onClick = {
                    // A fresh start, so every screen reads the restored settings.
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (launch?.component != null) {
                        context.startActivity(Intent.makeRestartActivityTask(launch.component))
                        Runtime.getRuntime().exit(0)
                    }
                }) { Text(stringResource(R.string.restore_restart_now)) }
            },
            dismissButton = { TextButton(onClick = { askRestart = false }) { Text(stringResource(R.string.restore_restart_later)) } },
        )
    }
}

@Composable
private fun RestoreChoice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
