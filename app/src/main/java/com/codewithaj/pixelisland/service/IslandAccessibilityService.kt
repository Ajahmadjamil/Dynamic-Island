package com.codewithaj.pixelisland.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Primary host for the island. TYPE_ACCESSIBILITY_OVERLAY windows can be drawn above the status
 * bar, which is what lets the pill sit around the camera cutout. The service declares no
 * capabilities beyond window-state events (see res/xml/accessibility_service_config.xml).
 */
class IslandAccessibilityService : AccessibilityService() {

    private var controller: IslandOverlayController? = null
    private var imePackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        _running.value = true
        imePackages = try {
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
                ?.mapTo(HashSet()) { it.packageName }.orEmpty()
        } catch (_: Exception) { emptySet() }
        // Avoid two islands: the fallback host stands down while we're connected.
        stopService(Intent(this, OverlayFallbackService::class.java))
        controller = IslandOverlayController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            .also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The keyboard popping up over Spotify doesn't mean Spotify left the foreground.
        if (pkg in imePackages || pkg == packageName) return
        _foregroundPackage.value = pkg
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        controller?.stop()
        controller = null
        _running.value = false
        _foregroundPackage.value = null
    }

    companion object {
        private val _running = MutableStateFlow(false)
        /** Whether the service is connected in this (the island) process. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        /**
         * App currently in front (from window-state events). Like iOS, the island steps aside
         * while the app that owns the live activity is open. Null when accessibility is off.
         */
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()
    }
}
