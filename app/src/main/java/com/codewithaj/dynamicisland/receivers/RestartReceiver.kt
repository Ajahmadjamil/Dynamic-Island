package com.codewithaj.dynamicisland.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codewithaj.dynamicisland.ServiceLocator
import com.codewithaj.dynamicisland.service.OverlayFallbackService
import com.codewithaj.dynamicisland.util.PermissionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings the fallback overlay back after a reboot or an app update. (The accessibility service
 * is rebound by the system on its own; the fallback foreground service isn't.)
 * Both broadcasts are exempt from the background foreground-service start restrictions.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                val s = ServiceLocator.settings.settings.first()
                val p = PermissionUtils.status(context)
                if (s.islandEnabled && s.useFallbackOverlay && p.overlay && !p.accessibility) {
                    OverlayFallbackService.start(context)
                }
            } catch (_: Exception) {
                // Never crash at boot.
            } finally {
                pending.finish()
            }
        }
    }
}
