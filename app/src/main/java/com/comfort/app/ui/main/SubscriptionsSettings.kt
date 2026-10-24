package com.comfort.app.ui.main

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.comfort.app.R
import com.comfort.app.data.Subscription
import com.comfort.app.data.SubscriptionChecks
import com.comfort.app.viewmodel.SubscriptionsViewModel
import com.comfort.app.viewmodel.rememberSheetViewModelStoreOwner

// Settings > Subscriptions.

/** The watched channels and playlists: each one's last check, Check now, Pause/Resume, how often
 * it's checked, and Remove. Sources are added from a channel or playlist link's download sheet. */
@Composable
internal fun SubscriptionsSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val owner = rememberSheetViewModelStoreOwner()
    val viewModel: SubscriptionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = owner)
    val subscriptions by viewModel.subscriptions.collectAsState()
    val checking by viewModel.checking.collectAsState()
    var pendingRemove by remember { mutableStateOf<Subscription?>(null) }

    SettingsSubScaffold(
        title = stringResource(R.string.subscriptions_title),
        topicIcon = Icons.Outlined.Subscriptions,
        onBack = onBack,
        highlightKey = highlightKey,
    ) {
        val list = subscriptions
        when {
            // Still reading the database: nothing rather than a flash of the empty state.
            list == null -> Unit
            list.isEmpty() -> SettingsSection(title = stringResource(R.string.subscriptions_how_section), icon = Icons.Outlined.Info) {
                Text(
                    stringResource(R.string.subscriptions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                SettingsSection(title = stringResource(R.string.subscriptions_sources_section), icon = Icons.Outlined.Subscriptions) {
                    Text(
                        stringResource(R.string.subscriptions_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                list.forEach { subscription ->
                    SubscriptionCard(
                        subscription = subscription,
                        checking = subscription.id in checking,
                        onCheckNow = { viewModel.checkNow(subscription.id) },
                        onTogglePaused = { viewModel.setPaused(subscription.id, !subscription.paused) },
                        onIntervalChange = { viewModel.setInterval(subscription.id, it) },
                        onRemove = { pendingRemove = subscription },
                    )
                }
            }
        }
    }

    pendingRemove?.let { subscription ->
        ConfirmDeleteSheet(
            title = stringResource(R.string.remove_x, subscription.title),
            message = stringResource(R.string.subscriptions_remove_message),
            confirmLabel = stringResource(R.string.subscriptions_remove),
            onConfirm = {
                viewModel.remove(subscription.id)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    checking: Boolean,
    onCheckNow: () -> Unit,
    onTogglePaused: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Subscriptions, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (subscription.thumbnail != null) {
                        AsyncImage(
                            model = subscription.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        subscription.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    SubscriptionStatus(subscription, checking)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.subscriptions_check_every),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = SubscriptionChecks.INTERVAL_CHOICES
                options.forEachIndexed { index, hours ->
                    SegmentedButton(
                        selected = subscription.intervalHours == hours,
                        onClick = { onIntervalChange(hours) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(R.string.subscriptions_hours, hours), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCheckNow, enabled = !checking) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.subscriptions_check_now))
                }
                OutlinedButton(onClick = onTogglePaused) {
                    Icon(
                        if (subscription.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (subscription.paused) R.string.subscriptions_resume else R.string.subscriptions_pause))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.remove_x_2, subscription.title),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** "Checking…", the last check's error, or when it last worked — plus "Paused" when it is. */
@Composable
private fun SubscriptionStatus(subscription: Subscription, checking: Boolean) {
    val error = subscription.lastError
    val (text, isError) = when {
        checking -> stringResource(R.string.subscriptions_checking) to false
        error != null -> stringResource(R.string.subscriptions_last_error, error) to true
        subscription.lastCheckedAt != null -> stringResource(
            R.string.subscriptions_last_checked,
            DateUtils.getRelativeTimeSpanString(subscription.lastCheckedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        ) to false
        else -> stringResource(R.string.subscriptions_not_checked) to false
    }
    val shown = if (subscription.paused && !checking) stringResource(R.string.subscriptions_paused_prefix, text) else text
    Text(
        shown,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
