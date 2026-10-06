package com.comfort.app.ui.main

import com.comfort.app.viewmodel.DownloadsSettingsViewModel
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.comfort.app.data.GalleryDlPreferences
import dev.darkokoa.datetimewheelpicker.WheelTimePicker
import dev.darkokoa.datetimewheelpicker.core.format.TimeFormat
import dev.darkokoa.datetimewheelpicker.core.format.timeFormatter
import kotlinx.datetime.LocalTime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Downloads.

@Composable
internal fun DownloadsSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Settings that also re-plan existing downloads (reschedule, restart, alarm, cleanup).
    val downloadsSettings: DownloadsSettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val sharedPreferences = remember { context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var concurrentDownloads by remember { mutableStateOf(GalleryDlPreferences.getConcurrentDownloads(context)) }
    var concurrentDownloadsEnabled by remember { mutableStateOf(GalleryDlPreferences.isConcurrentDownloadsEnabled(context)) }
    var wifiOnly by remember { mutableStateOf(GalleryDlPreferences.isWifiOnly(context)) }
    var scheduleEnabled by remember { mutableStateOf(GalleryDlPreferences.isScheduleEnabled(context)) }
    var alarmSchedulingEnabled by remember { mutableStateOf(GalleryDlPreferences.isAlarmSchedulingEnabled(context)) }
    var scheduleStartMin by remember { mutableStateOf(GalleryDlPreferences.getScheduleStartMinutes(context)) }
    var scheduleEndMin by remember { mutableStateOf(GalleryDlPreferences.getScheduleEndMinutes(context)) }
    var speedLimit by remember { mutableStateOf(GalleryDlPreferences.getSpeedLimit(context)) }
    var speedLimitEnabled by remember { mutableStateOf(GalleryDlPreferences.isSpeedLimitEnabled(context)) }
    var proxyUrl by remember { mutableStateOf(GalleryDlPreferences.getProxyUrl(context)) }
    var proxyEnabled by remember { mutableStateOf(GalleryDlPreferences.isProxyEnabled(context)) }
    var maxFilesizeEnabled by remember { mutableStateOf(GalleryDlPreferences.isMaxFilesizeEnabled(context)) }
    var maxFilesize by remember { mutableStateOf(GalleryDlPreferences.getMaxFilesize(context)) }
    var shareMode by remember { mutableStateOf(GalleryDlPreferences.getShareMode(context)) }
    var deleteLeftoverOnFailure by remember { mutableStateOf(GalleryDlPreferences.isDeleteLeftoverOnFailure(context)) }
    var cleanupLeftoverInterval by remember { mutableStateOf(GalleryDlPreferences.getCleanupLeftoverInterval(context)) }
    var preventDuplicateDownloads by remember { mutableStateOf(GalleryDlPreferences.isPreventDuplicateDownloads(context)) }
    var rememberDownloadType by remember { mutableStateOf(GalleryDlPreferences.isRememberDownloadType(context)) }
    var networkRetries by remember { mutableStateOf(GalleryDlPreferences.getNetworkRetries(context)) }
    var networkRetriesEnabled by remember { mutableStateOf(GalleryDlPreferences.isNetworkRetriesEnabled(context)) }
    var fragmentRetries by remember { mutableStateOf(GalleryDlPreferences.getFragmentRetries(context)) }
    var fragmentRetriesEnabled by remember { mutableStateOf(GalleryDlPreferences.isFragmentRetriesEnabled(context)) }
    // Imported from YTDLnis's own downloading_preferences.xml (see GalleryDlPreferences' own doc
    // comments on each of these).
    var forceIpv4 by remember { mutableStateOf(GalleryDlPreferences.isForceIpv4(context)) }
    var concurrentFragments by remember { mutableStateOf(GalleryDlPreferences.getConcurrentFragments(context)) }
    var concurrentFragmentsEnabled by remember { mutableStateOf(GalleryDlPreferences.isConcurrentFragmentsEnabled(context)) }
    var noCheckCertificates by remember { mutableStateOf(GalleryDlPreferences.isNoCheckCertificates(context)) }
    var sleepIntervalSeconds by remember { mutableStateOf(GalleryDlPreferences.getSleepIntervalSeconds(context)) }
    var sleepIntervalEnabled by remember { mutableStateOf(GalleryDlPreferences.isSleepIntervalEnabled(context)) }
    var socketTimeoutSeconds by remember { mutableStateOf(GalleryDlPreferences.getSocketTimeoutSeconds(context)) }
    var socketTimeoutEnabled by remember { mutableStateOf(GalleryDlPreferences.isSocketTimeoutEnabled(context)) }
    var bufferSizeKb by remember { mutableStateOf(GalleryDlPreferences.getBufferSizeKb(context)) }
    var bufferSizeEnabled by remember { mutableStateOf(GalleryDlPreferences.isBufferSizeEnabled(context)) }
    var aria2Enabled by remember { mutableStateOf(GalleryDlPreferences.isAria2Enabled(context)) }
    var downloadDelayEnabled by remember { mutableStateOf(GalleryDlPreferences.isDownloadDelayEnabled(context)) }
    var downloadDelaySeconds by remember { mutableStateOf(GalleryDlPreferences.getDownloadDelaySeconds(context)) }
    var incognitoDefault by remember { mutableStateOf(GalleryDlPreferences.isIncognitoDefault(context)) }

    // A download forced past the schedule window (Start Now) requests setExpedited(), but Android
    // grants each app only a limited expedited-job quota — once a burst of Start Now taps burns
    // through it, the next one silently falls back to a plain background job instead of erroring.
    // A plain background job is exactly the kind of work Battery Saver + the app being backgrounded
    // is allowed to hold indefinitely (reproduced live: one sat QUEUED with its own real
    // workRequestId assigned, but DownloadWorker.doWork() was never actually invoked for it).
    // Whitelisting the app from battery optimization exempts it from that background-network block
    // entirely, the same fix standard Android download managers point users to for this exact class
    // of stall. Re-read (not just set once) via the launcher's callback below, since the user grants
    // or denies this from a system dialog this screen has no other way to observe returning from.
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryUnrestricted = isIgnoringBatteryOptimizations() }

    SettingsSubScaffold(title = "Downloads", topicIcon = Icons.Outlined.Download, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Sharing", icon = Icons.Outlined.Share) {
            ShareModeRow(
                mode = shareMode,
                onModeChange = {
                    shareMode = it
                    GalleryDlPreferences.setShareMode(context, it)
                },
            )
        }

        SettingsSection(title = "Concurrent downloads", icon = Icons.Outlined.Layers) {
            IconToggleRow(
                icon = if (concurrentDownloadsEnabled) Icons.Outlined.Layers else Icons.Outlined.LayersClear,
                title = "Multiple concurrent downloads",
                subtitle = "Run more than one download at the same time. Off means exactly one at a time, regardless of the slider below.",
                checked = concurrentDownloadsEnabled,
                onCheckedChange = {
                    concurrentDownloadsEnabled = it
                    downloadsSettings.setConcurrentDownloadsEnabled(it)
                },
            )
            ToggleReveal(concurrentDownloadsEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = concurrentDownloads,
                    // Starts at 2, not 1 — 1 is exactly what the toggle above already means when
                    // off, so the slider (only shown while it's on) has nothing meaningful to say
                    // at that value; every position on it should actually mean "more than one."
                    valueRange = 2..GalleryDlPreferences.MAX_CONCURRENT_DOWNLOADS,
                    label = { "$it at once" },
                    onValueChange = {
                        concurrentDownloads = it
                        downloadsSettings.setConcurrentDownloads(it)
                    },
                )
            }
        }

        SettingsSection(title = "Network", icon = Icons.Outlined.Wifi) {
            IconToggleRow(
                icon = if (wifiOnly) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                title = "Wi-Fi only",
                subtitle = "Queued downloads wait for a Wi-Fi connection instead of using mobile data.",
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    sharedPreferences.edit().putBoolean(GalleryDlPreferences.KEY_WIFI_ONLY, it).apply()
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (speedLimitEnabled) {
                    ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_speed_2)
                } else {
                    Icons.Outlined.Speed
                },
                title = "Speed limit",
                subtitle = "Caps download bandwidth for all future downloads.",
                checked = speedLimitEnabled,
                onCheckedChange = {
                    speedLimitEnabled = it
                    downloadsSettings.setSpeedLimitEnabled(it)
                },
            )
            ToggleReveal(speedLimitEnabled) {
                Spacer(Modifier.height(16.dp))
                SizeSheetField(
                    currentValue = speedLimit,
                    label = "Speed limit",
                    units = SPEED_UNITS,
                    onValueChange = {
                        speedLimit = it
                        downloadsSettings.setSpeedLimit(it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Refresh,
                title = "Retries",
                subtitle = "How many times a failed request is retried before giving up. Off uses the engine's built-in default.",
                checked = networkRetriesEnabled,
                onCheckedChange = {
                    networkRetriesEnabled = it
                    GalleryDlPreferences.setNetworkRetriesEnabled(context, it)
                },
            )
            ToggleReveal(networkRetriesEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = networkRetries,
                    valueRange = 1..GalleryDlPreferences.MAX_NETWORK_RETRIES,
                    label = { "$it retries" },
                    onValueChange = {
                        networkRetries = it
                        GalleryDlPreferences.setNetworkRetries(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Refresh,
                title = "Fragment retries",
                subtitle = "How many times a failed video/audio fragment is retried. Off shares the main Retries budget.",
                checked = fragmentRetriesEnabled,
                onCheckedChange = {
                    fragmentRetriesEnabled = it
                    GalleryDlPreferences.setFragmentRetriesEnabled(context, it)
                },
            )
            ToggleReveal(fragmentRetriesEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = fragmentRetries,
                    valueRange = 1..GalleryDlPreferences.MAX_FRAGMENT_RETRIES,
                    label = { "$it retries" },
                    onValueChange = {
                        fragmentRetries = it
                        GalleryDlPreferences.setFragmentRetries(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Lock,
                title = "Proxy",
                subtitle = "Routes all future downloads through this proxy. Supports http://, https:// and socks5://.",
                checked = proxyEnabled,
                onCheckedChange = {
                    proxyEnabled = it
                    downloadsSettings.setProxyEnabled(it)
                },
            )
            ToggleReveal(proxyEnabled) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = proxyUrl,
                    onValueChange = {
                        proxyUrl = it
                        downloadsSettings.setProxyUrl(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Proxy") },
                    placeholder = { Text("None") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Public,
                title = "Force IPv4",
                subtitle = "Forces connections over IPv4. Try this if downloads fail due to broken IPv6 routes.",
                checked = forceIpv4,
                onCheckedChange = {
                    forceIpv4 = it
                    GalleryDlPreferences.setForceIpv4(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.GppBad,
                title = "Skip certificate checks",
                subtitle = "Disables security certificate checks. Only enable this if a server is misconfigured.",
                checked = noCheckCertificates,
                onCheckedChange = {
                    noCheckCertificates = it
                    GalleryDlPreferences.setNoCheckCertificates(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.CallSplit,
                title = "Concurrent fragments",
                subtitle = "How many fragments of a single video to download in parallel.",
                checked = concurrentFragmentsEnabled,
                onCheckedChange = {
                    concurrentFragmentsEnabled = it
                    GalleryDlPreferences.setConcurrentFragmentsEnabled(context, it)
                },
            )
            ToggleReveal(concurrentFragmentsEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = concurrentFragments,
                    // Same reasoning as Concurrent downloads' own slider above — 1 is already what
                    // the toggle above means when off.
                    valueRange = 2..GalleryDlPreferences.MAX_CONCURRENT_FRAGMENTS,
                    label = { "$it at once" },
                    onValueChange = {
                        concurrentFragments = it
                        GalleryDlPreferences.setConcurrentFragments(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (sleepIntervalEnabled) Icons.Outlined.AvTimer else Icons.Outlined.TimerOff,
                title = "Sleep interval",
                subtitle = "Adds a random delay before requests to avoid triggering rate limits and bot bans.",
                checked = sleepIntervalEnabled,
                onCheckedChange = {
                    sleepIntervalEnabled = it
                    GalleryDlPreferences.setSleepIntervalEnabled(context, it)
                },
            )
            ToggleReveal(sleepIntervalEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = sleepIntervalSeconds,
                    // Starts at 2, not the toggle's own off-value (0) — same off-value-redundancy
                    // reasoning as Concurrent downloads/fragments' sliders — and the floor
                    // yt_dlp_wrapper.py itself hardcodes for the random range's lower end.
                    valueRange = 2..GalleryDlPreferences.MAX_SLEEP_INTERVAL_SECONDS,
                    label = { "2-${it}s" },
                    onValueChange = {
                        sleepIntervalSeconds = it
                        GalleryDlPreferences.setSleepIntervalSeconds(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = "Socket timeout",
                subtitle = "How long to wait on a stalled connection before retrying. Off uses the engine's default.",
                checked = socketTimeoutEnabled,
                onCheckedChange = {
                    socketTimeoutEnabled = it
                    GalleryDlPreferences.setSocketTimeoutEnabled(context, it)
                },
            )
            ToggleReveal(socketTimeoutEnabled) {
                Spacer(Modifier.height(16.dp))
                SettingsSlider(
                    value = socketTimeoutSeconds,
                    valueRange = 5..120,
                    label = { "${it}s" },
                    onValueChange = {
                        socketTimeoutSeconds = it
                        GalleryDlPreferences.setSocketTimeoutSeconds(context, it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Storage,
                title = "Buffer size",
                subtitle = "The size of each read chunk (yt-dlp only). Rarely worth changing from the default.",
                checked = bufferSizeEnabled,
                onCheckedChange = {
                    bufferSizeEnabled = it
                    GalleryDlPreferences.setBufferSizeEnabled(context, it)
                },
            )
            ToggleReveal(bufferSizeEnabled) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (bufferSizeKb > 0) bufferSizeKb.toString() else "",
                    onValueChange = { input ->
                        val kb = input.filter { it.isDigit() }.toIntOrNull() ?: 0
                        bufferSizeKb = kb
                        GalleryDlPreferences.setBufferSizeKb(context, kb)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("KB") },
                    placeholder = { Text("1024") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (aria2Enabled) {
                    ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_bolt_boost)
                } else {
                    Icons.Outlined.FlashOff
                },
                title = "Multi-connection downloads (aria2c)",
                subtitle = "Downloads files faster by splitting them into multiple parts (yt-dlp only). Best for slow connections.",
                checked = aria2Enabled,
                onCheckedChange = {
                    aria2Enabled = it
                    GalleryDlPreferences.setAria2Enabled(context, it)
                },
            )
        }

        SettingsSection(title = "Reliability", icon = Icons.Outlined.Bolt) {
            if (batteryUnrestricted) {
                StatusRow(Icons.Outlined.CheckCircle, "Unrestricted — downloads can keep running in the background.", MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    "Unrestricted background activity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Without this, Android's Battery Saver can silently freeze a download that's " +
                        "waiting to start once the app is backgrounded — especially after \"Start now\" " +
                        "is tapped several times in a row. Recommended if downloads seem to get stuck.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        // Some OEM skins ship no activity for this action at all despite declaring
                        // the permission — falls back to the general battery-settings screen rather
                        // than crashing on startActivity() with no handler.
                        runCatching { batteryOptimizationLauncher.launch(intent) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Allow unrestricted background activity")
                }
            }
        }

        SettingsSection(title = "Schedule", icon = Icons.Outlined.Schedule) {
            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = "Restrict to time window",
                subtitle = "New downloads wait in the queue until the window opens.",
                checked = scheduleEnabled,
                onCheckedChange = {
                    scheduleEnabled = it
                    downloadsSettings.setScheduleEnabled(it)
                },
            )

            ToggleReveal(scheduleEnabled) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = "Start",
                        minutesSinceMidnight = scheduleStartMin,
                        onPicked = { minutes ->
                            scheduleStartMin = minutes
                            downloadsSettings.setScheduleStart(minutes)
                        },
                    )
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = "End",
                        minutesSinceMidnight = scheduleEndMin,
                        onPicked = { minutes ->
                            scheduleEndMin = minutes
                            downloadsSettings.setScheduleEnd(minutes)
                        },
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))

                IconToggleRow(
                    icon = Icons.Outlined.Refresh,
                    title = "Use alarm for scheduling",
                    subtitle = "Ensures scheduled downloads start exactly on time by bypassing Android's battery-saving delays.",
                    checked = alarmSchedulingEnabled,
                    onCheckedChange = {
                        alarmSchedulingEnabled = it
                        downloadsSettings.setAlarmSchedulingEnabled(it)
                    },
                )
            }
        }

        SettingsSection(title = "Max file size", icon = Icons.Outlined.SdStorage) {
            IconToggleRow(
                icon = Icons.Outlined.SdStorage,
                title = "Limit max file size",
                subtitle = "Files larger than this are skipped instead of downloaded.",
                checked = maxFilesizeEnabled,
                onCheckedChange = {
                    maxFilesizeEnabled = it
                    downloadsSettings.setMaxFilesizeEnabled(it)
                },
            )

            ToggleReveal(maxFilesizeEnabled) {
                Spacer(Modifier.height(16.dp))
                SizeSheetField(
                    currentValue = maxFilesize,
                    label = "Max file size",
                    units = FILESIZE_UNITS,
                    onValueChange = {
                        maxFilesize = it
                        downloadsSettings.setMaxFilesize(it)
                    },
                )
            }
        }

        // Imported from YTDLnis's own "Download Delay" and "Incognito" settings.
        SettingsSection(title = "Pacing & privacy", icon = Icons.Outlined.VisibilityOff) {
            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = "Download delay",
                subtitle = "Adds a delay between a finished download and the next one in the queue.",
                checked = downloadDelayEnabled,
                onCheckedChange = {
                    downloadDelayEnabled = it
                    GalleryDlPreferences.setDownloadDelayEnabled(context, it)
                },
            )

            ToggleReveal(downloadDelayEnabled) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (downloadDelaySeconds > 0) downloadDelaySeconds.toString() else "",
                    onValueChange = { input ->
                        val seconds = input.filter { it.isDigit() }.toIntOrNull() ?: 0
                        downloadDelaySeconds = seconds
                        GalleryDlPreferences.setDownloadDelaySeconds(context, seconds)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Seconds") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_domino_mask),
                title = "Incognito by default",
                subtitle = "Downloads are still saved to your device, but won't appear in the app's History or Library.",
                checked = incognitoDefault,
                onCheckedChange = {
                    incognitoDefault = it
                    GalleryDlPreferences.setIncognitoDefault(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.ContentCopy,
                title = "Prevent duplicate downloads",
                subtitle = "Skips downloading a link if it's already queued, running, or finished.",
                checked = preventDuplicateDownloads,
                onCheckedChange = {
                    preventDuplicateDownloads = it
                    GalleryDlPreferences.setPreventDuplicateDownloads(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = if (rememberDownloadType) {
                    Icons.Outlined.HighQuality
                } else {
                    ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_high_quality_off)
                },
                title = "Remember last quality",
                subtitle = "Makes the quality chosen on the download sheet the new default for future downloads.",
                checked = rememberDownloadType,
                onCheckedChange = {
                    rememberDownloadType = it
                    GalleryDlPreferences.setRememberDownloadType(context, it)
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.DeleteForever,
                title = "Clean up leftover downloads",
                subtitle = "Automatically deletes partial files when a download is cancelled or fails.",
                checked = deleteLeftoverOnFailure,
                onCheckedChange = {
                    deleteLeftoverOnFailure = it
                    downloadsSettings.setDeleteLeftoverOnFailure(it)
                },
            )
            if (deleteLeftoverOnFailure) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "\"On failure\" only runs from inside the download itself, so it can't catch the app being killed outright — the periodic options add that as a backstop, on top of the same on-failure cleanup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // Real FilterChips, not a hand-rolled Surface+onClick row — same conversion, and
                // same reasoning, as the Sharing mode row above: the chip API's own accessibility
                // semantics, minimum touch target, and selected-state contract, for free.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // "Never" isn't one of the options here — the toggle above already covers
                    // that state, so (same off-value-redundancy fix as Concurrent downloads/
                    // fragments' sliders) it's deliberately excluded from what's reachable once
                    // the toggle reveals this row, rather than being selectable two different ways.
                    val cleanupOptions = listOf("" to "On failure", "daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly")
                    cleanupOptions.forEachIndexed { index, (value, label) ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val selected = cleanupLeftoverInterval == value
                        FilterChip(
                            selected = selected,
                            onClick = {
                                cleanupLeftoverInterval = value
                                downloadsSettings.setCleanupLeftoverInterval(value)
                            },
                            modifier = Modifier.weight(1f).height(40.dp),
                            interactionSource = interactionSource,
                            shape = rememberMorphingChipShape(index, cleanupOptions.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                            label = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
    }
}

@Composable
private fun TimePickerButton(
    modifier: Modifier = Modifier,
    label: String,
    minutesSinceMidnight: Int,
    onPicked: (Int) -> Unit,
) {
    val hour = minutesSinceMidnight / 60
    val minute = minutesSinceMidnight % 60
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        onClick = { showPicker = true },
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%d:%02d %s".format(displayHour, minute, amPm), style = MaterialTheme.typography.titleSmall)
        }
    }

    if (showPicker) {
        // Seeded once from the committed value when the dialog opens, then only ever written by
        // the wheel's own onSnappedTime — re-deriving it from minutesSinceMidnight on every
        // recomposition would fight the wheel's scroll position every time it snaps.
        var pendingMinutes by remember { mutableStateOf(minutesSinceMidnight) }
        val initialTime = remember { LocalTime(hour = hour, minute = minute) }
        Dialog(onDismissRequest = { showPicker = false }) {
            Card(shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    WheelTimePicker(
                        modifier = Modifier.size(280.dp, 160.dp),
                        startTime = initialTime,
                        timeFormatter = timeFormatter(timeFormat = TimeFormat.AM_PM),
                        textStyle = MaterialTheme.typography.headlineSmall,
                        selectedTextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        onSnappedTime = { snapped -> pendingMinutes = snapped.hour * 60 + snapped.minute },
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPicker = false }) { Text("Cancel") }
                        TextButton(onClick = { onPicked(pendingMinutes); showPicker = false }) { Text("Done") }
                    }
                }
            }
        }
    }
}

/** What sharing a link to Comfort does: "Configure" (open the picker/preview sheet), "Instant"
 * (download right away), or "Always ask" (a small sheet asking which, per share). A 3-segment chip row (same connected-group shape as the Queue
 * card's Pause/Cancel chips) rather than a Switch — this replaced a plain on/off toggle once a
 * genuine third option (Always ask) existed that a boolean couldn't represent. */
@Composable
private fun ShareModeRow(mode: com.comfort.app.data.ShareMode, onModeChange: (com.comfort.app.data.ShareMode) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Text("Sharing mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "What happens when you share a link to Comfort.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val options = com.comfort.app.data.ShareMode.entries
        // Same connected-group treatment as the Library toolbar row's chips (Sort/Deleted/
        // Duplicates/Audio) — near-touching (2dp) with rounded outer ends/square inner corners,
        // not the wider 8dp-gap/uniform-shape look tried first.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { index, option ->
                val interactionSource = remember { MutableInteractionSource() }
                FilterChip(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    interactionSource = interactionSource,
                    shape = rememberMorphingChipShape(index, options.size, selected = mode == option, interactionSource = interactionSource, height = 40.dp),
                    label = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(option.label, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
