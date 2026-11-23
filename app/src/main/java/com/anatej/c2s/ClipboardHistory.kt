package com.anatej.c2s

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Local, session-free history of text copied by this app. */
object ClipboardHistory {

    private const val MAX = 20

    data class Entry(val text: String, val time: Long)

    fun add(prefs: SharedPreferences, text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val entries = load(prefs).toMutableList()
        if (entries.firstOrNull()?.text == value) return
        entries.add(0, Entry(value, System.currentTimeMillis()))
        save(prefs, entries.take(MAX))
    }

    fun load(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val text = obj.optString("text")
                if (text.isBlank()) null else Entry(text, obj.optLong("time"))
            }
        }.getOrDefault(emptyList())
    }

    fun clear(prefs: SharedPreferences) = save(prefs, emptyList())

    private fun save(prefs: SharedPreferences, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("text", entry.text).put("time", entry.time))
        }
        prefs.edit()
            .putString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, array.toString())
            .apply()
    }
}
