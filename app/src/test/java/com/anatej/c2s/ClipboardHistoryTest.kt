package com.anatej.c2s

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClipboardHistoryTest {

    private lateinit var context: Context
    private lateinit var settings: android.content.SharedPreferences
    private lateinit var history: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = context.getSharedPreferences(CaptureAccessibilityService.PREFS, Context.MODE_PRIVATE)
        history = ClipboardHistory.prefs(context)
        settings.edit().clear().commit()
        history.edit().clear().commit()
    }

    @Test
    fun `history is opt in and deduplicated`() {
        ClipboardHistory.add(settings, history, "secret")
        assertTrue(ClipboardHistory.load(history, settings).isEmpty())

        settings.edit().putBoolean(CaptureAccessibilityService.PREF_HISTORY_ENABLED, true).apply()
        ClipboardHistory.add(settings, history, "secret")
        ClipboardHistory.add(settings, history, "secret")
        val entries = ClipboardHistory.load(history, settings)
        assertEquals(1, entries.size)
        assertEquals("secret", entries.single().text)
    }

    @Test
    fun `expired entries are removed`() {
        settings.edit()
            .putBoolean(CaptureAccessibilityService.PREF_HISTORY_ENABLED, true)
            .putString(CaptureAccessibilityService.PREF_HISTORY_EXPIRY, CaptureAccessibilityService.HISTORY_EXPIRY_HOUR)
            .apply()
        ClipboardHistory.add(settings, history, "old")
        val raw = history.getString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, null)!!
        val array = org.json.JSONArray(raw)
        array.getJSONObject(0).put("time", System.currentTimeMillis() - 7_200_000)
        history.edit().putString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, array.toString()).apply()
        assertTrue(ClipboardHistory.load(history, settings).isEmpty())
    }
}
