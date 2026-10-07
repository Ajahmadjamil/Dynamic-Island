package com.codewithaj.pixelisland.island.view.content

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.SystemClock
import android.text.TextPaint
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.view.IslandContentRenderer
import com.codewithaj.pixelisland.island.view.Presentation
import kotlin.math.exp
import kotlin.math.sin

/**
 * Short system alerts shown as a *wide* compact pill (iOS style):
 *   Charging      "Charging"            85% [▮▮▮▯]   (green, fills up)
 *   Low battery   "Low Battery"         12% [▮▯▯▯]   (red)
 *   Ringer        (🔔 swing / slash)       Silent     (red / orange / white)
 *   Bluetooth     (🎧) Device name           (ring 80%)
 *
 * Animations are timed from the alert's shownAtMs and only request full-rate frames for
 * [ANIM_MS]; after that the content is static and costs nothing.
 */
class AlertRenderer(private val density: Float) : IslandContentRenderer {

    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val r = RectF()
    private val path = Path()
    private val pct = CharArray(4)
    private val nameCache = EllipsisCache()

    /** Wider than music: there's text on both sides. */
    override fun compactWingPx(pillHeightPx: Float) = pillHeightPx * 2.3f

    override fun expandedHeightPx(density: Float) = 0f // never expanded

    override fun wantsFullFrameRate(data: Any?): Boolean {
        val a = data as? IslandActivity.Alert ?: return false
        return SystemClock.uptimeMillis() - a.shownAtMs < ANIM_MS
    }

    override fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        // Animations start once the content has had time to fade in.
        val t = ((nowMs - ((data as? IslandActivity.Alert)?.shownAtMs ?: nowMs) - START_DELAY_MS) / 1000f).coerceAtLeast(0f)
        when (data) {
            is IslandActivity.Charging -> battery(canvas, layout, a, t, "Charging", data.level, GREEN, animateFill = true)
            is IslandActivity.LowBattery -> battery(canvas, layout, a, t, "Low Battery", data.level, RED, animateFill = false)
            is IslandActivity.Ringer -> ringer(canvas, layout, a, t, data.mode)
            is IslandActivity.BluetoothDevice -> bluetooth(canvas, layout, a, t, data)
            is IslandActivity.Connectivity -> connectivity(canvas, layout, a, t, data)
            else -> Unit
        }
    }

    // ---- VPN / hotspot ------------------------------------------------------------------------------

    private fun connectivity(c: Canvas, l: RectF, a: Int, t: Float, d: IslandActivity.Connectivity) {
        val h = l.height()
        val color = if (d.on) (if (d.kind == IslandActivity.Connectivity.Kind.VPN) BLUE else GREEN) else GREY_TEXT
        val cx = l.left + h * 0.85f
        val cy = l.centerY()
        val s = h * 0.62f
        // Icon pops in slightly (scale 0.8 → 1).
        val pop = 0.8f + 0.2f * easeOut(t / 0.35f)
        c.save()
        c.scale(pop, pop, cx, cy)
        if (d.kind == IslandActivity.Connectivity.Kind.VPN) {
            // "VPN" badge.
            r.set(cx - s * 0.75f, cy - s * 0.38f, cx + s * 0.75f, cy + s * 0.38f)
            fill.color = color; fill.alpha = a
            c.drawRoundRect(r, s * 0.18f, s * 0.18f, fill)
            text.textSize = s * 0.42f
            text.textAlign = Paint.Align.CENTER
            text.color = BLACK; text.alpha = a
            c.drawText("VPN", cx, cy + text.textSize * 0.36f, text)
        } else {
            // Hotspot: a dot with two arcs on each side.
            fill.color = color; fill.alpha = a
            c.drawCircle(cx, cy, s * 0.1f, fill)
            stroke.color = color; stroke.alpha = a; stroke.strokeWidth = s * 0.09f
            for (k in 1..2) {
                val rr = s * 0.22f * k
                r.set(cx - rr, cy - rr, cx + rr, cy + rr)
                c.drawArc(r, -45f, 90f, false, stroke)
                c.drawArc(r, 135f, 90f, false, stroke)
            }
        }
        c.restore()

        text.textSize = h * 0.34f
        text.textAlign = Paint.Align.RIGHT
        text.color = color; text.alpha = a
        val label = when (d.kind) {
            IslandActivity.Connectivity.Kind.VPN -> if (d.on) "Connected" else "Disconnected"
            IslandActivity.Connectivity.Kind.HOTSPOT -> if (d.on) "Hotspot On" else "Hotspot Off"
        }
        c.drawText(label, l.right - h * 0.45f, cy + text.textSize * 0.36f, text)
    }

    // ---- Battery ----------------------------------------------------------------------------------

    private fun battery(c: Canvas, l: RectF, a: Int, t: Float, label: String, level: Int, color: Int, animateFill: Boolean) {
        val h = l.height()
        val pad = h * 0.42f
        text.textSize = h * 0.36f
        text.textAlign = Paint.Align.LEFT
        text.color = WHITE; text.alpha = a
        c.drawText(label, l.left + pad, l.centerY() + text.textSize * 0.36f, text)

        // Battery icon on the right.
        val bw = h * 0.62f
        val bh = h * 0.32f
        val right = l.right - pad - dp(3f)
        r.set(right - bw, l.centerY() - bh / 2f, right, l.centerY() + bh / 2f)
        stroke.color = color; stroke.alpha = a * 140 / 255; stroke.strokeWidth = dp(1.3f)
        val rad = bh * 0.3f
        c.drawRoundRect(r, rad, rad, stroke)
        fill.color = color; fill.alpha = a * 140 / 255
        c.drawRoundRect(r.right + dp(1.5f), r.centerY() - bh * 0.18f, r.right + dp(3f), r.centerY() + bh * 0.18f, dp(1f), dp(1f), fill)

        val shown = if (animateFill) level * easeOut(t / FILL_S) else level.toFloat()
        val inset = dp(2.2f)
        val innerW = (r.width() - inset * 2) * (shown / 100f).coerceIn(0.04f, 1f)
        fill.alpha = a
        c.drawRoundRect(r.left + inset, r.top + inset, r.left + inset + innerW, r.bottom - inset, rad * 0.6f, rad * 0.6f, fill)

        // Percentage left of the icon.
        text.textAlign = Paint.Align.RIGHT
        text.color = color; text.alpha = a
        val n = formatPercent(shown.toInt())
        c.drawText(pct, 0, n, r.left - dp(7f), l.centerY() + text.textSize * 0.36f, text)
    }

    // ---- Ringer -----------------------------------------------------------------------------------

    private fun ringer(c: Canvas, l: RectF, a: Int, t: Float, mode: Int) {
        val h = l.height()
        val pad = h * 0.2f
        val bg = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> RED
            AudioManager.RINGER_MODE_VIBRATE -> ORANGE
            else -> GREY_BG
        }
        val fg = WHITE
        val label = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Ring"
        }
        // Left: coloured capsule with the bell.
        val capH = h - pad * 2
        val capW = capH * 1.55f
        r.set(l.left + pad * 1.3f, l.top + pad, l.left + pad * 1.3f + capW, l.bottom - pad)
        fill.color = bg; fill.alpha = a
        c.drawRoundRect(r, capH / 2f, capH / 2f, fill)

        // Bell swings like a struck bell: damped sine, pivot at the top of the bell.
        val size = capH * 0.62f
        val cx = r.centerX()
        val cy = r.centerY()
        val swing = if (mode == AudioManager.RINGER_MODE_SILENT) 0f
        else 22f * exp(-t * 4.5f) * sin(t * 26f)
        c.save()
        c.rotate(swing, cx, cy - size * 0.45f)
        fill.color = fg; fill.alpha = a
        drawBell(c, cx, cy, size)
        c.restore()

        if (mode == AudioManager.RINGER_MODE_SILENT) {
            // Slash draws in across the bell, with a background-coloured gap underneath.
            val p = easeOut(t / 0.3f)
            val x0 = cx - size * 0.5f; val y0 = cy - size * 0.5f
            val x1 = x0 + size * p; val y1 = y0 + size * p
            stroke.color = bg; stroke.alpha = a; stroke.strokeWidth = size * 0.22f
            c.drawLine(x0, y0, x1, y1, stroke)
            stroke.color = fg; stroke.alpha = a; stroke.strokeWidth = size * 0.1f
            c.drawLine(x0, y0, x1, y1, stroke)
        }

        text.textSize = h * 0.36f
        text.textAlign = Paint.Align.RIGHT
        text.color = if (mode == AudioManager.RINGER_MODE_NORMAL) WHITE else bg
        text.alpha = a
        c.drawText(label, l.right - h * 0.45f, l.centerY() + text.textSize * 0.36f, text)
    }

    private fun drawBell(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.42f, cy + s * 0.24f)
        path.lineTo(cx - s * 0.32f, cy + s * 0.1f)
        path.cubicTo(cx - s * 0.32f, cy - s * 0.32f, cx - s * 0.16f, cy - s * 0.44f, cx, cy - s * 0.44f)
        path.cubicTo(cx + s * 0.16f, cy - s * 0.44f, cx + s * 0.32f, cy - s * 0.32f, cx + s * 0.32f, cy + s * 0.1f)
        path.lineTo(cx + s * 0.42f, cy + s * 0.24f)
        path.close()
        c.drawPath(path, fill)
        c.drawCircle(cx, cy + s * 0.36f, s * 0.1f, fill)
    }

    // ---- Bluetooth --------------------------------------------------------------------------------

    private fun bluetooth(c: Canvas, l: RectF, a: Int, t: Float, d: IslandActivity.BluetoothDevice) {
        val h = l.height()
        val pad = h * 0.22f
        val icon = h - pad * 2
        val ix = l.left + pad * 1.6f
        drawDeviceGlyph(c, ix, l.top + pad, icon, d.kind, a)

        // Right: battery ring (fills in) or "Connected".
        val ringD = icon * 0.86f
        val ringRight = l.right - pad * 1.6f
        val rightEdgeOfName: Float
        if (d.battery != null) {
            r.set(ringRight - ringD, l.centerY() - ringD / 2f, ringRight, l.centerY() + ringD / 2f)
            stroke.strokeWidth = dp(2.6f)
            stroke.color = GREEN; stroke.alpha = a * 70 / 255
            c.drawOval(r, stroke)
            stroke.alpha = a
            val color = if (d.battery <= 20) RED else GREEN
            stroke.color = color
            c.drawArc(r, -90f, 360f * d.battery / 100f * easeOut(t / FILL_S), false, stroke)
            text.textSize = ringD * 0.36f
            text.textAlign = Paint.Align.CENTER
            text.color = WHITE; text.alpha = a
            val n = formatPercent(d.battery, withSign = false)
            c.drawText(pct, 0, n, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
            rightEdgeOfName = r.left - dp(10f)
        } else {
            text.textSize = h * 0.3f
            text.textAlign = Paint.Align.RIGHT
            text.color = GREEN; text.alpha = a
            c.drawText("Connected", ringRight, l.centerY() + text.textSize * 0.36f, text)
            rightEdgeOfName = ringRight - text.measureText("Connected") - dp(10f)
        }

        // Middle: device name, ellipsized to the space left.
        text.textSize = h * 0.32f
        text.textAlign = Paint.Align.LEFT
        text.color = WHITE; text.alpha = a
        val nameX = ix + icon + dp(10f)
        val name = nameCache.get(d.name, text, rightEdgeOfName - nameX)
        c.drawText(name, nameX, l.centerY() + text.textSize * 0.36f, text)
    }

    /** Minimal headphones / watch glyphs. */
    private fun drawDeviceGlyph(c: Canvas, x: Float, y: Float, s: Float, kind: IslandActivity.BluetoothDevice.Kind, a: Int) {
        fill.color = WHITE; fill.alpha = a
        stroke.color = WHITE; stroke.alpha = a
        when (kind) {
            IslandActivity.BluetoothDevice.Kind.WATCH -> {
                r.set(x + s * 0.2f, y + s * 0.12f, x + s * 0.8f, y + s * 0.88f)
                stroke.strokeWidth = s * 0.1f
                c.drawRoundRect(r, s * 0.18f, s * 0.18f, stroke)
                c.drawRect(x + s * 0.32f, y, x + s * 0.68f, y + s * 0.12f, fill)
                c.drawRect(x + s * 0.32f, y + s * 0.88f, x + s * 0.68f, y + s, fill)
            }
            else -> {
                // Headband arc + two ear cups.
                stroke.strokeWidth = s * 0.1f
                r.set(x + s * 0.1f, y + s * 0.08f, x + s * 0.9f, y + s * 0.88f)
                c.drawArc(r, 180f, 180f, false, stroke)
                r.set(x + s * 0.04f, y + s * 0.5f, x + s * 0.3f, y + s * 0.92f)
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, fill)
                r.set(x + s * 0.7f, y + s * 0.5f, x + s * 0.96f, y + s * 0.92f)
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, fill)
            }
        }
    }

    /** "85%" (or "85") into [pct] without allocating; returns length. */
    private fun formatPercent(v: Int, withSign: Boolean = true): Int {
        val x = v.coerceIn(0, 100)
        var i = 0
        if (x >= 100) pct[i++] = '1'
        if (x >= 10) pct[i++] = '0' + (x / 10) % 10
        pct[i++] = '0' + x % 10
        if (withSign) pct[i++] = '%'
        return i
    }

    private companion object {
        const val ANIM_MS = 1_400L
        const val START_DELAY_MS = 150L
        const val FILL_S = 0.8f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val RED = 0xFFFF453A.toInt()
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val GREY_BG = 0xFF3A3A3C.toInt()
        const val GREY_TEXT = 0xFF8E8E93.toInt()
        const val BLUE = 0xFF0A84FF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
