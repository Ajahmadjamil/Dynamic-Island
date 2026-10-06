package com.codewithaj.dynamicisland.island

import android.content.Context
import android.graphics.RectF
import com.codewithaj.dynamicisland.data.Calibration
import com.codewithaj.dynamicisland.data.IslandSettings
import com.codewithaj.dynamicisland.island.view.IslandContentRenderer
import com.codewithaj.dynamicisland.island.view.ShapeTarget
import com.codewithaj.dynamicisland.util.DisplayUtils

/** Resting (idle) pill position on screen, in px. */
data class PillBounds(
    val centerX: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float,
) {
    val left get() = centerX - width / 2f
    val bottom get() = top + height
}

/** Auto values before calibration, in dp, so the UI can show them on the sliders. */
data class AutoPill(val widthDp: Float, val heightDp: Float)

object IslandGeometry {

    /** iPhone's island is ~126×37pt; we keep the same aspect for the idle pill. */
    private const val IDLE_ASPECT = 3.4f
    private const val HOLE_PADDING_DP = 5f
    private const val MIN_HEIGHT_DP = 26f
    private const val NO_CUTOUT_HEIGHT_DP = 28f
    private const val NO_CUTOUT_TOP_DP = 6f
    private const val MIN_TOP_DP = 2f

    fun auto(context: Context): AutoPill {
        val density = context.resources.displayMetrics.density
        val hole = DisplayUtils.topCutoutHole(context)
        val h = autoHeightPx(hole, density)
        return AutoPill(widthDp = h * IDLE_ASPECT / density, heightDp = h / density)
    }

    fun compute(context: Context, calibration: Calibration): PillBounds {
        val density = context.resources.displayMetrics.density
        val screenW = DisplayUtils.screenWidthPx(context).toFloat()
        val hole = DisplayUtils.topCutoutHole(context)

        val autoH = autoHeightPx(hole, density)
        val h = if (calibration.heightDp > 0f) calibration.heightDp * density else autoH
        val w = if (calibration.widthDp > 0f) calibration.widthDp * density else autoH * IDLE_ASPECT

        // Centre the pill on the hole so the camera sits inside it; otherwise top-centre.
        val cx = (hole?.centerX() ?: (screenW / 2f)) + calibration.offsetXDp * density
        val baseTop = if (hole != null) {
            maxOf(MIN_TOP_DP * density, hole.centerY() - h / 2f)
        } else {
            NO_CUTOUT_TOP_DP * density
        }
        val top = maxOf(0f, baseTop + calibration.offsetYDp * density)

        val radius = if (calibration.cornerRadiusDp == IslandSettings.AUTO) h / 2f
        else minOf(calibration.cornerRadiusDp * density, h / 2f)

        return PillBounds(cx.coerceIn(w / 2f, screenW - w / 2f), top, w, h, radius)
    }

    // ---- Per-mode shape targets ---------------------------------------------------------------

    /** Gap between the expanded card and the screen edges (iOS ≈ 11pt). */
    private const val EXPANDED_SIDE_MARGIN_DP = 10f
    /** Cap so the card doesn't become absurdly wide on tablets/foldables. */
    private const val EXPANDED_MAX_WIDTH_DP = 420f
    private const val EXPANDED_RADIUS_DP = 44f
    private const val COMPACT_SIDE_MARGIN_DP = 12f

    fun targetFor(
        mode: IslandMode,
        idle: PillBounds,
        screenWidthPx: Float,
        density: Float,
        renderer: IslandContentRenderer?,
    ): ShapeTarget = when (mode) {
        IslandMode.HIDDEN -> ShapeTarget(idle.centerX, idle.top, idle.width, idle.height, idle.cornerRadius, alpha = 0f)
        IslandMode.IDLE -> ShapeTarget(idle.centerX, idle.top, idle.width, idle.height, idle.cornerRadius, alpha = 1f)
        IslandMode.COMPACT -> {
            val wing = renderer?.compactWingPx(idle.height) ?: 0f
            val w = minOf(idle.width + wing * 2f, screenWidthPx - COMPACT_SIDE_MARGIN_DP * 2f * density)
            val cx = idle.centerX.coerceIn(w / 2f, screenWidthPx - w / 2f)
            ShapeTarget(cx, idle.top, w, idle.height, idle.height / 2f, alpha = 1f)
        }
        IslandMode.EXPANDED -> {
            val w = minOf(screenWidthPx - EXPANDED_SIDE_MARGIN_DP * 2f * density, EXPANDED_MAX_WIDTH_DP * density)
            val h = renderer?.expandedHeightPx(density) ?: (idle.height * 3f)
            ShapeTarget(screenWidthPx / 2f, idle.top, w, h, minOf(EXPANDED_RADIUS_DP * density, h / 2f), alpha = 1f)
        }
    }

    private fun autoHeightPx(hole: RectF?, density: Float): Float {
        if (hole == null) return NO_CUTOUT_HEIGHT_DP * density
        val diameter = minOf(hole.width(), hole.height())
        return maxOf(MIN_HEIGHT_DP * density, diameter + 2f * HOLE_PADDING_DP * density)
    }
}
