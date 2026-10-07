package com.codewithaj.pixelisland.media

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/** Downscales album art and extracts an accent colour. Call off the main thread. */
object ArtProcessor {

    /** Expanded art is 62dp; 192px covers it at xxxhdpi-ish densities. */
    const val MAX_ART_PX = 192

    const val DEFAULT_ACCENT = 0xFFFFFFFF.toInt()

    class Result(val bitmap: Bitmap, val accent: Int, /** True if [bitmap] is our own copy (safe to recycle). */ val owned: Boolean)

    fun process(source: Bitmap): Result? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return try {
            // Hardware bitmaps can't be read by Palette; make a software copy first.
            val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else source

            val scale = MAX_ART_PX.toFloat() / maxOf(readable.width, readable.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    readable,
                    (readable.width * scale).toInt().coerceAtLeast(1),
                    (readable.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else readable
            if (readable !== source && readable !== scaled) readable.recycle()

            Result(scaled, accentOf(scaled), owned = scaled !== source)
        } catch (_: Exception) {
            null // OOM / odd configs: fall back to no art
        }
    }

    /**
     * Prefer vibrant swatches (what iOS appears to do), then fall back to muted/dominant.
     * The island is black, so very dark accents are lifted toward white until readable.
     */
    private fun accentOf(bitmap: Bitmap): Int {
        val p = Palette.from(bitmap).maximumColorCount(12).generate()
        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        var c = swatch?.rgb ?: return DEFAULT_ACCENT
        var guard = 0
        while (ColorUtils.calculateLuminance(c) < 0.18 && guard++ < 6) {
            c = ColorUtils.blendARGB(c, Color.WHITE, 0.2f)
        }
        return c
    }
}
