package com.codewithaj.pixelisland.data

/**
 * Immutable snapshot of everything the user can configure.
 *
 * Calibration values are stored in dp. A value of [AUTO] means "derive from the display cutout",
 * so the island adapts if the same backup is restored on a different phone.
 */
data class IslandSettings(
    val islandEnabled: Boolean = true,
    val showIdlePill: Boolean = true,
    val hideInLandscape: Boolean = true,
    val hideInFullscreen: Boolean = true,
    /**
     * On by default: it only takes effect when the accessibility service is off and
     * "Display over other apps" is granted, and then it's the only way to show the island.
     */
    val useFallbackOverlay: Boolean = true,
    val onboardingDone: Boolean = false,
    /** True while the calibration screen is open; the overlay draws a guide outline. */
    val calibrating: Boolean = false,
    val calibration: Calibration = Calibration(),
    val animationSpeed: AnimationSpeed = AnimationSpeed.NORMAL,

    // ---- Feature toggles ----
    val showMedia: Boolean = true,
    val showCalls: Boolean = true,
    val showTimers: Boolean = true,
    /** Navigation, deliveries/rides (Android 16 Live Updates) and determinate progress. */
    val showLiveUpdates: Boolean = true,
    val alertCharging: Boolean = true,
    val alertRinger: Boolean = true,
    val alertBluetooth: Boolean = true,
    /** VPN connected/disconnected and hotspot on/off. */
    val alertConnectivity: Boolean = true,
    val notificationPreviews: Boolean = true,
    /**
     * Channels of island-allowed apps that still pop up natively ([PopUpChannel] entries),
     * shown in Settings with a one-tap "turn off pop-up" fix.
     */
    val popUpChannels: Set<String> = emptySet(),

    // ---- Per-app filter (applies to notification previews and app-owned live activities) ----
    val appFilterMode: AppFilterMode = AppFilterMode.ALL_EXCEPT_BLOCKED,
    val appFilterPackages: Set<String> = emptySet(),
) {
    fun allowsApp(packageName: String): Boolean = when (appFilterMode) {
        AppFilterMode.ALL_EXCEPT_BLOCKED -> packageName !in appFilterPackages
        AppFilterMode.ONLY_SELECTED -> packageName in appFilterPackages
    }

    companion object {
        const val AUTO = -1f
    }
}

data class Calibration(
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    val widthDp: Float = IslandSettings.AUTO,
    val heightDp: Float = IslandSettings.AUTO,
    /** [IslandSettings.AUTO] = fully rounded ends (height / 2). */
    val cornerRadiusDp: Float = IslandSettings.AUTO,
)

/** "package · channel id · channel name" stored as one string in a DataStore string set. */
object PopUpChannel {
    private const val SEP = '\u001F' // unit separator: never appears in package/channel ids

    data class Entry(val packageName: String, val channelId: String, val channelName: String)

    fun prefix(packageName: String, channelId: String) = "$packageName$SEP$channelId$SEP"
    fun encode(packageName: String, channelId: String, channelName: String) =
        prefix(packageName, channelId) + channelName

    fun decode(s: String): Entry? {
        val parts = s.split(SEP)
        return if (parts.size == 3) Entry(parts[0], parts[1], parts[2]) else null
    }
}

/** How [IslandSettings.appFilterPackages] is interpreted. */
enum class AppFilterMode {
    /** Every app may appear, except the listed ones (blocklist). */
    ALL_EXCEPT_BLOCKED,
    /** Only the listed apps may appear (allowlist). */
    ONLY_SELECTED,
}

/** Global multiplier applied to spring stiffness (see island/animation/AnimationSpec, Phase 2). */
enum class AnimationSpeed(val stiffnessMultiplier: Float) {
    RELAXED(0.7f),
    NORMAL(1f),
    SNAPPY(1.4f),
}
