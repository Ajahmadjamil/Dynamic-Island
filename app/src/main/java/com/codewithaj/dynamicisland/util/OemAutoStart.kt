package com.codewithaj.dynamicisland.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * OEM "auto-start" / background-restriction screens. These are private activities that change
 * between OS versions, so we try a list of known components and fall back to App info.
 * Steps are shown alongside because the deep links don't work on every build.
 */
object OemAutoStart {

    data class Guide(val brand: String, val steps: List<String>, val intents: List<Intent>)

    fun guideFor(context: Context): Guide {
        val m = Build.MANUFACTURER.lowercase()
        val appInfo = PermissionUtils.appDetailsIntent(context)
        return when {
            "samsung" in m -> Guide(
                "Samsung (One UI)",
                listOf(
                    "Settings → Apps → Islet → Battery → choose \"Unrestricted\".",
                    "Settings → Battery → Background usage limits → make sure Islet is not in \"Sleeping\" or \"Deep sleeping\" apps.",
                    "Optionally add Islet to \"Never auto sleeping apps\".",
                ),
                listOf(
                    component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    appInfo,
                ),
            )
            "xiaomi" in m || "redmi" in m || "poco" in m -> Guide(
                "Xiaomi / HyperOS / MIUI",
                listOf(
                    "Security app → Permissions → Autostart → enable Islet.",
                    "Settings → Apps → Islet → Battery saver → \"No restrictions\".",
                    "Open Recents, long-press Islet and tap the lock icon so it isn't cleared.",
                ),
                listOf(
                    component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    appInfo,
                ),
            )
            "oppo" in m || "realme" in m || "oneplus" in m -> Guide(
                if ("oneplus" in m) "OnePlus (OxygenOS)" else "Oppo / Realme (ColorOS)",
                listOf(
                    "Settings → Apps → App management → Islet → Battery usage → allow \"Auto launch\" and \"Run in background\".",
                    "Settings → Battery → turn off optimisation for Islet.",
                    "Lock Islet in Recents.",
                ),
                listOf(
                    component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                    component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                    appInfo,
                ),
            )
            "vivo" in m || "iqoo" in m -> Guide(
                "Vivo / iQOO (Funtouch / OriginOS)",
                listOf(
                    "i Manager → App manager → Autostart manager → enable Islet.",
                    "Settings → Battery → Background power consumption → allow Islet.",
                ),
                listOf(
                    component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    appInfo,
                ),
            )
            else -> Guide(
                Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                listOf(
                    "Settings → Apps → Islet → Battery → \"Unrestricted\" / \"Don't optimise\".",
                    "If the island disappears after a while, check dontkillmyapp.com for your phone.",
                ),
                listOf(appInfo),
            )
        }
    }

    private fun component(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls))
}
