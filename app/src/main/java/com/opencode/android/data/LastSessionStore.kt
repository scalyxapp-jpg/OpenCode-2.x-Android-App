package com.opencode.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers where the user was, so relaunching the app does not throw them back
 * to the backend picker with an empty screen.
 *
 * The process can be killed by the system at any time (low memory on a phone),
 * and users expect to land back in the conversation they were in — not to
 * re-select backend, project and session.
 */
object LastSessionStore {
    private const val PREFS = "opencode.last"
    private const val KEY_SESSION = "sessionId"
    private const val KEY_TITLE = "title"
    private const val KEY_DIR = "directory"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences? = prefs

    /**
     * Records the active session. A null [directory] means "unknown, keep what
     * is stored" — writing null here used to erase the remembered project,
     * because the caller (openSession) never knows the directory while
     * /api/session omits it. Losing it made the home screen reopen an arbitrary
     * project after a process death.
     */
    fun save(sessionId: String, title: String?, directory: String? = null) {
        p()?.edit {
            putString(KEY_SESSION, sessionId)
            putString(KEY_TITLE, title)
            if (directory != null) putString(KEY_DIR, directory)
        }
    }

    /**
     * Remembers the project the user was browsing, independently of a session.
     * Null is ignored for the same reason as in [save] — an unresolved
     * directory must never erase a known-good one.
     */
    fun saveDirectory(directory: String?) {
        if (directory == null) return
        p()?.edit { putString(KEY_DIR, directory) }
    }

    fun sessionId(): String? = p()?.getString(KEY_SESSION, null)?.ifBlank { null }

    fun title(): String? = p()?.getString(KEY_TITLE, null)?.ifBlank { null }

    fun directory(): String? = p()?.getString(KEY_DIR, null)?.ifBlank { null }

    fun clear() {
        p()?.edit { clear() }
    }
}
