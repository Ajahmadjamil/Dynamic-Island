package com.codewithaj.pixelisland.notifications

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.codewithaj.pixelisland.ServiceLocator
import com.codewithaj.pixelisland.data.PopUpChannel
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.IslandStateManager
import com.codewithaj.pixelisland.service.IslandAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Bound by the system once notification access is granted.
 *  - Keeps the media watcher alive (MediaSessionManager requires an enabled listener).
 *  - Turns ongoing notifications into live activities: calls, timers, navigation/progress
 *    ([LiveActivityParser]).
 *  - Turns other new notifications into island previews ([NotificationParser]).
 */
class IslandNotificationListener : NotificationListenerService() {

    private var scope: CoroutineScope? = null
    private val parser by lazy { NotificationParser(this) }
    private val live by lazy { LiveActivityParser(this) }

    /** Per-notification build job, so rapid updates (download progress) conflate. */
    private val liveJobs = HashMap<String, Job>()
    private val lastLivePost = HashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = WeakReference(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ServiceLocator.media.start()
        // Pick up calls/timers/navigation that were already running before we connected.
        try {
            activeNotifications?.forEach { handleLive(it) }
        } catch (_: Exception) { }
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
        ServiceLocator.media.stop()
        scope?.cancel()
        scope = null
        liveJobs.clear()
        // Live activities from notifications can't be kept up to date anymore.
        ServiceLocator.islandState.state.value.activities
            .filter { it.id.startsWith(LIVE_PREFIX) }
            .forEach { ServiceLocator.islandState.remove(it.id) }
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        if (handleLive(sbn)) return

        val ranking = rankingMap?.let { map -> Ranking().takeIf { map.getRanking(sbn.key, it) } }
        val settings = ServiceLocator.settingsState.value
        if (!parser.shouldShow(sbn, ranking, settings, IslandAccessibilityService.foregroundPackage.value)) return
        // One banner, never two. Android pops a notification up itself when its chat type
        // (channel) has "Pop on screen" on (importance HIGH), and no app can switch that off
        // for another app. So: if Android will pop this one up, let it, and list the channel
        // in Settings with a one-tap fix. Once the user turns "Pop on screen" off for it,
        // Android stays quiet and Islet shows its card instead. On the lock screen Android
        // doesn't pop up, so Islet shows the card there.
        val popsNatively = ranking != null &&
            ranking.importance >= NotificationManager.IMPORTANCE_HIGH && !keyguardLocked()
        trackPopUpChannel(sbn, ranking, popsNatively)
        if (popsNatively) return
        val s = scope ?: return
        s.launch {
            // Icon decoding/scaling off the main thread.
            val preview = withContext(Dispatchers.Default) { parser.build(sbn) } ?: return@launch
            ServiceLocator.islandState.showAlert(preview, IslandStateManager.ALERT_NOTIFICATION_MS)
        }
    }

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private fun keyguardLocked() = keyguard?.isKeyguardLocked == true

    /**
     * Remembers which notification channels of island-allowed apps still pop up natively
     * ("Pop on screen" on), so Settings can offer a one-tap fix that removes the double banner
     * for good. A channel leaves the list once it arrives without popping up.
     */
    private fun trackPopUpChannel(sbn: StatusBarNotification, ranking: Ranking?, popsNatively: Boolean) {
        val channel = ranking?.channel ?: return
        val prefix = PopUpChannel.prefix(sbn.packageName, channel.id)
        val current = ServiceLocator.settingsState.value.popUpChannels
        val listed = current.any { it.startsWith(prefix) }
        if (popsNatively == listed) return
        val entry = PopUpChannel.encode(sbn.packageName, channel.id, channel.name?.toString().orEmpty())
        ServiceLocator.appScope.launch {
            ServiceLocator.settings.update { st ->
                val without = st.popUpChannels.filterNot { it.startsWith(prefix) }.toSet()
                st.copy(popUpChannels = if (popsNatively) without + entry else without)
            }
        }
    }

    /**
     * Calls, timers, navigation/progress → live activity (posted/updated, or removed when the
     * notification stops qualifying). Returns true if the notification was a live activity.
     */
    private fun handleLive(sbn: StatusBarNotification): Boolean {
        val id = LIVE_PREFIX + sbn.key
        val kind = live.classify(sbn, ServiceLocator.settingsState.value)
        if (kind == null) {
            liveJobs.remove(sbn.key)?.cancel()
            ServiceLocator.islandState.remove(id)
            return false
        }
        val s = scope ?: return true
        liveJobs.remove(sbn.key)?.cancel()
        // Throttle very chatty updates (downloads report every few ms) to ~4 per second.
        val since = SystemClock.uptimeMillis() - (lastLivePost[sbn.key] ?: 0L)
        val wait = if (kind == LiveActivityParser.Kind.CALL) 0L else (LIVE_MIN_INTERVAL_MS - since).coerceAtLeast(0L)
        liveJobs[sbn.key] = s.launch {
            if (wait > 0) delay(wait)
            val activity = withContext(Dispatchers.Default) { live.build(sbn, kind) }
            if (activity != null) {
                lastLivePost[sbn.key] = SystemClock.uptimeMillis()
                ServiceLocator.islandState.post(activity)
            }
            liveJobs.remove(sbn.key)
        }
        return true
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        liveJobs.remove(key)?.cancel()
        lastLivePost.remove(key)
        ServiceLocator.islandState.remove(LIVE_PREFIX + key)
        // Read/dismissed elsewhere → take the preview down too.
        val alert = ServiceLocator.islandState.state.value.alert
        if (alert is IslandActivity.NotificationPreview && alert.key == key) {
            ServiceLocator.islandState.dismissAlert()
        }
    }

    companion object {
        private const val LIVE_PREFIX = "n:"
        private const val LIVE_MIN_INTERVAL_MS = 250L

        @Volatile
        private var instance: WeakReference<IslandNotificationListener>? = null

        /** Dismisses a notification after the user opened it from the island (auto-cancel). */
        fun cancel(key: String) {
            try { instance?.get()?.cancelNotification(key) } catch (_: Exception) { }
        }
    }
}
