package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.EventProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SseFilterTest {
    private fun event(
        type: String?,
        sessionId: String? = null,
    ) = Event(type = type, properties = sessionId?.let { EventProperties(sessionId = it) })

    @Test
    fun `drops ignored broadcast types`() {
        assertFalse(SseFilter.shouldDeliver(event("sync"), "s1"))
        assertFalse(SseFilter.shouldDeliver(event("server.heartbeat"), "s1"))
        assertFalse(SseFilter.shouldDeliver(event("server.connected"), "s1"))
        assertFalse(SseFilter.shouldDeliver(event("tui.toast.show"), "s1"))
    }

    @Test
    fun `keeps events for the active session`() {
        assertTrue(SseFilter.shouldDeliver(event("message.part.delta", "s1"), "s1"))
    }

    @Test
    fun `keeps events with no session id`() {
        assertTrue(SseFilter.shouldDeliver(event("sse.connected"), "s1"))
    }

    @Test
    fun `drops events for another session`() {
        assertFalse(SseFilter.shouldDeliver(event("message.part.delta", "s2"), "s1"))
    }

    @Test
    fun `a global consumer receives every session's running state`() {
        // The home list subscribes with an empty session id to track badges for
        // all sessions; session-scoped status events must not be dropped.
        assertTrue(SseFilter.shouldDeliver(event("session.status", "s2"), ""))
        assertTrue(SseFilter.shouldDeliver(event("session.idle", "s2"), ""))
        assertTrue(SseFilter.shouldDeliver(event("sse.connected"), ""))
    }

    @Test
    fun `a global consumer ignores other sessions' message traffic`() {
        assertFalse(SseFilter.shouldDeliver(event("message.part.delta", "s2"), ""))
        assertFalse(SseFilter.shouldDeliver(event("message.updated", "s2"), ""))
    }
}
