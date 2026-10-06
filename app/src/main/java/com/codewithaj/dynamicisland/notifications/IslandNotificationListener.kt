package com.codewithaj.dynamicisland.notifications

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.codewithaj.dynamicisland.ServiceLocator
import com.codewithaj.dynamicisland.island.IslandActivity
import com.codewithaj.dynamicisland.island.IslandStateManager
import com.codewithaj.dynamicisland.service.IslandAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Bound by the system once notification access is granted.
 *  - Keeps the media watcher alive (MediaSessionManager requires an enabled listener).
 *  - Turns other apps' notifications into island previews (filtering in [NotificationParser]).
 */
class IslandNotificationListener : NotificationListenerService() {

    private var scope: CoroutineScope? = null
    private val parser by lazy { NotificationParser(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = WeakReference(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ServiceLocator.media.start()
        HeadsUpSuppressor.listenerConnected = true
        HeadsUpSuppressor.sync(this)
    }

    override fun onListenerDisconnected() {
        teardown()
        super.onListenerDisconnected()
        // Some OEMs unbind listeners under memory pressure and never rebind on their own.
        try {
            requestRebind(ComponentName(this, IslandNotificationListener::class.java))
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        // Without the listener Islet can't show previews → give the system its banners back.
        HeadsUpSuppressor.listenerConnected = false
        HeadsUpSuppressor.sync(this)
        ServiceLocator.media.stop()
        scope?.cancel()
        scope = null
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        val ranking = rankingMap?.let { map -> Ranking().takeIf { map.getRanking(sbn.key, it) } }
        val settings = ServiceLocator.settingsState.value
        if (!parser.shouldShow(sbn, ranking, settings, IslandAccessibilityService.foregroundPackage.value)) return
        val s = scope ?: return
        s.launch {
            // Icon decoding/scaling off the main thread.
            val preview = withContext(Dispatchers.Default) { parser.build(sbn) } ?: return@launch
            ServiceLocator.islandState.showAlert(preview, IslandStateManager.ALERT_NOTIFICATION_MS)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        // Read/dismissed elsewhere → take the preview down too.
        val alert = ServiceLocator.islandState.state.value.alert
        if (alert is IslandActivity.NotificationPreview && alert.key == key) {
            ServiceLocator.islandState.dismissAlert()
        }
    }

    companion object {
        @Volatile
        private var instance: WeakReference<IslandNotificationListener>? = null

        /** Dismisses a notification after the user opened it from the island (auto-cancel). */
        fun cancel(key: String) {
            try { instance?.get()?.cancelNotification(key) } catch (_: Exception) { }
        }
    }
}
