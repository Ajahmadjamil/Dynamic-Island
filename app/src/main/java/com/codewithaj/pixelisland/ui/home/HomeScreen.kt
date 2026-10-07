package com.codewithaj.pixelisland.ui.home

import android.Manifest
import android.content.Intent
import androidx.compose.material3.TextButton
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.codewithaj.pixelisland.data.AppFilterMode
import com.codewithaj.pixelisland.data.PopUpChannel
import com.codewithaj.pixelisland.util.startFirstResolvable
import android.provider.Settings
import com.codewithaj.pixelisland.util.AdbCommands
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import com.codewithaj.pixelisland.service.IslandOverlayController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codewithaj.pixelisland.BuildConfig
import com.codewithaj.pixelisland.ServiceLocator
import com.codewithaj.pixelisland.data.AnimationSpeed
import com.codewithaj.pixelisland.data.IslandSettings
import com.codewithaj.pixelisland.service.OverlayFallbackService
import com.codewithaj.pixelisland.ui.Screen
import com.codewithaj.pixelisland.ui.components.GroupCard
import com.codewithaj.pixelisland.ui.components.IsletTopBar
import com.codewithaj.pixelisland.ui.components.NavRow
import com.codewithaj.pixelisland.ui.components.SectionTitle
import com.codewithaj.pixelisland.ui.components.SwitchRow
import com.codewithaj.pixelisland.ui.components.rememberPermissionStatus
import com.codewithaj.pixelisland.ui.theme.SuccessGreen
import com.codewithaj.pixelisland.util.PermissionStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(settings: IslandSettings, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val update: ((IslandSettings) -> IslandSettings) -> Unit = { t ->
        ServiceLocator.appScope.launch { ServiceLocator.settings.update(t) }
    }
    // The status refreshes on resume; the permission dialog doesn't pause us, so re-read here.
    var bluetoothGranted by remember { mutableStateOf(false) }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        bluetoothGranted = granted
        if (!granted) update { it.copy(alertBluetooth = false) }
    }
    val status = rememberPermissionStatus().let { if (bluetoothGranted) it.copy(bluetooth = true) else it }


    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { IsletTopBar("Pixel Island", scroll, showLogo = true) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { StatusCard(settings, status, onFix = { navigate(Screen.Onboarding) }) }

            item { SectionTitle("Island") }
            item {
                GroupCard {
                    SwitchRow("Show island", checked = settings.islandEnabled) { v -> update { it.copy(islandEnabled = v) } }
                    SwitchRow(
                        "Show idle pill",
                        subtitle = "Keep the black pill visible when nothing is happening",
                        checked = settings.showIdlePill,
                    ) { v -> update { it.copy(showIdlePill = v) } }
                    SwitchRow("Hide in landscape", checked = settings.hideInLandscape) { v -> update { it.copy(hideInLandscape = v) } }
                    SwitchRow(
                        "Hide in fullscreen apps & games",
                        subtitle = "Best effort; depends on your phone",
                        checked = settings.hideInFullscreen,
                    ) { v -> update { it.copy(hideInFullscreen = v) } }
                    SwitchRow(
                        "Fallback overlay mode",
                        subtitle = if (status.overlay) "Use when the accessibility service is off" else "Needs \"Display over other apps\"",
                        checked = settings.useFallbackOverlay,
                        enabled = status.overlay,
                    ) { v ->
                        update { it.copy(useFallbackOverlay = v) }
                        if (v && !status.accessibility) OverlayFallbackService.start(context) else OverlayFallbackService.stop(context)
                    }
                }
            }

            item { SectionTitle("Live activities") }
            item {
                GroupCard {
                    SwitchRow("Music", subtitle = "Now playing from any media app", checked = settings.showMedia) { v ->
                        update { it.copy(showMedia = v) }
                    }
                    SwitchRow("Calls", subtitle = "Incoming call with answer/decline, call timer", checked = settings.showCalls) { v ->
                        update { it.copy(showCalls = v) }
                    }
                    SwitchRow("Timers & stopwatch", subtitle = "From your clock app", checked = settings.showTimers) { v ->
                        update { it.copy(showTimers = v) }
                    }
                    SwitchRow(
                        "Navigation & live progress",
                        subtitle = "Maps directions, deliveries, rides, downloads (Android 16 Live Updates)",
                        checked = settings.showLiveUpdates,
                    ) { v -> update { it.copy(showLiveUpdates = v) } }
                }
            }

            item { SectionTitle("Alerts") }
            item {
                GroupCard {
                    SwitchRow("Charging & low battery", checked = settings.alertCharging) { v ->
                        update { it.copy(alertCharging = v) }
                    }
                    SwitchRow("Silent mode", subtitle = "Ring / vibrate / silent changes", checked = settings.alertRinger) { v ->
                        update { it.copy(alertRinger = v) }
                    }
                    SwitchRow(
                        "Bluetooth devices",
                        subtitle = if (status.bluetooth) "Headphones and watches, with battery when available"
                        else "Needs the \"Nearby devices\" permission",
                        checked = settings.alertBluetooth && status.bluetooth,
                    ) { v ->
                        if (v && !status.bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        update { it.copy(alertBluetooth = v) }
                    }
                    SwitchRow("VPN & hotspot", subtitle = "When a VPN connects or the hotspot turns on/off", checked = settings.alertConnectivity) { v ->
                        update { it.copy(alertConnectivity = v) }
                    }
                }
            }

            item { SectionTitle("Notifications") }
            item {
                GroupCard {
                    SwitchRow(
                        "Notification previews",
                        subtitle = if (!status.notificationListener) "Needs notification access"
                        else "New notifications drop down from the island",
                        checked = settings.notificationPreviews,
                    ) { v -> update { it.copy(notificationPreviews = v) } }
                    val nativePopUpApps = settings.popUpChannels
                        .mapNotNull { PopUpChannel.decode(it)?.packageName }.toSet()
                        .count { settings.allowsApp(it) && context.packageManager.getLaunchIntentForPackage(it) != null }
                    NavRow(
                        Icons.Outlined.Apps,
                        "Choose apps",
                        buildString {
                            append(
                                when (settings.appFilterMode) {
                                    AppFilterMode.ALL_EXCEPT_BLOCKED ->
                                        if (settings.appFilterPackages.isEmpty()) "All apps can appear"
                                        else "All apps except ${settings.appFilterPackages.size} blocked"
                                    AppFilterMode.ONLY_SELECTED -> "Only ${settings.appFilterPackages.size} selected apps"
                                },
                            )
                            if (settings.notificationPreviews && nativePopUpApps > 0) {
                                append(" · $nativePopUpApps use Android's pop-up")
                            }
                        },
                    ) { navigate(Screen.AppFilter) }
                }
            }

            item { SectionTitle("Animation speed") }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = AnimationSpeed.entries
                    options.forEachIndexed { i, speed ->
                        SegmentedButton(
                            selected = settings.animationSpeed == speed,
                            onClick = { update { it.copy(animationSpeed = speed) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(speed.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }

            item { SectionTitle("Animation playground") }
            item { PlaygroundCard(enabled = status.canShowIsland && settings.islandEnabled) }

            item { SectionTitle("Setup") }
            item {
                GroupCard {
                    NavRow(Icons.Outlined.Straighten, "Calibrate island", "Position and size over your camera") { navigate(Screen.Calibration) }
                    NavRow(Icons.Outlined.Security, "Permissions & setup", "Accessibility, notifications, battery") { navigate(Screen.Onboarding) }
                    val adbItems = AdbCommands.items(context, status)
                    val requiredMissing = adbItems.count { it.level == AdbCommands.Level.REQUIRED && !it.done }
                    NavRow(
                        Icons.Outlined.Terminal,
                        "ADB commands",
                        if (requiredMissing > 0) "$requiredMissing required step(s) missing" else "All required steps done",
                    ) { navigate(Screen.Adb) }
                }
            }

            item { SectionTitle("About") }
            item {
                GroupCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Pixel Island ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Everything runs on your phone; Pixel Island has no internet permission and sends nothing anywhere. " +
                                "The island runs separately from these settings, which close completely when you leave.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Drives the island with demo content so the springs can be judged before real activities
 * exist. Commands go to the island process as a package-private broadcast.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaygroundCard(enabled: Boolean) {
    val context = LocalContext.current
    fun send(cmd: String) = context.sendBroadcast(
        Intent(IslandOverlayController.ACTION_PLAYGROUND)
            .setPackage(context.packageName)
            .putExtra(IslandOverlayController.EXTRA_CMD, cmd),
    )
    GroupCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (enabled) "Try the morphs, then tap, long-press or swipe the island itself."
                else "Enable the island first (accessibility or fallback mode).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                FilledTonalButton(enabled = enabled, onClick = { send(IslandOverlayController.CMD_SHOW) }) { Text("Show activity") }
                FilledTonalButton(enabled = enabled, onClick = { send(IslandOverlayController.CMD_SWITCH) }) { Text("Switch content") }
                FilledTonalButton(enabled = enabled, onClick = { send(IslandOverlayController.CMD_SPLIT) }) { Text("Split bubble") }
                FilledTonalButton(enabled = enabled, onClick = { send(IslandOverlayController.CMD_EXPAND) }) { Text("Expand") }
                FilledTonalButton(enabled = enabled, onClick = { send(IslandOverlayController.CMD_COLLAPSE) }) { Text("Collapse") }
                OutlinedButton(enabled = enabled, onClick = { send(IslandOverlayController.CMD_CLEAR) }) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun StatusCard(settings: IslandSettings, status: PermissionStatus, onFix: () -> Unit) {
    val (label, detail, ok) = when {
        !settings.islandEnabled -> Triple("Island off", "Turn on \"Show island\" below.", false)
        status.accessibility -> Triple("Island running", "Accessibility overlay: drawn above the status bar.", true)
        settings.useFallbackOverlay && status.overlay -> Triple(
            "Running in fallback mode",
            "The status bar sits above the island in this mode and takes taps on it. " +
                "Enable the accessibility service (tap here) for the real island over the camera.",
            true,
        )
        else -> Triple("Island not running", "Tap to finish setup.", false)
    }
    Card(
        onClick = onFix,
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = if (ok) SuccessGreen else Color(0xFFFF453A), modifier = Modifier.size(12.dp)) {}
            Spacer(Modifier.width(14.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                if (!status.notificationListener) {
                    Text("Notification access is off — live activities won't appear.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
