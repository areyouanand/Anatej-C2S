package com.anatej.c2s

import android.content.SharedPreferences
import org.json.JSONObject

/** Exports the app's SharedPreferences to JSON and imports them back. */
object PrefsBackup {

    fun export(prefs: SharedPreferences): String {
        val json = JSONObject()
        prefs.all.forEach { (key, value) -> json.put(key, value) }
        return json.toString(2)
    }

    /** Only known keys with the expected type are restored. */
    fun import(prefs: SharedPreferences, json: String) {
        val data = JSONObject(json)
        val editor = prefs.edit()
        for (key in data.keys()) {
            val value = data.get(key)
            when {
                key in CaptureAccessibilityService.BOOLEAN_KEYS && value is Boolean ->
                    editor.putBoolean(key, value)
                key in CaptureAccessibilityService.INT_KEYS && value is Number ->
                    editor.putInt(key, value.toInt())
                key in CaptureAccessibilityService.STRING_KEYS && value is String ->
                    editor.putString(key, value)
            }
        }
        editor.apply()
    }
}
