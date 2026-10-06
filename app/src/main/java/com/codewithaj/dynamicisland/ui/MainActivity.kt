package com.codewithaj.dynamicisland.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.codewithaj.dynamicisland.ServiceLocator
import com.codewithaj.dynamicisland.service.OverlayFallbackService
import com.codewithaj.dynamicisland.ui.theme.IsletTheme
import com.codewithaj.dynamicisland.util.PermissionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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

    private suspend fun syncFallbackService() {
        val s = ServiceLocator.settings.settings.first()
        val p = PermissionUtils.status(this)
        if (s.islandEnabled && s.useFallbackOverlay && !p.accessibility && p.overlay) {
            OverlayFallbackService.start(this)
        } else if (!s.useFallbackOverlay || p.accessibility) {
            OverlayFallbackService.stop(this)
        }
    }
}
