package com.codewithaj.dynamicisland.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codewithaj.dynamicisland.ServiceLocator
import com.codewithaj.dynamicisland.ui.components.IsletTopBar
import com.codewithaj.dynamicisland.ui.components.PermissionCard
import com.codewithaj.dynamicisland.ui.components.rememberPermissionStatus
import com.codewithaj.dynamicisland.util.OemAutoStart
import com.codewithaj.dynamicisland.util.PermissionUtils
import com.codewithaj.dynamicisland.util.startFirstResolvable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    showBack: Boolean,
    onBack: () -> Unit,
    onOemGuide: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val status = rememberPermissionStatus()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.startFirstResolvable(PermissionUtils.appNotificationSettingsIntent(context))
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Permanently denied → the system won't show the dialog again; send them to App info.
        if (!granted) context.startFirstResolvable(PermissionUtils.appDetailsIntent(context))
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { IsletTopBar("Set up Islet", scroll, onBack = if (showBack) onBack else null) },
        bottomBar = {
            Button(
                onClick = {
                    ServiceLocator.appScope.launch { ServiceLocator.settings.update { it.copy(onboardingDone = true) } }
                    onFinish()
                },
                enabled = status.canShowIsland,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            ) { Text(if (status.canShowIsland) "Done" else "Enable Accessibility or Overlay to continue") }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Islet needs a few permissions to draw over the status bar and show live activities. " +
                        "Everything stays on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                PermissionCard(
                    title = "Accessibility service",
                    description = "Lets the island sit on top of the status bar around the camera. Islet doesn't read your screen or perform actions.",
                    granted = status.accessibility,
                    required = true,
                    actionLabel = "Open settings",
                ) { context.startFirstResolvable(PermissionUtils.accessibilitySettingsIntent(context)) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (!status.accessibility || !status.notificationListener)) {
                item { RestrictedSettingsTip() }
            }
            item {
                PermissionCard(
                    title = "Notification access",
                    description = "Turns music, calls, timers, navigation and deliveries into live activities.",
                    granted = status.notificationListener,
                    required = true,
                    actionLabel = "Open settings",
                ) {
                    context.startFirstResolvable(
                        PermissionUtils.notificationListenerIntent(context),
                        PermissionUtils.notificationListenerFallbackIntent(),
                    )
                }
            }
            item {
                PermissionCard(
                    title = "Display over other apps",
                    description = "Fallback mode when the accessibility service is off. The pill then shows under the status bar icons.",
                    granted = status.overlay,
                    required = false,
                    actionLabel = "Open settings",
                ) { context.startFirstResolvable(PermissionUtils.overlayIntent(context)) }
            }
            item {
                PermissionCard(
                    title = "Notifications",
                    description = "Needed for the small persistent notification in fallback mode.",
                    granted = status.postNotifications,
                    required = false,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startFirstResolvable(PermissionUtils.appNotificationSettingsIntent(context))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    PermissionCard(
                        title = "Nearby devices",
                        description = "Shows your headphones or watch on the island when they connect, with their battery level.",
                        granted = status.bluetooth,
                        required = false,
                    ) { bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                }
            }
            item {
                PermissionCard(
                    title = "Unrestricted battery",
                    description = "Stops Android from killing the island in the background. Islet does no work while idle, so the battery cost is negligible.",
                    granted = status.ignoringBatteryOptimizations,
                    required = false,
                    actionLabel = "Allow",
                ) {
                    context.startFirstResolvable(
                        PermissionUtils.batteryOptimizationIntent(context),
                        PermissionUtils.appDetailsIntent(context),
                    )
                }
            }
            item {
                val guide = OemAutoStart.guideFor(context)
                PermissionCard(
                    title = "Auto-start (${guide.brand})",
                    description = "Some phone makers kill background apps aggressively. Follow the steps for your phone.",
                    granted = false,
                    required = false,
                    actionLabel = "Show steps",
                    onClick = onOemGuide,
                )
            }
        }
    }
}

/**
 * Android 13+ blocks Accessibility and Notification access for side-loaded apps until the user
 * taps "Allow restricted settings" in App info. Without this tip people think the app is broken.
 */
@Composable
private fun RestrictedSettingsTip() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("\"Restricted setting\" or switch greyed out?", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android blocks this for apps not installed from an app store. " +
                    "1) Try to turn the switch on once and dismiss the warning. " +
                    "2) Open App info → ⋮ (top-right) → \"Allow restricted settings\" and confirm. " +
                    "3) Come back and turn it on.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { context.startFirstResolvable(PermissionUtils.appDetailsIntent(context)) }) {
                Text("Open App info")
            }
        }
    }
}
