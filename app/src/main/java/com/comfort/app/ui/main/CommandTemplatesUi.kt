package com.comfort.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.CommandTemplate
import com.comfort.app.data.CommandTemplates
import com.comfort.app.R
import androidx.compose.ui.res.stringResource

/** Settings > Advanced > Command templates: the saved templates, each opened to edit or deleted,
 * and a button for a new one. They're picked per download on its Commands screen. */
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
