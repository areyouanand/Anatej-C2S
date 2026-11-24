package com.anatej.c2s

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrefsBackupTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(CaptureAccessibilityService.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `export excludes clipboard history and round trips settings`() {
        prefs.edit()
            .putInt(CaptureAccessibilityService.PREF_SIZE_DP, 68)
            .putBoolean(CaptureAccessibilityService.PREF_HAPTIC, false)
            .putString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, "sentinel-secret")
            .apply()

        val json = PrefsBackup.export(prefs)
        assertFalse(json.contains("sentinel-secret"))
        assertFalse(json.contains(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY))

        prefs.edit().clear().commit()
        PrefsBackup.import(prefs, json, setOf(CaptureAccessibilityService.SCRIPT_LATIN))
        assertEquals(68, prefs.getInt(CaptureAccessibilityService.PREF_SIZE_DP, 56))
        assertFalse(prefs.getBoolean(CaptureAccessibilityService.PREF_HAPTIC, true))
    }

    @Test
    fun `parser validates ranges and supported scripts`() {
        val invalid = JSONObject()
            .put("schemaVersion", PrefsBackup.SCHEMA_VERSION)
            .put("settings", JSONObject().put(CaptureAccessibilityService.PREF_SIZE_DP, 65))
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            PrefsBackup.parse(invalid, setOf(CaptureAccessibilityService.SCRIPT_LATIN))
        }

        val unsupported = JSONObject()
            .put("settings", JSONObject().put(CaptureAccessibilityService.PREF_OCR_SCRIPT, "chinese"))
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            PrefsBackup.parse(unsupported, setOf(CaptureAccessibilityService.SCRIPT_LATIN))
        }
    }

    @Test
    fun `legacy flat format remains readable`() {
        val values = PrefsBackup.parse(
            """{"${CaptureAccessibilityService.PREF_THEME}":"dark"}""",
            setOf(CaptureAccessibilityService.SCRIPT_LATIN)
        )
        assertEquals(CaptureAccessibilityService.THEME_DARK, values[CaptureAccessibilityService.PREF_THEME])
        assertTrue(values.isNotEmpty())
    }
}
