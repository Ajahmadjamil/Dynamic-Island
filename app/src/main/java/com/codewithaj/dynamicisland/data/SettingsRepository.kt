package com.codewithaj.dynamicisland.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/**
 * Settings shared between the island process and the ":ui" process.
 *
 * A *multi-process* DataStore is required: the regular one caches in memory per process and
 * would never notice writes made by the other process. With this one, a slider moved on the
 * calibration screen reaches the running overlay within a few milliseconds.
 */
class SettingsRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = MultiProcessDataStoreFactory.create(
        serializer = PreferencesFileSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { File(context.applicationContext.filesDir, "datastore/islet_settings.preferences_pb") },
    )

    val settings: Flow<IslandSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (IslandSettings) -> IslandSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    suspend fun updateCalibration(transform: (Calibration) -> Calibration) =
        update { it.copy(calibration = transform(it.calibration)) }

    private fun Preferences.toSettings(): IslandSettings {
        val d = IslandSettings()
        val c = d.calibration
        return IslandSettings(
            islandEnabled = this[K.enabled] ?: d.islandEnabled,
            showIdlePill = this[K.showIdle] ?: d.showIdlePill,
            hideInLandscape = this[K.hideLandscape] ?: d.hideInLandscape,
            hideInFullscreen = this[K.hideFullscreen] ?: d.hideInFullscreen,
            useFallbackOverlay = this[K.fallback] ?: d.useFallbackOverlay,
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
            calibrating = this[K.calibrating] ?: d.calibrating,
            calibration = Calibration(
                offsetXDp = this[K.offX] ?: c.offsetXDp,
                offsetYDp = this[K.offY] ?: c.offsetYDp,
                widthDp = this[K.width] ?: c.widthDp,
                heightDp = this[K.height] ?: c.heightDp,
                cornerRadiusDp = this[K.radius] ?: c.cornerRadiusDp,
            ),
            animationSpeed = this[K.speed]
                ?.let { name -> AnimationSpeed.entries.firstOrNull { it.name == name } }
                ?: d.animationSpeed,
            showMedia = this[K.showMedia] ?: d.showMedia,
            alertCharging = this[K.alertCharging] ?: d.alertCharging,
            alertRinger = this[K.alertRinger] ?: d.alertRinger,
            alertBluetooth = this[K.alertBluetooth] ?: d.alertBluetooth,
            notificationPreviews = this[K.previews] ?: d.notificationPreviews,
            replaceSystemHeadsUp = this[K.replaceHeadsUp] ?: d.replaceSystemHeadsUp,
            appFilterMode = this[K.filterMode]
                ?.let { name -> AppFilterMode.entries.firstOrNull { it.name == name } }
                ?: d.appFilterMode,
            appFilterPackages = this[K.filterPackages] ?: d.appFilterPackages,
        )
    }

    private fun MutablePreferences.write(s: IslandSettings) {
        this[K.enabled] = s.islandEnabled
        this[K.showIdle] = s.showIdlePill
        this[K.hideLandscape] = s.hideInLandscape
        this[K.hideFullscreen] = s.hideInFullscreen
        this[K.fallback] = s.useFallbackOverlay
        this[K.onboardingDone] = s.onboardingDone
        this[K.calibrating] = s.calibrating
        this[K.offX] = s.calibration.offsetXDp
        this[K.offY] = s.calibration.offsetYDp
        this[K.width] = s.calibration.widthDp
        this[K.height] = s.calibration.heightDp
        this[K.radius] = s.calibration.cornerRadiusDp
        this[K.speed] = s.animationSpeed.name
        this[K.showMedia] = s.showMedia
        this[K.alertCharging] = s.alertCharging
        this[K.alertRinger] = s.alertRinger
        this[K.alertBluetooth] = s.alertBluetooth
        this[K.previews] = s.notificationPreviews
        this[K.replaceHeadsUp] = s.replaceSystemHeadsUp
        this[K.filterMode] = s.appFilterMode.name
        this[K.filterPackages] = s.appFilterPackages
    }

    private object K {
        val enabled = booleanPreferencesKey("island_enabled")
        val showIdle = booleanPreferencesKey("show_idle_pill")
        val hideLandscape = booleanPreferencesKey("hide_landscape")
        val hideFullscreen = booleanPreferencesKey("hide_fullscreen")
        val fallback = booleanPreferencesKey("use_fallback_overlay")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val calibrating = booleanPreferencesKey("calibrating")
        val offX = floatPreferencesKey("cal_offset_x")
        val offY = floatPreferencesKey("cal_offset_y")
        val width = floatPreferencesKey("cal_width")
        val height = floatPreferencesKey("cal_height")
        val radius = floatPreferencesKey("cal_radius")
        val speed = stringPreferencesKey("animation_speed")
        val showMedia = booleanPreferencesKey("show_media")
        val alertCharging = booleanPreferencesKey("alert_charging")
        val alertRinger = booleanPreferencesKey("alert_ringer")
        val alertBluetooth = booleanPreferencesKey("alert_bluetooth")
        val previews = booleanPreferencesKey("notification_previews")
        val replaceHeadsUp = booleanPreferencesKey("replace_system_heads_up")
        val filterMode = stringPreferencesKey("app_filter_mode")
        val filterPackages = stringSetPreferencesKey("app_filter_packages")
    }
}
