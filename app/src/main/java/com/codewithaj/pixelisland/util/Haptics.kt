package com.codewithaj.pixelisland.util

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

/**
 * Crisp, short haptics. Prefers VibrationEffect.Composition primitives (API 30+, on devices
 * whose actuator supports them), then predefined effects (API 29), then a 10 ms one-shot.
 * Effects are built once; playing one doesn't allocate.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val available = vibrator?.hasVibrator() == true

    private val primitives = available && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        vibrator!!.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
        )

    private val expandEffect = build(PRIMITIVE_CLICK, scale = 0.7f, predefined = EFFECT_CLICK)
    private val collapseEffect = build(PRIMITIVE_TICK, scale = 0.6f, predefined = EFFECT_TICK)
    private val tapEffect = build(PRIMITIVE_TICK, scale = 0.35f, predefined = EFFECT_TICK)

    private val attrs: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // USAGE_TOUCH respects the user's "touch feedback" system setting.
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    } else null

    fun expand() = play(expandEffect)
    fun collapse() = play(collapseEffect)
    fun tap() = play(tapEffect)

    private fun play(effect: VibrationEffect?) {
        if (effect == null) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, attrs as VibrationAttributes)
            } else {
                v.vibrate(effect)
            }
        } catch (_: Exception) {
            // Some OEM builds throw if haptics are disabled system-wide.
        }
    }

    private fun build(primitive: Int, scale: Float, predefined: Int): VibrationEffect? {
        if (!available) return null
        return when {
            primitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> composed(primitive, scale)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> VibrationEffect.createPredefined(predefined)
            else -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun composed(primitive: Int, scale: Float): VibrationEffect =
        VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()

    private companion object {
        // Literal values of VibrationEffect.EFFECT_* (API 29) and Composition.PRIMITIVE_* (API 30)
        // so the constants compile into API 26 code paths.
        const val EFFECT_CLICK = 0
        const val EFFECT_TICK = 2
        const val PRIMITIVE_CLICK = 1
        const val PRIMITIVE_TICK = 7
    }
}
