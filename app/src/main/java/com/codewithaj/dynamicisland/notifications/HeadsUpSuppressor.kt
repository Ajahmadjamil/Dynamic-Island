package com.codewithaj.dynamicisland.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.codewithaj.dynamicisland.data.IslandSettings

/**
 * "Replace system pop-ups", per app: apps allowed on the island show ONLY Islet's card; every
 * other app keeps Android's normal pop-up (heads-up) banner.
 *
 * Android gives apps no per-app switch for another app's pop-ups. What it does have is the
 * global `heads_up_notifications_enabled` setting, and SystemUI dismisses every heads-up on
 * screen the moment that setting turns OFF (it calls releaseAllImmediately()). So:
 *
 *   - Normally the setting stays ON → other apps pop up as usual.
 *   - When Islet is about to show a preview for an allowed app, it switches the setting OFF
 *     (SystemUI instantly removes that app's banner) and back ON [PULSE_MS] later.
 *
 * The notification itself is untouched and stays in the shade. Writing the setting needs
 * WRITE_SECURE_SETTINGS (granted once via adb).
 *
 * Safety: Islet records "I switched it off" before each pulse, so if the process dies
 * mid-pulse, the next [sync] switches pop-ups back on. Island process only.
 */
object HeadsUpSuppressor {

    private const val GLOBAL_KEY = "heads_up_notifications_enabled"
    private const val PREFS = "islet_runtime"
    private const val PREF_DISABLED_BY_US = "heads_up_disabled_by_islet"
    /** Long enough for SystemUI's settings observer to fire and release the banner. */
    private const val PULSE_MS = 300L
    /** Second pulse, after SystemUI has had time to inflate and show its banner. */
    private const val SECOND_PULSE_AT_MS = 650L

    @Volatile var hostRunning = false
    @Volatile var listenerConnected = false
    @Volatile private var lastSettings: IslandSettings? = null

    private val main = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null

    fun canControl(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isSystemHeadsUpEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, GLOBAL_KEY, 1) != 0

    /** Call with fresh settings from the overlay controller. */
    fun onSettings(context: Context, settings: IslandSettings) {
        lastSettings = settings
        sync(context)
    }

    /** True if previews for allowed apps should replace the system banner right now. */
    fun active(context: Context): Boolean {
        val s = lastSettings ?: return false
        return s.islandEnabled && s.notificationPreviews && s.replaceSystemHeadsUp &&
            hostRunning && listenerConnected && canControl(context)
    }

    /**
     * Islet is showing its own card for a notification Android will ALSO pop up: make SystemUI
     * drop its banner.
     *
     * Timing matters: SystemUI decides "heads-up" the instant the notification is posted, but
     * then spends ~100–400 ms inflating the banner before it's on screen. A switch-off during
     * that window finds nothing to release, and the banner appears anyway. So we pulse twice:
     * immediately (catches banners already up / decided late) and again after
     * [SECOND_PULSE_AT_MS], when the banner has been inflated and shown.
     */
    fun replaceSystemBanner(context: Context) {
        if (!active(context)) return
        val app = context.applicationContext
        restoreRunnable?.let { main.removeCallbacks(it) }
        secondPulse?.let { main.removeCallbacks(it) }
        if (!pulseOff(app)) return
        val second = Runnable {
            secondPulse = null
            if (pulseOff(app)) scheduleRestore(app)
        }
        secondPulse = second
        scheduleRestore(app)
        main.postDelayed(second, SECOND_PULSE_AT_MS)
    }

    private var secondPulse: Runnable? = null

    /** Switches system pop-ups off (SystemUI then releases every heads-up). */
    private fun pulseOff(app: Context): Boolean {
        if (!isSystemHeadsUpEnabled(app)) return true // already off (mid-pulse): fine
        return try {
            prefs(app).edit(commit = true) { putBoolean(PREF_DISABLED_BY_US, true) }
            Settings.Global.putInt(app.contentResolver, GLOBAL_KEY, 0)
            true
        } catch (e: SecurityException) {
            Log.w("HeadsUpSuppressor", "WRITE_SECURE_SETTINGS missing", e)
            false
        }
    }

    private fun scheduleRestore(app: Context) {
        restoreRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { restore(app) }
        restoreRunnable = r
        main.postDelayed(r, PULSE_MS)
    }

    /**
     * Makes sure Islet never leaves the system's pop-ups switched off (e.g. after a crash
     * mid-pulse, or from older versions that kept them off while previews were on).
     */
    fun sync(context: Context) {
        if (restoreRunnable != null || secondPulse != null) return // a pulse is in flight; it restores itself
        if (prefs(context).getBoolean(PREF_DISABLED_BY_US, false)) restore(context.applicationContext)
    }

    private fun restore(context: Context) {
        restoreRunnable = null
        try {
            if (canControl(context)) Settings.Global.putInt(context.contentResolver, GLOBAL_KEY, 1)
        } catch (_: SecurityException) { }
        prefs(context).edit { putBoolean(PREF_DISABLED_BY_US, false) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
