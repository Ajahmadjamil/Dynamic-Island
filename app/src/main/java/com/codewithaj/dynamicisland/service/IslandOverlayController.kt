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
import com.codewithaj.dynamicisland.island.view.content.CallRenderer
import com.codewithaj.dynamicisland.island.view.content.ProgressRenderer
import com.codewithaj.dynamicisland.island.view.content.TimerRenderer
import com.codewithaj.dynamicisland.island.view.BubbleTarget
import com.codewithaj.dynamicisland.island.view.TouchProxyView
import com.codewithaj.dynamicisland.island.view.LiveActionHandler
import com.codewithaj.dynamicisland.island.view.content.NotificationRenderer
import com.codewithaj.dynamicisland.notifications.HeadsUpSuppressor
import com.codewithaj.dynamicisland.notifications.IslandNotificationListener
import com.codewithaj.dynamicisland.util.PendingIntents
import com.codewithaj.dynamicisland.util.DisplayUtils
import com.codewithaj.dynamicisland.util.FullscreenProbe
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
 * Window strategy (smoothness + touch pass-through):
 *
 *  Accessibility mode ("fixed canvas"): the island is drawn in a window that NEVER moves or
 *  resizes — full screen width, tall enough for the biggest card, NOT_TOUCHABLE. Every
 *  animation is pure draw work inside a stationary surface, so nothing can jitter. Touches are
 *  received by a second, invisible window ([TouchProxyView]) that hugs the shape's target
 *  bounds; moving that one has no visual effect. (Moving the drawing window itself, as earlier
 *  versions did, makes the compositor apply the new position a frame before the re-offset
 *  content arrives → a visible left/right twitch.)
 *
 *  Fallback mode: Android blocks touches passing *through* untrusted overlays (Android 12+
 *  "untrusted touch occlusion"), so a big pass-through canvas would freeze the top of the
 *  screen. There the single window grows at the start of a transition and shrinks once the
 *  springs settle, as before.
 */
class IslandOverlayController(
    private val context: Context,
    private val windowType: Int,
) : IslandView.Listener {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = ServiceLocator.islandState
    private val view = IslandView(context)
    private val isFallback = windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    /** See the class doc: stationary drawing window + separate touch window. */
    private val fixedCanvas = !isFallback
    private val params = buildParams()
    private val touchView = if (fixedCanvas) TouchProxyView(context, view) else null
    private val touchParams = buildTouchParams()
    /** Accessibility mode only: our own windows can't see the status bar, the probe can. */
    private val fullscreenProbe = if (fixedCanvas) FullscreenProbe(context) { visible ->
        statusBarVisible = visible
        applyVisibility()
    } else null
    private var touchAttached = false
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
    /** Buttons inside live activities fire the owning app's PendingIntents. */
    private val liveActions = LiveActionHandler { pi -> haptics.tap(); PendingIntents.send(context, pi) }
    private val callRenderer = CallRenderer(density, liveActions).also { r -> r.invalidator = { view.invalidate() } }
    private val timerRenderer = TimerRenderer(density, liveActions).also { r -> r.invalidator = { view.invalidate() } }
    private val progressRenderer = ProgressRenderer(density)

    /** What's on screen (after filtering), for gesture handling. */
    private var shownAlert: IslandActivity.Alert? = null
    private var shownPrimary: IslandActivity? = null
    private var shownSecondary: IslandActivity? = null
    private var bubble: BubbleTarget? = null
    private var lastAlertKey: Any? = null

    private var attached = false
    private var settings = IslandSettings()
    private var ui = IslandUiState()
    private var foregroundPackage: String? = null
    private var statusBarVisible = true

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
        // Fallback windows see the status bar themselves; accessibility mode uses the probe.
        if (!fixedCanvas) {
            view.onStatusBarVisibilityChanged = { visible ->
                statusBarVisible = visible
                applyVisibility()
            }
        }
        fullscreenProbe?.start()
        try {
            wm.addView(view, params)
            attached = true
            touchView?.let { wm.addView(it, touchParams); touchAttached = true }
        } catch (e: Exception) {
            // BadTokenException / SecurityException: permission revoked or service not connected yet.
            Log.w(TAG, "Could not add island window", e)
            if (!attached) return
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
        fullscreenProbe?.stop()
        // No island → give the system its pop-up banners back.
        HeadsUpSuppressor.hostRunning = false
        HeadsUpSuppressor.sync(context)
        try { context.unregisterReceiver(playgroundReceiver) } catch (_: Exception) { }
        view.listener = null
        view.onStatusBarVisibilityChanged = null
        if (touchAttached) {
            try { wm.removeViewImmediate(touchView) } catch (_: Exception) { }
            touchAttached = false
        }
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

        // Main pill + split bubble, chosen from the activities the user allows right now.
        val (primary, secondary) = ui.pick(ui.activities.filter(::isVisible))
        shownPrimary = primary
        shownSecondary = secondary
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

        // Two activities in compact mode → the second splits off into a bubble on the right.
        // If pill + gap + bubble would run off-screen, the pill shifts left to make room.
        var b: BubbleTarget? = null
        if (alert == null && newMode == IslandMode.COMPACT && secondary != null) {
            val r = t.height / 2f
            var cx = t.centerX + t.width / 2f + AnimationSpec.BUBBLE_GAP_DP * density + r
            val overflow = cx + r - (screenW - EDGE_DP * density)
            if (overflow > 0f) {
                t = t.copy(centerX = t.centerX - overflow)
                cx -= overflow
            }
            b = BubbleTarget(cx, t.top + t.height / 2f, r)
        }
        bubble = b
        mode = newMode
        target = t

        val targetRight = maxOf(t.centerX + t.width / 2f, b?.let { it.centerX + it.radius } ?: 0f)
        if (fixedCanvas) {
            // Drawing window stays put (sized once per rotation); only the invisible touch
            // window follows where the shape is going.
            ensureCanvasSize(pill, needCard = newMode == IslandMode.EXPANDED, allowShrink = false)
            setTouchBounds(t.centerX - t.width / 2f, t.top, targetRight, t.top + t.height)
        } else {
            growWindowFor(t, b, targetRight, animate)
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
        // After transitionTo, so a merge heads for the pill's *new* right end.
        view.setBubble(b, b?.let { rendererFor(secondary) }, if (b != null) secondary else null, animate)
    }

    // ---- Fixed canvas (accessibility mode) ------------------------------------------------------

    /**
     * The drawing window's origin (0,0) and width (full screen) never change, so drawn content
     * can never shift sideways. Only its *height* adapts, to save graphics memory (3 buffers of
     * a full-width, card-tall surface are ~7 MB; pill-tall ones ~1.5 MB):
     *  - grows to card height at the *start* of any transition to a card,
     *  - shrinks back to pill height once the springs have settled in idle/compact.
     * Growing/shrinking the bottom edge of a window anchored at the top moves nothing on screen.
     */
    private fun ensureCanvasSize(pill: PillBounds, needCard: Boolean, allowShrink: Boolean) {
        val m = MARGIN_DP * 2 * density
        val room = 1f + AnimationSpec.OVERSHOOT_ROOM
        val full = run {
            val cardTop = maxOf(pill.top, DisplayUtils.statusBarHeightPx(context) + CARD_GAP_DP * density)
            ceil(cardTop + MAX_CARD_HEIGHT_DP * density * room + m).toInt()
        }
        val small = ceil(pill.top + pill.height * room + m).toInt()
        val h = when {
            needCard -> full
            // Only shrink once settled — a collapsing card still needs the room.
            allowShrink -> small
            else -> maxOf(params.height, small)
        }
        val w = screenW.toInt()
        if (params.x == 0 && params.y == 0 && params.width == w && params.height == h && view.windowX == 0) return
        params.x = 0
        params.y = 0
        params.width = w
        params.height = h
        view.windowX = 0
        safeUpdate()
    }

    /** Moves the invisible touch window over the shape's target bounds (+ a little slop). */
    private fun setTouchBounds(left: Float, top: Float, right: Float, bottom: Float) {
        val tv = touchView ?: return
        val m = TOUCH_MARGIN_DP * density
        val l = floor(left - m).toInt().coerceAtLeast(0)
        val t = floor(top - m).toInt().coerceAtLeast(0)
        val r = ceil(right + m).toInt().coerceAtMost(screenW.toInt().takeIf { it > 0 } ?: Int.MAX_VALUE)
        val b = ceil(bottom + m).toInt()
        tv.offsetX = l.toFloat()
        tv.offsetY = t.toFloat()
        if (touchParams.x == l && touchParams.y == t && touchParams.width == r - l && touchParams.height == b - t) return
        touchParams.x = l
        touchParams.y = t
        touchParams.width = r - l
        touchParams.height = b - t
        safeUpdateTouch()
    }

    // ---- Resizing window (fallback mode) --------------------------------------------------------

    /** Grow the window to cover where the shape is now and where it's going. */
    private fun growWindowFor(t: ShapeTarget, b: BubbleTarget?, targetRight: Float, animate: Boolean) {
        view.visualBounds(tmpRect)
        if (animate && tmpRect.width() > 0f) {
            val room = 1f + AnimationSpec.OVERSHOOT_ROOM
            setWindowBounds(
                left = minOf(tmpRect.left, t.centerX - t.width / 2f * room),
                right = maxOf(tmpRect.right, t.centerX + t.width / 2f * room, targetRight + (b?.radius ?: 0f) * 0.3f),
                bottom = maxOf(tmpRect.bottom, t.top + t.height * room),
            )
        } else {
            setWindowBounds(t.centerX - t.width / 2f, targetRight, t.top + t.height)
        }
    }

    /** Which activities may appear right now (feature toggles, per-app filter, own app in front). */
    private fun isVisible(a: IslandActivity): Boolean {
        val pkg = a.packageName
        val featureOn = when (a) {
            is IslandActivity.Media -> settings.showMedia
            is IslandActivity.Call -> settings.showCalls
            is IslandActivity.Timer -> settings.showTimers
            is IslandActivity.Progress -> settings.showLiveUpdates
            else -> true
        }
        if (!featureOn) return false
        if (pkg != null && !settings.allowsApp(pkg)) return false
        // Like iOS: a compact activity steps aside while its own app is open.
        if (!ui.expanded && pkg != null && pkg == foregroundPackage) return false
        return true
    }

    private fun rendererFor(activity: IslandActivity?): IslandContentRenderer? = when (activity) {
        is IslandActivity.Demo -> demoRenderer
        is IslandActivity.Media -> mediaRenderer
        is IslandActivity.Call -> callRenderer
        is IslandActivity.Timer -> timerRenderer
        is IslandActivity.Progress -> progressRenderer
        is IslandActivity.NotificationPreview -> notificationRenderer
        is IslandActivity.Charging, is IslandActivity.LowBattery,
        is IslandActivity.Ringer, is IslandActivity.BluetoothDevice,
        is IslandActivity.Connectivity -> alertRenderer
        null -> null
    }

    /** Springs at rest. Fallback mode: shrink the window to hug the target (and bubble). */
    override fun onSettled() {
        val t = target ?: return
        if (fixedCanvas) {
            idle?.let { ensureCanvasSize(it, needCard = mode == IslandMode.EXPANDED, allowShrink = true) }
        } else {
            val right = maxOf(t.centerX + t.width / 2f, bubble?.let { it.centerX + it.radius } ?: 0f)
            setWindowBounds(t.centerX - t.width / 2f, right, t.top + t.height)
        }
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

        if (fixedCanvas) {
            // The drawing window is always NOT_TOUCHABLE; only the touch window toggles.
            val flags = if (shown) touchParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else touchParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (flags != touchParams.flags) {
                touchParams.flags = flags
                safeUpdateTouch()
            }
            return
        }

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
        val tapIntent = shownPrimary?.tapIntent
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

    /** Swipe sideways: the bubble's activity and the main pill trade places. */
    override fun onSwipeHorizontal(direction: Int) {
        if (dismissShownAlert()) return
        val second = shownSecondary ?: return
        haptics.tap()
        state.swap(second.id)
    }

    /** Tap the bubble: open its app (iOS); without an app to open, bring it to the main pill. */
    override fun onBubbleTap() {
        val second = shownSecondary ?: return
        haptics.tap()
        val intent = second.tapIntent
        if (intent != null) PendingIntents.send(context, intent) else state.swap(second.id)
    }

    /** Long-press the bubble: bring it to the main pill and expand it. */
    override fun onBubbleLongPress() {
        val second = shownSecondary ?: return
        haptics.expand()
        state.swap(second.id)
        state.expand()
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
                CMD_SPLIT -> {
                    // Toggle a second demo activity → gooey split / merge.
                    if (state.state.value.activities.any { it.id == "demo1" }) state.remove("demo1")
                    else state.post(IslandActivity.Demo(IslandActivity.Demo.Variant.TIMER, SystemClock.uptimeMillis(), slot = 1))
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

    private fun safeUpdateTouch() {
        val tv = touchView ?: return
        if (!touchAttached) return
        try { wm.updateViewLayout(tv, touchParams) } catch (e: Exception) { Log.w(TAG, "updateViewLayout(touch)", e) }
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
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            // Fixed canvas: draw only; the separate touch window takes the touches.
            (if (fixedCanvas) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
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

    /** Small invisible window that receives the island's touches (fixed-canvas mode). */
    private fun buildTouchParams() = WindowManager.LayoutParams(
        1, 1,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Islet touch"
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
        /** Fixed canvas: tallest card (music) the drawing window must fit. */
        private const val MAX_CARD_HEIGHT_DP = 200f
        /** Extra touchable slop around the shape in the touch window. */
        private const val TOUCH_MARGIN_DP = 6f
        /** Room around the shape for the calibration guide stroke and the press "squish". */
        private const val MARGIN_DP = 6f
        /** Fallback mode: touchable strip below the status bar, under the compact pill. */
        private const val TOUCH_SKIRT_DP = 28f
        /** Gap between the status bar and a notification preview card. */
        private const val CARD_GAP_DP = 6f
        /** Minimum distance between the split bubble and the screen edge. */
        private const val EDGE_DP = 8f

        const val ACTION_PLAYGROUND = "com.codewithaj.dynamicisland.action.PLAYGROUND"
        const val EXTRA_CMD = "cmd"
        const val CMD_SHOW = "show"
        const val CMD_SWITCH = "switch"
        const val CMD_SPLIT = "split"
        const val CMD_EXPAND = "expand"
        const val CMD_COLLAPSE = "collapse"
        const val CMD_CLEAR = "clear"
    }
}
