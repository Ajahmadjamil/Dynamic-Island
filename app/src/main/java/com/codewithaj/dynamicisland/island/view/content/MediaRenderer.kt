package com.codewithaj.dynamicisland.island.view.content

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.codewithaj.dynamicisland.island.IslandActivity
import com.codewithaj.dynamicisland.island.view.InteractiveContent
import com.codewithaj.dynamicisland.island.view.IslandContentRenderer
import com.codewithaj.dynamicisland.island.view.Presentation
import com.codewithaj.dynamicisland.media.MediaInfo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Now playing.
 *  Compact:  [art] ········ pill ········ [equaliser in accent colour]
 *  Expanded: art · title/artist · equaliser, seek bar with elapsed/remaining, prev/play/next.
 *
 * The layout is computed from the layout rect by [layoutExpanded], shared by drawing and
 * touch handling, so hit areas always match what's on screen.
 */
class MediaRenderer(
    private val density: Float,
    private val actions: Actions,
) : IslandContentRenderer, InteractiveContent {

    interface Actions {
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
        fun onSeek(positionMs: Long)
    }

    override var invalidator: (() -> Unit)? = null

    private fun dp(v: Float) = v * density

    // ---- Paints & scratch objects (allocated once) ---------------------------------------------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE; textSize = dp(16f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; textSize = dp(14.5f) }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREY; textSize = dp(11.5f); fontFeatureSettings = "tnum"
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val r = RectF()
    private val artSrc = RectF()
    private val matrix = Matrix()
    private val path = Path()
    private val elapsedChars = CharArray(9)
    private val remainingChars = CharArray(10)

    // ---- Expanded layout (view coords), recomputed from the layout rect ------------------------
    private val art = RectF()
    private val seek = RectF()
    private var textX = 0f
    private var textMaxW = 0f
    private var titleY = 0f
    private var artistY = 0f
    private var eqRight = 0f
    private var eqY = 0f
    private var controlsY = 0f
    private var prevX = 0f
    private var playX = 0f
    private var nextX = 0f

    private fun layoutExpanded(l: RectF) {
        val pad = dp(20f)
        val artSize = dp(62f)
        art.set(l.left + pad, l.top + pad, l.left + pad + artSize, l.top + pad + artSize)
        textX = art.right + dp(14f)
        eqRight = l.right - pad
        eqY = art.top + dp(16f)
        textMaxW = (eqRight - dp(30f)) - textX
        titleY = art.top + dp(27f)
        artistY = art.top + dp(49f)
        val seekY = art.bottom + dp(24f)
        seek.set(l.left + pad, seekY, l.right - pad, seekY)
        controlsY = seekY + dp(52f)
        playX = l.centerX()
        prevX = playX - dp(84f)
        nextX = playX + dp(84f)
    }

    override fun compactWingPx(pillHeightPx: Float) = pillHeightPx * 1.3f

    override fun expandedHeightPx(density: Float) = 196f * density

    /** Split bubble: round album art (or the note placeholder). */
    override fun drawMini(canvas: Canvas, circle: RectF, alpha: Float, nowMs: Long, data: Any?) {
        val info = (data as? IslandActivity.Media)?.info ?: return
        r.set(circle)
        r.inset(circle.width() * 0.16f, circle.height() * 0.16f)
        drawArt(canvas, r, r.width() / 2f, info, (alpha * 255).toInt().coerceIn(0, 255))
    }

    override fun animatesContinuously(presentation: Presentation, data: Any?): Boolean =
        (data as? IslandActivity.Media)?.info?.playing == true

    override fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?) {
        val info = (data as? IslandActivity.Media)?.info ?: return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        if (presentation == Presentation.COMPACT) drawCompact(canvas, layout, info, a, nowMs)
        else drawExpanded(canvas, layout, info, a, nowMs)
    }

    // ---- Compact --------------------------------------------------------------------------------

    private fun drawCompact(c: Canvas, l: RectF, info: MediaInfo, a: Int, now: Long) {
        val inset = l.height() * 0.18f
        val side = l.height() - inset * 2
        r.set(l.left + inset * 1.5f, l.top + inset, l.left + inset * 1.5f + side, l.top + inset + side)
        drawArt(c, r, side * 0.24f, info, a)
        drawEqualizer(c, l.right - inset * 1.7f, l.centerY(), side * 0.75f, info, a, now)
    }

    // ---- Expanded -------------------------------------------------------------------------------

    private fun drawExpanded(c: Canvas, l: RectF, info: MediaInfo, a: Int, now: Long) {
        layoutExpanded(l)
        drawArt(c, art, dp(14f), info, a)

        titlePaint.alpha = a
        artistPaint.alpha = a
        val title = ellipsized(info.title, titlePaint, textMaxW, isTitle = true)
        val artist = ellipsized(info.artist, artistPaint, textMaxW, isTitle = false)
        c.drawText(title, 0, title.length, textX, titleY, titlePaint)
        c.drawText(artist, 0, artist.length, textX, artistY, artistPaint)
        drawEqualizer(c, eqRight, eqY, dp(18f), info, a, now)

        // Seek bar.
        val hasDuration = info.durationMs > 0
        if (hasDuration) {
            val pos = displayedPosition(info)
            val frac = (pos.toFloat() / info.durationMs).coerceIn(0f, 1f)
            stroke.strokeWidth = if (scrubbing) dp(9f) else dp(6f)
            stroke.color = WHITE; stroke.alpha = a * 56 / 255
            c.drawLine(seek.left, seek.top, seek.right, seek.top, stroke)
            stroke.alpha = a
            c.drawLine(seek.left, seek.top, seek.left + (seek.width()) * frac, seek.top, stroke)

            timePaint.alpha = a
            val ty = seek.top + dp(22f)
            timePaint.textAlign = Paint.Align.LEFT
            val n1 = formatTime(pos, elapsedChars, 0)
            c.drawText(elapsedChars, 0, n1, seek.left, ty, timePaint)
            timePaint.textAlign = Paint.Align.RIGHT
            remainingChars[0] = '-'
            val n2 = formatTime(info.durationMs - pos, remainingChars, 1)
            c.drawText(remainingChars, 0, n2, seek.right, ty, timePaint)
        }

        // Transport controls with pressed highlight.
        val hl = dp(28f)
        fill.color = WHITE
        if (pressed != NONE) {
            fill.alpha = a * 36 / 255
            c.drawCircle(xOf(pressed), controlsY, hl, fill)
        }
        fill.alpha = if (info.canPrevious) a else a / 3
        drawSkip(c, prevX, controlsY, -1)
        fill.alpha = a
        if (info.playing) drawPause(c, playX, controlsY) else drawPlay(c, playX, controlsY)
        fill.alpha = if (info.canNext) a else a / 3
        drawSkip(c, nextX, controlsY, 1)
    }

    // ---- Touch (expanded only) --------------------------------------------------------------------

    private var pressed = NONE
    private var scrubbing = false
    private var scrubFraction = 0f
    private var pendingSeekMs = -1L
    private var pendingSeekUntil = 0L
    private var touchData: MediaInfo? = null

    override fun onContentTouch(event: MotionEvent, layout: RectF, data: Any?): Boolean {
        val info = (data as? IslandActivity.Media)?.info ?: return false
        layoutExpanded(layout)
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchData = info
                val slop = dp(18f)
                if (info.durationMs > 0 && info.canSeek && y in seek.top - slop..seek.top + slop &&
                    x in seek.left - dp(6f)..seek.right + dp(6f)
                ) {
                    scrubbing = true
                    scrubFraction = ((x - seek.left) / seek.width()).coerceIn(0f, 1f)
                    invalidator?.invoke()
                    return true
                }
                pressed = controlAt(x, y)
                if (pressed != NONE) invalidator?.invoke()
                return pressed != NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbing) {
                    scrubFraction = ((x - seek.left) / seek.width()).coerceIn(0f, 1f)
                    invalidator?.invoke()
                } else if (pressed != NONE && controlAt(x, y) != pressed) {
                    pressed = NONE
                    invalidator?.invoke()
                }
            }
            MotionEvent.ACTION_UP -> {
                val d = touchData ?: info
                if (scrubbing) {
                    val ms = (scrubFraction * d.durationMs).toLong()
                    // Show the target position until the player reports it (or 1.5 s pass).
                    pendingSeekMs = ms
                    pendingSeekUntil = SystemClock.elapsedRealtime() + 1500L
                    actions.onSeek(ms)
                } else when (pressed) {
                    PREV -> if (d.canPrevious) actions.onPrevious()
                    PLAY -> actions.onPlayPause()
                    NEXT -> if (d.canNext) actions.onNext()
                }
                resetTouch()
            }
            MotionEvent.ACTION_CANCEL -> resetTouch()
        }
        return true
    }

    private fun resetTouch() {
        scrubbing = false
        pressed = NONE
        touchData = null
        invalidator?.invoke()
    }

    private fun controlAt(x: Float, y: Float): Int {
        val rad = dp(30f)
        return when {
            hypot(x - prevX, y - controlsY) <= rad -> PREV
            hypot(x - playX, y - controlsY) <= rad -> PLAY
            hypot(x - nextX, y - controlsY) <= rad -> NEXT
            else -> NONE
        }
    }

    private fun xOf(control: Int) = when (control) { PREV -> prevX; NEXT -> nextX; else -> playX }

    private fun displayedPosition(info: MediaInfo): Long {
        val now = SystemClock.elapsedRealtime()
        if (scrubbing) return (scrubFraction * info.durationMs).toLong()
        if (pendingSeekMs >= 0) {
            val reported = info.positionAt(now)
            if (now < pendingSeekUntil && abs(reported - pendingSeekMs) > 1500L) return pendingSeekMs
            pendingSeekMs = -1L
        }
        return info.positionAt(now)
    }

    // ---- Pieces -------------------------------------------------------------------------------------

    // Two cached shaders: during a track cross-fade both the old and new art are on screen.
    private var shaderBmpA: Bitmap? = null
    private var shaderA: BitmapShader? = null
    private var shaderBmpB: Bitmap? = null
    private var shaderB: BitmapShader? = null

    private fun shaderFor(bmp: Bitmap): BitmapShader {
        if (bmp === shaderBmpA) return shaderA!!
        if (bmp === shaderBmpB) return shaderB!!
        // Evict the older entry (B), promote A.
        shaderBmpB = shaderBmpA; shaderB = shaderA
        shaderBmpA = bmp
        return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaderA = it }
    }

    /** Rounded, centre-cropped art via a BitmapShader (no per-frame allocation). */
    private fun drawArt(c: Canvas, dst: RectF, radius: Float, info: MediaInfo, a: Int) {
        val bmp = info.art
        if (bmp == null || bmp.isRecycled) {
            fill.color = PLACEHOLDER; fill.alpha = a
            c.drawRoundRect(dst, radius, radius, fill)
            // Simple note glyph.
            fill.color = info.accent; fill.alpha = a
            val s = dst.width()
            c.drawCircle(dst.left + s * 0.42f, dst.top + s * 0.66f, s * 0.11f, fill)
            stroke.color = info.accent; stroke.alpha = a; stroke.strokeWidth = s * 0.06f
            c.drawLine(dst.left + s * 0.52f, dst.top + s * 0.66f, dst.left + s * 0.52f, dst.top + s * 0.3f, stroke)
            return
        }
        val shader = shaderFor(bmp)
        artSrc.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val scale = maxOf(dst.width() / artSrc.width(), dst.height() / artSrc.height())
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            dst.centerX() - artSrc.width() * scale / 2f,
            dst.centerY() - artSrc.height() * scale / 2f,
        )
        shader.setLocalMatrix(matrix)
        artPaint.shader = shader
        artPaint.alpha = a
        c.drawRoundRect(dst, radius, radius, artPaint)
    }

    /** Four bars in the accent colour; animated while playing, resting low when paused. */
    private fun drawEqualizer(c: Canvas, rightX: Float, cy: Float, maxH: Float, info: MediaInfo, a: Int, now: Long) {
        val barW = maxH / 6f
        val gap = barW * 0.75f
        stroke.strokeWidth = barW
        stroke.color = info.accent; stroke.alpha = a
        val t = now / 1000f
        for (i in 0 until 4) {
            val level = if (info.playing) 0.3f + 0.7f * abs(sin(t * (2.3f + i * 0.85f) + i * 1.7f)) else 0.22f
            val h = maxOf(maxH * level, barW)
            val x = rightX - (3 - i) * (barW + gap) - barW / 2f
            c.drawLine(x, cy - h / 2 + barW / 2, x, cy + h / 2 - barW / 2, stroke)
        }
    }

    private fun drawPlay(c: Canvas, cx: Float, cy: Float) {
        val h = dp(28f)
        path.reset()
        path.moveTo(cx - h * 0.36f, cy - h / 2)
        path.lineTo(cx + h * 0.5f, cy)
        path.lineTo(cx - h * 0.36f, cy + h / 2)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawPause(c: Canvas, cx: Float, cy: Float) {
        val h = dp(27f); val w = dp(7.5f); val gap = dp(7f)
        r.set(cx - gap / 2 - w, cy - h / 2, cx - gap / 2, cy + h / 2)
        c.drawRoundRect(r, dp(2f), dp(2f), fill)
        r.set(cx + gap / 2, cy - h / 2, cx + gap / 2 + w, cy + h / 2)
        c.drawRoundRect(r, dp(2f), dp(2f), fill)
    }

    /** Double triangle; [dir] = 1 next, -1 previous. */
    private fun drawSkip(c: Canvas, cx: Float, cy: Float, dir: Int) {
        val h = dp(19f); val w = dp(14f)
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

    // ---- Text helpers -------------------------------------------------------------------------------

    // Ellipsizing allocates, so results are cached per (text, width) — two entries each, because
    // a cross-fade draws two tracks at once.
    private val titleCache = TextCache()
    private val artistCache = TextCache()

    private fun ellipsized(text: String, paint: TextPaint, maxW: Float, isTitle: Boolean): String {
        val cache = if (isTitle) titleCache else artistCache
        cache.lookup(text, maxW)?.let { return it }
        val v = TextUtils.ellipsize(text, paint, maxW, TextUtils.TruncateAt.END).toString()
        cache.put(text, maxW, v)
        return v
    }

    private class TextCache {
        private var k1: String? = null; private var w1 = 0f; private var v1 = ""
        private var k2: String? = null; private var w2 = 0f; private var v2 = ""

        fun lookup(key: String, w: Float): String? = when {
            key == k1 && w == w1 -> v1
            key == k2 && w == w2 -> v2
            else -> null
        }

        fun put(key: String, w: Float, value: String) {
            k2 = k1; w2 = w1; v2 = v1
            k1 = key; w1 = w; v1 = value
        }
    }

    /** Formats ms as m:ss or h:mm:ss into [out] starting at [start]; returns the end index. */
    private fun formatTime(ms: Long, out: CharArray, start: Int): Int {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val h = total / 3600; val m = (total / 60) % 60; val s = total % 60
        var i = start
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

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF98989F.toInt()
        const val PLACEHOLDER = 0xFF2C2C2E.toInt()
        const val NONE = 0
        const val PREV = 1
        const val PLAY = 2
        const val NEXT = 3
    }
}
