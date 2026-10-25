package com.comfort.app.ui.main

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.CommandTemplate
import com.comfort.app.data.CommandTemplates
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.R
import com.comfort.app.worker.CurrentCommand
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > Advanced > Command templates: the saved templates, each opened to edit or deleted,
 * a button for a new one, and the current command the Settings make. Templates are picked per
 * download on its Commands screen. */
@Composable
internal fun CommandTemplatesSection() {
    val context = LocalContext.current
    var templates by remember { mutableStateOf(CommandTemplates.getAll(context)) }
    // The template being edited; a blank one is a new template.
    var editing by remember { mutableStateOf<CommandTemplate?>(null) }

    Text(
        stringResource(R.string.command_templates_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    templates.forEach { template ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { editing = template }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(template.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    template.args,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                CommandTemplates.remove(context, template.name)
                templates = CommandTemplates.getAll(context)
            }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.command_template_delete, template.name))
            }
        }
    }
    if (templates.isNotEmpty()) Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { editing = CommandTemplate("", "") },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.command_template_new))
    }

    Spacer(Modifier.height(16.dp))
    CurrentSettingsCommand()

    editing?.let { template ->
        CommandTemplateDialog(
            initial = template,
            onDismiss = { editing = null },
            onSave = { saved ->
                CommandTemplates.put(context, saved, replacing = template.name.takeIf { it.isNotEmpty() })
                templates = CommandTemplates.getAll(context)
                editing = null
            },
        )
    }
}

/** gallery-dl's and yt-dlp's commands from the Settings alone, styled like the preview sheet's
 * "Current command". Rebuilt on every Settings change (this page has switches of its own) and on
 * resume (cookies and engines are set on other screens). */
@Composable
private fun CurrentSettingsCommand() {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val prefs = context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) version++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    // Off the main thread: it reads the preferences and the cookie file.
    val commands by produceState(emptyList<CurrentCommand.Shown>(), version) {
        value = withContext(Dispatchers.IO) { CurrentCommand.forSettings(context) }
    }

    CommandCodePanel(
        commands = commands,
        subtitle = stringResource(R.string.current_command_settings_subtitle),
        onCopy = { text ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(context.getString(R.string.current_command), text)))
                // Android 13+ shows its own copied confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, context.getString(R.string.command_copied), Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}

/** Names and edits a template: its name and its arguments. [initial] with a blank name is a new
 * one (its arguments may already be filled in, from a download's Commands screen). */
@Composable
internal fun CommandTemplateDialog(
    initial: CommandTemplate,
    onDismiss: () -> Unit,
    onSave: (CommandTemplate) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var args by remember { mutableStateOf(initial.args) }
    val unclosedQuotes = hasUnmatchedQuotes(args)
    val valid = name.isNotBlank() && args.isNotBlank() && !unclosedQuotes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.name.isEmpty()) R.string.command_template_new else R.string.command_template_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", "") },
                    label = { Text(stringResource(R.string.command_template_name)) },
                    placeholder = { Text(stringResource(R.string.command_template_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text(stringResource(R.string.command_template_args)) },
                    placeholder = { Text(stringResource(R.string.command_template_args_hint)) },
                    isError = unclosedQuotes,
                    supportingText = {
                        if (unclosedQuotes) Text(stringResource(R.string.command_template_unclosed_quotes), color = MaterialTheme.colorScheme.error)
                        else Text(stringResource(R.string.command_template_args_help))
                    },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(CommandTemplate(name.trim(), args.trim())) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
