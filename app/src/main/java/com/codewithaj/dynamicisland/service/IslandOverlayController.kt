package com.codewithaj.dynamicisland.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.codewithaj.dynamicisland.ServiceLocator
import com.codewithaj.dynamicisland.data.IslandSettings
import com.codewithaj.dynamicisland.island.IslandActivity
import com.codewithaj.dynamicisland.island.IslandGeometry
import com.codewithaj.dynamicisland.island.IslandMode
import com.codewithaj.dynamicisland.island.IslandUiState
import com.codewithaj.dynamicisland.island.PillBounds
import com.codewithaj.dynamicisland.island.animation.AnimationSpec
import com.codewithaj.dynamicisland.island.view.IslandContentRenderer
import com.codewithaj.dynamicisland.island.view.IslandView
import com.codewithaj.dynamicisland.island.view.Presentation
import com.codewithaj.dynamicisland.island.view.ShapeTarget
import com.codewithaj.dynamicisland.island.view.content.DemoRenderer
import com.codewithaj.dynamicisland.island.view.content.MediaRenderer
import com.codewithaj.dynamicisland.island.view.content.AlertRenderer
import com.codewithaj.dynamicisland.island.view.content.NotificationRenderer
import com.codewithaj.dynamicisland.notifications.HeadsUpSuppressor
import com.codewithaj.dynamicisland.notifications.IslandNotificationListener
import com.codewithaj.dynamicisland.util.PendingIntents
import com.codewithaj.dynamicisland.util.DisplayUtils
import com.codewithaj.dynamicisland.util.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Owns the island window. Shared by both hosts:
 *  - [IslandAccessibilityService] with TYPE_ACCESSIBILITY_OVERLAY (can sit above the status bar),
 *  - [OverlayFallbackService] with TYPE_APPLICATION_OVERLAY (sits under the status bar icons).
 *
 * Window strategy (the key performance rule): layout params change only at the *start* and
 * *end* of a transition, never per frame.
 *   1. Start: grow the window to cover both the current visual shape and the target shape,
 *      plus room for spring overshoot.
 *   2. Every frame: springs update floats inside [IslandView] → draw only.
 *   3. Settled: shrink the window to hug the target, so touches around the island reach the
 *      app below (and the status-bar pull-down still works beside it).
 */
class IslandOverlayController(
    private val context: Context,
    private val windowType: Int,
) : IslandView.Listener {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = ServiceLocator.islandState
    private val view = IslandView(context)
    private val params = buildParams()
    private val haptics = Haptics(context)
    private val density = context.resources.displayMetrics.density

    private val demoRenderer = DemoRenderer(density)
    private val mediaRenderer = MediaRenderer(density, object : MediaRenderer.Actions {
        override fun onPlayPause() { haptics.tap(); ServiceLocator.media.playPause() }
        override fun onNext() { haptics.tap(); ServiceLocator.media.next() }
        override fun onPrevious() { haptics.tap(); ServiceLocator.media.previous() }
        override fun onSeek(positionMs: Long) { haptics.tap(); ServiceLocator.media.seekTo(positionMs) }
    }).also { r -> r.invalidator = { view.invalidate() } }
    private val alertRenderer = AlertRenderer(density)
    private val notificationRenderer = NotificationRenderer(density)

    /** The alert currently on screen (after filtering), for gesture handling. */
    private var shownAlert: IslandActivity.Alert? = null
    private var lastAlertKey: Any? = null

    private var attached = false
    private var settings = IslandSettings()
    private var ui = IslandUiState()
    private var foregroundPackage: String? = null
    private var statusBarVisible = true

    private val isFallback = windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var skirtBottom = 0f

    private var idle: PillBounds? = null
    private var screenW = 0f
    private var mode = IslandMode.HIDDEN
    private var target: ShapeTarget? = null

    private val tmpRect = RectF()
    private val contentRect = RectF()

    fun start() {
        if (attached) return
        view.listener = this
        view.onStatusBarVisibilityChanged = { visible ->
            statusBarVisible = visible
            applyVisibility()
        }
        try {
            wm.addView(view, params)
            attached = true
        } catch (e: Exception) {
            // BadTokenException / SecurityException: permission revoked or service not connected yet.
            Log.w(TAG, "Could not add island window", e)
            return
        }
        ContextCompat.registerReceiver(
            context, playgroundReceiver, IntentFilter(ACTION_PLAYGROUND), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ServiceLocator.systemEvents.start()
        HeadsUpSuppressor.hostRunning = true
        combine(
            ServiceLocator.settings.settings,
            state.state,
            IslandAccessibilityService.foregroundPackage,
        ) { s, st, fg -> Inputs(s, st, fg) }
            .onEach { onInputs(it.settings, it.state, it.foreground) }
            .launchIn(scope)
    }

    fun stop() {
        scope.cancel()
        ServiceLocator.systemEvents.stop()
        // No island → give the system its pop-up banners back.
        HeadsUpSuppressor.hostRunning = false
        HeadsUpSuppressor.sync(context)
        try { context.unregisterReceiver(playgroundReceiver) } catch (_: Exception) { }
        view.listener = null
        view.onStatusBarVisibilityChanged = null
        if (attached) {
            try { wm.removeViewImmediate(view) } catch (_: Exception) { }
            attached = false
        }
    }

    fun onConfigurationChanged() {
        // Cutout position and screen width change with rotation: recompute and snap.
        idle = null
        render(animate = false)
    }

    // ---- State → view ---------------------------------------------------------------------------

    private class Inputs(val settings: IslandSettings, val state: IslandUiState, val foreground: String?)

    private fun onInputs(s: IslandSettings, st: IslandUiState, fg: String?) {
        val geometryChanged = s.calibration != settings.calibration || idle == null
        settings = s
        ui = st
        foregroundPackage = fg
        view.speedMultiplier = s.animationSpeed.stiffnessMultiplier
        view.calibrating = s.calibrating
        HeadsUpSuppressor.onSettings(context, s)
        if (geometryChanged) idle = null
        // Calibration changes snap so the pill tracks the sliders 1:1; state changes spring.
        render(animate = !geometryChanged)
        applyVisibility()
    }

    private fun render(animate: Boolean) {
        if (!attached) return
        val pill = idle ?: IslandGeometry.compute(context, settings.calibration).also {
            idle = it
            screenW = DisplayUtils.screenWidthPx(context).toFloat()
        }

        val primary = ui.primary?.takeUnless { a ->
            val pkg = a.packageName
            (a is IslandActivity.Media && !settings.showMedia) ||
                (pkg != null && !settings.allowsApp(pkg)) ||
                // Like iOS: a compact activity steps aside while its own app is open.
                (!ui.expanded && pkg != null && pkg == foregroundPackage)
        }
        // A transient alert temporarily takes over; when it ends, `primary` simply shows again.
        val alert = ui.alert?.takeUnless { a -> a.packageName?.let { !settings.allowsApp(it) } == true }
        shownAlert = alert
        if (alert != null && alert.contentKey != lastAlertKey && alert is IslandActivity.Charging) haptics.tap()
        lastAlertKey = alert?.contentKey

        val shown = alert ?: primary
        val renderer = rendererFor(shown)
        val newMode = when {
            alert != null -> if (alert.isCard) IslandMode.EXPANDED else IslandMode.COMPACT
            primary == null -> if (settings.showIdlePill || settings.calibrating) IslandMode.IDLE else IslandMode.HIDDEN
            ui.expanded -> IslandMode.EXPANDED
            else -> IslandMode.COMPACT
        }
        val spec = when {
            newMode == IslandMode.EXPANDED -> AnimationSpec.EXPAND
            mode == IslandMode.EXPANDED -> AnimationSpec.COLLAPSE
            else -> AnimationSpec.MORPH
        }
        var t = IslandGeometry.targetFor(newMode, pill, screenW, density, renderer)
        if (alert is IslandActivity.NotificationPreview) {
            // Notification cards sit just below the status bar so the clock and icons stay
            // visible; the island springs down into the card and back up afterwards.
            val below = DisplayUtils.statusBarHeightPx(context) + CARD_GAP_DP * density
            if (t.top < below) t = t.copy(top = below)
        }

        // Fallback mode: the status bar window is above ours and eats every touch on the pill.
        // The island stays over the camera regardless; we only accept touches in a strip just
        // below the status bar. Direct taps on the pill need the accessibility overlay.
        skirtBottom = if (isFallback && newMode == IslandMode.COMPACT) {
            DisplayUtils.statusBarHeightPx(context) + TOUCH_SKIRT_DP * density
        } else 0f
        view.touchSkirtBottom = skirtBottom
        mode = newMode
        target = t

        // 1. Grow the window to cover where the shape is now and where it's going.
        view.visualBounds(tmpRect)
        if (animate && tmpRect.width() > 0f) {
            val room = 1f + AnimationSpec.OVERSHOOT_ROOM
            setWindowBounds(
                left = minOf(tmpRect.left, t.centerX - t.width / 2f * room),
                right = maxOf(tmpRect.right, t.centerX + t.width / 2f * room),
                bottom = maxOf(tmpRect.bottom, t.top + t.height * room),
            )
        } else {
            setWindowBounds(t.centerX - t.width / 2f, t.centerX + t.width / 2f, t.top + t.height)
        }

        // 2. Hand the target to the view; springs take it from here.
        contentRect.set(t.centerX - t.width / 2f, t.top, t.centerX + t.width / 2f, t.top + t.height)
        val presentation = if (newMode == IslandMode.EXPANDED) Presentation.EXPANDED else Presentation.COMPACT
        val showsContent = newMode == IslandMode.COMPACT || newMode == IslandMode.EXPANDED
        view.transitionTo(
            target = t,
            spec = spec,
            renderer = if (showsContent) renderer else null,
            presentation = presentation,
            key = if (showsContent) shown?.contentKey else null,
            data = if (showsContent) shown else null,
            contentLayout = contentRect,
            animate = animate,
        )
    }

    private fun rendererFor(activity: IslandActivity?): IslandContentRenderer? = when (activity) {
        is IslandActivity.Demo -> demoRenderer
        is IslandActivity.Media -> mediaRenderer
        is IslandActivity.NotificationPreview -> notificationRenderer
        is IslandActivity.Charging, is IslandActivity.LowBattery,
        is IslandActivity.Ringer, is IslandActivity.BluetoothDevice -> alertRenderer
        null -> null
    }

    /** 3. Springs at rest: hug the target so the rest of the screen stays touchable. */
    override fun onSettled() {
        val t = target ?: return
        setWindowBounds(t.centerX - t.width / 2f, t.centerX + t.width / 2f, t.top + t.height)
        applyVisibility()
    }

    private fun setWindowBounds(left: Float, right: Float, bottom: Float) {
        val m = MARGIN_DP * density
        val l = floor(left - m).toInt().coerceAtLeast(0)
        val r = ceil(right + m).toInt().coerceAtMost(screenW.toInt().takeIf { it > 0 } ?: Int.MAX_VALUE)
        val h = ceil(maxOf(bottom + m, skirtBottom)).toInt()
        if (params.x == l && params.width == r - l && params.height == h) return
        params.x = l
        params.y = 0
        params.width = r - l
        params.height = h
        view.windowX = l
        safeUpdate()
    }

    private fun applyVisibility() {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val allowed = settings.islandEnabled &&
            !(settings.hideInLandscape && landscape) &&
            !(settings.hideInFullscreen && !statusBarVisible && !settings.calibrating)
        val shown = allowed && (mode != IslandMode.HIDDEN || view.isAnimating())

        view.visibility = if (allowed) View.VISIBLE else View.INVISIBLE // INVISIBLE keeps receiving insets

        // An invisible/hidden window would still eat touches, so make it pass-through.
        // For TYPE_APPLICATION_OVERLAY, Android 12+ also blocks touches through untrusted
        // windows above 0.8 alpha, so drop the window alpha too.
        val flags = if (shown) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val alpha = if (shown) 1f else 0f
        if (flags != params.flags || alpha != params.alpha) {
            params.flags = flags
            params.alpha = alpha
            safeUpdate()
        }
    }

    // ---- Gestures -------------------------------------------------------------------------------

    /**
     * iOS behaviour: tapping a compact activity opens its app (long-press/swipe-down expands).
     * Activities without an app to open (the playground) expand instead. Tapping the
     * expanded card outside its controls opens the app too, then collapses.
     */
    override fun onTap() {
        shownAlert?.let { alert ->
            // Alerts: open what they point at (a notification's content), then go away.
            haptics.tap()
            alert.tapIntent?.let { PendingIntents.send(context, it) }
            if (alert is IslandActivity.NotificationPreview && alert.autoCancel) {
                IslandNotificationListener.cancel(alert.key)
            }
            state.dismissAlert()
            return
        }
        val tapIntent = ui.primary?.tapIntent
        when (mode) {
            IslandMode.COMPACT -> if (tapIntent != null) {
                haptics.tap()
                PendingIntents.send(context, tapIntent)
            } else {
                haptics.expand(); state.expand()
            }
            IslandMode.EXPANDED -> {
                haptics.collapse()
                if (tapIntent != null) PendingIntents.send(context, tapIntent)
                state.collapse()
            }
            else -> haptics.tap()
        }
    }

    /** Any swipe or long-press on an alert dismisses it (swipe-to-dismiss). */
    private fun dismissShownAlert(): Boolean {
        if (shownAlert == null) return false
        haptics.collapse()
        state.dismissAlert()
        return true
    }

    override fun onLongPress() {
        if (dismissShownAlert()) return
        if (mode == IslandMode.COMPACT) { haptics.expand(); state.expand() }
    }

    override fun onSwipeUp() {
        if (dismissShownAlert()) return
        if (mode == IslandMode.EXPANDED) { haptics.collapse(); state.collapse() }
    }

    override fun onSwipeDown() {
        if (shownAlert != null) return
        if (mode == IslandMode.COMPACT) { haptics.expand(); state.expand() }
    }

    override fun onSwipeHorizontal(direction: Int) {
        if (dismissShownAlert()) return
        // Phase 5: switch between the main pill and the split bubble.
        if (mode == IslandMode.COMPACT || mode == IslandMode.EXPANDED) haptics.tap()
    }

    override fun onOutsideTouch() {
        // Alerts time out on their own; tapping elsewhere doesn't dismiss them (iOS behaviour).
        if (shownAlert != null) return
        if (mode == IslandMode.EXPANDED) { haptics.collapse(); state.collapse() }
    }

    // ---- Playground (debug commands from the settings UI process) ------------------------------

    private val playgroundReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getStringExtra(EXTRA_CMD)) {
                CMD_SHOW -> state.post(IslandActivity.Demo(IslandActivity.Demo.Variant.MUSIC, SystemClock.uptimeMillis()))
                CMD_SWITCH -> {
                    val cur = state.state.value.activities.filterIsInstance<IslandActivity.Demo>().firstOrNull()
                    val next = if (cur?.variant == IslandActivity.Demo.Variant.MUSIC) IslandActivity.Demo.Variant.TIMER
                    else IslandActivity.Demo.Variant.MUSIC
                    state.post(IslandActivity.Demo(next, SystemClock.uptimeMillis()))
                }
                CMD_EXPAND -> state.expand()
                CMD_COLLAPSE -> state.collapse()
                CMD_CLEAR -> state.clear()
            }
        }
    }

    private fun safeUpdate() {
        if (!attached) return
        try { wm.updateViewLayout(view, params) } catch (e: Exception) { Log.w(TAG, "updateViewLayout", e) }
    }

    private fun buildParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // Lets the expanded card collapse when the user taps anywhere else.
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Islet"
        // Allow y = 0 to be the physical top edge, inside the cutout area.
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    companion object {
        private const val TAG = "IslandOverlay"
        /** Room around the shape for the calibration guide stroke and the press "squish". */
        private const val MARGIN_DP = 6f
        /** Fallback mode: touchable strip below the status bar, under the compact pill. */
        private const val TOUCH_SKIRT_DP = 28f
        /** Gap between the status bar and a notification preview card. */
        private const val CARD_GAP_DP = 6f

        const val ACTION_PLAYGROUND = "com.codewithaj.dynamicisland.action.PLAYGROUND"
        const val EXTRA_CMD = "cmd"
        const val CMD_SHOW = "show"
        const val CMD_SWITCH = "switch"
        const val CMD_EXPAND = "expand"
        const val CMD_COLLAPSE = "collapse"
        const val CMD_CLEAR = "clear"
    }
}
