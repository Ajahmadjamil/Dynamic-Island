package com.codewithaj.dynamicisland.island.animation

/**
 * Every spring constant and timing of the island, in one place. Tune here.
 *
 * Units follow androidx.dynamicanimation's SpringForce (mass = 1):
 *   stiffness:  50 very low · 200 low · 400 medium-low · 1500 medium · 10000 high
 *   damping:    1.0 = no overshoot (critically damped) · 0.75 ≈ one small visible bounce · 0.5 = bouncy
 *
 * How these were chosen (by eye, against iOS screen recordings at 120 fps):
 *  - iOS island expansion has a small overshoot on the width and very little on the height,
 *    settling in roughly 450–550 ms. Stiffness ~320 with damping ~0.74 matches that feel.
 *  - Collapsing is quicker and almost overshoot-free, so it feels like it "snaps home".
 *  - Content uses critically damped springs (no bounce) so text never wobbles.
 *
 * The user's "animation speed" preset multiplies every stiffness. Settle time scales with
 * 1/sqrt(stiffness), so 1.4× stiffness ≈ 15% faster, 0.7× ≈ 20% slower.
 */
object AnimationSpec {

    data class Spring(val stiffness: Float, val dampingRatio: Float)

    /** Compact/idle → expanded card. Slightly bouncy, like iOS. */
    val EXPAND = Spring(stiffness = 320f, dampingRatio = 0.74f)

    /** Expanded → compact/idle. Faster, nearly no overshoot. */
    val COLLAPSE = Spring(stiffness = 460f, dampingRatio = 0.86f)

    /** Idle ↔ compact and compact ↔ compact (content change) morphs. */
    val MORPH = Spring(stiffness = 400f, dampingRatio = 0.80f)

    /** Fade of the black shape itself when the island appears from "hidden". */
    val SHAPE_FADE = Spring(stiffness = 500f, dampingRatio = 1f)

    /** Finger-down "squish": the pill grows a touch, then springs back on release. */
    val PRESS = Spring(stiffness = 900f, dampingRatio = 0.6f)
    const val PRESS_SCALE = 1.045f

    // ---- Content choreography -------------------------------------------------------------
    // Old content leaves fast (and shrinks + blurs) while the container is already morphing;
    // new content arrives after a short stagger, growing in from slightly smaller and blurred.

    val CONTENT_OUT = Spring(stiffness = 1100f, dampingRatio = 1f)
    val CONTENT_IN = Spring(stiffness = 420f, dampingRatio = 1f)
    const val CONTENT_IN_DELAY_MS = 80L
    const val CONTENT_OUT_SCALE = 0.86f
    const val CONTENT_IN_START_SCALE = 0.9f

    /** Max blur applied to fading content (API 31+). Quantised to [BLUR_LEVELS] steps. */
    const val CONTENT_MAX_BLUR_DP = 7f
    const val BLUR_LEVELS = 4

    /** Ambient content animations (equaliser bars) redraw at ~30 fps, not 120, to save power. */
    const val AMBIENT_FRAME_MS = 33L

    /** Extra room around the shape while a spring may overshoot, as a fraction of its size. */
    const val OVERSHOOT_ROOM = 0.08f
}
