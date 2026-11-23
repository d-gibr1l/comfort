package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.R

/** The Queue's ⓘ: what its controls do (Pause All and Resume All, Start now and Start at…, the
 * schedule window, selecting and swiping) and what the common errors mean and what to do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberSturdySheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 20.dp)) {
            SheetHeader(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.queue_help_title),
                subtitle = stringResource(R.string.queue_help_subtitle),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HelpSection(Icons.Outlined.PauseCircle, R.string.queue_help_pause_title, R.string.queue_help_pause_body)
                HelpSection(Icons.Outlined.Bolt, R.string.queue_help_start_title, R.string.queue_help_start_body)
                HelpSection(Icons.Outlined.Schedule, R.string.queue_help_schedule_title, R.string.queue_help_schedule_body)
                HelpSection(Icons.Outlined.Checklist, R.string.queue_help_select_title, R.string.queue_help_select_body)
                HelpSection(Icons.Outlined.ErrorOutline, R.string.queue_help_errors_title, R.string.queue_help_errors_body)
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun HelpSection(icon: ImageVector, title: Int, body: Int) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // The icon in a tinted circle, centred on the heading (top-aligned, it sat off it).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
