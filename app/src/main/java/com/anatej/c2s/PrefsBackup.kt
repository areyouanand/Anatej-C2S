package com.anatej.c2s

import android.content.SharedPreferences
import org.json.JSONObject

/** Versioned, allow-listed settings backup. Clipboard history never participates. */
object PrefsBackup {

    const val SCHEMA_VERSION = 1
    const val MAX_BYTES = 64 * 1024

    fun export(prefs: SharedPreferences): String {
        val values = linkedMapOf<String, Any>()
        CaptureAccessibilityService.BOOLEAN_KEYS.forEach { key ->
            if (prefs.contains(key)) values[key] = prefs.getBoolean(key, false)
        }
        CaptureAccessibilityService.INT_KEYS.forEach { key ->
            if (prefs.contains(key)) values[key] = prefs.getInt(key, 0)
        }
        CaptureAccessibilityService.STRING_KEYS.forEach { key ->
            prefs.getString(key, null)?.let { values[key] = it }
        }
        return serialize(values)
    }

    /** Pure serializer kept separate so backup privacy is unit-testable. */
    fun serialize(values: Map<String, Any>): String {
        val settings = JSONObject()
        values.forEach { (key, value) -> settings.put(key, value) }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("settings", settings)
            .toString(2)
    }

    fun import(prefs: SharedPreferences, json: String, availableScripts: Set<String>) {
        val values = parse(json, availableScripts)
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    /** Accepts the current versioned format and the flat v1.1.0 format. */
    fun parse(json: String, availableScripts: Set<String>): Map<String, Any> {
        val root = JSONObject(json)
        val source = if (root.has("settings")) root.getJSONObject("settings") else root
        if (root.has("schemaVersion") && root.getInt("schemaVersion") != SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported settings schema")
        }

        val result = linkedMapOf<String, Any>()
        for (key in source.keys()) {
            val value = source.get(key)
            when (key) {
                in CaptureAccessibilityService.BOOLEAN_KEYS ->
                    if (value is Boolean) result[key] = value
                in CaptureAccessibilityService.INT_KEYS ->
                    if (value is Number && validInt(key, value.toInt())) result[key] = value.toInt()
                in CaptureAccessibilityService.STRING_KEYS ->
                    if (value is String && validString(key, value, availableScripts)) result[key] = value
            }
        }
        if (result.isEmpty()) throw IllegalArgumentException("No valid settings found")
        return result
    }

    private fun validInt(key: String, value: Int): Boolean = when (key) {
        CaptureAccessibilityService.PREF_SIZE_DP -> value in 40..96 && value % 4 == 0
        CaptureAccessibilityService.PREF_ALPHA -> value in 30..100 && value % 5 == 0
        CaptureAccessibilityService.PREF_COLOR ->
            value in CaptureAccessibilityService.BUBBLE_COLORS
        CaptureAccessibilityService.PREF_EDGE_WIDTH_DP -> value in 40..140 && value % 4 == 0
        CaptureAccessibilityService.PREF_EDGE_HEIGHT_DP -> value in 100..280 && value % 4 == 0
        CaptureAccessibilityService.PREF_EDGE_INSET_DP -> value in 0..160 && value % 4 == 0
        CaptureAccessibilityService.PREF_EDGE_LIFT_DP -> value in 0..200 && value % 4 == 0
        CaptureAccessibilityService.PREF_BUBBLE_X,
        CaptureAccessibilityService.PREF_BUBBLE_Y -> true
        else -> false
    }

    private fun validString(key: String, value: String, scripts: Set<String>): Boolean = when (key) {
        CaptureAccessibilityService.PREF_THEME -> value in setOf(
            CaptureAccessibilityService.THEME_SYSTEM,
            CaptureAccessibilityService.THEME_LIGHT,
            CaptureAccessibilityService.THEME_DARK,
        )
        CaptureAccessibilityService.PREF_OCR_SCRIPT -> value in scripts
        CaptureAccessibilityService.PREF_EDGE_CORNER -> value in setOf(
            CaptureAccessibilityService.CORNER_LEFT,
            CaptureAccessibilityService.CORNER_RIGHT,
            CaptureAccessibilityService.CORNER_BOTH,
        )
        CaptureAccessibilityService.PREF_HISTORY_EXPIRY -> value in setOf(
            CaptureAccessibilityService.HISTORY_EXPIRY_HOUR,
            CaptureAccessibilityService.HISTORY_EXPIRY_DAY,
            CaptureAccessibilityService.HISTORY_EXPIRY_WEEK,
            CaptureAccessibilityService.HISTORY_EXPIRY_NEVER,
        )
        else -> false
    }
}
