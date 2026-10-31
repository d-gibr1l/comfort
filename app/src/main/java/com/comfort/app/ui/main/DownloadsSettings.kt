package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import com.comfort.app.viewmodel.DownloadsSettingsViewModel
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                batteryUnrestricted = isIgnoringBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    SettingsSubScaffold(title = stringResource(R.string.downloads), topicIcon = Icons.Outlined.Download, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = stringResource(R.string.sharing), icon = Icons.Outlined.Share) {
            ShareModeRow(
                mode = shareMode,
                onModeChange = {
                    shareMode = it
                    GalleryDlPreferences.setShareMode(context, it)
                },
            )
        }

        SettingsSection(title = stringResource(R.string.concurrent_downloads), icon = Icons.Outlined.Layers) {
            IconToggleRow(
                icon = if (concurrentDownloadsEnabled) Icons.Outlined.Layers else Icons.Outlined.LayersClear,
                title = stringResource(R.string.multiple_concurrent_downloads),
                subtitle = stringResource(R.string.run_more_than_one_download_at),
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
                    label = { context.getString(R.string.x_at_once, it) },
                    onValueChange = {
                        concurrentDownloads = it
                        downloadsSettings.setConcurrentDownloads(it)
                    },
                )
            }
        }

        SettingsSection(title = stringResource(R.string.network), icon = Icons.Outlined.Wifi) {
            IconToggleRow(
                icon = if (wifiOnly) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                title = stringResource(R.string.wi_fi_only),
                subtitle = stringResource(R.string.queued_downloads_wait_for_a_wi),
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    downloadsSettings.setWifiOnly(it)
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
                title = stringResource(R.string.speed_limit),
                subtitle = stringResource(R.string.caps_download_bandwidth_for_all_future),
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
                    label = stringResource(R.string.speed_limit),
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
                title = stringResource(R.string.retries),
                subtitle = stringResource(R.string.how_many_times_a_failed_request),
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
                    label = { context.getString(R.string.x_retries, it) },
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
                title = stringResource(R.string.fragment_retries),
                subtitle = stringResource(R.string.how_many_times_a_failed_video),
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
                    label = { context.getString(R.string.x_retries, it) },
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
                title = stringResource(R.string.proxy),
                subtitle = stringResource(R.string.routes_all_future_downloads_through_this),
                checked = proxyEnabled,
                onCheckedChange = {
                    proxyEnabled = it
                    downloadsSettings.setProxyEnabled(it)
                },
            )
            ToggleReveal(proxyEnabled) {
                Spacer(Modifier.height(16.dp))
                val isProxyError = proxyUrl.isNotBlank() && !DownloadsSettingsViewModel.isValidProxyUrl(proxyUrl)
                OutlinedTextField(
                    value = proxyUrl,
                    onValueChange = {
                        proxyUrl = it
                        downloadsSettings.setProxyUrl(it)
                    },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    label = { Text(stringResource(R.string.proxy)) },
                    placeholder = { Text(stringResource(R.string.none)) },
                    singleLine = true,
                    isError = isProxyError,
                    supportingText = {
                        if (isProxyError) {
                            Text(stringResource(R.string.must_start_with_http_https_or), color = MaterialTheme.colorScheme.error)
                        } else if (proxyUrl.isBlank()) {
                            Text(stringResource(R.string.e_g_http_10_0_0))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.medium,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = Icons.Outlined.Public,
                title = stringResource(R.string.force_ipv4),
                subtitle = stringResource(R.string.forces_connections_over_ipv4_try_this),
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
                title = stringResource(R.string.skip_certificate_checks),
                subtitle = stringResource(R.string.disables_security_certificate_checks_only_enable),
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
                icon = Icons.AutoMirrored.Outlined.CallSplit,
                title = stringResource(R.string.concurrent_fragments),
                subtitle = stringResource(R.string.how_many_fragments_of_a_single),
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
                    label = { context.getString(R.string.x_at_once, it) },
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
                title = stringResource(R.string.sleep_interval),
                subtitle = stringResource(R.string.adds_a_random_delay_before_requests),
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
                title = stringResource(R.string.socket_timeout),
                subtitle = stringResource(R.string.how_long_to_wait_on_a),
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
                title = stringResource(R.string.buffer_size),
                subtitle = stringResource(R.string.the_size_of_each_read_chunk),
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
                        val digits = input.filter { it.isDigit() }.take(7)
                        val kb = digits.toIntOrNull()?.coerceIn(0, 1048576) ?: 0
                        bufferSizeKb = kb
                        GalleryDlPreferences.setBufferSizeKb(context, kb)
                    },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    label = { Text("KB") },
                    placeholder = { Text("1024") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
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
                title = stringResource(R.string.multi_connection_downloads_aria2c),
                subtitle = stringResource(R.string.downloads_files_faster_by_splitting_them),
                checked = aria2Enabled,
                onCheckedChange = {
                    aria2Enabled = it
                    GalleryDlPreferences.setAria2Enabled(context, it)
                },
            )
        }

        SettingsSection(title = stringResource(R.string.reliability), icon = Icons.Outlined.Bolt) {
            if (batteryUnrestricted) {
                StatusRow(Icons.Outlined.CheckCircle, stringResource(R.string.unrestricted_downloads_can_keep_running_in), MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    stringResource(R.string.unrestricted_background_activity),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.without_this_android_s_battery_saver),
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
                    Text(stringResource(R.string.allow_unrestricted_background_activity))
                }
            }
        }

        SettingsSection(title = stringResource(R.string.schedule), icon = Icons.Outlined.Schedule) {
            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.restrict_to_time_window),
                subtitle = stringResource(R.string.new_downloads_wait_in_the_queue),
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
                        label = stringResource(R.string.start),
                        minutesSinceMidnight = scheduleStartMin,
                        onPicked = { minutes ->
                            scheduleStartMin = minutes
                            downloadsSettings.setScheduleStart(minutes)
                        },
                    )
                    TimePickerButton(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.end),
                        minutesSinceMidnight = scheduleEndMin,
                        onPicked = { minutes ->
                            scheduleEndMin = minutes
                            downloadsSettings.setScheduleEnd(minutes)
                        },
                    )
                }
                ToggleReveal(scheduleStartMin == scheduleEndMin) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.start_and_end_times_are_identical),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))

                IconToggleRow(
                    icon = Icons.Outlined.Refresh,
                    title = stringResource(R.string.use_alarm_for_scheduling),
                    subtitle = stringResource(R.string.ensures_scheduled_downloads_start_exactly_on),
                    checked = alarmSchedulingEnabled,
                    onCheckedChange = {
                        alarmSchedulingEnabled = it
                        downloadsSettings.setAlarmSchedulingEnabled(it)
                    },
                )
            }
        }

        SettingsSection(title = stringResource(R.string.max_file_size), icon = Icons.Outlined.SdStorage) {
            IconToggleRow(
                icon = Icons.Outlined.SdStorage,
                title = stringResource(R.string.limit_max_file_size),
                subtitle = stringResource(R.string.files_larger_than_this_are_skipped),
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
                    label = stringResource(R.string.max_file_size),
                    units = FILESIZE_UNITS,
                    onValueChange = {
                        maxFilesize = it
                        downloadsSettings.setMaxFilesize(it)
                    },
                )
            }
        }

        // Imported from YTDLnis's own "Download Delay" and "Incognito" settings.
        SettingsSection(title = stringResource(R.string.pacing_privacy), icon = Icons.Outlined.VisibilityOff) {
            IconToggleRow(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.download_delay),
                subtitle = stringResource(R.string.adds_a_delay_between_a_finished),
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
                        val digits = input.filter { it.isDigit() }.take(6)
                        val seconds = digits.toIntOrNull()?.coerceIn(0, 86400) ?: 0
                        downloadDelaySeconds = seconds
                        GalleryDlPreferences.setDownloadDelaySeconds(context, seconds)
                    },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    label = { Text(stringResource(R.string.seconds)) },
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.medium,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            IconToggleRow(
                icon = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_domino_mask),
                title = stringResource(R.string.incognito_by_default),
                subtitle = stringResource(R.string.downloads_are_still_saved_to_your),
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
                title = stringResource(R.string.prevent_duplicate_downloads),
                subtitle = stringResource(R.string.skips_downloading_a_link_if_it),
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
                title = stringResource(R.string.remember_last_quality),
                subtitle = stringResource(R.string.makes_the_quality_chosen_on_the),
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
                title = stringResource(R.string.clean_up_leftover_downloads),
                subtitle = stringResource(R.string.automatically_deletes_partial_files_when_a),
                checked = deleteLeftoverOnFailure,
                onCheckedChange = {
                    deleteLeftoverOnFailure = it
                    downloadsSettings.setDeleteLeftoverOnFailure(it)
                },
            )
            ToggleReveal(deleteLeftoverOnFailure) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.on_failure_only_runs_from_inside),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // Real FilterChips, not a hand-rolled Surface+onClick row — same conversion, and
                // same reasoning, as the Sharing mode row above: the chip API's own accessibility
                // semantics, minimum touch target, and selected-state contract, for free.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // "Never" isn't one of the options here — the toggle above already covers
                    // that state, so (same off-value-redundancy fix as Concurrent downloads/
                    // fragments' sliders) it's deliberately excluded from what's reachable once
                    // the toggle reveals this row, rather than being selectable two different ways.
                    val cleanupOptions = listOf("" to stringResource(R.string.on_failure), "daily" to stringResource(R.string.daily), "weekly" to stringResource(R.string.weekly), "monthly" to stringResource(R.string.monthly))
                    cleanupOptions.forEachIndexed { index, (value, label) ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val selected = cleanupLeftoverInterval == value
                        FilterChip(
                            selected = selected,
                            onClick = {
                                cleanupLeftoverInterval = value
                                downloadsSettings.setCleanupLeftoverInterval(value)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .semantics { role = Role.RadioButton },
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
    // The composition's locale (follows a language change), not Locale.getDefault().
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val formattedTime = String.format(locale, "%d:%02d %s", displayHour, minute, amPm)

    val timeDescription = stringResource(R.string.x_schedule_time_x, label, formattedTime)
    OutlinedButton(
        modifier = modifier.semantics {
            contentDescription = timeDescription
        },
        shape = MaterialTheme.shapes.medium,
        onClick = { showPicker = true },
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formattedTime, style = MaterialTheme.typography.titleSmall)
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
                        TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = { onPicked(pendingMinutes); showPicker = false }) { Text(stringResource(R.string.done)) }
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
    Column(modifier = Modifier.fillMaxWidth().then(highlightRowModifier(stringResource(R.string.sharing_mode))).padding(4.dp)) {
        Text(stringResource(R.string.sharing_mode), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.what_happens_when_you_share_a),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val options = com.comfort.app.data.ShareMode.entries
        // Same connected-group treatment as the Library toolbar row's chips (Sort/Deleted/
        // Duplicates/Audio) — near-touching (2dp) with rounded outer ends/square inner corners,
        // not the wider 8dp-gap/uniform-shape look tried first.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, option ->
                val interactionSource = remember { MutableInteractionSource() }
                FilterChip(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .semantics { role = Role.RadioButton },
                    interactionSource = interactionSource,
                    shape = rememberMorphingChipShape(index, options.size, selected = mode == option, interactionSource = interactionSource, height = 40.dp),
                    label = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(option.displayLabel, style = MaterialTheme.typography.labelLarge)
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
