package com.codewithaj.dynamicisland

import android.content.Context
import com.codewithaj.dynamicisland.data.IslandSettings
import com.codewithaj.dynamicisland.data.SettingsRepository
import com.codewithaj.dynamicisland.island.IslandStateManager
import com.codewithaj.dynamicisland.media.MediaSessionWatcher
import com.codewithaj.dynamicisland.receivers.SystemEventsMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Manual DI. Everything is lazy so each process only builds what it touches
 * (the ":ui" process and the island process each get their own instances).
 */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val context: Context
        get() = checkNotNull(appContext) { "ServiceLocator.init() not called" }

    val settings: SettingsRepository by lazy { SettingsRepository(context) }

    /** Process-wide scope for fire-and-forget writes that must outlive a screen. */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /**
     * Latest settings as a synchronous value, for event handlers that can't suspend
     * (broadcast receivers, notification callbacks). Island process only.
     */
    val settingsState: StateFlow<IslandSettings> by lazy {
        settings.settings.stateIn(appScope, SharingStarted.Eagerly, IslandSettings())
    }

    /** Island process only (the UI process never touches it). */
    val islandState: IslandStateManager by lazy { IslandStateManager() }

    /** Island process only. Started/stopped by the notification listener. */
    val media: MediaSessionWatcher by lazy { MediaSessionWatcher(context, islandState) }

    /** Island process only. Started/stopped with the overlay controller. */
    val systemEvents: SystemEventsMonitor by lazy { SystemEventsMonitor(context, islandState, settingsState) }
}
