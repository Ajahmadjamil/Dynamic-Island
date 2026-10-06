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
    data class Demo(val variant: Variant, val startedAtMs: Long) : IslandActivity {
        enum class Variant { MUSIC, TIMER }
        override val id get() = "demo"
        override val priority get() = 0
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

    companion object {
        // Calls > navigation > timers > media > generic, filled in by later phases.
        const val PRIORITY_MEDIA = 50
    }
}

data class IslandUiState(
    val activities: List<IslandActivity> = emptyList(),
    val expanded: Boolean = false,
    /** Transient alert; temporarily shown instead of [primary]. */
    val alert: IslandActivity.Alert? = null,
) {
    val primary: IslandActivity? get() = activities.maxByOrNull { it.priority }
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
        val list = if (i >= 0) s.activities.toMutableList().also { it[i] = activity } else s.activities + activity
        s.copy(activities = list)
    }

    fun remove(id: String) = _state.update { s ->
        if (s.activities.none { it.id == id }) return@update s
        val left = s.activities.filterNot { it.id == id }
        s.copy(activities = left, expanded = s.expanded && left.isNotEmpty())
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
