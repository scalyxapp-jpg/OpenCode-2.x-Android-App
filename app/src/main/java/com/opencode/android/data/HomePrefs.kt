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

    // The other two filter chips are persisted the same way: they reset on every
    // app start, so a user who works with "Running" or "Archived" had to re-tap it
    // each time.
    private const val KEY_ONLY_RUNNING = "only_running"
    private const val KEY_SHOW_ARCHIVED = "show_archived"

    fun onlyRunning(): Boolean = prefs?.getBoolean(KEY_ONLY_RUNNING, false) ?: false

    fun setOnlyRunning(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ONLY_RUNNING, value)?.apply()
    }

    fun showArchived(): Boolean = prefs?.getBoolean(KEY_SHOW_ARCHIVED, false) ?: false

    fun setShowArchived(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SHOW_ARCHIVED, value)?.apply()
    }

    // Display-name overrides per project directory. The server's project payload has
    // no name field (the app derived one from the path), so a rename could never show
    // up — it is kept here instead, for local AND server projects.
    private const val KEY_PROJECT_NAMES = "project_names"

    fun projectNames(): Map<String, String> {
        val raw = prefs?.getString(KEY_PROJECT_NAMES, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optString(it, "") }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setProjectName(directory: String, name: String?) {
        if (directory.isBlank()) return
        val obj = org.json.JSONObject()
        for ((key, value) in projectNames()) {
            if (key != directory) obj.put(key, value)
        }
        if (!name.isNullOrBlank()) obj.put(directory, name)
        prefs?.edit()?.putString(KEY_PROJECT_NAMES, obj.toString())?.apply()
    }
}
