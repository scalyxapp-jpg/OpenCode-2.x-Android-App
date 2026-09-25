package com.opencode.android.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences for the home screen (pinned sessions + local projects).
 *
 * Resolved by [com.opencode.android.AppStartup] on a background dispatcher so
 * the initial disk load never happens on the main thread; the UI is gated on
 * AppStartup.ready, so every read afterwards is in-memory.
 */
object HomePrefs {
    private const val PREFS = "opencode_home"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun p(): SharedPreferences? = prefs

    private const val KEY_ONLY_GUARDED = "only_guarded"

    fun onlyGuarded(): Boolean = prefs?.getBoolean(KEY_ONLY_GUARDED, false) ?: false

    fun setOnlyGuarded(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ONLY_GUARDED, value)?.apply()
    }
}
