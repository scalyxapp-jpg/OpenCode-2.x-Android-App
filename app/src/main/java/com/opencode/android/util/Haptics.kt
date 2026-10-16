package com.opencode.android.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Press feedback that actually plays.
 *
 * `View.performHapticFeedback` is gated on the device's **touch-feedback setting**: with
 * `Settings.System.HAPTIC_FEEDBACK_ENABLED = 0` the system logs the request as
 * `ignored_for_settings` and plays **nothing** (`played: null` in `dumpsys vibrator_manager`) —
 * which is why the dictation button felt dead although the call was made (reported, and then proven
 * on the device).
 *
 * So the Vibrator is used directly: it plays regardless of that setting, and it only needs the
 * normal `VIBRATE` permission. Two distinguishable pulses make "recording" and "done" clear without
 * looking at the screen. If no vibrator exists, the old `performHapticFeedback` path is used as a
 * fallback.
 *
 * Limits, stated honestly: in Do-not-disturb with vibration disabled, or on a device whose vibrator
 * is switched off in the system, nothing can play - that is the platform, not the app.
 */
object Haptics {
    /** Recording started: firm, ~30 ms. */
    fun press(context: Context) {
        if (!play(context, durationMs = 30L, amplitude = 255)) {
            fallbackPress(context)
        }
    }

    /** Recording ended: lighter, ~15 ms - clearly different from the start pulse. */
    fun release(context: Context) {
        if (!play(context, durationMs = 15L, amplitude = 140)) {
            fallbackRelease(context)
        }
    }

    private fun play(
        context: Context,
        durationMs: Long,
        amplitude: Int,
    ): Boolean =
        runCatching {
            val vibrator = vibrator(context) ?: return false
            if (!vibrator.hasVibrator()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
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
    private fun fallbackPress(context: Context) {
        runCatching {
            (context as? android.app.Activity)?.window?.decorView?.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS,
            )
        }
    }

    private fun fallbackRelease(context: Context) {
        runCatching {
            (context as? android.app.Activity)?.window?.decorView?.performHapticFeedback(
                android.view.HapticFeedbackConstants.CLOCK_TICK,
            )
        }
    }
}
