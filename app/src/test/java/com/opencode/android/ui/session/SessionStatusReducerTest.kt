package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.EventProperties
import com.opencode.android.domain.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The global-feed reducer behind the home list's "running" badge. A session
 * started by the web / TUI / another phone must show as running here, and the
 * badge must clear the moment the server reports it idle.
 */
class SessionStatusReducerTest {
    private fun event(
        type: String,
        sessionId: String? = "ses_a",
        status: SessionStatus? = null,
    ) = Event(type = type, properties = EventProperties(sessionId = sessionId, status = status))

    @Test
    fun `busy adds the session to running and clears a retry`() {
        val next = SessionStatusReducer.reduce(setOf(), setOf("ses_a"), event("session.status", status = SessionStatus("busy")))
        assertEquals(setOf("ses_a"), next?.first)
        assertEquals(emptySet<String>(), next?.second)
    }

    @Test
    fun `retry moves the session to retrying, not running`() {
        val next = SessionStatusReducer.reduce(setOf("ses_a"), setOf(), event("session.status", status = SessionStatus("retry")))
        assertEquals(emptySet<String>(), next?.first)
        assertEquals(setOf("ses_a"), next?.second)
    }

    @Test
    fun `idle clears only that session from both sets`() {
        val next =
            SessionStatusReducer.reduce(
                setOf("ses_a", "ses_x"),
                setOf("ses_a", "ses_b"),
                event("session.status", status = SessionStatus("idle")),
            )
        assertEquals(setOf("ses_x"), next?.first)
        assertEquals(setOf("ses_b"), next?.second)
    }

    @Test
    fun `session dot idle clears both sets`() {
        val next = SessionStatusReducer.reduce(setOf("ses_a"), setOf("ses_a"), event("session.idle"))
        assertEquals(emptySet<String>(), next?.first)
        assertEquals(emptySet<String>(), next?.second)
    }

    @Test
    fun `unrelated events and events without a session id are ignored`() {
        assertNull(SessionStatusReducer.reduce(setOf("ses_a"), setOf(), event("message.part.delta")))
        assertNull(
            SessionStatusReducer.reduce(setOf("ses_a"), setOf(), event("session.status", sessionId = null, status = SessionStatus("busy"))),
        )
        assertNull(SessionStatusReducer.reduce(setOf("ses_a"), setOf(), event("session.status", status = SessionStatus("unknown"))))
    }
}
