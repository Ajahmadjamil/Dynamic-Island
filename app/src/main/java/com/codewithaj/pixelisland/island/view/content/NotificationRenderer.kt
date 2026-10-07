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
 * Notification preview card:
 *   [icon]  App name
 *           Title
 *           Text…
 * The icon is the sender's avatar (round) when the notification has one, else the app icon.
 */
class NotificationRenderer(private val density: Float) : IslandContentRenderer {

    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val appPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; textSize = dp(12f) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE; textSize = dp(15f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = BODY; textSize = dp(14f) }

    private val icon = RectF()
    private val shaders = BitmapShaderCache()
    private val appCache = EllipsisCache()
    private val titleCache = EllipsisCache()
    private val bodyCache = EllipsisCache()

    override fun compactWingPx(pillHeightPx: Float) = pillHeightPx * 2f

    override fun expandedHeightPx(density: Float) = 86f * density

    override fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?) {
        val n = data as? IslandActivity.NotificationPreview ?: return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val l = layout
        val pad = dp(18f)
        val size = dp(46f)
        icon.set(l.left + pad, l.centerY() - size / 2f, l.left + pad + size, l.centerY() + size / 2f)

        val bmp = n.icon
        if (bmp != null && !bmp.isRecycled) {
            iconPaint.shader = shaders.cropInto(bmp, icon)
            iconPaint.alpha = a
            if (n.iconIsAvatar) canvas.drawOval(icon, iconPaint)
            else canvas.drawRoundRect(icon, dp(11f), dp(11f), iconPaint)
        } else {
            fill.color = PLACEHOLDER; fill.alpha = a
            canvas.drawRoundRect(icon, dp(11f), dp(11f), fill)
        }

        val x = icon.right + dp(14f)
        val maxW = l.right - pad - x
        val top = l.centerY() - dp(26f)
        appPaint.alpha = a
        titlePaint.alpha = a
        bodyPaint.alpha = a
        canvas.drawText(appCache.get(n.appLabel, appPaint, maxW), x, top + dp(11f), appPaint)
        canvas.drawText(titleCache.get(n.title, titlePaint, maxW), x, top + dp(31f), titlePaint)
        if (n.text.isNotEmpty()) {
            canvas.drawText(bodyCache.get(n.text, bodyPaint, maxW), x, top + dp(50f), bodyPaint)
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF8E8E93.toInt()
        const val BODY = 0xFFD1D1D6.toInt()
        const val PLACEHOLDER = 0xFF2C2C2E.toInt()
    }
}
