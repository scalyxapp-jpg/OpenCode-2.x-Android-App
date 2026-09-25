package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.EventProperties
import com.opencode.android.domain.Model
import com.opencode.android.domain.Part
import com.opencode.android.domain.PartTime
import com.opencode.android.domain.SessionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate 2 — StreamReducer. The pure event->state transition extracted from
 * `ChatViewModel.startSse`. Each test pins one failure mode that was previously
 * only observable against a live server.
 */
class StreamReducerTest {

    private val models = listOf(
        Model(id = "nvidia/nemotron-3-super", name = "Nemotron", providerId = "nvidia"),
    )

    private val noError: (kotlinx.serialization.json.JsonElement?) -> String? = { null }

    private fun reduce(
        state: StreamState,
        event: Event,
        friendlyError: (kotlinx.serialization.json.JsonElement?) -> String? = noError,
    ) = StreamReducer.reduce(state, event, models, friendlyError)

    private fun props(
        partId: String? = null,
        field: String? = null,
        delta: String? = null,
        part: Part? = null,
        info: com.opencode.android.domain.MessageInfo? = null,
        status: com.opencode.android.domain.SessionStatus? = null,
        error: kotlinx.serialization.json.JsonElement? = null,
    ) = EventProperties(
        partId = partId,
        field = field,
        delta = delta,
        part = part,
        info = info,
        status = status,
        error = error,
    )

    @Test
    fun `delta appends to the matching pending buffer and asks for a flush`() {
        val event = Event(
            type = "message.part.delta",
            properties = props(partId = "p1", field = "text", delta = "hel"),
        )
        val next = reduce(StreamState(), event)
        assertEquals("hel", next.pendingText)
        assertEquals("", next.pendingReasoning)
        assertTrue(next.effects.contains(StreamEffect.ScheduleFlush))

        val more = reduce(next.copy(effects = emptyList()), event.copy(properties = props(partId = "p1", field = "text", delta = "lo")))
        assertEquals("hello", more.pendingText)
    }

    @Test
    fun `delta remembers the part type from a previous snapshot`() {
        val started = reduce(
            StreamState(),
            Event(
                type = "message.part.updated",
                properties = props(part = Part(id = "p1", type = "reasoning", text = "why", time = PartTime(start = 1))),
            ),
        )
        assertTrue(started.live.thinking)
        val delta = reduce(
            started.copy(effects = emptyList()),
            Event(type = "message.part.delta", properties = props(partId = "p1", field = null, delta = "!")),
        )
        assertEquals("!", delta.pendingReasoning)
    }

    @Test
    fun `text snapshot is authoritative and drops queued deltas`() {
        val state = StreamState(pendingText = "partial")
        val next = reduce(
            state,
            Event(
                type = "message.part.updated",
                properties = props(part = Part(id = "p1", type = "text", text = "hello", time = PartTime(start = 1))),
            ),
        )
        assertEquals("hello", next.live.response)
        assertEquals("", next.pendingText)
        assertTrue(next.isGenerating)
        assertNull(next.statusError)
    }

    @Test
    fun `reasoning snapshot clears thinking when finished`() {
        val next = reduce(
            StreamState(),
            Event(
                type = "message.part.updated",
                properties = props(part = Part(id = "p1", type = "reasoning", text = "done", time = PartTime(start = 1, end = 2))),
            ),
        )
        assertEquals("done", next.live.reasoning)
        assertEquals(false, next.live.thinking)
    }

    @Test
    fun `tool snapshot replaces the same part id instead of duplicating`() {
        val first = reduce(
            StreamState(),
            Event(type = "message.part.updated", properties = props(part = Part(id = "t1", type = "tool", tool = "bash"))),
        )
        assertEquals(1, first.live.parts.size)
        val second = reduce(
            first.copy(effects = emptyList()),
            Event(type = "message.part.updated", properties = props(part = Part(id = "t1", type = "tool", tool = "read"))),
        )
        assertEquals(1, second.live.parts.size)
        assertEquals("read", second.live.parts.single().tool)
    }

    @Test
    fun `step-finish finalizes and reconciles with meta`() {
        val next = reduce(
            StreamState(),
            Event(
                type = "message.part.updated",
                properties = props(part = Part(id = "sf", type = "step-finish", time = PartTime(end = 9))),
            ),
        )
        assertTrue(next.effects.contains(StreamEffect.Finalize))
        assertTrue(next.effects.contains(StreamEffect.Reconcile(includeMeta = true)))
        assertTrue(next.effects.contains(StreamEffect.LoadPendingQuestions))
    }

    @Test
    fun `session error resets live and surfaces the friendly message`() {
        val state = StreamState(live = com.opencode.android.ui.LiveStreamState(response = "half"), isGenerating = true)
        val next = reduce(
            state,
            Event(
                type = "session.error",
                properties = props(error = kotlinx.serialization.json.JsonPrimitive("boom")),
            ),
            friendlyError = { "friendly boom" },
        )
        assertEquals("", next.live.response)
        assertEquals(false, next.isGenerating)
        assertEquals("friendly boom", next.statusError)
        assertTrue(next.effects.any { it is StreamEffect.NotifyError && it.message == "friendly boom" })
        assertTrue(next.effects.contains(StreamEffect.Reconcile(includeMeta = true)))
    }

    @Test
    fun `retry status is shown under the composer`() {
        val next = reduce(
            StreamState(),
            Event(
                type = "session.status",
                properties = props(status = com.opencode.android.domain.SessionStatus(type = "retry", message = "nope")),
            ),
        )
        assertTrue(next.isGenerating)
        assertEquals("nope", next.statusError)
    }

    @Test
    fun `message updated resolves the selected model`() {
        val next = reduce(
            StreamState(),
            Event(
                type = "message.updated",
                properties = props(
                    info = com.opencode.android.domain.MessageInfo(
                        role = "assistant",
                        agent = "build",
                        model = SessionModel(providerID = "nvidia", modelID = "nvidia/nemotron-3-super"),
                    ),
                ),
            ),
        )
        assertEquals("nvidia/nemotron-3-super", next.selectedModel)
        assertEquals("build", next.live.agent)
        assertNotNull(next.live.model)
    }

    @Test
    fun `connection events toggle sseConnected and reconcile on connect`() {
        val connected = reduce(StreamState(), Event(type = SseEventDecoder.SSE_CONNECTED))
        assertTrue(connected.sseConnected)
        assertTrue(connected.effects.contains(StreamEffect.Reconcile(includeMeta = true)))

        val dropped = reduce(connected.copy(effects = emptyList()), Event(type = SseEventDecoder.SSE_DISCONNECTED))
        assertEquals(false, dropped.sseConnected)
    }
}
