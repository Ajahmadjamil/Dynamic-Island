package com.codewithaj.pixelisland.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codewithaj.pixelisland.ServiceLocator
import com.codewithaj.pixelisland.ui.adb.AdbCommandsScreen
import com.codewithaj.pixelisland.ui.apps.AppFilterScreen
import com.codewithaj.pixelisland.ui.calibration.CalibrationScreen
import com.codewithaj.pixelisland.ui.home.HomeScreen
import com.codewithaj.pixelisland.ui.onboarding.OemGuideScreen
import com.codewithaj.pixelisland.ui.onboarding.OnboardingScreen
import com.codewithaj.pixelisland.ui.setup.SetupWizardScreen
import com.codewithaj.pixelisland.ui.setup.needsSetup
import com.codewithaj.pixelisland.util.PermissionUtils

enum class Screen { Home, Setup, Onboarding, Calibration, OemGuide, AppFilter, Adb }

/**
 * Tiny back-stack navigation. A handful of screens don't justify navigation-compose's footprint.
 */
@Composable
fun IsletNavHost() {
    val context = LocalContext.current
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val current = settings ?: return // first DataStore read takes a few ms

    val stack = rememberSaveable(
        saver = listSaver(save = { it.map(Screen::name) }, restore = { l -> mutableStateListOf(*l.map(Screen::valueOf).toTypedArray()) }),
    ) {
        // Guided setup opens by itself on first run and whenever a required switch is off
        // (e.g. Android turned Accessibility off after an update).
        val start = if (needsSetup(current, PermissionUtils.status(context))) Screen.Setup else Screen.Home
        mutableStateListOf(start)
    }
    val top = stack.last()
    val navigate: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    /** Leave a screen that may be the root (setup on launch) for Home. */
    val leave: () -> Unit = { if (stack.size > 1) back() else { stack.clear(); stack.add(Screen.Home) } }

    BackHandler(enabled = stack.size > 1, onBack = back)

    AnimatedContent(targetState = top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(settings = current, navigate = navigate)
            Screen.Setup -> SetupWizardScreen(
                firstRun = !current.onboardingDone,
                onFinish = { calibrate ->
                    leave()
                    if (calibrate) navigate(Screen.Calibration)
                },
                onLater = leave,
            )
            Screen.Onboarding -> OnboardingScreen(
                showBack = stack.size > 1,
                onBack = back,
                onOemGuide = { navigate(Screen.OemGuide) },
                onAdb = { navigate(Screen.Adb) },
                onFinish = leave,
            )
            Screen.Calibration -> CalibrationScreen(settings = current, onBack = back)
            Screen.OemGuide -> OemGuideScreen(onBack = back)
            Screen.AppFilter -> AppFilterScreen(settings = current, onBack = back)
            Screen.Adb -> AdbCommandsScreen(onBack = back)
        }
    }
}
