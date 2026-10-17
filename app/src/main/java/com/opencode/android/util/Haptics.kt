package com.opencode.android.util

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Press feedback on the dictation button that is actually felt.
 *
 * Two dead ends were found on the device, and both are handled here:
 *
 * 1. `View.performHapticFeedback` is gated on the device's **touch-feedback setting**. With
 *    `Settings.System.HAPTIC_FEEDBACK_ENABLED = 0` the system logs the request as
 *    `ignored_for_settings` and plays nothing (`played: null`) - which is why the first attempt at
 *    this feature was silent.
 * 2. The Vibrator then played (`played: Step=30ms(amplitude=1.00)`) but was still not felt: the
 *    effect carried `usage: TOUCH`, and that category is scaled by the "touch interactions"
 *    vibration intensity - the very slider that sits next to the touch-feedback switch. A pulse the
 *    user explicitly asked for must not be scaled down to nothing by a setting about touch feedback.
 *
 * So the Vibrator is used directly, with the device's own **predefined** effects (tuned for the
 * actuator, which is perceptibly better than a raw one-shot) and with
 * [VibrationAttributes.USAGE_PHYSICAL_EMULATION] from API 30 on - the category for "vibration that
 * imitates a physical button", which is what a held dictation button is.
 *
 * Limits, stated honestly: with "Do not disturb" silencing vibration, or a device whose vibrator is
 * switched off entirely, the platform may still play nothing.
 */
object Haptics {
    /** Recording started: firm. */
    fun press(context: Context) {
        if (!play(context, predefined = VibrationEffect.EFFECT_CLICK, durationMs = 45L, amplitude = 255)) {
            fallback(context, android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** Recording ended: lighter, so "running" and "done" are distinguishable by feel. */
    fun release(context: Context) {
        if (!play(context, predefined = VibrationEffect.EFFECT_TICK, durationMs = 20L, amplitude = 150)) {
            fallback(context, android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun play(
        context: Context,
        predefined: Int,
        durationMs: Long,
        amplitude: Int,
    ): Boolean =
        runCatching {
            val vibrator = vibrator(context) ?: return false
            if (!vibrator.hasVibrator()) return false
            val effect =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // The device's own tuned click/tick: much more likely to be felt than a waveform
                    // that ignores the actuator's characteristics.
                    VibrationEffect.createPredefined(predefined)
                } else {
                    @Suppress("DEPRECATION")
                    VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                vibrator.vibrate(
                    effect,
                    // USAGE_PHYSICAL_EMULATION: "vibration that imitates a physical button".
                    // Deliberately NOT USAGE_TOUCH - that category is the one the touch-feedback
                    // switch and its intensity slider scale down, which is why the first version of
                    // this pulse was played and still not felt.
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION),
                )
            } else {
                vibrator.vibrate(effect)
            }
            true
        }.getOrDefault(false)

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Only reached when the device has no usable vibrator. */
    private fun fallback(
        context: Context,
        constant: Int,
    ) {
        runCatching {
            (context as? android.app.Activity)?.window?.decorView?.performHapticFeedback(constant)
        }
    }
}
