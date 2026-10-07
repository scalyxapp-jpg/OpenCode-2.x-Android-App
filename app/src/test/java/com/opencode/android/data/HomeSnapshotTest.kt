package com.opencode.android.data

import com.opencode.android.domain.Session
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The home cache snapshot must survive a disk round-trip and stay bounded, so a
 * cold start can paint the last-known projects + sessions without a network
 * call.
 */
class HomeSnapshotTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `round-trips projects, selection and sessions`() {
        val snapshot =
            HomeSnapshot(
                projects =
                    listOf(
                        CachedProject(id = "p1", name = "app", directory = "/work/app"),
                        CachedProject(name = "local", directory = "/tmp/x", isLocal = true),
                    ),
                selectedDirectory = "/work/app",
                sessions = listOf(Session(id = "ses_1", title = "Hello")),
            )

        val decoded = json.decodeFromString<HomeSnapshot>(json.encodeToString(HomeSnapshot.serializer(), snapshot))

        assertEquals(snapshot, decoded)
        assertEquals("p1", decoded.projects.first().id)
        assertEquals("/work/app", decoded.selectedDirectory)
        assertEquals("ses_1", decoded.sessions.single().id)
    }

    @Test
    fun `bounded keeps only the newest sessions`() {
        val sessions = List(100) { Session(id = "ses_$it") }
        val snapshot = HomeSnapshot(sessions = sessions)

        val bounded = snapshot.bounded(maxSessions = 80)

        assertEquals(80, bounded.sessions.size)
        // The list is newest-first, so the first 80 are kept.
        assertEquals("ses_0", bounded.sessions.first().id)
        assertEquals("ses_79", bounded.sessions.last().id)
    }

    @Test
    fun `bounded is a no-op when under the cap`() {
        val snapshot = HomeSnapshot(sessions = List(3) { Session(id = "s$it") })
        assertEquals(snapshot, snapshot.bounded(maxSessions = 80))
    }
}
