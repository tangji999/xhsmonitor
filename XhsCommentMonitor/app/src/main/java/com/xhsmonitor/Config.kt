package com.xhsmonitor

import android.content.Context
import org.json.JSONArray

object Config {
    private const val PREF = "xhs_monitor"
    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_SELECTORS = "selectors"
    private const val KEY_RUNNING = "running"
    private const val KEY_HUMAN_MODE = "human_mode"

    const val KEY_INTERVAL = "interval"
    const val KEY_MAX_NOTES = "max_notes"
    const val KEY_REPORT_MIN = "report_min"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getKeywords(c: Context): MutableList<String> {
        val raw = prefs(c).getString(KEY_KEYWORDS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) list.add(s)
        }
        return list
    }

    fun setKeywords(c: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.trim()) }
        prefs(c).edit().putString(KEY_KEYWORDS, arr.toString()).apply()
    }

    fun getInt(c: Context, key: String, def: Int): Int = prefs(c).getInt(key, def)

    fun setInt(c: Context, key: String, value: Int) {
        prefs(c).edit().putInt(key, value).apply()
    }

    fun isRunning(c: Context): Boolean = prefs(c).getBoolean(KEY_RUNNING, false)

    fun setRunning(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_RUNNING, value).apply()
    }

    fun isHumanMode(c: Context): Boolean = prefs(c).getBoolean(KEY_HUMAN_MODE, true)

    fun setHumanMode(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_HUMAN_MODE, value).apply()
    }

    fun getSelectors(c: Context): Selectors =
        Selectors.fromJson(prefs(c).getString(KEY_SELECTORS, null))

    fun setSelectors(c: Context, s: Selectors) {
        prefs(c).edit().putString(KEY_SELECTORS, s.toJson()).apply()
    }
}
