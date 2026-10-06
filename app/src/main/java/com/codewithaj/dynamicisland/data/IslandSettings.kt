package com.codewithaj.dynamicisland.data

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
    val alertCharging: Boolean = true,
    val alertRinger: Boolean = true,
    val alertBluetooth: Boolean = true,
    val notificationPreviews: Boolean = true,
    /**
     * Turn off Android's own heads-up banners while Islet shows previews (needs
     * WRITE_SECURE_SETTINGS, granted once via adb). See notifications/HeadsUpSuppressor.
     */
    val replaceSystemHeadsUp: Boolean = false,

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
