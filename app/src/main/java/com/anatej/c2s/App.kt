package com.anatej.c2s

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        ClipboardHistory.migrateLegacy(this)
        // Warm up the recognizer so the first shared image is instant. A model
        // initialization failure must not prevent the app from opening.
        runCatching { OcrEngine.warmUp() }
    }

    companion object {
        /** Reads the saved theme and applies it process-wide. */
        fun applyTheme(context: Context) {
            val prefs = context.getSharedPreferences(
                CaptureAccessibilityService.PREFS, Context.MODE_PRIVATE
            )
            val mode = when (prefs.getString(
                CaptureAccessibilityService.PREF_THEME,
                CaptureAccessibilityService.THEME_SYSTEM
            )) {
                CaptureAccessibilityService.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                CaptureAccessibilityService.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (AppCompatDelegate.getDefaultNightMode() != mode) {
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }
}
