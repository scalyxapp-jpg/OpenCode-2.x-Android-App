package com.opencode.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Sessions the user archived, kept on THIS device.
 *
 * Why local: OpenCode 2.x has no server-side archive (`PATCH /api/session/{id}`
 * only accepts a title), so the previous "Archive" action issued a request that
 * changed nothing while the row vanished from the list until the next reload —
 * an action that silently did the opposite of what it promised. Archiving now
 * hides the session locally, which is what the user expects, and the id stays
 * here until it is restored or the session is deleted.
 */
object ArchivedSessionsStore {
    private const val PREFS = "opencode.archived_sessions"
    private const val KEY = "ids"
    private const val SEP = "\n"

    /** Bounded: long-lived installs must not grow the preference without limit. */
    private const val MAX = 500

    @Volatile
    private var prefs: SharedPreferences? = null

    private val lock = Any()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun ids(): Set<String> =
        prefs
            ?.getString(KEY, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun isArchived(sessionId: String): Boolean = sessionId in ids()

    fun archive(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            // Insertion-ordered, newest last; the OLDEST entries fall out past MAX.
            val updated = LinkedHashSet(ids())
            updated.remove(sessionId)
            updated.add(sessionId)
            val iterator = updated.iterator()
            while (updated.size > MAX && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
            prefs?.edit { putString(KEY, updated.joinToString(SEP)) }
        }
    }

    fun unarchive(sessionId: String) {
        synchronized(lock) {
            val updated = ids() - sessionId
            prefs?.edit { putString(KEY, updated.joinToString(SEP)) }
        }
    }

    fun clear() {
        synchronized(lock) { prefs?.edit { remove(KEY) } }
    }
}
