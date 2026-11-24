package com.anatej.c2s

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Local clipboard history stored outside settings backup and Auto Backup. */
object ClipboardHistory {

    private const val MAX = 20
    private const val MAX_TEXT = 4_000

    data class Entry(val text: String, val time: Long)

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CaptureAccessibilityService.PRIVATE_PREFS, Context.MODE_PRIVATE)

    fun isEnabled(settings: SharedPreferences): Boolean =
        settings.getBoolean(CaptureAccessibilityService.PREF_HISTORY_ENABLED, false)

    fun add(settings: SharedPreferences, history: SharedPreferences, text: String) {
        if (!isEnabled(settings)) return
        val value = text.trim().take(MAX_TEXT)
        if (value.isEmpty()) return
        val entries = load(history, settings).toMutableList()
        entries.removeAll { it.text == value }
        entries.add(0, Entry(value, System.currentTimeMillis()))
        save(history, entries.take(MAX))
    }

    fun load(history: SharedPreferences, settings: SharedPreferences): List<Entry> {
        val raw = history.getString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, null)
            ?: return emptyList()
        val cutoff = expiryCutoff(settings)
        val entries = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val text = obj.optString("text").take(MAX_TEXT)
                val time = obj.optLong("time")
                if (text.isBlank() || time < cutoff) null else Entry(text, time)
            }
        }.getOrDefault(emptyList())
        if (entries.size != runCatching { JSONArray(raw).length() }.getOrDefault(entries.size)) {
            save(history, entries)
        }
        return entries
    }

    fun clear(history: SharedPreferences) = save(history, emptyList())

    private fun expiryCutoff(settings: SharedPreferences): Long {
        val duration = when (settings.getString(
            CaptureAccessibilityService.PREF_HISTORY_EXPIRY,
            CaptureAccessibilityService.HISTORY_EXPIRY_DAY
        )) {
            CaptureAccessibilityService.HISTORY_EXPIRY_HOUR -> 60 * 60 * 1000L
            CaptureAccessibilityService.HISTORY_EXPIRY_WEEK -> 7 * 24 * 60 * 60 * 1000L
            CaptureAccessibilityService.HISTORY_EXPIRY_NEVER -> Long.MAX_VALUE
            else -> 24 * 60 * 60 * 1000L
        }
        return if (duration == Long.MAX_VALUE) Long.MIN_VALUE
        else System.currentTimeMillis() - duration
    }

    /** Moves any v1.1.0 history out of the general preferences file once. */
    fun migrateLegacy(context: Context) {
        val history = prefs(context)
        if (history.contains(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY)) return
        val settings = context.getSharedPreferences(CaptureAccessibilityService.PREFS, Context.MODE_PRIVATE)
        val legacy = settings.getString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, null)
            ?: return
        history.edit().putString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, legacy).apply()
        settings.edit().remove(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY).apply()
    }

    private fun save(history: SharedPreferences, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("text", entry.text).put("time", entry.time))
        }
        history.edit()
            .putString(CaptureAccessibilityService.PREF_CLIPBOARD_HISTORY, array.toString())
            .apply()
    }
}
