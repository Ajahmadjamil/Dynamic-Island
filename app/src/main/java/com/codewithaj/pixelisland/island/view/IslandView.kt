package com.codewithaj.pixelisland.island.view

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
import com.codewithaj.pixelisland.island.animation.AnimationSpec
import com.codewithaj.pixelisland.island.shader.GooShader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Where the split bubble (second activity) should be, in screen coordinates (px). */
data class BubbleTarget(val centerX: Float, val centerY: Float, val radius: Float)

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
        /** Tap / long-press on the split bubble. */
        fun onBubbleTap()
        fun onBubbleLongPress()
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

    // ---- Split bubble (screen coords) ---------------------------------------------------------
    private var bubbleCx = 0f
    private var bubbleCy = 0f
    private var bubbleR = 0f
    private var bubbleTargetR = 0f
    private var bubbleRenderer: IslandContentRenderer? = null
    private var bubbleData: Any? = null
    private val bubbleCxAnim = spring({ bubbleCx }, { bubbleCx = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true)
    private val bubbleCyAnim = spring({ bubbleCy }, { bubbleCy = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true)
    private val bubbleRAnim = spring({ bubbleR }, { bubbleR = it.coerceAtLeast(0f) }, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, notifySettle = true).apply {
        // Fully merged back in: forget the content.
        addEndListener { _, _, value, _ -> if (value <= 0.5f) { bubbleRenderer = null; bubbleData = null } }
    }
    private val bubbleAnims = arrayOf(bubbleCxAnim, bubbleCyAnim, bubbleRAnim)
    private val goo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GooShader() else null
    private val gooRect = RectF()
    private val bubbleRect = RectF()
    private val bubbleClip = Path()

    // ---- Content slots -----------------------------------------------------------------------
    private inner class Slot(name: String) {
        var renderer: IslandContentRenderer? = null
        var presentation = Presentation.COMPACT
        /** Cross-fade identity ([com.codewithaj.pixelisland.island.IslandActivity.contentKey]). */
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
                // Content fade finished: draw once more so the ambient tick re-arms (see onDraw).
                invalidate()
            }
        }
        val scaleAnim = spring({ scale }, { scale = it }, DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        val node: RenderNode? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode(name) else null
    }

    private val slotA = Slot("islandContentA")
    private val slotB = Slot("islandContentB")
    private var current = slotA
    private val other get() = if (current === slotA) slotB else slotA

    /** Ambient redraw tick (equaliser, clocks); re-armed, never stacked. See onDraw. */
    private val ambientTick = Runnable { invalidate() }

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

    /** Current visual bounds of the shape (and bubble) in screen coords, including press scale. */
    fun visualBounds(out: RectF) {
        val w = shapeW * pressScale
        val h = shapeH * pressScale
        val cy = top + shapeH / 2f
        out.set(centerX - w / 2f, cy - h / 2f, centerX + w / 2f, cy + h / 2f)
        if (bubbleR > 0.5f) {
            out.union(bubbleCx - bubbleR, bubbleCy - bubbleR, bubbleCx + bubbleR, bubbleCy + bubbleR)
        }
    }

    /**
     * Shows the second activity as a separate bubble ([target] non-null) or merges it back
     * into the pill (null). Splitting starts the bubble small, inside the pill's right end, and
     * springs it out — with the gooey shader that reads as the island pinching in two.
     */
    fun setBubble(target: BubbleTarget?, renderer: IslandContentRenderer?, data: Any?, animate: Boolean) {
        val pill = lastTarget
        if (target != null) {
            bubbleRenderer = renderer
            bubbleData = data
            bubbleTargetR = target.radius
            if (!animate || !initialized) {
                for (a in bubbleAnims) a.cancel()
                bubbleCx = target.centerX; bubbleCy = target.centerY; bubbleR = target.radius
            } else {
                if (bubbleR <= 0.5f) {
                    // Start hidden inside the pill's right end.
                    val right = centerX + shapeW / 2f
                    bubbleCx = right - target.radius
                    bubbleCy = target.centerY
                    bubbleR = target.radius * 0.35f
                }
                animate(bubbleCxAnim, target.centerX, AnimationSpec.BUBBLE_SPLIT)
                animate(bubbleCyAnim, target.centerY, AnimationSpec.BUBBLE_SPLIT)
                animate(bubbleRAnim, target.radius, AnimationSpec.BUBBLE_SPLIT)
            }
        } else if (bubbleR > 0.5f) {
            if (!animate || pill == null) {
                for (a in bubbleAnims) a.cancel()
                bubbleR = 0f; bubbleRenderer = null; bubbleData = null
            } else {
                // Merge: slide into the pill's (target) right end while shrinking away.
                val right = pill.centerX + pill.width / 2f
                animate(bubbleCxAnim, right - bubbleR * 0.6f, AnimationSpec.BUBBLE_MERGE)
                animate(bubbleCyAnim, pill.top + pill.height / 2f, AnimationSpec.BUBBLE_MERGE)
                animate(bubbleRAnim, 0f, AnimationSpec.BUBBLE_MERGE)
            }
        }
        invalidate()
    }

    fun isAnimating(): Boolean = shapeAnims.any { it.isRunning } || bubbleAnims.any { it.isRunning }

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
        val hasBubble = bubbleR > 0.5f
        val bcx = bubbleCx - windowX
        val g = goo
        if (hasBubble && g != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated) {
            // Pill + bubble as one metaball shape (see GooShader).
            val k = AnimationSpec.GOO_BLEND_DP * density
            g.update(
                shapeRect.left, shapeRect.top, shapeRect.right, shapeRect.bottom, radius,
                bcx, bubbleCy, bubbleR, k, shapeAlpha,
            )
            gooRect.set(shapeRect)
            gooRect.union(bcx - bubbleR, bubbleCy - bubbleR, bcx + bubbleR, bubbleCy + bubbleR)
            gooRect.inset(-k - 2f, -k - 2f)
            canvas.drawRect(gooRect, g.paint)
        } else {
            fillPaint.alpha = (shapeAlpha * 255).roundToInt()
            canvas.drawRoundRect(shapeRect, radius, radius, fillPaint)
            // < API 33: a plain circle that slides out of the pill (no goo).
            if (hasBubble) canvas.drawCircle(bcx, bubbleCy, bubbleR, fillPaint)
        }

        // Content is clipped to the (animating) container shape.
        val now = SystemClock.uptimeMillis()
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(shapeRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        drawSlot(canvas, other, cx, cy, now)
        drawSlot(canvas, current, cx, cy, now)
        canvas.restore()

        // Bubble content fades in only once the bubble has (nearly) reached full size.
        val br = bubbleRenderer
        if (hasBubble && br != null && bubbleTargetR > 0f) {
            val grown = ((bubbleR / bubbleTargetR - 0.6f) / 0.4f).coerceIn(0f, 1f)
            if (grown > 0f) {
                bubbleRect.set(bcx - bubbleR, bubbleCy - bubbleR, bcx + bubbleR, bubbleCy + bubbleR)
                canvas.save()
                bubbleClip.reset()
                bubbleClip.addCircle(bcx, bubbleCy, bubbleR, Path.Direction.CW)
                canvas.clipPath(bubbleClip)
                br.drawMini(canvas, bubbleRect, grown * shapeAlpha, now, bubbleData)
                canvas.restore()
            }
        }
        canvas.restore()

        if (calibrating) drawGuide(canvas)

        // Content animations tick only while visible: every vsync for short foreground
        // animations (bell, battery fill), ~30 fps for ambient ones (equaliser, countdown).
        val r = current.renderer
        if (r != null && current.alpha > 0f && visibility == VISIBLE) {
            if (isAnimating() || current.alphaAnim.isRunning) return // springs already redraw every frame
            // ONE pending tick at a time. Every draw (including extra ones from data updates or
            // touches) re-arms the same Runnable instead of posting another, otherwise each extra
            // draw would start a parallel chain and 30 fps would creep up to 60, 1 Hz clocks to more.
            removeCallbacks(ambientTick)
            if (r.wantsFullFrameRate(current.data)) {
                postInvalidateOnAnimation() // coalesces into the next vsync; no chains
            } else if (r.animatesContinuously(current.presentation, current.data)) {
                postDelayed(ambientTick, r.ambientFrameMs(current.presentation, current.data))
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
            // Record into a RenderNode so a blur can be applied to the content alone. The node
            // (and so the offscreen layer the blur renders into) is only as big as the content
            // plus the blur radius — not the whole window — which keeps GPU memory small.
            val pad = AnimationSpec.CONTENT_MAX_BLUR_DP * density * 2f
            val l = floor(slotRect.left - pad).toInt()
            val t = floor(slotRect.top - pad).toInt()
            node.setPosition(l, t, ceil(slotRect.right + pad).toInt(), ceil(slotRect.bottom + pad).toInt())
            node.setRenderEffect(blurEffects[level])
            val rc = node.beginRecording()
            rc.translate(-l.toFloat(), -t.toFloat()) // node-local → view coordinates
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
            if (touchOnBubble) listener?.onBubbleTap() else listener?.onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (touchOnBubble) listener?.onBubbleLongPress() else listener?.onLongPress()
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
    /** The current touch stream started on the split bubble. */
    private var touchOnBubble = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            listener?.onOutsideTouch()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOnBubble = shapeAlpha > 0.5f && hitBubble(event.x, event.y)
            // The window can be larger than the shape mid-transition; ignore touches off-shape.
            tracking = touchOnBubble || (shapeAlpha > 0.5f && hitShape(event.x, event.y))
            contentTouch = tracking && !touchOnBubble && offerToContent(event)
            if (tracking && !contentTouch && !touchOnBubble) animate(pressAnim, AnimationSpec.PRESS_SCALE, AnimationSpec.PRESS)
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

    private fun hitBubble(x: Float, y: Float): Boolean {
        if (bubbleR < bubbleTargetR * 0.8f || bubbleR <= 0.5f) return false
        val dx = x - (bubbleCx - windowX)
        val dy = y - bubbleCy
        val r = bubbleR + TOUCH_SLOP_DP * density
        return dx * dx + dy * dy <= r * r
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
        removeCallbacks(ambientTick)
        for (a in shapeAnims) a.cancel()
        for (a in bubbleAnims) a.cancel()
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
        if (!isAnimating()) {
            // One more draw after the last spring frame, so ambient ticking re-arms itself
            // (frames drawn while springs ran deliberately don't arm it).
            invalidate()
            listener?.onSettled()
        }
    }

    private companion object {
        const val FLING_MIN_DP = 350f
        const val TOUCH_SLOP_DP = 6f
    }
}
