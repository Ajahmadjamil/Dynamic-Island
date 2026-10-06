package com.codewithaj.dynamicisland.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.codewithaj.dynamicisland.notifications.IslandNotificationListener
import com.codewithaj.dynamicisland.service.IslandAccessibilityService

data class PermissionStatus(
    val accessibility: Boolean,
    val notificationListener: Boolean,
    val overlay: Boolean,
    val postNotifications: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    /** "Nearby devices" (BLUETOOTH_CONNECT) on Android 12+; always true below. */
    val bluetooth: Boolean,
    /** WRITE_SECURE_SETTINGS (adb-granted), needed to replace the system's pop-up banners. */
    val secureSettings: Boolean,
    /**
     * Android 13+ "Allow restricted settings" for side-loaded apps: true = allowed,
     * false = not allowed, null = not applicable (older Android) or not readable.
     */
    val restrictedSettingsAllowed: Boolean?,
    /** Global heads-up banners setting (Islet turns it off for "Replace system pop-ups"). */
    val systemHeadsUpEnabled: Boolean,
) {
    /** The island can be shown at all. */
    val canShowIsland get() = accessibility || overlay
}

object PermissionUtils {

    fun status(context: Context) = PermissionStatus(
        accessibility = isAccessibilityEnabled(context),
        notificationListener = isNotificationListenerEnabled(context),
        overlay = Settings.canDrawOverlays(context),
        postNotifications = canPostNotifications(context),
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
        bluetooth = hasBluetoothPermission(context),
        secureSettings = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED,
        restrictedSettingsAllowed = restrictedSettingsAllowed(context),
        systemHeadsUpEnabled = Settings.Global.getInt(context.contentResolver, "heads_up_notifications_enabled", 1) != 0,
    )

    /**
     * Reads our own ACCESS_RESTRICTED_SETTINGS app-op (the "Allow restricted settings" switch).
     * The op name isn't in the public SDK, so this is best-effort and returns null if the
     * platform doesn't know it or refuses to answer.
     */
    fun restrictedSettingsAllowed(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        return try {
            // Deprecated in newer SDKs but still the simplest read-only check of our own op.
            @Suppress("DEPRECATION")
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            null
        }
    }

    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val ours = ComponentName(context, IslandAccessibilityService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.let { si -> ComponentName(si.packageName, si.name) } == ours }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun canPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    // ---- Intents ---------------------------------------------------------------------------

    /**
     * Opens Accessibility settings. The ":settings:fragment_args_key" extra is an undocumented
     * but widely honoured hint (AOSP, Pixel, Samsung) that scrolls to and highlights our entry.
     */
    fun accessibilitySettingsIntent(context: Context): Intent {
        val component = ComponentName(context, IslandAccessibilityService::class.java).flattenToString()
        val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", args)
    }

    fun notificationListenerIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, IslandNotificationListener::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun notificationListenerFallbackIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun overlayIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    @Suppress("BatteryLife") // An always-on overlay companion is an accepted use case.
    fun batteryOptimizationIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun appNotificationSettingsIntent(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** App info page; needed for "Allow restricted settings" on Android 13+ side-loaded installs. */
    fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

/** Starts [intent], falling back to the given alternatives if no activity handles it. */
fun Context.startFirstResolvable(vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Exception) {
            // ActivityNotFoundException / SecurityException on some OEM builds: try the next one.
        }
    }
    return false
}
