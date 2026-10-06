package com.codewithaj.dynamicisland.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.codewithaj.dynamicisland.data.IslandSettings
import com.codewithaj.dynamicisland.island.IslandActivity

/**
 * Decides whether a posted notification becomes an island preview, and builds it.
 *
 * Heuristics (in order), aimed at "things the user would want a heads-up for":
 *  - feature off / app filtered / our own notifications → skip
 *  - ongoing, foreground-service, group summaries → skip (they're status, not news)
 *  - media, calls, progress, navigation, alarms, system → skip (handled by live activities
 *    or not interesting as a banner)
 *  - silent (importance < DEFAULT) or hidden by Do Not Disturb → skip
 *  - updates of an already-shown notification with "only alert once" → skip
 *  - nothing to show (no title and no text) → skip
 * On the lock screen, private notifications show only the app name (like the system does).
 */
class NotificationParser(private val context: Context) {

    private val pm = context.packageManager
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val iconPx = (48 * context.resources.displayMetrics.density).toInt()

    /** key → last time we showed it; small LRU to drop rapid re-posts. */
    private val recent = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }

    /** Cheap checks only; runs on the listener's thread for every notification. */
    fun shouldShow(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.Ranking?,
        settings: IslandSettings,
        foregroundPackage: String?,
    ): Boolean {
        if (!settings.islandEnabled || !settings.notificationPreviews) return false
        val pkg = sbn.packageName
        if (pkg == context.packageName || !settings.allowsApp(pkg)) return false
        // Don't announce an app's notification while that app is on screen.
        if (pkg == foregroundPackage) return false

        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.category in SKIPPED_CATEGORIES) return false
        val template = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if ("MediaStyle" in template || "CallStyle" in template) return false

        if (ranking != null) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
            if (!ranking.matchesInterruptionFilter()) return false // Do Not Disturb
        }

        val now = SystemClock.uptimeMillis()
        val last = recent[sbn.key]
        if (last != null && (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 || now - last < REPOST_WINDOW_MS)) {
            return false
        }
        recent[sbn.key] = now
        return true
    }

    /** Builds the preview; loads and scales icons, so call it off the main thread. */
    fun build(sbn: StatusBarNotification): IslandActivity.NotificationPreview? {
        val n = sbn.notification
        val pkg = sbn.packageName
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) { pkg }

        var title: String
        var text: String
        // Chat apps: show the latest message and its sender.
        val messaging = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = messaging?.messages?.lastOrNull()
        if (last != null) {
            val sender = last.person?.name?.toString()
            val conversation = messaging.conversationTitle?.toString()
            title = when {
                conversation != null && sender != null -> "$sender · $conversation"
                else -> sender ?: conversation ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            }
            text = last.text?.toString().orEmpty()
        } else {
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        }
        title = title.trim().replace('\n', ' ')
        text = text.trim().replace('\n', ' ')
        if (title.isEmpty() && text.isEmpty()) return null

        // Lock screen privacy: private notifications only reveal the app, like the system does.
        val locked = keyguard?.isKeyguardLocked == true
        if (locked && n.visibility != Notification.VISIBILITY_PUBLIC) {
            title = appLabel
            text = "New notification"
        }

        val avatar = if (!locked) n.getLargeIcon()?.loadDrawable(context)?.toSafeBitmap() else null
        val icon = avatar ?: appIcon(pkg)

        return IslandActivity.NotificationPreview(
            key = sbn.key,
            packageName = pkg,
            appLabel = appLabel,
            title = title.ifEmpty { appLabel },
            text = text,
            icon = icon,
            iconIsAvatar = avatar != null,
            contentIntent = n.contentIntent,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            shownAtMs = SystemClock.uptimeMillis(),
        )
    }

    private fun appIcon(pkg: String): Bitmap? = try {
        pm.getApplicationIcon(pkg).toSafeBitmap()
    } catch (_: Exception) { null }

    private fun Drawable.toSafeBitmap(): Bitmap? = try {
        toBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) { null }

    private companion object {
        const val REPOST_WINDOW_MS = 4_000L
        val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT, // media
            Notification.CATEGORY_CALL,      // Phase 5: call activity
            Notification.CATEGORY_PROGRESS,  // Phase 5: progress activity
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS,
        )
    }
}
