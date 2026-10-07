package com.codewithaj.pixelisland.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Detects fullscreen apps (videos, games) while the island runs as an accessibility overlay.
 *
 * Accessibility overlays sit *above* the status bar and are never told whether it's showing
 * (their insets contain no status-bar source at all). Normal app overlays are: they receive the
 * status bar's real visibility through WindowInsets. So, when "Display over other apps" is
 * granted, we add a 1×1 px TYPE_APPLICATION_OVERLAY window at the top-left that:
 *  - draws nothing (alpha 0),
 *  - is NOT_TOUCHABLE (and, at alpha 0, doesn't trip Android 12+ touch-occlusion rules),
 *  - only listens to insets → [onChange] when the status bar hides/shows.
 * Event-driven; costs one tiny window and no CPU while nothing changes.
 *
 * Without the overlay permission there's no probe and the island simply doesn't auto-hide.
 */
class FullscreenProbe(private val context: Context, private val onChange: (statusBarVisible: Boolean) -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var last = true

    fun start() {
        if (view != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!Settings.canDrawOverlays(context)) return
        val v = ProbeView(context)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
            title = "Islet probe"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        try {
            wm.addView(v, params)
            view = v
        } catch (e: Exception) {
            Log.w("FullscreenProbe", "probe window not added", e)
        }
    }

    fun stop() {
        view?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        view = null
        if (!last) { last = true; onChange(true) }
    }

    @SuppressLint("ViewConstructor")
    private inner class ProbeView(context: Context) : View(context) {
        init { setWillNotDraw(true) }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val type = WindowInsets.Type.statusBars()
                // Only trust it if the status-bar source reaches this window at all.
                if (insets.getInsetsIgnoringVisibility(type).top > 0) {
                    val visible = insets.isVisible(type)
                    if (visible != last) {
                        last = visible
                        onChange(visible)
                    }
                }
            }
            return super.onApplyWindowInsets(insets)
        }
    }
}
