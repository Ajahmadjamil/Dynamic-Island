package com.codewithaj.dynamicisland.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.codewithaj.dynamicisland.R
import com.codewithaj.dynamicisland.ui.MainActivity

/**
 * Fallback host used when the accessibility service is off but "Display over other apps" is
 * granted. A foreground service keeps it alive; the window type is TYPE_APPLICATION_OVERLAY, so
 * the pill renders *under* the (transparent) status bar icons rather than above them.
 */
class OverlayFallbackService : Service() {

    private var controller: IslandOverlayController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (IslandAccessibilityService.running.value || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (controller == null) {
            controller = IslandOverlayController(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                .also { it.start() }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.fallback_channel_name), NotificationManager.IMPORTANCE_MIN)
                    .apply { description = getString(R.string.fallback_channel_desc); setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(getString(R.string.fallback_notification_title))
            .setContentText(getString(R.string.fallback_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "island_fallback"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayFallbackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayFallbackService::class.java))
        }
    }
}
