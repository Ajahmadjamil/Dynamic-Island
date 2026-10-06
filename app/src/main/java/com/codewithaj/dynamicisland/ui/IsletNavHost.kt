package com.codewithaj.dynamicisland.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codewithaj.dynamicisland.ServiceLocator
import com.codewithaj.dynamicisland.ui.apps.AppFilterScreen
import com.codewithaj.dynamicisland.ui.calibration.CalibrationScreen
import com.codewithaj.dynamicisland.ui.home.HomeScreen
import com.codewithaj.dynamicisland.ui.onboarding.OemGuideScreen
import com.codewithaj.dynamicisland.ui.onboarding.OnboardingScreen

enum class Screen { Home, Onboarding, Calibration, OemGuide, AppFilter }

/**
 * Tiny back-stack navigation. A handful of screens don't justify navigation-compose's footprint.
 */
@Composable
fun IsletNavHost() {
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val current = settings ?: return // first DataStore read takes a few ms

    val stack = rememberSaveable(
        saver = listSaver(save = { it.map(Screen::name) }, restore = { l -> mutableStateListOf(*l.map(Screen::valueOf).toTypedArray()) }),
    ) {
        mutableStateListOf(if (current.onboardingDone) Screen.Home else Screen.Onboarding)
    }
    val top = stack.last()
    val navigate: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1, onBack = back)

    AnimatedContent(targetState = top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(settings = current, navigate = navigate)
            Screen.Onboarding -> OnboardingScreen(
                showBack = stack.size > 1,
                onBack = back,
                onOemGuide = { navigate(Screen.OemGuide) },
                onFinish = {
                    if (stack.size > 1) back() else { stack.clear(); stack.add(Screen.Home) }
                },
            )
            Screen.Calibration -> CalibrationScreen(settings = current, onBack = back)
            Screen.OemGuide -> OemGuideScreen(onBack = back)
            Screen.AppFilter -> AppFilterScreen(settings = current, onBack = back)
        }
    }
}
