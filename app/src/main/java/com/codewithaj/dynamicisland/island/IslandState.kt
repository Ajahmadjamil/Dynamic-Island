package com.codewithaj.dynamicisland.island

import android.app.PendingIntent
import android.graphics.Bitmap
import com.codewithaj.dynamicisland.media.MediaInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the island shape is doing. */
enum class IslandMode { HIDDEN, IDLE, COMPACT, EXPANDED }

/** Something shown on the island: a long-lived live activity or a short transient alert. */
sealed interface IslandActivity {
    /** Stable identity, e.g. "media". Same id = update in place. */
    val id: String
    /** Higher wins the main pill. */
    val priority: Int
    /**
     * When this changes the content cross-fades (e.g. a new track). Changes that keep the key
     * (playback position, play/pause) update in place without an animation.
     */
    val contentKey: Any get() = id
    /** App the activity belongs to; used by the per-app filter and "hide over own app". */
    val packageName: String? get() = null
    /** Fired when the island is tapped (iOS: open the app). Null = tap expands. */
    val tapIntent: PendingIntent? get() = null

    // ---- Live activities ----------------------------------------------------------------------

    /** Animation playground content (Home → Animation playground). */
    data class Demo(val variant: Variant, val startedAtMs: Long, val slot: Int = 0) : IslandActivity {
        enum class Variant { MUSIC, TIMER }
        /** slot 1 is a second demo activity, to try the split bubble. */
        override val id get() = "demo$slot"
        override val priority get() = if (slot == 0) 1 else 0
        override val contentKey: Any get() = variant
    }

    data class Media(val info: MediaInfo) : IslandActivity {
        override val id get() = "media"
        override val priority get() = PRIORITY_MEDIA
        override val contentKey: Any get() = info.trackKey
        override val packageName get() = info.packageName
        override val tapIntent get() = info.sessionActivity
    }

    // ---- Transient alerts (shown in the alert slot, then the previous state comes back) --------
    // [shownAtMs] is SystemClock.uptimeMillis() when raised; renderers animate relative to it.

    sealed interface Alert : IslandActivity {
        val shownAtMs: Long
        override val priority get() = Int.MAX_VALUE
        override val contentKey: Any get() = "$id@$shownAtMs"
        /** Card alerts use the expanded shape; the others a wide compact pill. */
        val isCard: Boolean get() = false
    }

    data class Charging(val level: Int, val wireless: Boolean, override val shownAtMs: Long) : Alert {
        override val id get() = "alert:charging"
    }

    data class LowBattery(val level: Int, override val shownAtMs: Long) : Alert {
        override val id get() = "alert:low_battery"
    }

    /** [mode] is an AudioManager.RINGER_MODE_* constant. */
    data class Ringer(val mode: Int, override val shownAtMs: Long) : Alert {
        override val id get() = "alert:ringer"
    }

    data class BluetoothDevice(
        val name: String,
        val address: String,
        val kind: Kind,
        /** 0–100, or null if the device doesn't report it (yet). */
        val battery: Int?,
        override val shownAtMs: Long,
    ) : Alert {
        enum class Kind { HEADPHONES, WATCH, OTHER }
        override val id get() = "alert:bluetooth"
        // Battery level arriving a moment after "connected" updates in place, no cross-fade.
        override val contentKey: Any get() = "$id@$address@$shownAtMs"
    }

    /** VPN / hotspot switched on or off. */
    data class Connectivity(val kind: Kind, val on: Boolean, override val shownAtMs: Long) : Alert {
        enum class Kind { VPN, HOTSPOT }
        override val id get() = "alert:connectivity"
    }

    data class NotificationPreview(
        val key: String,
        override val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        /** Sender avatar or app icon, already downscaled. */
        val icon: Bitmap?,
        val iconIsAvatar: Boolean,
        val contentIntent: PendingIntent?,
        val autoCancel: Boolean,
        override val shownAtMs: Long,
    ) : Alert {
        override val id get() = "alert:notification"
        override val tapIntent get() = contentIntent
        override val isCard get() = true
        override val contentKey: Any get() = "$id@$key@$shownAtMs"
    }

    // ---- Live activities from other apps' notifications (Phase 5) ------------------------------
    // ids are "n:<notification key>", so a removed notification removes its activity.

    data class Call(
        val key: String,
        override val packageName: String,
        val caller: String,
        val avatar: Bitmap?,
        val incoming: Boolean,
        /** Wall-clock call start (System.currentTimeMillis), null while not connected. */
        val startedAtWallMs: Long?,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
        val hangUp: PendingIntent?,
        val contentIntent: PendingIntent?,
    ) : IslandActivity {
        override val id get() = "n:$key"
        override val priority get() = if (incoming) PRIORITY_CALL_INCOMING else PRIORITY_CALL_ONGOING
        // Incoming → ongoing is a real content change (buttons → timer), so it cross-fades.
        override val contentKey: Any get() = "$id@$incoming"
        override val tapIntent get() = contentIntent
    }

    data class Timer(
        val key: String,
        override val packageName: String,
        val label: String,
        val countDown: Boolean,
        /** Count-down: wall-clock end time. Stopwatch: wall-clock start time. */
        val chronometerBaseWallMs: Long?,
        /** Shown instead of a running clock when paused (the app's own text, e.g. "4:12"). */
        val pausedText: String?,
        val actions: List<LiveAction>,
        val contentIntent: PendingIntent?,
    ) : IslandActivity {
        val paused get() = chronometerBaseWallMs == null
        override val id get() = "n:$key"
        override val priority get() = PRIORITY_TIMER
        override val tapIntent get() = contentIntent
    }

    /** Navigation, deliveries, rides, downloads: ongoing notifications with progress / Live Updates. */
    data class Progress(
        val key: String,
        override val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        /** Live Update chip text (Android 16 "short critical text"), e.g. "5 min", "200 m". */
        val shortText: String?,
        val icon: Bitmap?,
        val progress: Int,
        val max: Int,
        val indeterminate: Boolean,
        val navigation: Boolean,
        val contentIntent: PendingIntent?,
    ) : IslandActivity {
        val fraction get() = if (max > 0) (progress.toFloat() / max).coerceIn(0f, 1f) else -1f
        override val id get() = "n:$key"
        override val priority get() = if (navigation) PRIORITY_NAVIGATION else PRIORITY_PROGRESS
        override val tapIntent get() = contentIntent
    }

    companion object {
        // Calls > navigation > timers > media > progress.
        const val PRIORITY_CALL_INCOMING = 100
        const val PRIORITY_CALL_ONGOING = 90
        const val PRIORITY_NAVIGATION = 70
        const val PRIORITY_TIMER = 60
        const val PRIORITY_MEDIA = 50
        const val PRIORITY_PROGRESS = 40
    }
}

/** A button taken from another app's notification (label + its PendingIntent). */
data class LiveAction(val label: String, val intent: PendingIntent)

data class IslandUiState(
    val activities: List<IslandActivity> = emptyList(),
    val expanded: Boolean = false,
    /** Transient alert; temporarily shown instead of [primary]. */
    val alert: IslandActivity.Alert? = null,
    /** Set by "swipe sideways" to put a specific activity in the main pill. */
    val preferredPrimaryId: String? = null,
) {
    val primary: IslandActivity? get() = pick(activities).first

    /**
     * Picks (main pill, split bubble) from [visible]. An incoming call always wins; otherwise
     * the user's swipe choice, otherwise priority.
     */
    fun pick(visible: List<IslandActivity>): Pair<IslandActivity?, IslandActivity?> {
        if (visible.isEmpty()) return null to null
        val urgent = visible.firstOrNull { it is IslandActivity.Call && it.incoming }
        val main = urgent
            ?: visible.firstOrNull { it.id == preferredPrimaryId }
            ?: visible.maxBy { it.priority }
        val second = visible.filter { it !== main }.maxByOrNull { it.priority }
        return main to second
    }
}

/**
 * Single source of truth for what the island shows.
 *
 * Alerts live in their own slot: showing one doesn't touch the activity list or the expanded
 * flag, so when it times out the island simply returns to whatever it was showing before.
 */
class IslandStateManager {

    private val _state = MutableStateFlow(IslandUiState())
    val state: StateFlow<IslandUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var alertJob: Job? = null

    fun post(activity: IslandActivity) = _state.update { s ->
        val i = s.activities.indexOfFirst { it.id == activity.id }
        val previous = if (i >= 0) s.activities[i] else null
        val list = if (i >= 0) s.activities.toMutableList().also { it[i] = activity } else s.activities + activity
        // A call that just started ringing opens the island (iOS); the user can still collapse it.
        val newRing = activity is IslandActivity.Call && activity.incoming &&
            (previous as? IslandActivity.Call)?.incoming != true
        // A call that was just answered goes back to compact.
        val answered = activity is IslandActivity.Call && !activity.incoming &&
            (previous as? IslandActivity.Call)?.incoming == true
        s.copy(
            activities = list,
            expanded = when {
                newRing -> true
                answered -> false
                else -> s.expanded
            },
        )
    }

    fun remove(id: String) = _state.update { s ->
        if (s.activities.none { it.id == id }) return@update s
        val left = s.activities.filterNot { it.id == id }
        s.copy(
            activities = left,
            expanded = s.expanded && left.isNotEmpty() && s.primary?.id != id,
            preferredPrimaryId = s.preferredPrimaryId.takeUnless { it == id },
        )
    }

    /** Swipe sideways / tap bubble: the bubble's activity takes the main pill. */
    fun swap(visibleSecondaryId: String) = _state.update {
        it.copy(preferredPrimaryId = visibleSecondaryId, expanded = false)
    }

    fun expand() = _state.update { if (it.primary != null) it.copy(expanded = true) else it }

    fun collapse() = _state.update { it.copy(expanded = false) }

    fun clear() = _state.update { IslandUiState() }

    // ---- Alerts ---------------------------------------------------------------------------------

    /** Shows [alert] for [durationMs], replacing any alert already on screen. */
    fun showAlert(alert: IslandActivity.Alert, durationMs: Long) {
        alertJob?.cancel()
        _state.update { it.copy(alert = alert) }
        alertJob = scope.launch {
            delay(durationMs)
            _state.update { if (it.alert?.contentKey == alert.contentKey) it.copy(alert = null) else it }
        }
    }

    /** Updates the visible alert in place (same content key), e.g. a late battery reading. */
    fun updateAlert(transform: (IslandActivity.Alert) -> IslandActivity.Alert?) = _state.update { s ->
        val a = s.alert ?: return@update s
        s.copy(alert = transform(a))
    }

    fun dismissAlert() {
        alertJob?.cancel()
        alertJob = null
        _state.update { if (it.alert != null) it.copy(alert = null) else it }
    }

    companion object {
        const val ALERT_SHORT_MS = 2_600L
        const val ALERT_NOTIFICATION_MS = 4_500L
    }
}
