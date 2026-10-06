package com.codewithaj.dynamicisland.island.view.content

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils

/**
 * Two-entry caches for renderers. Two entries because during a cross-fade the outgoing and
 * incoming content are drawn in the same frame; with one entry they'd evict each other and
 * allocate every frame.
 */

/** BitmapShader per bitmap, plus a centre-crop helper. */
class BitmapShaderCache {
    private var bmpA: Bitmap? = null
    private var shaderA: BitmapShader? = null
    private var bmpB: Bitmap? = null
    private var shaderB: BitmapShader? = null
    private val matrix = Matrix()

    /** Returns a shader drawing [bmp] centre-cropped into [dst]. Allocates only on a cache miss. */
    fun cropInto(bmp: Bitmap, dst: RectF): BitmapShader {
        val shader = when {
            bmp === bmpA -> shaderA!!
            bmp === bmpB -> shaderB!!
            else -> {
                bmpB = bmpA; shaderB = shaderA
                bmpA = bmp
                BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaderA = it }
            }
        }
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = maxOf(dst.width() / bw, dst.height() / bh)
        matrix.setScale(scale, scale)
        matrix.postTranslate(dst.centerX() - bw * scale / 2f, dst.centerY() - bh * scale / 2f)
        shader.setLocalMatrix(matrix)
        return shader
    }
}

/** Ellipsized strings keyed by (text, width). */
class EllipsisCache {
    private var k1: String? = null; private var w1 = 0f; private var v1 = ""
    private var k2: String? = null; private var w2 = 0f; private var v2 = ""

    fun get(text: String, paint: TextPaint, maxWidth: Float): String {
        if (text == k1 && maxWidth == w1) return v1
        if (text == k2 && maxWidth == w2) return v2
        val v = if (maxWidth <= 0f) "" else TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
        k2 = k1; w2 = w1; v2 = v1
        k1 = text; w1 = maxWidth; v1 = v
        return v
    }
}

/** Ease-out cubic on t ∈ [0, 1]. */
internal fun easeOut(t: Float): Float {
    val x = 1f - t.coerceIn(0f, 1f)
    return 1f - x * x * x
}
