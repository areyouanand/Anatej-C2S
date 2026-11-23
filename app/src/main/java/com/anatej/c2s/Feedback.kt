package com.anatej.c2s

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short, configurable confirmation when a capture trigger fires. */
object Feedback {

    fun capture(context: Context, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(CaptureAccessibilityService.PREF_HAPTIC, true)) vibrate(context)
        if (prefs.getBoolean(CaptureAccessibilityService.PREF_SOUND, false)) beep()
    }

    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
        }
    }
}
