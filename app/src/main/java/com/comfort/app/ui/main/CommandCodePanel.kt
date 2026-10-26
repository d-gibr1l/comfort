package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.data.DownloadEngine
import com.comfort.app.worker.CurrentCommand

/** The "Current command" as a code panel (the preview sheet's Commands screen and Settings >
 * Advanced > Command templates): one tab per engine when there's more than one, the command
 * tinted like an editor would (flags, values, # notes), copy in the panel's corner. Copies the
 * engine shown. */
@Composable
internal fun CommandCodePanel(
    commands: List<CurrentCommand.Shown>,
    subtitle: String,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    // In a height-capped sheet: the panel gives up height so what's under it (the Add field,
    // Done) stays on screen. Pass a weighted [modifier] too. Off in a scrolling page.
    shrinkable: Boolean = false,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, (commands.size - 1).coerceAtLeast(0))
    val shown = commands.getOrNull(index)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetHeader(icon = Icons.Outlined.Terminal, title = stringResource(R.string.current_command), subtitle = subtitle)
        if (commands.size > 1) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                commands.forEachIndexed { i, command ->
                    SegmentedButton(
                        selected = i == index,
                        onClick = { selected = i },
                        shape = SegmentedButtonDefaults.itemShape(i, commands.size),
                        label = { Text(engineLabel(command.engine), maxLines = 1) },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().then(
                if (shrinkable) Modifier.weight(1f, fill = false).heightIn(min = 96.dp, max = 260.dp) else Modifier.height(260.dp),
            ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Box {
                SelectionContainer {
                    Text(
                        highlight(shown?.text.orEmpty(), codeColors()),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            // Room on the right of the first lines for the copy button.
                            .padding(start = 16.dp, top = 16.dp, end = 56.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                FilledTonalIconButton(
                    onClick = { shown?.let { onCopy(it.text) } },
                    enabled = shown != null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_current_command), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun engineLabel(engine: DownloadEngine) = when (engine) {
    DownloadEngine.YT_DLP -> "yt-dlp"
    DownloadEngine.GALLERY_DL -> "gallery-dl"
    DownloadEngine.INSTALOADER -> "Instaloader"
    DownloadEngine.SPOTIFY -> "Spotify"
}

private data class CodeColors(val program: Color, val flag: Color, val note: Color, val continuation: Color, val url: Color)

@Composable
private fun codeColors() = MaterialTheme.colorScheme.let {
    CodeColors(program = it.onSurface, flag = it.primary, note = it.onSurfaceVariant, continuation = it.outline, url = it.tertiary)
}

/** Editor-style tinting of the rendered command: "# notes" dimmed and italic, the program name
 * bold, flags in the theme colour, the line-continuation backslashes faint, the link last. */
private fun highlight(text: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    lines.forEachIndexed { i, line ->
        when {
            line.startsWith("#") -> withStyle(SpanStyle(color = colors.note, fontStyle = FontStyle.Italic)) { append(line) }
            else -> {
                val continued = line.endsWith(" \\")
                val body = if (continued) line.dropLast(2) else line
                val indent = body.takeWhile { it == ' ' }
                append(indent)
                val content = body.drop(indent.length)
                when {
                    // A command's first line: just the program.
                    indent.isEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.program)) { append(content) }
                    // The last line of a command is its link.
                    !continued -> withStyle(SpanStyle(color = colors.url)) { append(content) }
                    else -> appendTokens(content, colors)
                }
                if (continued) withStyle(SpanStyle(color = colors.continuation)) { append(" \\") }
            }
        }
        if (i < lines.lastIndex) append('\n')
    }
}

/** Flags ("-f", "--retries") tinted; their values left plain. Spaces inside quotes stay with
 * their value. */
private fun AnnotatedString.Builder.appendTokens(content: String, colors: CodeColors) {
    var i = 0
    while (i < content.length) {
        if (content[i] == ' ') { append(' '); i++; continue }
        var end = i
        var quote: Char? = null
        while (end < content.length && (quote != null || content[end] != ' ')) {
            val c = content[end]
            if (quote == null && (c == '"' || c == '\'')) quote = c
            else if (c == quote && content.getOrNull(end - 1) != '\\') quote = null
            end++
        }
        val token = content.substring(i, end)
        if (token.startsWith("-")) withStyle(SpanStyle(color = colors.flag, fontWeight = FontWeight.Medium)) { append(token) }
        else append(token)
        i = end
    }
}
