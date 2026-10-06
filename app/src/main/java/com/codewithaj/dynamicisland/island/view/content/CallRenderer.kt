package com.codewithaj.dynamicisland.island.view.content

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.graphics.PathParser
import android.view.MotionEvent
import com.codewithaj.dynamicisland.island.IslandActivity
import com.codewithaj.dynamicisland.island.view.InteractiveContent
import com.codewithaj.dynamicisland.island.view.IslandContentRenderer
import com.codewithaj.dynamicisland.island.view.LiveActionHandler
import com.codewithaj.dynamicisland.island.view.Presentation
import com.codewithaj.dynamicisland.island.view.msToNextSecond
import kotlin.math.hypot

/**
 * Phone calls (any app using CallStyle or category=call: dialer, WhatsApp, Meet…).
 *  Compact (ongoing):  (📞 green)  ········  0:42 (green)
 *  Expanded incoming:  [avatar] Incoming call / Name        (✕ red) (📞 green)
 *  Expanded ongoing:   [avatar] Name / 0:42                  (end red)
 * Buttons fire the call app's own answer/decline/hang-up PendingIntents.
 */
class CallRenderer(
    private val density: Float,
    private val actions: LiveActionHandler,
) : IslandContentRenderer, InteractiveContent {

    override var invalidator: (() -> Unit)? = null

    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; textSize = dp(12.5f) }
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE; textSize = dp(16f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); fontFeatureSettings = "tnum"
    }
    private val r = RectF()
    private val avatar = RectF()
    private val shaders = BitmapShaderCache()
    private val nameCache = EllipsisCache()
    private val clock = CharArray(9)

    // Expanded button centres (view coords), recomputed from the layout.
    private var btnY = 0f
    private var btnR = 0f
    private var declineX = 0f
    private var answerX = 0f
    private var pressed = NONE

    override fun compactWingPx(pillHeightPx: Float) = pillHeightPx * 1.25f
    override fun expandedHeightPx(density: Float) = 84f * density

    override fun animatesContinuously(presentation: Presentation, data: Any?): Boolean =
        (data as? IslandActivity.Call)?.startedAtWallMs != null

    override fun ambientFrameMs(presentation: Presentation, data: Any?) = msToNextSecond()

    override fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?) {
        val call = data as? IslandActivity.Call ?: return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        if (presentation == Presentation.COMPACT) drawCompact(canvas, layout, call, a)
        else drawExpanded(canvas, layout, call, a)
    }

    private fun drawCompact(c: Canvas, l: RectF, call: IslandActivity.Call, a: Int) {
        val h = l.height()
        val d = h * 0.62f
        val cx = l.left + h * 0.3f + d / 2f
        fill.color = GREEN; fill.alpha = a
        c.drawCircle(cx, l.centerY(), d / 2f, fill)
        fill.color = WHITE; fill.alpha = a
        drawHandset(c, cx, l.centerY(), d * 0.55f, rotation = 0f)

        time.textSize = h * 0.4f
        time.textAlign = Paint.Align.RIGHT
        time.alpha = a
        val n = callClock(call)
        val y = l.centerY() + time.textSize * 0.36f
        if (n > 0) c.drawText(clock, 0, n, l.right - h * 0.42f, y, time)
        else c.drawText(if (call.incoming) "Incoming" else "Calling", l.right - h * 0.42f, y, time)
    }

    private fun drawExpanded(c: Canvas, l: RectF, call: IslandActivity.Call, a: Int) {
        layoutButtons(l, call)
        val pad = dp(18f)
        val size = dp(46f)
        avatar.set(l.left + pad, l.centerY() - size / 2f, l.left + pad + size, l.centerY() + size / 2f)
        drawAvatar(c, call, a)

        val x = avatar.right + dp(12f)
        val maxW = (if (call.incoming) declineX else answerX) - btnR - dp(10f) - x
        small.alpha = a
        name.alpha = a
        if (call.incoming) {
            c.drawText("Incoming call", x, l.centerY() - dp(6f), small)
            c.drawText(nameCache.get(call.caller, name, maxW), x, l.centerY() + dp(14f), name)
        } else {
            c.drawText(nameCache.get(call.caller, name, maxW), x, l.centerY() - dp(3f), name)
            time.textSize = dp(13.5f)
            time.textAlign = Paint.Align.LEFT
            time.alpha = a
            val n = callClock(call)
            if (n > 0) c.drawText(clock, 0, n, x, l.centerY() + dp(16f), time)
            else c.drawText("Calling…", x, l.centerY() + dp(16f), small)
        }

        if (call.incoming) {
            drawButton(c, declineX, RED, a, rotation = 135f, highlight = pressed == DECLINE)
            drawButton(c, answerX, GREEN, a, rotation = 0f, highlight = pressed == ANSWER)
        } else {
            drawButton(c, answerX, RED, a, rotation = 135f, highlight = pressed == HANG_UP)
        }
    }

    private fun layoutButtons(l: RectF, call: IslandActivity.Call) {
        btnR = dp(23f)
        btnY = l.centerY()
        answerX = l.right - dp(18f) - btnR
        declineX = answerX - btnR * 2 - dp(14f)
        if (!call.incoming) declineX = answerX
    }

    private fun drawButton(c: Canvas, cx: Float, color: Int, a: Int, rotation: Float, highlight: Boolean) {
        fill.color = color; fill.alpha = if (highlight) a * 170 / 255 else a
        c.drawCircle(cx, btnY, btnR, fill)
        fill.color = WHITE; fill.alpha = a
        drawHandset(c, cx, btnY, btnR * 0.95f, rotation)
    }

    private fun drawAvatar(c: Canvas, call: IslandActivity.Call, a: Int) {
        val bmp = call.avatar
        if (bmp != null && !bmp.isRecycled) {
            avatarPaint.shader = shaders.cropInto(bmp, avatar)
            avatarPaint.alpha = a
            c.drawOval(avatar, avatarPaint)
        } else {
            // Initial on a grey disc.
            fill.color = AVATAR_BG; fill.alpha = a
            c.drawOval(avatar, fill)
            name.textAlign = Paint.Align.CENTER
            name.alpha = a
            val initial = call.caller.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
            clock[0] = initial
            c.drawText(clock, 0, 1, avatar.centerX(), avatar.centerY() + name.textSize * 0.36f, name)
            name.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * Handset glyph (Material "call" icon, 24×24 viewport, Apache-2.0), parsed once and only
     * transformed per frame. Rotation 0 = answer, 135° = decline / hang up.
     */
    private val handset: Path = PathParser.createPathFromPathData(HANDSET_PATH)

    private fun drawHandset(c: Canvas, cx: Float, cy: Float, s: Float, rotation: Float) {
        c.save()
        c.translate(cx - s / 2f, cy - s / 2f)
        c.scale(s / 24f, s / 24f)
        c.rotate(rotation, 12f, 12f)
        c.drawPath(handset, fill)
        c.restore()
    }

    /** Elapsed call time into [clock]; returns length, 0 if not connected. */
    private fun callClock(call: IslandActivity.Call): Int {
        val start = call.startedAtWallMs ?: return 0
        return formatClock(System.currentTimeMillis() - start, clock)
    }

    override fun drawMini(canvas: Canvas, circle: RectF, alpha: Float, nowMs: Long, data: Any?) {
        val a = (alpha * 255).toInt()
        fill.color = GREEN; fill.alpha = a
        canvas.drawCircle(circle.centerX(), circle.centerY(), circle.width() * 0.36f, fill)
        fill.color = WHITE; fill.alpha = a
        drawHandset(canvas, circle.centerX(), circle.centerY(), circle.width() * 0.4f, 0f)
    }

    // ---- Touch --------------------------------------------------------------------------------

    override fun onContentTouch(event: MotionEvent, layout: RectF, data: Any?): Boolean {
        val call = data as? IslandActivity.Call ?: return false
        layoutButtons(layout, call)
        val hit = when {
            call.incoming && hypot(event.x - declineX, event.y - btnY) <= btnR * 1.25f -> DECLINE
            call.incoming && hypot(event.x - answerX, event.y - btnY) <= btnR * 1.25f -> ANSWER
            !call.incoming && hypot(event.x - answerX, event.y - btnY) <= btnR * 1.25f -> HANG_UP
            else -> NONE
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit
                if (hit != NONE) invalidator?.invoke()
                return hit != NONE
            }
            MotionEvent.ACTION_MOVE -> if (hit != pressed && pressed != NONE) { pressed = NONE; invalidator?.invoke() }
            MotionEvent.ACTION_UP -> {
                val intent = when (pressed) {
                    ANSWER -> call.answer
                    DECLINE -> call.decline
                    HANG_UP -> call.hangUp
                    else -> null
                }
                pressed = NONE
                invalidator?.invoke()
                intent?.let { actions.onAction(it) }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = NONE; invalidator?.invoke() }
        }
        return true
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val RED = 0xFFFF453A.toInt()
        const val AVATAR_BG = 0xFF48484A.toInt()
        const val HANDSET_PATH =
            "M20.01,15.38c-1.23,0 -2.42,-0.2 -3.53,-0.56 -0.35,-0.12 -0.74,-0.03 -1.01,0.24l-1.57,1.97" +
                "c-2.83,-1.35 -5.48,-3.9 -6.89,-6.83l1.95,-1.66c0.27,-0.28 0.35,-0.67 0.24,-1.02" +
                " -0.37,-1.11 -0.56,-2.3 -0.56,-3.53 0,-0.54 -0.45,-0.99 -0.99,-0.99H4.19C3.65,3 3,3.24 3,3.99" +
                " 3,13.28 10.73,21 20.01,21c0.71,0 0.99,-0.63 0.99,-1.18v-3.45c0,-0.54 -0.45,-0.99 -0.99,-0.99z"
        const val NONE = 0
        const val ANSWER = 1
        const val DECLINE = 2
        const val HANG_UP = 3
    }
}

/** h:mm:ss / m:ss into [out] without allocating; returns length. */
internal fun formatClock(ms: Long, out: CharArray): Int {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val h = total / 3600; val m = (total / 60) % 60; val s = total % 60
    var i = 0
    if (h > 0) {
        if (h >= 10) out[i++] = '0' + (h / 10) % 10
        out[i++] = '0' + h % 10
        out[i++] = ':'
        out[i++] = '0' + m / 10
    } else if (m >= 10) {
        out[i++] = '0' + m / 10
    }
    out[i++] = '0' + m % 10
    out[i++] = ':'
    out[i++] = '0' + s / 10
    out[i++] = '0' + s % 10
    return i
}
