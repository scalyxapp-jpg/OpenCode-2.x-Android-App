package com.opencode.android.data

import com.opencode.android.domain.Session
import kotlinx.serialization.Serializable

/**
 * A project row as persisted by the home cache. Mirrors the UI's `HomeProject`
 * without depending on it, so the data layer stays free of UI types.
 */
@Serializable
data class CachedProject(
    val id: String? = null,
    val name: String,
    val directory: String,
    val isLocal: Boolean = false,
)

/**
 * The last-known home screen: the project list, which project was selected, and
 * that project's newest sessions.
 *
 * Only the selected project's sessions are stored: that is what the list shows
 * on launch, and keeping every project's list would grow the cache for no
 * benefit (switching project refreshes from the network anyway).
 */
@Serializable
data class HomeSnapshot(
    val projects: List<CachedProject> = emptyList(),
    val selectedDirectory: String? = null,
    val sessions: List<Session> = emptyList(),
) {
    /** Keeps only the newest [maxSessions] (the list is newest-first). */
    fun bounded(maxSessions: Int = MAX_CACHED_SESSIONS): HomeSnapshot =
        if (sessions.size <= maxSessions) this else copy(sessions = sessions.take(maxSessions))

    companion object {
        const val MAX_CACHED_SESSIONS = 80
    }
}

/**
 * The home screen's offline cache, as a seam.
 *
 * [HomeCache] is the on-disk implementation; tests can provide their own.
 * Without it a cold start showed an empty project list and a skeleton until the
 * network answered.
 */
interface HomeStore {
    /** The cached home snapshot, or null when there is nothing usable. */
    suspend fun read(): HomeSnapshot?

    /** Persists the home snapshot. Best-effort. */
    suspend fun write(snapshot: HomeSnapshot)

    /** Drops the cache (e.g. when switching backend). */
    suspend fun clearAll()
}
