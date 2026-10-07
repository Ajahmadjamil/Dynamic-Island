package com.codewithaj.pixelisland.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager

/** Small helpers for screen size, status bar height and the camera cutout. */
object DisplayUtils {

    fun defaultDisplay(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** Full physical width in the current rotation, in px. */
    fun screenWidthPx(context: Context): Int {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            return wm.maximumWindowMetrics.bounds.width()
        }
        val p = Point()
        @Suppress("DEPRECATION")
        defaultDisplay(context)?.getRealSize(p)
        return if (p.x > 0) p.x else context.resources.displayMetrics.widthPixels
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun statusBarHeightPx(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 24f).toInt()
    }

    /**
     * Returns the bounds of the camera hole/notch at the top edge, in px, or null if there is none
     * (or we can't know it: API 26–28 has no display-level cutout API, so the caller falls back
     * to a centred pill + manual calibration).
     *
     * Heuristics:
     *  - API 31+: the real cutout *path* is available, so we use its exact bounds (the circle).
     *  - API 29/30: only the bounding rect, which is extended up to the screen edge. For a hole
     *    punch the hole is the bottom square of that rect (diameter = min(w, h)).
     */
    fun topCutoutHole(context: Context): RectF? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cutout = defaultDisplay(context)?.cutout ?: return null
        val top = cutout.boundingRectTop
        if (top.isEmpty) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val path = cutout.cutoutPath
            if (path != null) {
                val b = RectF()
                @Suppress("DEPRECATION")
                path.computeBounds(b, true)
                // Path may contain several cutouts; trust it only if it lies within the top rect.
                if (!b.isEmpty && b.bottom <= top.bottom + 1 && b.width() <= top.width() + 1) return b
            }
        }
        val d = minOf(top.width(), top.height()).toFloat()
        val cx = top.exactCenterX()
        return RectF(cx - d / 2f, top.bottom - d, cx + d / 2f, top.bottom.toFloat())
    }

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density
}
