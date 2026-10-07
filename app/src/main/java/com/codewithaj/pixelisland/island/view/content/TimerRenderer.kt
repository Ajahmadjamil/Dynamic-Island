package com.codewithaj.pixelisland.island.view.content

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.MotionEvent
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.view.InteractiveContent
import com.codewithaj.pixelisland.island.view.IslandContentRenderer
import com.codewithaj.pixelisland.island.view.LiveActionHandler
import com.codewithaj.pixelisland.island.view.Presentation
import com.codewithaj.pixelisland.island.view.msToNextSecond
import kotlin.math.cos
import kotlin.math.sin

/**
 * Timers and stopwatches from the clock app.
 *  Compact:  (⏱ orange, hand ticks)  ·······  4:59 (orange)
 *  Expanded: [Pause] [Reset]                  Timer
 *                                              4:59   (big)
 * The time is computed from the notification's chronometer base, so nothing polls: the view
 * redraws once per second (aligned to the second boundary) only while it's on screen.
 * Buttons are the clock app's own notification actions, with its own (localised) labels.
 */
class TimerRenderer(
    private val density: Float,
    private val actions: LiveActionHandler,
) : IslandContentRenderer, InteractiveContent {

    override var invalidator: (() -> Unit)? = null

    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ORANGE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); fontFeatureSettings = "tnum"
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; textSize = dp(13f) }
    private val btnText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13.5f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val r = RectF()
    private val clock = CharArray(10)
    private val labelCache = EllipsisCache()
    private val btnCache = EllipsisCache()

    // Expanded buttons (max 2), view coords.
    private val btn = arrayOf(RectF(), RectF())
    private var btnCount = 0
    private var pressed = -1

    override fun compactWingPx(pillHeightPx: Float) = pillHeightPx * 1.3f
    override fun expandedHeightPx(density: Float) = 96f * density

    override fun animatesContinuously(presentation: Presentation, data: Any?) =
        (data as? IslandActivity.Timer)?.paused == false

    override fun ambientFrameMs(presentation: Presentation, data: Any?) = msToNextSecond()

    override fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?) {
        val t = data as? IslandActivity.Timer ?: return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        if (presentation == Presentation.COMPACT) drawCompact(canvas, layout, t, a) else drawExpanded(canvas, layout, t, a)
    }

    private fun drawCompact(c: Canvas, l: RectF, t: IslandActivity.Timer, a: Int) {
        val h = l.height()
        val d = h * 0.6f
        drawTimerIcon(c, l.left + h * 0.3f + d / 2f, l.centerY(), d / 2f, t, a)
        time.textSize = h * 0.42f
        time.textAlign = Paint.Align.RIGHT
        time.alpha = if (t.paused) a * 150 / 255 else a
        drawTime(c, t, l.right - h * 0.42f, l.centerY() + time.textSize * 0.36f)
    }

    private fun drawExpanded(c: Canvas, l: RectF, t: IslandActivity.Timer, a: Int) {
        val pad = dp(18f)
        // Right: label + big time.
        time.textSize = dp(44f)
        time.textAlign = Paint.Align.RIGHT
        time.alpha = if (t.paused) a * 150 / 255 else a
        drawTime(c, t, l.right - pad, l.centerY() + dp(20f))
        label.alpha = a
        label.textAlign = Paint.Align.RIGHT
        c.drawText(labelCache.get(t.label, label, l.width() * 0.45f), l.right - pad, l.centerY() - dp(20f), label)

        // Left: up to two of the app's own buttons as round-ended pills.
        layoutButtons(l, t)
        for (i in 0 until btnCount) {
            val primary = i == 0
            fill.color = if (primary) ORANGE_DIM else GREY_BG
            fill.alpha = if (pressed == i) a / 2 else a
            val b = btn[i]
            c.drawRoundRect(b, b.height() / 2f, b.height() / 2f, fill)
            btnText.color = if (primary) ORANGE else WHITE
            btnText.alpha = a
            val text = btnCache.get(t.actions[i].label, btnText, b.width() - dp(12f))
            c.drawText(text, b.centerX(), b.centerY() + btnText.textSize * 0.36f, btnText)
        }
    }

    private fun layoutButtons(l: RectF, t: IslandActivity.Timer) {
        btnCount = minOf(2, t.actions.size)
        val w = dp(78f)
        val h = dp(38f)
        var x = l.left + dp(18f)
        for (i in 0 until btnCount) {
            btn[i].set(x, l.centerY() - h / 2f, x + w, l.centerY() + h / 2f)
            x += w + dp(8f)
        }
    }

    private fun drawTime(c: Canvas, t: IslandActivity.Timer, x: Float, y: Float) {
        val base = t.chronometerBaseWallMs
        if (base == null) {
            c.drawText(t.pausedText ?: "Paused", x, y, time)
            return
        }
        val now = System.currentTimeMillis()
        // Count-down rounds up (shows 0:01 until it's really over), like the clock app does.
        val ms = if (t.countDown) base - now + 999L else now - base
        val n = formatClock(ms, clock)
        c.drawText(clock, 0, n, x, y, time)
    }

    /** Orange dial with a hand that steps once per second. */
    private fun drawTimerIcon(c: Canvas, cx: Float, cy: Float, radius: Float, t: IslandActivity.Timer, a: Int) {
        stroke.color = ORANGE; stroke.alpha = a; stroke.strokeWidth = radius * 0.2f
        c.drawCircle(cx, cy, radius * 0.85f, stroke)
        val sec = ((System.currentTimeMillis() / 1000L) % 60L).toInt()
        val ang = Math.toRadians((if (t.countDown) -sec else sec) * 6.0 - 90.0)
        val len = radius * 0.55f
        stroke.strokeWidth = radius * 0.16f
        c.drawLine(cx, cy, cx + (cos(ang) * len).toFloat(), cy + (sin(ang) * len).toFloat(), stroke)
    }

    override fun drawMini(canvas: Canvas, circle: RectF, alpha: Float, nowMs: Long, data: Any?) {
        val t = data as? IslandActivity.Timer ?: return
        drawTimerIcon(canvas, circle.centerX(), circle.centerY(), circle.width() * 0.34f, t, (alpha * 255).toInt())
    }

    // ---- Touch --------------------------------------------------------------------------------

    override fun onContentTouch(event: MotionEvent, layout: RectF, data: Any?): Boolean {
        val t = data as? IslandActivity.Timer ?: return false
        layoutButtons(layout, t)
        var hit = -1
        for (i in 0 until btnCount) if (btn[i].contains(event.x, event.y)) hit = i
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit
                if (hit >= 0) invalidator?.invoke()
                return hit >= 0
            }
            MotionEvent.ACTION_MOVE -> if (hit != pressed && pressed >= 0) { pressed = -1; invalidator?.invoke() }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1
                invalidator?.invoke()
                if (p in t.actions.indices) actions.onAction(t.actions[p].intent)
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidator?.invoke() }
        }
        return true
    }

    private companion object {
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val ORANGE_DIM = 0xFF4A3006.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val GREY_BG = 0xFF3A3A3C.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
