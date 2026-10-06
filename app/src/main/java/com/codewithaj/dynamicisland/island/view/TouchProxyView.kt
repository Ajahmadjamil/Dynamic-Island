package com.codewithaj.dynamicisland.island.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * Invisible touch target for the island.
 *
 * The island is drawn in a window that never moves or resizes (so animations can't jitter),
 * and that window is NOT_TOUCHABLE so everything around the island stays usable. This view
 * lives in a second, small window that hugs the island's target shape; it draws nothing and
 * just forwards touches to [target], translated into the drawing window's coordinates.
 * Moving this window has no visual effect, so it can follow the shape freely.
 */
@SuppressLint("ViewConstructor")
class TouchProxyView(context: Context, private val target: IslandView) : View(context) {

    /** Screen position of this window's top-left (the drawing window sits at 0,0). */
    var offsetX = 0f
    var offsetY = 0f

    init {
        // Never draws; keep it out of the accessibility tree too (the island view is the target).
        setWillNotDraw(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) return target.onTouchEvent(event)
        event.offsetLocation(offsetX, offsetY)
        val handled = target.onTouchEvent(event)
        event.offsetLocation(-offsetX, -offsetY)
        return handled
    }
}
