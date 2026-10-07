package com.codewithaj.pixelisland.island.view.content

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.view.IslandContentRenderer
import com.codewithaj.pixelisland.island.view.Presentation

/**
 * Navigation, deliveries/rides (Android 16 Live Updates) and determinate progress (downloads,
 * uploads).
 *  Compact:  [icon]  ········  "200 m" / "5 min"   (Live Update chip text, else a progress ring)
 *  Expanded: [icon] App · Title / Text,  progress bar underneath when there's progress.
 * For navigation the icon is the app's large icon, which Maps uses for the turn arrow.
 */
class ProgressRenderer(private val density: Float) : IslandContentRenderer {

    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val chip = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); fontFeatureSettings = "tnum"
        textAlign = Paint.Align.RIGHT
    }
    private val appPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; textSize = dp(12f) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE; textSize = dp(16f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = BODY; textSize = dp(14f) }
    private val r = RectF()
    private val icon = RectF()
    private val shaders = BitmapShaderCache()
    private val chipCache = EllipsisCache()
    private val appCache = EllipsisCache()
    private val titleCache = EllipsisCache()
    private val textCache = EllipsisCache()

    override fun compactWingPx(pillHeightPx: Float) = pillHeightPx * 1.45f
    override fun expandedHeightPx(density: Float) = 100f * density

    override fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?) {
        val p = data as? IslandActivity.Progress ?: return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        if (presentation == Presentation.COMPACT) drawCompact(canvas, layout, p, a) else drawExpanded(canvas, layout, p, a)
    }

    private fun drawCompact(c: Canvas, l: RectF, p: IslandActivity.Progress, a: Int) {
        val h = l.height()
        val inset = h * 0.18f
        val side = h - inset * 2
        r.set(l.left + inset * 1.5f, l.top + inset, l.left + inset * 1.5f + side, l.top + inset + side)
        drawIcon(c, r, p, a, round = !p.navigation)

        val right = l.right - h * 0.4f
        val short = p.shortText
        if (short != null) {
            chip.textSize = h * 0.38f
            chip.color = if (p.navigation) WHITE else ACCENT
            chip.alpha = a
            val text = chipCache.get(short, chip, l.width() * 0.4f)
            c.drawText(text, right, l.centerY() + chip.textSize * 0.36f, chip)
        } else if (p.fraction >= 0f) {
            drawRing(c, right - side * 0.42f, l.centerY(), side * 0.42f, p.fraction, a)
        }
    }

    private fun drawExpanded(c: Canvas, l: RectF, p: IslandActivity.Progress, a: Int) {
        val pad = dp(18f)
        val size = dp(48f)
        val hasBar = p.fraction >= 0f
        val cy = if (hasBar) l.centerY() - dp(8f) else l.centerY()
        icon.set(l.left + pad, cy - size / 2f, l.left + pad + size, cy + size / 2f)
        drawIcon(c, icon, p, a, round = false)

        val x = icon.right + dp(14f)
        val maxW = l.right - pad - x
        appPaint.alpha = a; titlePaint.alpha = a; textPaint.alpha = a
        val top = cy - dp(26f)
        // "App · 5 min", drawn in two parts (no string concatenation per frame).
        val app = appCache.get(p.appLabel, appPaint, maxW * 0.6f)
        c.drawText(app, x, top + dp(11f), appPaint)
        val short = p.shortText
        if (short != null) {
            val sx = x + appPaint.measureText(app)
            c.drawText(SEPARATOR, sx, top + dp(11f), appPaint)
            val rest = maxW - (sx - x) - appPaint.measureText(SEPARATOR)
            c.drawText(chipCache.get(short, appPaint, rest), sx + appPaint.measureText(SEPARATOR), top + dp(11f), appPaint)
        }
        c.drawText(titleCache.get(p.title, titlePaint, maxW), x, top + dp(32f), titlePaint)
        if (p.text.isNotEmpty()) c.drawText(textCache.get(p.text, textPaint, maxW), x, top + dp(51f), textPaint)

        if (hasBar) {
            val y = l.bottom - dp(14f)
            stroke.strokeWidth = dp(5f)
            stroke.color = WHITE; stroke.alpha = a * 50 / 255
            c.drawLine(l.left + pad, y, l.right - pad, y, stroke)
            stroke.color = ACCENT; stroke.alpha = a
            c.drawLine(l.left + pad, y, l.left + pad + (l.width() - pad * 2) * p.fraction, y, stroke)
        }
    }

    private fun drawIcon(c: Canvas, dst: RectF, p: IslandActivity.Progress, a: Int, round: Boolean) {
        val bmp = p.icon
        if (bmp == null || bmp.isRecycled) {
            fill.color = PLACEHOLDER; fill.alpha = a
            c.drawRoundRect(dst, dst.width() * 0.25f, dst.width() * 0.25f, fill)
            return
        }
        iconPaint.shader = shaders.cropInto(bmp, dst)
        iconPaint.alpha = a
        if (round) c.drawOval(dst, iconPaint) else c.drawRoundRect(dst, dst.width() * 0.22f, dst.width() * 0.22f, iconPaint)
    }

    private fun drawRing(c: Canvas, cx: Float, cy: Float, radius: Float, fraction: Float, a: Int) {
        r.set(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.strokeWidth = radius * 0.28f
        stroke.color = ACCENT; stroke.alpha = a * 60 / 255
        c.drawOval(r, stroke)
        stroke.alpha = a
        c.drawArc(r, -90f, 360f * fraction, false, stroke)
    }

    override fun drawMini(canvas: Canvas, circle: RectF, alpha: Float, nowMs: Long, data: Any?) {
        val p = data as? IslandActivity.Progress ?: return
        val a = (alpha * 255).toInt()
        val f = p.fraction
        if (f >= 0f && !p.navigation) {
            drawRing(canvas, circle.centerX(), circle.centerY(), circle.width() * 0.3f, f, a)
        } else {
            r.set(circle); r.inset(circle.width() * 0.2f, circle.height() * 0.2f)
            drawIcon(canvas, r, p, a, round = true)
        }
    }

    private companion object {
        const val SEPARATOR = " · "
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val BODY = 0xFFD1D1D6.toInt()
        const val ACCENT = 0xFF0A84FF.toInt()
        const val PLACEHOLDER = 0xFF2C2C2E.toInt()
    }
}
