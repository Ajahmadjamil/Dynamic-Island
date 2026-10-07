package com.codewithaj.pixelisland.util

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.codewithaj.pixelisland.notifications.IslandNotificationListener
import com.codewithaj.pixelisland.service.IslandAccessibilityService

/**
 * Every adb command Pixel Island may need, with its live status. Shown in Settings → ADB commands so the
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
            why = "Draws the island above the status bar around the camera, so you can tap it. Pixel Island reads no screen content. " +
                "Note: this replaces any other enabled accessibility service (e.g. TalkBack); use the Settings switch if you rely on one.",
            // Two plain commands: they survive every shell (cmd, PowerShell, bash) without quoting.
            command = "adb shell settings put secure enabled_accessibility_services $a11y\n" +
                "adb shell settings put secure accessibility_enabled 1",
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
            why = "Stops Android from killing the island in the background. Pixel Island does no work while idle.",
            command = "adb shell dumpsys deviceidle whitelist +$pkg",
            level = Level.RECOMMENDED,
            done = s.ignoringBatteryOptimizations,
            statusText = if (s.ignoringBatteryOptimizations) "Unrestricted" else "Optimized",
        )

        // ---- Optional ----
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
        return list
    }
}
