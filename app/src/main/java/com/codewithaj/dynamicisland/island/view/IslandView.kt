package com.codewithaj.dynamicisland.island.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.codewithaj.dynamicisland.island.animation.AnimationSpec
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the shape should end up, in *screen* coordinates (px). */
data class ShapeTarget(
    val centerX: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
    val alpha: Float,
)

/**
 * The island, drawn entirely with Canvas and animated with springs.
 *
 * Geometry is kept in screen coordinates; [windowX] converts to view coordinates. The window
 * is resized by the controller only at the start/end of a transition, so every frame of a
 * morph is pure draw-phase work: springs update a few floats and call invalidate().
 *
 * Content choreography uses two slots: when content changes, the current slot fades out
 * (shrinking and blurring) while the container morphs, and the other slot fades in after a
 * short stagger. See [AnimationSpec] for the timings.
 *
 * Performance: nothing in [onDraw] allocates. Blur effects are pre-built per quantised level.
 *
 * On hardware layers: a layer only pays off when a view's *content* is static and only its
 * transform/alpha animate. Here the content is re-recorded every frame while morphing, so a
 * layer would add a full extra render pass per frame. We therefore never enable one; the
 * display list stays tiny (one round rect + a few glyph runs) instead.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context) : View(context) {

    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onSwipeUp()
        fun onSwipeDown()
        /** -1 = swipe left, 1 = swipe right. */
        fun onSwipeHorizontal(direction: Int)
        fun onOutsideTouch()
        /** All shape springs have come to rest. */
        fun onSettled()
    }

    var listener: Listener? = null

    /** Called when the system bars appear/disappear (best-effort fullscreen detection). */
    var onStatusBarVisibilityChanged: ((visible: Boolean) -> Unit)? = null

    /** Screen x of this window's left edge. Updated by the controller with the layout params. */
    var windowX = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Multiplies every spring stiffness (user "animation speed" preset). */
    var speedMultiplier = 1f

    var calibrating = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * Screen y down to which touches below the shape still count as touching the island
     * (0 = off). Used in fallback mode, where the status bar window sits above ours and
     * swallows every touch on the pill itself.
     */
    var touchSkirtBottom = 0f

    private val density = resources.displayMetrics.density

    // ---- Shape state (screen coords), driven by springs --------------------------------------
    private var centerX = 0f
    private var top = 0f
    private var shapeW = 0f
    private var shapeH = 0f
    private var radius = 0f
    private var shapeAlpha = 0f
    private var pressScale = 1f

    private var initialized = false

    private val endListener = DynamicAnimation.OnAnimationEndListener { _, _, _, _ -> checkSettled() }

    private val centerXAnim = spring({ centerX }, { centerX = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true)
    private val topAnim = spring({ top }, { top = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true)
    private val widthAnim = spring({ shapeW }, { shapeW = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true)
    private val heightAnim = spring({ shapeH }, { shapeH = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true)
    private val radiusAnim = spring({ radius }, { radius = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true)
    private val alphaAnim = spring({ shapeAlpha }, { shapeAlpha = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA, notifySettle = true)
    private val pressAnim = spring({ pressScale }, { pressScale = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
    private val shapeAnims = arrayOf(centerXAnim, topAnim, widthAnim, heightAnim, radiusAnim, alphaAnim)

    // ---- Content slots -----------------------------------------------------------------------
    private inner class Slot(name: String) {
        var renderer: IslandContentRenderer? = null
        var presentation = Presentation.COMPACT
        /** Cross-fade identity ([com.codewithaj.dynamicisland.island.IslandActivity.contentKey]). */
        var key: Any? = null
        /** Payload handed to the renderer for this slot. */
        var data: Any? = null
        /** Layout rect in screen coords (the target bounds when this content was set). */
        val layout = RectF()
        var alpha = 0f
        var scale = 1f
        val alphaAnim = spring({ alpha }, { alpha = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA).apply {
            addEndListener { _, _, value, _ ->
                if (value <= 0.001f && this@Slot !== current) { renderer = null; data = null; key = null }
            }
        }
        val scaleAnim = spring({ scale }, { scale = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        val node: RenderNode? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode(name) else null
    }

    private val slotA = Slot("islandContentA")
    private val slotB = Slot("islandContentB")
    private var current = slotA
    private val other get() = if (current === slotA) slotB else slotA

    private val fadeInRunnable = Runnable {
        val s = current
        if (s.renderer != null) {
            animate(s.alphaAnim, 1f, AnimationSpec.CONTENT_IN)
            animate(s.scaleAnim, 1f, AnimationSpec.CONTENT_IN)
        }
    }

    // Pre-built blur effects, one per quantised level (index 0 = no blur).
    private val blurEffects: Array<RenderEffect?> = Array(AnimationSpec.BLUR_LEVELS + 1) { level ->
        if (level == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
        else {
            val r = AnimationSpec.CONTENT_MAX_BLUR_DP * density * level / AnimationSpec.BLUR_LEVELS
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL)
        }
    }

    // ---- Drawing resources ---------------------------------------------------------------------
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34C759.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val shapeRect = RectF()
    private val slotRect = RectF()
    private val clipPath = Path()

    // ---- Public API ----------------------------------------------------------------------------

    private var lastTarget: ShapeTarget? = null

    /**
     * Animate (or snap, if [animate] is false or this is the first layout) to [target], and
     * switch content to [renderer]/[presentation] laid out in [contentLayout] (screen coords).
     * Content with the same [key] updates in place; a different key cross-fades.
     */
    fun transitionTo(
        target: ShapeTarget,
        spec: AnimationSpec.Spring,
        renderer: IslandContentRenderer?,
        presentation: Presentation,
        key: Any?,
        data: Any?,
        contentLayout: RectF,
        animate: Boolean,
    ) {
        val snap = !animate || !initialized
        initialized = true
        if (!snap && target == lastTarget) {
            // Shape unchanged (e.g. a playback-position update): content only, no spring kick.
            setContent(renderer, presentation, key, data, contentLayout, snap = false)
            invalidate()
            return
        }
        lastTarget = target
        if (snap) {
            for (a in shapeAnims) a.cancel()
            centerX = target.centerX; top = target.top; shapeW = target.width
            shapeH = target.height; radius = target.radius; shapeAlpha = target.alpha
        } else {
            animate(centerXAnim, target.centerX, spec)
            animate(topAnim, target.top, spec)
            animate(widthAnim, target.width, spec)
            // Height uses a slightly firmer spring than width so the shape grows "wide first"
            // and never looks like it's inflating uniformly — part of the iOS feel.
            animate(heightAnim, target.height, spec, stiffnessScale = 1.15f)
            animate(radiusAnim, target.radius, spec)
            animate(alphaAnim, target.alpha, AnimationSpec.SHAPE_FADE)
        }
        setContent(renderer, presentation, key, data, contentLayout, snap)
        invalidate()
        if (snap) post { listener?.onSettled() }
    }

    /** Current visual bounds of the shape in screen coords, including press scale. */
    fun visualBounds(out: RectF) {
        val w = shapeW * pressScale
        val h = shapeH * pressScale
        val cy = top + shapeH / 2f
        out.set(centerX - w / 2f, cy - h / 2f, centerX + w / 2f, cy + h / 2f)
    }

    fun isAnimating(): Boolean = shapeAnims.any { it.isRunning }

    // ---- Content --------------------------------------------------------------------------------

    private fun setContent(
        renderer: IslandContentRenderer?,
        presentation: Presentation,
        key: Any?,
        data: Any?,
        layout: RectF,
        snap: Boolean,
    ) {
        val cur = current
        if (cur.renderer === renderer && cur.presentation == presentation && cur.key == key) {
            // Same content: update data in place and follow the container.
            cur.data = data
            cur.layout.set(layout)
            return
        }
        removeCallbacks(fadeInRunnable)
        val outgoing = cur
        val incoming = other

        // Outgoing: fast fade + shrink (+ blur, derived from alpha in onDraw).
        if (snap) {
            outgoing.alphaAnim.cancel(); outgoing.alpha = 0f; outgoing.renderer = null
        } else if (outgoing.renderer != null) {
            animate(outgoing.alphaAnim, 0f, AnimationSpec.CONTENT_OUT)
            animate(outgoing.scaleAnim, AnimationSpec.CONTENT_OUT_SCALE, AnimationSpec.CONTENT_OUT)
        }

        // Incoming: starts invisible and slightly small; fades in after the stagger.
        incoming.alphaAnim.cancel(); incoming.scaleAnim.cancel()
        incoming.renderer = renderer
        incoming.presentation = presentation
        incoming.key = key
        incoming.data = data
        incoming.layout.set(layout)
        current = incoming
        if (renderer == null) return
        if (snap) {
            incoming.alpha = 1f; incoming.scale = 1f
        } else {
            incoming.alpha = 0f; incoming.scale = AnimationSpec.CONTENT_IN_START_SCALE
            postDelayed(fadeInRunnable, AnimationSpec.CONTENT_IN_DELAY_MS)
        }
    }

    // ---- Drawing --------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (shapeAlpha <= 0.004f || shapeW <= 0f || shapeH <= 0f) {
            if (calibrating) drawGuide(canvas)
            return
        }
        val cx = centerX - windowX
        val cy = top + shapeH / 2f

        canvas.save()
        if (pressScale != 1f) canvas.scale(pressScale, pressScale, cx, cy)

        shapeRect.set(cx - shapeW / 2f, top, cx + shapeW / 2f, top + shapeH)
        fillPaint.alpha = (shapeAlpha * 255).roundToInt()
        canvas.drawRoundRect(shapeRect, radius, radius, fillPaint)

        // Content is clipped to the (animating) container shape.
        clipPath.reset()
        clipPath.addRoundRect(shapeRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        val now = SystemClock.uptimeMillis()
        drawSlot(canvas, other, cx, cy, now)
        drawSlot(canvas, current, cx, cy, now)
        canvas.restore()

        if (calibrating) drawGuide(canvas)

        // Content animations tick only while visible: every vsync for short foreground
        // animations (bell, battery fill), ~30 fps for ambient ones (equaliser, countdown).
        val r = current.renderer
        if (r != null && current.alpha > 0f && visibility == VISIBLE) {
            if (isAnimating() || current.alphaAnim.isRunning) return // springs already redraw every frame
            if (r.wantsFullFrameRate(current.data)) {
                postInvalidateOnAnimation()
            } else if (r.animatesContinuously(current.presentation, current.data)) {
                postInvalidateDelayed(AnimationSpec.AMBIENT_FRAME_MS)
            }
        }
    }

    private fun drawSlot(canvas: Canvas, slot: Slot, cx: Float, cy: Float, now: Long) {
        val renderer = slot.renderer ?: return
        if (slot.alpha <= 0.01f) return
        slotRect.set(slot.layout)
        slotRect.offset(-windowX.toFloat(), 0f)
        val alpha = slot.alpha * shapeAlpha

        val level = ((1f - slot.alpha) * AnimationSpec.BLUR_LEVELS).roundToInt()
        val node = slot.node
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && node != null && level > 0 && canvas.isHardwareAccelerated) {
            // Record into a RenderNode so a blur can be applied to the content alone.
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(blurEffects[level])
            val rc = node.beginRecording()
            rc.scale(slot.scale, slot.scale, cx, cy)
            renderer.draw(rc, slotRect, slot.presentation, alpha, now, slot.data)
            node.endRecording()
            canvas.drawRenderNode(node)
        } else {
            canvas.save()
            canvas.scale(slot.scale, slot.scale, cx, cy)
            renderer.draw(canvas, slotRect, slot.presentation, alpha, now, slot.data)
            canvas.restore()
        }
    }

    private fun drawGuide(canvas: Canvas) {
        val cx = centerX - windowX
        val inset = guidePaint.strokeWidth
        shapeRect.set(cx - shapeW / 2f - inset, top - inset, cx + shapeW / 2f + inset, top + shapeH + inset)
        canvas.drawRoundRect(shapeRect, radius + inset, radius + inset, guidePaint)
    }

    // ---- Touch ------------------------------------------------------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            listener?.onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            listener?.onLongPress()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val min = FLING_MIN_DP * density
            if (abs(velocityY) > abs(velocityX)) {
                if (velocityY < -min) listener?.onSwipeUp() else if (velocityY > min) listener?.onSwipeDown()
            } else if (abs(velocityX) > min) {
                listener?.onSwipeHorizontal(if (velocityX > 0) 1 else -1)
            }
            return true
        }
    }).apply { setIsLongpressEnabled(true) }

    private var tracking = false
    /** The current touch stream belongs to the content (a button or the seek bar). */
    private var contentTouch = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            listener?.onOutsideTouch()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // The window can be larger than the shape mid-transition; ignore touches off-shape.
            tracking = shapeAlpha > 0.5f && hitShape(event.x, event.y)
            contentTouch = tracking && offerToContent(event)
            if (tracking && !contentTouch) animate(pressAnim, AnimationSpec.PRESS_SCALE, AnimationSpec.PRESS)
        }
        if (!tracking) return false
        if (contentTouch) {
            offerToContent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                contentTouch = false
                tracking = false
            }
            return true
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            animate(pressAnim, 1f, AnimationSpec.PRESS)
            tracking = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Only fully-visible, settled expanded content gets touches (no taps on half-faded UI). */
    private fun offerToContent(event: MotionEvent): Boolean {
        val s = current
        val content = s.renderer as? InteractiveContent ?: return false
        if (s.presentation != Presentation.EXPANDED || s.alpha < 0.9f) return false
        slotRect.set(s.layout)
        slotRect.offset(-windowX.toFloat(), 0f)
        return content.onContentTouch(event, slotRect, s.data)
    }

    private fun hitShape(x: Float, y: Float): Boolean {
        val slop = TOUCH_SLOP_DP * density
        val cx = centerX - windowX
        return x >= cx - shapeW / 2f - slop && x <= cx + shapeW / 2f + slop &&
            y >= top - slop && y <= maxOf(top + shapeH + slop, touchSkirtBottom)
    }

    // ---- Fullscreen detection ---------------------------------------------------------------------
    // Overlay windows still receive the *global* status bar visibility through insets (API 30+)
    // or the legacy system-UI-visibility callback. Best-effort: a few OEM builds don't propagate it.

    private var lastStatusBarVisible = true

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = WindowInsets.Type.statusBars()
            val visible = insets.isVisible(type)
            // Windows that don't get the status bar inset source at all (accessibility overlays
            // sit *above* the status bar) report it as "not visible" forever. Only trust the
            // signal when the source is actually present for this window.
            // (Verified on a Pixel 8 / Android 17: accessibility overlays get top = 0 here.)
            val sourcePresent = insets.getInsetsIgnoringVisibility(type).top > 0
            if (sourcePresent) {
                if (visible) sawStatusBarVisible = true
                // Also require having seen it visible once, so a window that's never told the
                // truth can't hide the island permanently.
                reportStatusBar(visible || !sawStatusBarVisible)
            } else {
                reportStatusBar(true)
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    private var sawStatusBarVisible = false

    @Deprecated("Deprecated in Java")
    override fun onWindowSystemUiVisibilityChanged(visible: Int) {
        @Suppress("DEPRECATION")
        super.onWindowSystemUiVisibilityChanged(visible)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            reportStatusBar(visible and SYSTEM_UI_FLAG_FULLSCREEN == 0)
        }
    }

    private fun reportStatusBar(visible: Boolean) {
        if (visible != lastStatusBarVisible) {
            lastStatusBarVisible = visible
            onStatusBarVisibilityChanged?.invoke(visible)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fadeInRunnable)
        for (a in shapeAnims) a.cancel()
        pressAnim.cancel()
        for (s in arrayOf(slotA, slotB)) { s.alphaAnim.cancel(); s.scaleAnim.cancel() }
        super.onDetachedFromWindow()
    }

    // ---- Spring helpers ---------------------------------------------------------------------------

    private fun spring(get: () -> Float, set: (Float) -> Unit, minChange: Float, notifySettle: Boolean = false): SpringAnimation {
        val prop = object : FloatPropertyCompat<IslandView>("islandProp") {
            override fun getValue(view: IslandView) = get()
            override fun setValue(view: IslandView, value: Float) {
                set(value)
                view.invalidate()
            }
        }
        return SpringAnimation(this, prop).apply {
            spring = SpringForce(0f)
            minimumVisibleChange = minChange
            if (notifySettle) addEndListener(endListener)
        }
    }

    /** Retargets a spring, preserving its current velocity — interruptions stay smooth. */
    private fun animate(anim: SpringAnimation, to: Float, spec: AnimationSpec.Spring, stiffnessScale: Float = 1f) {
        anim.spring.stiffness = spec.stiffness * speedMultiplier * stiffnessScale
        anim.spring.dampingRatio = spec.dampingRatio
        anim.animateToFinalPosition(to)
    }

    private fun checkSettled() {
        if (shapeAnims.none { it.isRunning }) listener?.onSettled()
    }

    private companion object {
        const val FLING_MIN_DP = 350f
        const val TOUCH_SLOP_DP = 6f
    }
}
