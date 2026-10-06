package com.codewithaj.dynamicisland.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.codewithaj.dynamicisland.data.IslandSettings

/**
 * "Replace system pop-ups": while Islet can show notification previews, turn off Android's own
 * heads-up banners so the user doesn't get both.
 *
 * Why a global switch: no regular app can change another app's channel "Pop on screen" flag.
 * The only reliable lever is the global `heads_up_notifications_enabled` setting, which SystemUI
 * still honours. Writing it needs WRITE_SECURE_SETTINGS, which the user grants once via adb.
 *
 * Safety rules (this is a system-wide setting, so Islet must give it back):
 *  - Only disabled while island host AND notification listener are running AND the user wants it.
 *  - Restored as soon as any of those stops (service off, access revoked, toggle off).
 *  - Islet remembers that *it* disabled the setting, so it never re-enables banners a user
 *    turned off by other means.
 *  - Uninstalling can't run code; the README documents the one-line restore.
 *
 * Island process only.
 */
object HeadsUpSuppressor {

    private const val GLOBAL_KEY = "heads_up_notifications_enabled"
    private const val PREFS = "islet_runtime"
    private const val PREF_DISABLED_BY_US = "heads_up_disabled_by_islet"

    @Volatile var hostRunning = false
    @Volatile var listenerConnected = false
    @Volatile private var lastSettings: IslandSettings? = null

    fun canControl(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isSystemHeadsUpEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, GLOBAL_KEY, 1) != 0

    /** Call with fresh settings from the overlay controller. */
    fun onSettings(context: Context, settings: IslandSettings) {
        lastSettings = settings
        sync(context)
    }

    /** Call when the host or listener starts/stops (uses the last real settings seen). */
    fun sync(context: Context) {
        val s = lastSettings
        val want = s != null && s.islandEnabled && s.notificationPreviews && s.replaceSystemHeadsUp &&
            hostRunning && listenerConnected
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val disabledByUs = prefs.getBoolean(PREF_DISABLED_BY_US, false)
        if (!canControl(context)) {
            if (disabledByUs) prefs.edit { putBoolean(PREF_DISABLED_BY_US, false) } // permission revoked
            return
        }
        try {
            if (want) {
                if (isSystemHeadsUpEnabled(context)) {
                    Settings.Global.putInt(context.contentResolver, GLOBAL_KEY, 0)
                    prefs.edit { putBoolean(PREF_DISABLED_BY_US, true) }
                }
            } else if (disabledByUs) {
                Settings.Global.putInt(context.contentResolver, GLOBAL_KEY, 1)
                prefs.edit { putBoolean(PREF_DISABLED_BY_US, false) }
            }
        } catch (e: SecurityException) {
            Log.w("HeadsUpSuppressor", "WRITE_SECURE_SETTINGS missing", e)
        }
    }
}
