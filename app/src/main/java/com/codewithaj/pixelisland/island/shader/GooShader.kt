package com.codewithaj.pixelisland.island.shader

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The "gooey" split/merge between the main pill and the split bubble (API 33+).
 *
 * Instead of the classic blur + alpha-threshold trick (two offscreen passes), the shape is
 * computed analytically per pixel as a signed distance field:
 *
 *   d_pill   = distance to the rounded rectangle
 *   d_bubble = distance to the circle
 *   d        = smin(d_pill, d_bubble, k)      ← polynomial smooth-minimum
 *
 * smin() blends the two distance fields wherever they are closer than k to each other, which
 * grows a smooth "bridge" between the shapes; as the bubble moves away beyond k the bridge
 * thins and snaps — the metaball look. Coverage is then a 1-px anti-aliased step on d.
 *
 * Cost: one cheap fragment shader over the union rect (~50 dp tall), one draw call, no
 * offscreen buffers. Uniforms are set with the scalar setters, so per-frame updates don't
 * allocate.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GooShader {

    private val shader = RuntimeShader(AGSL)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@GooShader.shader }

    /** All values in the coordinate space of the draw call (view coords). */
    fun update(
        left: Float, top: Float, right: Float, bottom: Float, radius: Float,
        bubbleCx: Float, bubbleCy: Float, bubbleR: Float,
        blend: Float, alpha: Float,
    ) {
        shader.setFloatUniform("pill", left, top, right, bottom)
        shader.setFloatUniform("pillR", radius)
        shader.setFloatUniform("bubble", bubbleCx, bubbleCy, bubbleR)
        shader.setFloatUniform("k", blend)
        shader.setFloatUniform("alpha", alpha)
    }

    private companion object {
        const val AGSL = """
            uniform float4 pill;     // left, top, right, bottom
            uniform float pillR;     // corner radius
            uniform float3 bubble;   // centre x, centre y, radius
            uniform float k;         // smooth-min blend distance
            uniform float alpha;

            // Signed distance to a rounded rectangle centred at c with half-extents he.
            float sdRoundRect(float2 p, float2 c, float2 he, float r) {
                float2 q = abs(p - c) - he + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            // Polynomial smooth minimum (Inigo Quilez): blends a and b within distance k.
            float smin(float a, float b, float k) {
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            half4 main(float2 p) {
                float2 c = (pill.xy + pill.zw) * 0.5;
                float2 he = (pill.zw - pill.xy) * 0.5;
                float d = sdRoundRect(p, c, he, min(pillR, min(he.x, he.y)));
                if (bubble.z > 0.25) {
                    float db = length(p - bubble.xy) - bubble.z;
                    d = smin(d, db, max(k, 0.001));
                }
                // 1 px anti-aliased edge; output is premultiplied black.
                float a = clamp(0.5 - d, 0.0, 1.0) * alpha;
                return half4(0.0, 0.0, 0.0, a);
            }
        """
    }
}
