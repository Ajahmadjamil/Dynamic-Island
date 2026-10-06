package com.codewithaj.dynamicisland.util

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.codewithaj.dynamicisland.notifications.IslandNotificationListener
import com.codewithaj.dynamicisland.service.IslandAccessibilityService

/**
 * Every adb command Islet may need, with its live status. Shown in Settings → ADB commands so the
 * user can see at a glance what's done, what's missing, and copy the exact command.
 *
 * All commands are idempotent: running one again when it's already done is harmless.
 */
object AdbCommands {

    enum class Level { REQUIRED, RECOMMENDED, OPTIONAL, RECOVERY }

    data class Item(
        val id: String,
        val title: String,
        val why: String,
        val command: String,
        val level: Level,
        /** True = done/OK, false = missing/needs action. */
        val done: Boolean,
        val statusText: String,
    )

    fun items(context: Context, s: PermissionStatus): List<Item> {
        val pkg = context.packageName
        val a11y = ComponentName(context, IslandAccessibilityService::class.java).flattenToString()
        val listener = ComponentName(context, IslandNotificationListener::class.java).flattenToString()
        val list = mutableListOf<Item>()

        // ---- Required ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // If accessibility + notification access are already on, the switch did its job
            // (or wasn't needed for this install), so it no longer blocks anything.
            val effectivelyOk = s.restrictedSettingsAllowed == true || (s.accessibility && s.notificationListener)
            list += Item(
                id = "restricted",
                title = "Allow restricted settings",
                why = "Android blocks Accessibility and Notification access for apps installed outside an app store. " +
                    "Without this, the switches below are greyed out, and every app update turns accessibility off again.",
                command = "adb shell appops set $pkg ACCESS_RESTRICTED_SETTINGS allow",
                level = Level.REQUIRED,
                done = effectivelyOk,
                statusText = when {
                    s.restrictedSettingsAllowed == true -> "Allowed"
                    effectivelyOk -> "Not allowed, but not blocking right now"
                    s.restrictedSettingsAllowed == null -> "Unknown — run it to be safe"
                    else -> "Not allowed"
                },
            )
        }
        list += Item(
            id = "accessibility",
            title = "Accessibility service",
            why = "Draws the island above the status bar around the camera, so you can tap it. Islet reads no screen content.",
            // Appends to the existing list instead of replacing it (keeps TalkBack etc. enabled).
            command = "adb shell 'S=$a11y; C=\$(settings get secure enabled_accessibility_services); " +
                "case \"\$C\" in *\"\$S\"*) ;; null|\"\") settings put secure enabled_accessibility_services \"\$S\";; " +
                "*) settings put secure enabled_accessibility_services \"\$C:\$S\";; esac; " +
                "settings put secure accessibility_enabled 1'",
            level = Level.REQUIRED,
            done = s.accessibility,
            statusText = if (s.accessibility) "On" else "Off",
        )
        list += Item(
            id = "listener",
            title = "Notification access",
            why = "Music, notification previews and (next phase) calls and timers all come from here.",
            command = "adb shell cmd notification allow_listener $listener",
            level = Level.REQUIRED,
            done = s.notificationListener,
            statusText = if (s.notificationListener) "On" else "Off",
        )

        // ---- Recommended ----
        list += Item(
            id = "battery",
            title = "Unrestricted battery",
            why = "Stops Android from killing the island in the background. Islet does no work while idle.",
            command = "adb shell dumpsys deviceidle whitelist +$pkg",
            level = Level.RECOMMENDED,
            done = s.ignoringBatteryOptimizations,
            statusText = if (s.ignoringBatteryOptimizations) "Unrestricted" else "Optimized",
        )

        // ---- Optional ----
        list += Item(
            id = "pop_ups",
            title = "Replace system pop-ups",
            why = "Lets Islet turn off Android's own pop-up banners while it shows notification cards, so you don't see both.",
            command = "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            level = Level.OPTIONAL,
            done = s.secureSettings,
            statusText = if (s.secureSettings) "Granted" else "Not granted",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Item(
                id = "bluetooth",
                title = "Nearby devices (Bluetooth)",
                why = "Shows your headphones or watch on the island when they connect.",
                command = "adb shell pm grant $pkg android.permission.BLUETOOTH_CONNECT",
                level = Level.OPTIONAL,
                done = s.bluetooth,
                statusText = if (s.bluetooth) "Granted" else "Not granted",
            )
        }
        list += Item(
            id = "overlay",
            title = "Display over other apps",
            why = "Fallback mode only, when the accessibility service is off (the pill then sits under the status bar and can't be tapped).",
            command = "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
            level = Level.OPTIONAL,
            done = s.overlay,
            statusText = if (s.overlay) "Allowed" else "Not allowed",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Item(
                id = "post_notifications",
                title = "Notifications",
                why = "Fallback mode only: the small persistent notification that keeps it running.",
                command = "adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS",
                level = Level.OPTIONAL,
                done = s.postNotifications,
                statusText = if (s.postNotifications) "Granted" else "Not granted",
            )
        }

        // ---- Recovery ----
        list += Item(
            id = "restore_pop_ups",
            title = "Restore system pop-ups",
            why = "Islet turns Android's pop-ups back on by itself. Use this only if you uninstalled Islet while " +
                "\"Replace system pop-ups\" was on, or the banners never came back.",
            command = "adb shell settings put global heads_up_notifications_enabled 1",
            level = Level.RECOVERY,
            done = s.systemHeadsUpEnabled,
            statusText = if (s.systemHeadsUpEnabled) "System pop-ups on" else "System pop-ups off",
        )
        return list
    }
}
