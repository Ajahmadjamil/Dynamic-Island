package com.codewithaj.pixelisland.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codewithaj.pixelisland.R
import com.codewithaj.pixelisland.ServiceLocator
import com.codewithaj.pixelisland.data.IslandSettings
import com.codewithaj.pixelisland.ui.MainActivity
import com.codewithaj.pixelisland.ui.theme.SuccessGreen
import com.codewithaj.pixelisland.util.OemAutoStart
import com.codewithaj.pixelisland.util.PermissionStatus
import com.codewithaj.pixelisland.util.PermissionUtils
import com.codewithaj.pixelisland.util.startFirstResolvable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Guided setup: one permission per screen, each with an animated walkthrough of the taps to make in
 * Android's Settings. Steps that are already granted are left out, each step notices by itself when
 * its permission is on and moves on, and after a trip to Settings the wizard tries to bring the
 * app back to the front on its own (best effort: Android only allows it in some cases).
 */

enum class SetupStep { Welcome, Unlock, Accessibility, Notifications, PostNotifications, Overlay, Battery, Bluetooth, AutoStart, Done }

private fun SetupStep.isGranted(s: PermissionStatus): Boolean = when (this) {
    // Allowed, or no longer needed because both restricted switches are already on.
    SetupStep.Unlock -> s.restrictedSettingsAllowed == true || (s.accessibility && s.notificationListener)
    SetupStep.Accessibility -> s.accessibility
    SetupStep.Notifications -> s.notificationListener
    SetupStep.PostNotifications -> s.postNotifications
    SetupStep.Overlay -> s.overlay
    SetupStep.Battery -> s.ignoringBatteryOptimizations
    SetupStep.Bluetooth -> s.bluetooth
    SetupStep.Welcome, SetupStep.AutoStart, SetupStep.Done -> false
}

/** The wizard opens by itself on launch while this is true: first run, or a required switch is off. */
fun needsSetup(settings: IslandSettings, status: PermissionStatus): Boolean =
    !settings.onboardingDone || !status.accessibility || !status.notificationListener

private fun planSteps(context: Context, s: PermissionStatus, firstRun: Boolean): List<SetupStep> = buildList {
    if (firstRun) add(SetupStep.Welcome)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s.restrictedSettingsAllowed == false &&
        (!s.accessibility || !s.notificationListener)
    ) add(SetupStep.Unlock)
    if (!s.accessibility) add(SetupStep.Accessibility)
    if (!s.notificationListener) add(SetupStep.Notifications)
    if (!s.postNotifications) add(SetupStep.PostNotifications)
    if (!s.overlay) add(SetupStep.Overlay)
    if (!s.ignoringBatteryOptimizations) add(SetupStep.Battery)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !s.bluetooth) add(SetupStep.Bluetooth)
    if (firstRun && OemAutoStart.guideFor(context).hasBrandSteps) add(SetupStep.AutoStart)
    add(SetupStep.Done)
}

private const val AUTO_ADVANCE_MS = 1_100L
private const val POLL_MS = 700L
private const val POLL_TIMEOUT_MS = 180_000L

@Composable
fun SetupWizardScreen(
    firstRun: Boolean,
    onFinish: (calibrate: Boolean) -> Unit,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val serviceLabel = stringResource(R.string.accessibility_service_label)
    val listenerLabel = stringResource(R.string.notification_listener_label)
    val tutorials = remember(serviceLabel, listenerLabel) { Tutorials(serviceLabel, listenerLabel) }
    var plan by rememberSaveable(
        stateSaver = listSaver(save = { it.map(SetupStep::name) }, restore = { it.map(SetupStep::valueOf) }),
    ) { mutableStateOf(planSteps(context, PermissionUtils.status(context), firstRun)) }

    var index by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(PermissionUtils.status(context)) }
    var waitingFor by remember { mutableStateOf<SetupStep?>(null) }
    var attempted by remember { mutableStateOf(emptySet<SetupStep>()) }
    var unlockSawWarning by rememberSaveable { mutableStateOf(false) }
    // Runtime permissions the user said no to: the system won't ask again, so we open Settings instead.
    var denied by remember { mutableStateOf(emptySet<SetupStep>()) }

    // Back from Settings: re-read everything and stop watching in the background.
    LifecycleResumeEffect(Unit) {
        status = PermissionUtils.status(context)
        waitingFor = null
        onPauseOrDispose { }
    }

    // While the user is in Settings, watch for the switch to flip and bring the app back.
    LaunchedEffect(waitingFor) {
        val target = waitingFor ?: return@LaunchedEffect
        val until = SystemClock.uptimeMillis() + POLL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < until) {
            delay(POLL_MS)
            val s = PermissionUtils.status(context)
            if (target.isGranted(s)) {
                status = s
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) bringAppToFront(context)
                break
            }
        }
        waitingFor = null
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = PermissionUtils.status(context)
        denied = if (granted) denied - SetupStep.Bluetooth else denied + SetupStep.Bluetooth
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = PermissionUtils.status(context)
        denied = if (granted) denied - SetupStep.PostNotifications else denied + SetupStep.PostNotifications
    }

    val step = plan[index]
    val granted = step.isGranted(status)
    // A step that was already on when the user got here (e.g. they went back) waits for "Next".
    val grantedOnArrival = remember(step) { granted }

    fun next() {
        var i = index + 1
        while (i < plan.lastIndex && plan[i].isGranted(status)) i++
        index = i.coerceAtMost(plan.lastIndex)
    }

    fun finish(calibrate: Boolean) {
        ServiceLocator.appScope.launch { ServiceLocator.settings.update { it.copy(onboardingDone = true) } }
        onFinish(calibrate)
    }

    fun open(s: SetupStep) {
        attempted = attempted + s
        when (s) {
            SetupStep.Unlock -> if (!unlockSawWarning) {
                unlockSawWarning = true
                context.startFirstResolvable(PermissionUtils.accessibilitySettingsIntent(context))
            } else {
                waitingFor = s
                context.startFirstResolvable(PermissionUtils.appDetailsIntent(context))
            }
            SetupStep.Accessibility -> {
                waitingFor = s
                context.startFirstResolvable(PermissionUtils.accessibilitySettingsIntent(context))
            }
            SetupStep.Notifications -> {
                waitingFor = s
                context.startFirstResolvable(
                    PermissionUtils.notificationListenerIntent(context),
                    PermissionUtils.notificationListenerFallbackIntent(),
                )
            }
            SetupStep.Overlay -> {
                waitingFor = s
                context.startFirstResolvable(PermissionUtils.overlayIntent(context))
            }
            SetupStep.Battery -> {
                waitingFor = s
                context.startFirstResolvable(
                    PermissionUtils.batteryOptimizationIntent(context),
                    PermissionUtils.appDetailsIntent(context),
                )
            }
            SetupStep.PostNotifications ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s !in denied) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    waitingFor = s
                    context.startFirstResolvable(
                        PermissionUtils.appNotificationSettingsIntent(context),
                        PermissionUtils.appDetailsIntent(context),
                    )
                }
            SetupStep.Bluetooth -> when {
                // Denied for good: the system won't ask again, so send them to App info.
                SetupStep.Bluetooth in denied -> context.startFirstResolvable(PermissionUtils.appDetailsIntent(context))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            SetupStep.Welcome, SetupStep.AutoStart, SetupStep.Done -> Unit
        }
    }

    /** Jump back to a step if it's in the plan. Otherwise Unlock is added in front of the current
     *  step (a switch turned out to be greyed out), and anything else opens Settings directly. */
    fun fix(s: SetupStep) {
        val i = plan.indexOf(s)
        when {
            i >= 0 -> index = i
            s == SetupStep.Unlock -> plan = plan.toMutableList().apply { add(index, SetupStep.Unlock) }
            else -> open(s)
        }
    }

    LaunchedEffect(step, granted) {
        if (granted && !grantedOnArrival) {
            delay(AUTO_ADVANCE_MS)
            next()
        }
    }

    BackHandler(enabled = index > 0) { index -= 1 }

    Scaffold(
        topBar = {
            WizardTopBar(
                plan = plan,
                index = index,
                onBack = if (index > 0) ({ index -= 1 }) else null,
                onLater = if (step != SetupStep.Done) onLater else null,
            )
        },
    ) { padding ->
        AnimatedContent(
            // Keyed by step (each appears once), so an inserted Unlock step animates in too.
            targetState = step,
            transitionSpec = {
                val forward = plan.indexOf(targetState) > plan.indexOf(initialState)
                (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "setupStep",
        ) { s ->
            val i = plan.indexOf(s)
            when (s) {
                SetupStep.Welcome -> WelcomePage(onStart = { next() })
                SetupStep.Done -> DonePage(status, onFinish = { finish(it) }, onFix = { fix(it) })
                SetupStep.AutoStart -> AutoStartPage(onNext = { next() })
                else -> {
                    val g = s.isGranted(status)
                    PermissionPage(
                        spec = pageSpec(s, tutorials, s in denied),
                        granted = g,
                        autoAdvancing = g && i == index && !grantedOnArrival,
                        primaryLabel = when {
                            s == SetupStep.Unlock && !unlockSawWarning -> "Open Accessibility"
                            s == SetupStep.Unlock -> "Open App info"
                            s == SetupStep.Bluetooth && s in denied -> "Open App info"
                            s == SetupStep.PostNotifications && (s in denied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ->
                                "Open notification settings"
                            else -> pageSpec(s, tutorials, s in denied).action
                        },
                        onPrimary = { open(s) },
                        secondaryLabel = when {
                            s == SetupStep.Unlock && !unlockSawWarning -> "Already saw the warning? Open App info"
                            s == SetupStep.Unlock -> "I've done it, next"
                            pageSpec(s, tutorials, s in denied).badge == Badge.Required -> "Skip for now"
                            else -> "Skip"
                        },
                        onSecondary = {
                            if (s == SetupStep.Unlock && !unlockSawWarning) {
                                unlockSawWarning = true
                                open(s)
                            } else {
                                next()
                            }
                        },
                        onNext = { next() },
                        help = when {
                            g || s !in attempted -> null
                            else -> ({
                                StepHelp(
                                    step = s,
                                    status = status,
                                    denied = s in denied,
                                    onUnlock = { fix(SetupStep.Unlock) },
                                    onAppInfo = { context.startFirstResolvable(PermissionUtils.appDetailsIntent(context)) },
                                    onRetryWarning = { context.startFirstResolvable(PermissionUtils.accessibilitySettingsIntent(context)) },
                                )
                            })
                        },
                    )
                }
            }
        }
    }
}

private fun bringAppToFront(context: Context) {
    try {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    } catch (_: Exception) {
        // Background start blocked: the user simply comes back with the Back button.
    }
}

// ---- Page content -------------------------------------------------------------------------------

private enum class Badge(val label: String) { Required("Required"), Recommended("Recommended"), Optional("Optional") }

private class PageSpec(
    val icon: ImageVector,
    val badge: Badge,
    val title: String,
    val why: String,
    val tutorial: Tutorial,
    val action: String,
    val note: String = "Screens can look a little different on your phone.",
)

private fun pageSpec(step: SetupStep, t: Tutorials, denied: Boolean): PageSpec = when (step) {
    SetupStep.Unlock -> PageSpec(
        Icons.Outlined.LockOpen, Badge.Required,
        "Unlock Pixel Island's settings",
        "Android blocks Accessibility and Notification access for apps installed outside the Play Store. " +
            "Unlock them once and you're set.",
        t.unlock, "Open App info",
        note = "The ⋮ option only appears after you've tried the greyed-out switch once.",
    )
    SetupStep.Accessibility -> PageSpec(
        Icons.Outlined.AccessibilityNew, Badge.Required,
        "Put the island on your screen",
        "Pixel Island uses Accessibility only to draw the island above the status bar, around your camera. " +
            "Android calls this \"full control\", but Pixel Island keeps everything else off: " +
            "it can't read your screen or tap for you.",
        t.accessibility, "Open Accessibility settings",
        note = "Can't see it? Look under \"Downloaded apps\" or \"Installed apps\".",
    )
    SetupStep.Notifications -> PageSpec(
        Icons.Outlined.NotificationsActive, Badge.Required,
        "Bring live activities to the island",
        "Lets the island show your music, calls, timers and navigation. " +
            "Notifications are read on your phone and never leave it.",
        t.notificationAccess, "Open notification access",
    )
    SetupStep.PostNotifications -> PageSpec(
        Icons.Outlined.Notifications, Badge.Recommended,
        "Allow notifications",
        "Pixel Island sends just one quiet notification, and only while the island runs in backup mode " +
            "(without Accessibility). No ads, nothing else.",
        if (denied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) t.notificationSettings else t.postNotifications,
        "Allow",
    )
    SetupStep.Overlay -> PageSpec(
        Icons.Outlined.Layers, Badge.Recommended,
        "Hide during videos and games",
        "Lets the island step aside in fullscreen apps, and keeps a backup island running if " +
            "Accessibility is ever turned off.",
        t.overlay, "Open settings",
    )
    SetupStep.Battery -> PageSpec(
        Icons.Outlined.BatteryChargingFull, Badge.Recommended,
        "Keep the island running",
        "Stops Android from closing Pixel Island in the background. It does nothing while idle, " +
            "so battery use stays tiny.",
        t.battery, "Allow",
    )
    SetupStep.Bluetooth -> PageSpec(
        Icons.Outlined.Headphones, Badge.Optional,
        "Headphones and watch alerts",
        "Shows your earbuds or watch on the island when they connect, with their battery level.",
        t.nearbyDevices, "Allow",
    )
    SetupStep.Welcome, SetupStep.AutoStart, SetupStep.Done -> error("$step has its own page")
}

@Composable
private fun WizardTopBar(plan: List<SetupStep>, index: Int, onBack: (() -> Unit)?, onLater: (() -> Unit)?) {
    val step = plan[index]
    val counted = plan.count { it != SetupStep.Welcome && it != SetupStep.Done }
    val position = plan.take(index + 1).count { it != SetupStep.Welcome && it != SetupStep.Done }
    val fraction = when {
        step == SetupStep.Done || counted == 0 -> 1f
        step == SetupStep.Welcome -> 0f
        else -> position / counted.toFloat()
    }
    val progress by animateFloatAsState(fraction, label = "setupProgress")
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterStart) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            }
            Text(
                when (step) {
                    SetupStep.Welcome -> ""
                    SetupStep.Done -> "Setup complete"
                    else -> "Step $position of $counted"
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterEnd) {
                if (onLater != null) TextButton(onClick = onLater) { Text("Later") }
            }
        }
        if (step != SetupStep.Welcome) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun PermissionPage(
    spec: PageSpec,
    granted: Boolean,
    autoAdvancing: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onNext: () -> Unit,
    help: (@Composable () -> Unit)?,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                BadgeChip(spec.badge)
            }
            Spacer(Modifier.height(12.dp))
            Text(spec.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(spec.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            TutorialCard(spec.tutorial, spec.note)
            if (help != null) {
                Spacer(Modifier.height(12.dp))
                help()
            }
            Spacer(Modifier.height(16.dp))
        }
        StepActions(
            granted = granted,
            autoAdvancing = autoAdvancing,
            primaryLabel = primaryLabel,
            onPrimary = onPrimary,
            secondaryLabel = secondaryLabel,
            onSecondary = onSecondary,
            onNext = onNext,
        )
    }
}

@Composable
private fun TutorialCard(tutorial: Tutorial, note: String) {
    val state = rememberTutorialState(tutorial)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 330.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TutorialPhone(state)
                        Spacer(Modifier.width(14.dp))
                        TutorialSteps(state, Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        TutorialPhone(state)
                        Spacer(Modifier.height(14.dp))
                        TutorialSteps(state, Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepActions(
    granted: Boolean,
    autoAdvancing: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (granted) {
            SuccessBar(if (autoAdvancing) "Done! Moving on…" else "Already on")
            if (autoAdvancing) Spacer(Modifier.height(48.dp))
            else TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Next") }
        } else {
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(primaryLabel) }
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun SuccessBar(label: String) {
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(SuccessGreen.copy(alpha = 0.16f)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(26.dp).scale(pop.value))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BadgeChip(badge: Badge) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (badge) {
        Badge.Required -> scheme.errorContainer to scheme.onErrorContainer
        Badge.Recommended -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Badge.Optional -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = bg) {
        Text(badge.label, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Shown after a trip to Settings that didn't turn the permission on. */
@Composable
private fun StepHelp(
    step: SetupStep,
    status: PermissionStatus,
    denied: Boolean,
    onUnlock: () -> Unit,
    onAppInfo: () -> Unit,
    onRetryWarning: () -> Unit,
) {
    val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && status.restrictedSettingsAllowed != true
    val (text, action) = when {
        step == SetupStep.Unlock ->
            "Don't see **Allow restricted settings** in the ⋮ menu? Android only shows it after you've tapped " +
                "the greyed-out switch once. Try step 1 again." to ("Open Accessibility" to onRetryWarning)
        (step == SetupStep.Accessibility || step == SetupStep.Notifications) && restricted ->
            "Switch greyed out, or a **Restricted setting** message? Android blocks it for apps installed " +
                "outside the Play Store. Unlock it first, it only takes a moment." to ("Show me how" to onUnlock)
        step == SetupStep.PostNotifications && denied ->
            "Notifications were blocked. Tap **Open notification settings** and turn on **All Pixel Island notifications**." to null
        step == SetupStep.Bluetooth && denied ->
            "Permission was denied. Open **App info → Permissions → Nearby devices** and choose **Allow**." to
                ("Open App info" to onAppInfo)
        else -> "Not on yet? Find **Pixel Island** in the list and turn its switch on, then come back here." to null
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(boldMarkup(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (action != null) {
                TextButton(onClick = action.second, modifier = Modifier.padding(top = 4.dp)) { Text(action.first) }
            }
        }
    }
}

// ---- Welcome ------------------------------------------------------------------------------------

@Composable
private fun WelcomePage(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            IslandHero()
            Spacer(Modifier.height(28.dp))
            Text("Welcome to Pixel Island", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Music, calls, timers and alerts live in a little island around your camera. " +
                    "Let's switch it on. It takes about a minute.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    WelcomeRow(Icons.Outlined.AccessibilityNew, "Accessibility", "Puts the island over your camera", Badge.Required)
                    WelcomeRow(Icons.Outlined.NotificationsActive, "Notification access", "Music, calls, timers and more", Badge.Required)
                    WelcomeRow(Icons.Outlined.Tune, "A few extras", "Skip any you like", Badge.Optional)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "No internet permission. Nothing leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp),
        ) { Text("Let's go") }
    }
}

@Composable
private fun WelcomeRow(icon: ImageVector, title: String, subtitle: String, badge: Badge) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BadgeChip(badge)
    }
}

/** A looping preview of the island itself: idle pill → music → expanded card → back. */
@Composable
private fun IslandHero() {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val holds = longArrayOf(1_100, 1_700, 2_200, 1_300)
        while (true) {
            delay(holds[phase])
            phase = (phase + 1) % holds.size
        }
    }
    val compact = phase == 1 || phase == 3
    val expanded = phase == 2
    // Same spring as the real island's expand (AnimationSpec.EXPAND).
    val shape = spring<Dp>(dampingRatio = 0.74f, stiffness = 320f)
    val width by animateDpAsState(if (expanded) 250.dp else if (compact) 170.dp else 84.dp, shape, label = "heroW")
    val height by animateDpAsState(if (expanded) 84.dp else 28.dp, shape, label = "heroH")
    val radius by animateDpAsState(if (expanded) 30.dp else 14.dp, shape, label = "heroR")
    val compactAlpha by animateFloatAsState(if (compact) 1f else 0f, tween(220), label = "heroCompact")
    val expandedAlpha by animateFloatAsState(if (expanded) 1f else 0f, tween(260, delayMillis = if (expanded) 140 else 0), label = "heroExpanded")
    val art = Brush.linearGradient(listOf(Color(0xFFFF375F), Color(0xFFFF9F0A)))

    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface))),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 14.dp)
                .size(width, height)
                .clip(RoundedCornerShape(radius))
                .background(Color.Black),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp).alpha(compactAlpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(art))
                Spacer(Modifier.weight(1f))
                EqualizerBars()
                Spacer(Modifier.width(4.dp))
            }
            Row(
                Modifier.fillMaxSize().padding(12.dp).alpha(expandedAlpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(art))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.width(110.dp).height(9.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(5.dp)))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(70.dp).height(7.dp).background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(Color.White, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerBars() {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (offset in intArrayOf(0, 160, 320)) {
            val level by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(420, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(offset),
                ),
                label = "eqBar",
            )
            Box(Modifier.width(3.dp).fillMaxHeight(level).background(Color(0xFF30D158), RoundedCornerShape(2.dp)))
        }
    }
}

// ---- Brand auto-start ---------------------------------------------------------------------------

@Composable
private fun AutoStartPage(onNext: () -> Unit) {
    val context = LocalContext.current
    val guide = remember { OemAutoStart.guideFor(context) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                BadgeChip(Badge.Recommended)
            }
            Spacer(Modifier.height(12.dp))
            Text("Stop your phone from closing the island", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "${guide.brand} phones close background apps to save battery. These settings keep Pixel Island running.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    guide.steps.forEachIndexed { i, text ->
                        Row {
                            Box(
                                Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary) }
                            Spacer(Modifier.width(10.dp))
                            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        StepActions(
            granted = false,
            autoAdvancing = false,
            primaryLabel = "Open settings",
            onPrimary = { context.startFirstResolvable(*guide.intents.toTypedArray()) },
            secondaryLabel = "Done, next",
            onSecondary = onNext,
            onNext = onNext,
        )
    }
}

// ---- Done ---------------------------------------------------------------------------------------

@Composable
private fun DonePage(status: PermissionStatus, onFinish: (calibrate: Boolean) -> Unit, onFix: (SetupStep) -> Unit) {
    val ready = status.accessibility && status.notificationListener
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            val pop = remember { Animatable(0.3f) }
            LaunchedEffect(ready) {
                pop.snapTo(0.3f)
                pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f))
            }
            Box(
                Modifier
                    .size(96.dp)
                    .scale(pop.value)
                    .background(if (ready) SuccessGreen else MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (ready) Icons.Filled.Check else Icons.Filled.PriorityHigh,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (ready) "You're all set!" else "Almost there",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (ready) "The island is live. Play a song or start a timer to see it come to life."
                else "Turn on the two required switches so the island can work.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SummaryRow("Accessibility", status.accessibility, required = true) { onFix(SetupStep.Accessibility) }
                    SummaryRow("Notification access", status.notificationListener, required = true) { onFix(SetupStep.Notifications) }
                    SummaryRow("Notifications", status.postNotifications, required = false) { onFix(SetupStep.PostNotifications) }
                    SummaryRow("Display over other apps", status.overlay, required = false) { onFix(SetupStep.Overlay) }
                    SummaryRow("Unrestricted battery", status.ignoringBatteryOptimizations, required = false) { onFix(SetupStep.Battery) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SummaryRow("Nearby devices", status.bluetooth, required = false) { onFix(SetupStep.Bluetooth) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Button(onClick = { onFinish(false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (ready) "Start using Pixel Island" else "Finish anyway")
            }
            TextButton(onClick = { onFinish(true) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Fine-tune the island's position")
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, on: Boolean, required: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when {
                on -> Icons.Filled.CheckCircle
                required -> Icons.Outlined.ErrorOutline
                else -> Icons.Outlined.RemoveCircleOutline
            },
            contentDescription = if (on) "On" else "Off",
            tint = when {
                on -> SuccessGreen
                required -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (on) {
            Text("On", style = MaterialTheme.typography.labelLarge, color = SuccessGreen, modifier = Modifier.padding(end = 12.dp))
        } else {
            TextButton(onClick = onFix) { Text("Turn on") }
        }
    }
}
