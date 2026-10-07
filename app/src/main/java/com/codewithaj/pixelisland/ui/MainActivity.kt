package com.codewithaj.pixelisland.ui

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.codewithaj.pixelisland.ServiceLocator
import com.codewithaj.pixelisland.service.OverlayFallbackService
import com.codewithaj.pixelisland.ui.theme.IsletTheme
import com.codewithaj.pixelisland.util.PermissionUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        liveInstances++
        setContent {
            IsletTheme { IsletNavHost() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Settings is the moment permissions change: (re)start the fallback
        // host if it's the only way to show the island. It stops itself if accessibility is on.
        lifecycleScope.launch { syncFallbackService() }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveInstances--
        // The settings UI runs in its own ":ui" process (Compose, Material3, icons). Android
        // would keep it cached at 100+ MB after you leave; nothing else lives in it, so when the
        // user backs out we let pending DataStore writes finish and then exit the process.
        // The island itself runs in the main process and is unaffected.
        if (isFinishing && !isChangingConfigurations) {
            ServiceLocator.appScope.launch {
                delay(UI_EXIT_DELAY_MS)
                if (liveInstances == 0) Process.killProcess(Process.myPid())
            }
        }
    }

    private suspend fun syncFallbackService() {
        val s = ServiceLocator.settings.settings.first()
        val p = PermissionUtils.status(this)
        if (s.islandEnabled && s.useFallbackOverlay && !p.accessibility && p.overlay) {
            OverlayFallbackService.start(this)
        } else if (!s.useFallbackOverlay || p.accessibility) {
            OverlayFallbackService.stop(this)
        }
    }

    private companion object {
        /** Activities alive in this (":ui") process. */
        @Volatile var liveInstances = 0
        const val UI_EXIT_DELAY_MS = 1_500L
    }
}
