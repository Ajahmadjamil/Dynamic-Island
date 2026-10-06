package com.codewithaj.dynamicisland.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log

object PendingIntents {

    /**
     * Sends another app's PendingIntent (e.g. the media session activity) from the island.
     *
     * Android 14+ requires the *sender* to opt in to letting the PendingIntent start an
     * activity from the background. We qualify (visible overlay window / system-bound
     * accessibility service), but must still say so explicitly.
     */
    fun send(context: Context, pi: PendingIntent): Boolean = try {
        val options = ActivityOptions.makeBasic()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA ->
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                @Suppress("DEPRECATION")
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        pi.send(context, 0, null, null, null, null, options.toBundle())
        true
    } catch (e: Exception) {
        // PendingIntent.CanceledException (the app cancelled it) or a BAL refusal.
        Log.w("PendingIntents", "send failed", e)
        false
    }
}
