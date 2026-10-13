package com.codewithaj.pixelisland.ui.setup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Notifications

/**
 * The animated walkthroughs, one per permission. Labels follow Pixel / AOSP Settings
 * (Android 13–17); other phone makers word things slightly differently, which the wizard says.
 *
 * [serviceLabel] and [listenerLabel] are the names Android shows for our accessibility service and
 * notification listener (strings.xml), so the mock matches what the user will really see.
 */
class Tutorials(private val serviceLabel: String, private val listenerLabel: String) {

    private val accessibilityPage = SwitchScreen(serviceLabel, "Use $serviceLabel")

    val accessibility = Tutorial(
        steps = listOf(
            "Tap **$serviceLabel**",
            "Turn on **Use $serviceLabel**",
            "Tap **Allow**",
        ),
        frames = listOf(
            TutorialFrame(
                ListScreen(
                    "Accessibility",
                    listOf(
                        MockRow("Display size and text"),
                        MockRow("Color and motion"),
                        MockRow("Downloaded apps", isHeader = true),
                        MockRow(serviceLabel, summary = "Off", isApp = true),
                    ),
                ),
                tap = Tap.row(3),
                step = 0,
            ),
            TutorialFrame(accessibilityPage, tap = Tap.SWITCH, step = 1),
            TutorialFrame(
                accessibilityPage,
                overlay = MockDialog(
                    "Allow $serviceLabel to have full control of your device?",
                    confirm = "Allow",
                    dismiss = "Deny",
                ),
                tap = Tap.CONFIRM,
                step = 2,
            ),
            TutorialFrame(accessibilityPage.copy(on = true), step = 2),
        ),
    )

    private val listenerPage = SwitchScreen(listenerLabel, "Allow notification access")

    val notificationAccess = Tutorial(
        steps = listOf(
            "Turn on **Allow notification access**",
            "Tap **Allow**",
        ),
        frames = listOf(
            TutorialFrame(listenerPage, tap = Tap.SWITCH, step = 0),
            TutorialFrame(
                listenerPage,
                overlay = MockDialog(
                    "Allow notification access for $listenerLabel?",
                    text = "It will be able to read your notifications.",
                    confirm = "Allow",
                    dismiss = "Don't allow",
                ),
                tap = Tap.CONFIRM,
                step = 1,
            ),
            TutorialFrame(listenerPage.copy(on = true), step = 1),
        ),
    )

    /** Android 13+ "Restricted setting" unlock for apps installed outside an app store. */
    val unlock = Tutorial(
        steps = listOf(
            "Tap the greyed-out switch once, then **OK**",
            "In App info, tap **⋮** at the top right",
            "Tap **Allow restricted settings** and confirm it's you",
        ),
        frames = listOf(
            TutorialFrame(accessibilityPage.copy(enabled = false), tap = Tap.SWITCH, step = 0),
            TutorialFrame(
                accessibilityPage.copy(enabled = false),
                overlay = MockDialog(
                    "Restricted setting",
                    text = "For your security, this setting is currently unavailable.",
                    confirm = "OK",
                ),
                tap = Tap.CONFIRM,
                step = 0,
            ),
            TutorialFrame(AppInfoScreen(menuItem = RESTRICTED_MENU_ITEM), tap = Tap.MENU, step = 1),
            TutorialFrame(AppInfoScreen(menuOpen = true, menuItem = RESTRICTED_MENU_ITEM), tap = Tap.MENU_ITEM, step = 2),
            TutorialFrame(
                AppInfoScreen(menuItem = RESTRICTED_MENU_ITEM),
                overlay = MockBiometric("Verify it's you", "Use your fingerprint or PIN"),
                tap = Tap.FINGERPRINT,
                step = 2,
            ),
            TutorialFrame(AppInfoScreen(menuItem = RESTRICTED_MENU_ITEM), step = 2),
        ),
    )

    private val overlayPage = SwitchScreen("Display over other apps", "Allow display over other apps")

    val overlay = Tutorial(
        steps = listOf("Turn on **Allow display over other apps**"),
        frames = listOf(
            TutorialFrame(overlayPage, tap = Tap.SWITCH, step = 0),
            TutorialFrame(overlayPage.copy(on = true), step = 0),
        ),
    )

    val battery = Tutorial(
        steps = listOf("Tap **Allow**"),
        frames = listOf(
            TutorialFrame(
                OwnAppScreen,
                overlay = MockDialog(
                    "Let app always run in background?",
                    text = "This may use a little more battery. You can change it later in Settings.",
                    confirm = "Allow",
                    dismiss = "Deny",
                ),
                tap = Tap.CONFIRM,
                step = 0,
            ),
            TutorialFrame(OwnAppScreen, step = 0),
        ),
    )

    /** Android 13+: the runtime "Allow notifications" prompt. */
    val postNotifications = Tutorial(
        steps = listOf("Tap **Allow**"),
        frames = listOf(
            TutorialFrame(
                OwnAppScreen,
                overlay = MockPermissionPrompt(Icons.Outlined.Notifications, "Allow Pixel Island to send you notifications?"),
                tap = Tap.CONFIRM,
                step = 0,
            ),
            TutorialFrame(OwnAppScreen, step = 0),
        ),
    )

    private val appNotificationsPage = SwitchScreen("App notifications", "All Pixel Island notifications")

    /** Android 12 and older, or after a denial: the app's notification settings page. */
    val notificationSettings = Tutorial(
        steps = listOf("Turn on **All Pixel Island notifications**"),
        frames = listOf(
            TutorialFrame(appNotificationsPage, tap = Tap.SWITCH, step = 0),
            TutorialFrame(appNotificationsPage.copy(on = true), step = 0),
        ),
    )

    val nearbyDevices = Tutorial(
        steps = listOf("Tap **Allow**"),
        frames = listOf(
            TutorialFrame(
                OwnAppScreen,
                overlay = MockPermissionPrompt(
                    Icons.Outlined.Bluetooth,
                    "Allow Pixel Island to find, connect to, and determine the relative position of nearby devices?",
                ),
                tap = Tap.CONFIRM,
                step = 0,
            ),
            TutorialFrame(OwnAppScreen, step = 0),
        ),
    )

    private companion object {
        const val RESTRICTED_MENU_ITEM = "Allow restricted settings"
    }
}
