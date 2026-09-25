package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.EventProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SseFilterTest {

    private fun event(type: String?, sessionId: String? = null) =
        Event(type = type, properties = sessionId?.let { EventProperties(sessionId = it) })

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
}
