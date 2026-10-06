package com.codewithaj.dynamicisland.island.view

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent

enum class Presentation { COMPACT, EXPANDED }

/**
 * Draws one kind of activity inside the island. A renderer is a stateless *drawer*: the data
 * it draws is passed in per call, because during a cross-fade the same renderer draws the
 * outgoing and incoming content (e.g. old track → new track) at the same time.
 *
 * Implementations must not allocate in [draw]: pre-allocate Paints/Paths and cache any text
 * layout keyed on the data.
 */
interface IslandContentRenderer {

    /** Extra width added on *each* side of the idle pill in compact mode, in px. */
    fun compactWingPx(pillHeightPx: Float): Float

    /** Height of the expanded card, in px. */
    fun expandedHeightPx(density: Float): Float

    /**
     * @param layout the rect this content was laid out for (the *target* bounds of the
     *   transition, in view coordinates). The container may be smaller while morphing;
     *   the view clips to it.
     * @param alpha 0..1, already combined with the shape's alpha.
     * @param data the activity payload for this slot.
     */
    fun draw(canvas: Canvas, layout: RectF, presentation: Presentation, alpha: Float, nowMs: Long, data: Any?)

    /** True if the content animates by itself (equaliser, countdown, seek bar) while visible. */
    fun animatesContinuously(presentation: Presentation, data: Any?): Boolean = false

    /**
     * True while a short, foreground animation runs (bell swing, battery fill): redraw every
     * vsync instead of the ~30 fps ambient rate. Must turn false once the animation is done.
     */
    fun wantsFullFrameRate(data: Any?): Boolean = false
}

/**
 * Renderers with controls (buttons, seek bar) in the expanded card. The view offers every
 * touch stream to the content first; returning true from ACTION_DOWN claims the whole stream
 * (no tap-to-collapse, no press squish).
 */
interface InteractiveContent {
    fun onContentTouch(event: MotionEvent, layout: RectF, data: Any?): Boolean
    /** Set by the view so content can request redraws (pressed states, scrubbing). */
    var invalidator: (() -> Unit)?
}
