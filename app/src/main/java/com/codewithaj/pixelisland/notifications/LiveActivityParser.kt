package com.codewithaj.pixelisland.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.codewithaj.pixelisland.data.IslandSettings
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.LiveAction

/**
 * Turns ongoing notifications from other apps into live activities.
 *
 * Classification (cheap, main thread) — first match wins:
 *  CALL      category=call, or the CallStyle template (Android 12+). Covers the dialer and
 *            VoIP apps (WhatsApp, Meet, Telegram…) that follow the platform conventions.
 *  TIMER     an ongoing notification showing a chronometer (clock-app timers/stopwatches,
 *            recorders), or an ongoing notification from a known clock app (paused timer,
 *            "time's up").
 *  PROGRESS  ongoing + (navigation category, or an Android 16 Live Update / promoted
 *            notification, or determinate progress). Indeterminate spinners ("Syncing…") are
 *            ignored unless promoted: they're noise.
 *
 * Building (icons/avatars) happens off the main thread in [build].
 */
class LiveActivityParser(private val context: Context) {

    enum class Kind { CALL, TIMER, PROGRESS }

    private val pm = context.packageManager
    private val iconPx = (48 * context.resources.displayMetrics.density).toInt()
    /** App icons/labels are re-used across the frequent progress updates. */
    private val appIcons = LruCache<String, Bitmap>(12)
    private val appLabels = LruCache<String, String>(32)

    fun classify(sbn: StatusBarNotification, s: IslandSettings): Kind? {
        if (sbn.packageName == context.packageName) return null
        val n = sbn.notification
        val extras = n.extras
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if ("MediaStyle" in template) return null // handled by MediaSessionWatcher

        val isCall = n.category == Notification.CATEGORY_CALL || "CallStyle" in template
        if (isCall) return if (s.showCalls) Kind.CALL else null

        val ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val promoted = isPromoted(n)
        val navigation = n.category == Notification.CATEGORY_NAVIGATION

        val chrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        if (!navigation && ((chrono && ongoing) || (ongoing && sbn.packageName in CLOCK_PACKAGES))) {
            return if (s.showTimers) Kind.TIMER else null
        }

        if (ongoing || promoted) {
            val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            if (navigation || promoted || max > 0 || "ProgressStyle" in template) {
                return if (s.showLiveUpdates) Kind.PROGRESS else null
            }
        }
        return null
    }

    fun build(sbn: StatusBarNotification, kind: Kind): IslandActivity? = try {
        when (kind) {
            Kind.CALL -> buildCall(sbn)
            Kind.TIMER -> buildTimer(sbn)
            Kind.PROGRESS -> buildProgress(sbn)
        }
    } catch (_: Exception) {
        null // a malformed notification must never take the island down
    }

    // ---- Calls --------------------------------------------------------------------------------

    private fun buildCall(sbn: StatusBarNotification): IslandActivity.Call {
        val n = sbn.notification
        val e = n.extras
        val callType = e.getInt(EXTRA_CALL_TYPE, 0)
        val chrono = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val incoming = when (callType) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> true
            CALL_TYPE_ONGOING -> false
            // Not CallStyle: ringing calls carry a full-screen intent and no running clock.
            else -> !chrono && n.fullScreenIntent != null
        }
        // android.app.Person (CallStyle's caller) exists from Android 9; read it only there.
        var personName: String? = null
        var personAvatar: Bitmap? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val person = e.parcel<Person>(EXTRA_CALL_PERSON)
            personName = person?.name?.toString()
            personAvatar = person?.icon?.loadDrawable(context)?.toSafeBitmap()
        }
        val caller = personName
            ?: e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: appLabel(sbn.packageName)
        val avatar = personAvatar ?: n.getLargeIcon()?.loadDrawable(context)?.toSafeBitmap()

        // CallStyle stores the intents explicitly; otherwise fall back to the action labels.
        var answer = e.parcel<PendingIntent>(EXTRA_ANSWER_INTENT)
        var decline = e.parcel<PendingIntent>(EXTRA_DECLINE_INTENT)
        var hangUp = e.parcel<PendingIntent>(EXTRA_HANG_UP_INTENT)
        val actions = n.actions.orEmpty()
        if (answer == null) answer = actions.firstOrNull { it.matches(ANSWER_WORDS) }?.actionIntent
        if (decline == null) decline = actions.firstOrNull { it.matches(DECLINE_WORDS) }?.actionIntent
        if (hangUp == null) hangUp = actions.firstOrNull { it.matches(HANG_UP_WORDS) }?.actionIntent
        // Dialers commonly order ringing actions [Decline, Answer] when labels are unknown.
        if (incoming && actions.size == 2 && answer == null && decline == null) {
            decline = actions[0].actionIntent
            answer = actions[1].actionIntent
        }
        if (!incoming && hangUp == null) hangUp = decline

        return IslandActivity.Call(
            key = sbn.key,
            packageName = sbn.packageName,
            caller = caller,
            avatar = avatar,
            incoming = incoming,
            startedAtWallMs = if (!incoming && chrono && n.`when` > 0) n.`when` else null,
            answer = answer,
            decline = decline,
            hangUp = hangUp,
            contentIntent = n.contentIntent,
        )
    }

    // ---- Timers -------------------------------------------------------------------------------

    private fun buildTimer(sbn: StatusBarNotification): IslandActivity.Timer {
        val n = sbn.notification
        val e = n.extras
        val chrono = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        return IslandActivity.Timer(
            key = sbn.key,
            packageName = sbn.packageName,
            label = title.ifEmpty { appLabel(sbn.packageName) },
            countDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            chronometerBaseWallMs = if (chrono && n.`when` > 0) n.`when` else null,
            // Paused / "time's up": show the app's own short text (usually the frozen time).
            pausedText = (text.takeIf { it.isNotEmpty() && it.length <= 12 } ?: title.takeIf { it.length <= 12 })
                ?.ifEmpty { null },
            actions = n.actions.orEmpty().mapNotNull { a ->
                val pi = a.actionIntent ?: return@mapNotNull null
                LiveAction(a.title?.toString().orEmpty(), pi)
            }.take(2),
            contentIntent = n.contentIntent,
        )
    }

    // ---- Progress / navigation / Live Updates ---------------------------------------------------

    private fun buildProgress(sbn: StatusBarNotification): IslandActivity.Progress {
        val n = sbn.notification
        val e = n.extras
        val navigation = n.category == Notification.CATEGORY_NAVIGATION
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequence(Notification.EXTRA_SUB_TEXT))
            ?.toString()?.trim()?.replace('\n', ' ').orEmpty()
        // Android 16 Live Updates: the status-bar chip text ("5 min", "200 m").
        val short = e.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString()?.trim()?.ifEmpty { null }
            ?: if (navigation) title.takeIf { it.length <= 10 } else null
        val large = n.getLargeIcon()?.loadDrawable(context)?.toSafeBitmap()
        return IslandActivity.Progress(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title.ifEmpty { appLabel(sbn.packageName) },
            text = text,
            shortText = short,
            icon = large ?: appIcon(sbn.packageName),
            progress = e.getInt(Notification.EXTRA_PROGRESS, 0),
            max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            indeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            navigation = navigation,
            contentIntent = n.contentIntent,
        )
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** Android 16 promoted ongoing notifications (Live Updates), or an app asking for it. */
    private fun isPromoted(n: Notification): Boolean {
        if (n.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && n.flags and FLAG_PROMOTED_ONGOING != 0
    }

    private fun appLabel(pkg: String): String = appLabels.get(pkg) ?: try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }.also { appLabels.put(pkg, it) }

    private fun appIcon(pkg: String): Bitmap? = appIcons.get(pkg) ?: try {
        pm.getApplicationIcon(pkg).toSafeBitmap()?.also { appIcons.put(pkg, it) }
    } catch (_: Exception) { null }

    private fun Drawable.toSafeBitmap(): Bitmap? = try {
        toBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) { null }

    private fun Notification.Action.matches(words: List<String>): Boolean {
        val t = title?.toString()?.lowercase() ?: return false
        return words.any { it in t }
    }

    private inline fun <reified T : android.os.Parcelable> Bundle.parcel(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key) as? T
        }

    private companion object {
        // Notification.CallStyle extras (public constants on Android 12+; literal keys so the
        // same code reads them on any version).
        const val EXTRA_CALL_TYPE = "android.callType"
        const val EXTRA_CALL_PERSON = "android.callPerson"
        const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
        const val CALL_TYPE_INCOMING = 1
        const val CALL_TYPE_ONGOING = 2
        const val CALL_TYPE_SCREENING = 3

        // Android 16 Live Updates.
        const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        const val FLAG_PROMOTED_ONGOING = 0x00040000

        // Best-effort label matching for call apps that don't use CallStyle (English + a few
        // common languages; CallStyle apps don't need this).
        val ANSWER_WORDS = listOf("answer", "accept", "pick up", "contestar", "responder", "annehmen", "répondre", "jawab")
        val DECLINE_WORDS = listOf("decline", "reject", "dismiss", "rechazar", "ablehnen", "refuser", "rifiuta")
        val HANG_UP_WORDS = listOf("hang up", "end call", "end", "colgar", "auflegen", "raccrocher", "chiudi")

        val CLOCK_PACKAGES = setOf(
            "com.google.android.deskclock",
            "com.android.deskclock",           // AOSP, Xiaomi
            "com.sec.android.app.clockpackage", // Samsung
            "com.oneplus.deskclock",
            "com.coloros.alarmclock",           // Oppo / Realme
            "com.oplus.alarmclock",
            "com.huawei.deskclock",
            "com.vivo.alarmclock",
            "com.bbk.alarmclock",
        )
    }
}
