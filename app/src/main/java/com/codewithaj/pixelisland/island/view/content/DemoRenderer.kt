package com.codewithaj.pixelisland.island.view.content

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.IslandActivity.Demo.Variant
import com.codewithaj.pixelisland.island.view.IslandContentRenderer
import com.codewithaj.pixelisland.island.view.Presentation
import kotlin.math.abs
import kotlin.math.sin

/**
 * Playground content for Phase 2 so the morphs can be judged before real data exists.
 * MUSIC mimics a now-playing layout (art + equaliser / controls), TIMER a countdown.
 * Phase 3's media renderer follows the same structure.
 */
class DemoRenderer(private val density: Float) : IslandContentRenderer {

    /** Start time of the slot currently being drawn (set at the top of [draw]). */
    private var startedAtMs = 0L

    private fun dp(v: Float) = v * density

    private val accentMusic = 0xFFFF375F.toInt()
    private val accentMusic2 = 0xFFBF5AF2.toInt()
    private val accentTimer = 0xFFFF9F0A.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = dp(16f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF98989F.toInt(); textSize = dp(14f) }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Tabular figures so the countdown doesn't jitter horizontally.
        fontFeatureSettings = "tnum"
    }

    private val r = RectF()
    private val path = Path()
    private val timeChars = CharArray(5)

    override fun compactWingPx(pillHeightPx: Float) = pillHeightPx * 1.35f

    override fun expandedHeightPx(density: Float) = 176f * density

    override fun animatesContinuously(presentation: Presentation, data: Any?) = true

    override fun drawMini(canvas: Canvas, circle: RectF, alpha: Float, nowMs: Long, data: Any?) {
        val demo = data as? IslandActivity.Demo ?: return
        fill.color = if (demo.variant == Variant.MUSIC) accentMusic else accentTimer
        fill.alpha = (alpha * 255).toInt()
        canvas.drawCircle(circle.centerX(), circle.centerY(), circle.width() * 0.3f, fill)
    }

    override fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?) {
        val demo = data as? IslandActivity.Demo ?: return
        startedAtMs = demo.startedAtMs
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        when (demo.variant) {
            Variant.MUSIC -> if (presentation == Presentation.COMPACT) musicCompact(canvas, layout, a, nowMs)
            else musicExpanded(canvas, layout, a, nowMs)
            Variant.TIMER -> if (presentation == Presentation.COMPACT) timerCompact(canvas, layout, a, nowMs)
            else timerExpanded(canvas, layout, a, nowMs)
        }
    }

    // ---- Music --------------------------------------------------------------------------------

    private fun musicCompact(c: Canvas, l: RectF, a: Int, now: Long) {
        val inset = l.height() * 0.2f
        val side = l.height() - inset * 2
        r.set(l.left + inset * 1.4f, l.top + inset, l.left + inset * 1.4f + side, l.top + inset + side)
        drawArt(c, r, dp(6f), a)
        drawEqualizer(c, l.right - inset * 1.6f, l.centerY(), side * 0.8f, a, now)
    }

    private fun musicExpanded(c: Canvas, l: RectF, a: Int, now: Long) {
        val pad = dp(20f)
        val art = dp(62f)
        r.set(l.left + pad, l.top + pad, l.left + pad + art, l.top + pad + art)
        drawArt(c, r, dp(14f), a)

        val textX = r.right + dp(14f)
        titlePaint.alpha = a
        subPaint.alpha = a
        c.drawText("Pixel Island Playground", textX, r.top + dp(26f), titlePaint)
        c.drawText("Phase 2 · spring morphs", textX, r.top + dp(48f), subPaint)
        drawEqualizer(c, l.right - pad - dp(6f), r.top + dp(18f), dp(18f), a, now)

        // Progress bar (loops every 30 s).
        val barY = r.bottom + dp(20f)
        val progress = ((now - startedAtMs) % 30_000L) / 30_000f
        stroke.strokeWidth = dp(5f)
        stroke.color = 0x40FFFFFF; stroke.alpha = a / 4
        c.drawLine(l.left + pad, barY, l.right - pad, barY, stroke)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.alpha = a
        c.drawLine(l.left + pad, barY, l.left + pad + (l.width() - pad * 2) * progress, barY, stroke)

        // Transport controls.
        val cy = barY + dp(40f)
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = a
        drawPrevNext(c, l.centerX() - dp(72f), cy, -1)
        drawPause(c, l.centerX(), cy)
        drawPrevNext(c, l.centerX() + dp(72f), cy, 1)
    }

    private fun drawArt(c: Canvas, rect: RectF, radius: Float, a: Int) {
        fill.color = accentMusic; fill.alpha = a
        c.drawRoundRect(rect, radius, radius, fill)
        // Two-tone "cover": a diagonal second colour instead of a gradient shader (no alloc).
        path.reset()
        path.moveTo(rect.right, rect.top + rect.height() * 0.25f)
        path.lineTo(rect.right, rect.bottom - radius)
        path.quadTo(rect.right, rect.bottom, rect.right - radius, rect.bottom)
        path.lineTo(rect.left + rect.width() * 0.25f, rect.bottom)
        path.close()
        fill.color = accentMusic2; fill.alpha = a
        c.drawPath(path, fill)
    }

    /** Four bars; heights are smooth sines with different speeds/phases. */
    private fun drawEqualizer(c: Canvas, rightX: Float, cy: Float, maxH: Float, a: Int, now: Long) {
        val barW = maxH / 6f
        val gap = barW * 0.75f
        stroke.strokeWidth = barW
        stroke.color = accentMusic; stroke.alpha = a
        val t = (now - startedAtMs) / 1000f
        for (i in 0 until 4) {
            val h = maxH * (0.3f + 0.7f * abs(sin(t * (2.2f + i * 0.9f) + i * 1.3f)))
            val x = rightX - (3 - i) * (barW + gap)
            c.drawLine(x, cy - h / 2 + barW / 2, x, cy + h / 2 - barW / 2, stroke)
        }
    }

    private fun drawPause(c: Canvas, cx: Float, cy: Float) {
        val h = dp(26f); val w = dp(7f); val gap = dp(6f)
        r.set(cx - gap / 2 - w, cy - h / 2, cx - gap / 2, cy + h / 2)
        c.drawRoundRect(r, dp(2f), dp(2f), fill)
        r.set(cx + gap / 2, cy - h / 2, cx + gap / 2 + w, cy + h / 2)
        c.drawRoundRect(r, dp(2f), dp(2f), fill)
    }

    /** Double triangle; [dir] = 1 next, -1 previous. */
    private fun drawPrevNext(c: Canvas, cx: Float, cy: Float, dir: Int) {
        val h = dp(18f); val w = dp(13f)
        path.reset()
        for (k in 0..1) {
            val x0 = cx + dir * (k * w - w)
            path.moveTo(x0, cy - h / 2)
            path.lineTo(x0 + dir * w, cy)
            path.lineTo(x0, cy + h / 2)
            path.close()
        }
        c.drawPath(path, fill)
    }

    // ---- Timer --------------------------------------------------------------------------------

    private fun remainingSeconds(now: Long): Int {
        val total = 5 * 60
        val elapsed = ((now - startedAtMs) / 1000L).toInt()
        return ((total - elapsed) % (total + 1) + total + 1) % (total + 1)
    }

    /** Formats m:ss into [timeChars] without allocating; returns the char count. */
    private fun formatTime(seconds: Int): Int {
        val m = seconds / 60; val s = seconds % 60
        var i = 0
        if (m >= 10) timeChars[i++] = '0' + m / 10
        timeChars[i++] = '0' + m % 10
        timeChars[i++] = ':'
        timeChars[i++] = '0' + s / 10
        timeChars[i++] = '0' + s % 10
        return i
    }

    private fun timerCompact(c: Canvas, l: RectF, a: Int, now: Long) {
        val inset = l.height() * 0.22f
        val d = l.height() - inset * 2
        drawTimerIcon(c, l.left + inset * 1.4f + d / 2, l.centerY(), d / 2, a, now)
        timePaint.textSize = l.height() * 0.42f
        timePaint.color = accentTimer; timePaint.alpha = a
        timePaint.textAlign = Paint.Align.RIGHT
        val n = formatTime(remainingSeconds(now))
        c.drawText(timeChars, 0, n, l.right - inset * 1.5f, l.centerY() + timePaint.textSize * 0.36f, timePaint)
    }

    private fun timerExpanded(c: Canvas, l: RectF, a: Int, now: Long) {
        val pad = dp(20f)
        // Buttons on the left, big time on the right (iOS layout).
        val bR = dp(26f)
        val cy = l.top + l.height() / 2
        fill.color = 0x4DFF9F0A; fill.alpha = a * 0x4D / 255
        c.drawCircle(l.left + pad + bR, cy, bR, fill)
        fill.color = accentTimer; fill.alpha = a
        drawPauseSmall(c, l.left + pad + bR, cy)
        fill.color = 0x4DFFFFFF; fill.alpha = a * 0x4D / 255
        c.drawCircle(l.left + pad + bR * 3 + dp(12f), cy, bR, fill)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.alpha = a; stroke.strokeWidth = dp(3f)
        val xcx = l.left + pad + bR * 3 + dp(12f); val k = dp(7f)
        c.drawLine(xcx - k, cy - k, xcx + k, cy + k, stroke)
        c.drawLine(xcx - k, cy + k, xcx + k, cy - k, stroke)

        timePaint.textSize = dp(46f)
        timePaint.color = accentTimer; timePaint.alpha = a
        timePaint.textAlign = Paint.Align.RIGHT
        val n = formatTime(remainingSeconds(now))
        c.drawText(timeChars, 0, n, l.right - pad, cy + dp(16f), timePaint)
        subPaint.alpha = a
        subPaint.textAlign = Paint.Align.RIGHT
        c.drawText("Timer", l.right - pad, cy - dp(26f), subPaint)
        subPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawPauseSmall(c: Canvas, cx: Float, cy: Float) {
        val h = dp(16f); val w = dp(5f); val gap = dp(4f)
        r.set(cx - gap / 2 - w, cy - h / 2, cx - gap / 2, cy + h / 2)
        c.drawRoundRect(r, dp(1.5f), dp(1.5f), fill)
        r.set(cx + gap / 2, cy - h / 2, cx + gap / 2 + w, cy + h / 2)
        c.drawRoundRect(r, dp(1.5f), dp(1.5f), fill)
    }

    private fun drawTimerIcon(c: Canvas, cx: Float, cy: Float, radius: Float, a: Int, now: Long) {
        stroke.strokeWidth = radius * 0.28f
        stroke.color = 0x55FF9F0A; stroke.alpha = a / 3
        c.drawCircle(cx, cy, radius * 0.82f, stroke)
        stroke.color = accentTimer; stroke.alpha = a
        r.set(cx - radius * 0.82f, cy - radius * 0.82f, cx + radius * 0.82f, cy + radius * 0.82f)
        val sweep = 360f * remainingSeconds(now) / 300f
        c.drawArc(r, -90f, -sweep, false, stroke)
    }
}
