package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.EventProperties
import com.opencode.android.domain.Model
import com.opencode.android.domain.Part
import com.opencode.android.domain.PartTime
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate 2 — SessionStreamer. Drives the streamer with a fake [EventSource]
 * (the seam that lets the SSE client be replaced in tests) and pins the
 * coalescing + persist lifecycle that used to be inline in the ViewModel.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStreamerTest {
    private val models = emptyList<Model>()
    private val noError: (JsonElement?) -> String? = { null }

    // Message events carry the session id on the wire (SseFilter drops
    // session-less non-broadcast events); the helpers mirror that.
    private fun delta(
        id: String,
        field: String,
        value: String,
    ) = Event(
        type = "message.part.delta",
        properties = EventProperties(sessionId = "s1", partId = id, field = field, delta = value),
    )

    private fun snapshot(
        id: String,
        type: String,
        text: String?,
        end: Long? = null,
    ) = Event(
        type = "message.part.updated",
        properties =
            EventProperties(
                sessionId = "s1",
                part = Part(id = id, type = type, text = text, time = PartTime(start = 1, end = end)),
            ),
    )

    @Test
    fun `coalesces text deltas into the live response`() =
        runTest {
            val source =
                EventSource { _, _ ->
                    flow {
                        emit(delta("p", "text", "he"))
                        emit(delta("p", "text", "llo"))
                    }
                }
            val signals = mutableListOf<StreamEffect>()
            val streamer = SessionStreamer(source, this, { models }, noError) { signals += it }
            streamer.start("s1", "http://x")
            advanceUntilIdle()
            assertEquals("hello", streamer.liveState.value.response)
            // Deltas alone coalesce into the buffer; the generating flag is owned
            // by the snapshot/turn events, matching the pre-extraction behaviour.
            assertTrue(signals.none { it is StreamEffect.GeneratingChanged && it.value })
        }

    @Test
    fun `snapshot clears queued deltas and is authoritative`() =
        runTest {
            val source =
                EventSource { _, _ ->
                    flow {
                        emit(delta("p", "text", "partial"))
                        emit(snapshot("p", "text", "final"))
                    }
                }
            val streamer = SessionStreamer(source, this, { models }, noError) {}
            streamer.start("s1", "http://x")
            advanceUntilIdle()
            assertEquals("final", streamer.liveState.value.response)
        }

    @Test
    fun `accept is synchronous apart from the coalesced flush`() =
        runTest {
            val source = EventSource { _, _ -> flowOf() }
            val streamer = SessionStreamer(source, this, { models }, noError) {}
            streamer.accept(snapshot("p", "text", "hi"))
            assertEquals("hi", streamer.liveState.value.response)
        }

    @Test
    fun `finalize arms persist and complete clears the live buffer`() =
        runTest {
            val source = EventSource { _, _ -> flowOf() }
            val streamer = SessionStreamer(source, this, { models }, noError) {}
            streamer.accept(snapshot("p", "text", "answer"))
            streamer.finalize()
            assertTrue(streamer.projection.value.pendingPersist)
            // A step finalize must NOT clear the turn-level running flag: the
            // composer keeps showing "stop" between steps of one turn.
            assertTrue(streamer.projection.value.isGenerating)
            assertFalse(streamer.liveState.value.streaming)
            streamer.completePersist()
            assertFalse(streamer.projection.value.pendingPersist)
            assertEquals("", streamer.liveState.value.response)
        }

    @Test
    fun `endTurn clears the running flag`() =
        runTest {
            val source = EventSource { _, _ -> flowOf() }
            val streamer = SessionStreamer(source, this, { models }, noError) {}
            streamer.accept(snapshot("p", "text", "answer"))
            assertTrue(streamer.projection.value.isGenerating)
            streamer.endTurn()
            assertFalse(streamer.projection.value.isGenerating)
            assertFalse(streamer.liveState.value.streaming)
            // The partial answer stays on screen until the persisted message lands.
            assertTrue(streamer.projection.value.pendingPersist)
        }

    @Test
    fun `a new step cancels the previous step's persist timeout`() =
        runTest {
            val source = EventSource { _, _ -> flowOf() }
            val streamer = SessionStreamer(source, this, { models }, noError) {}
            // Step 1 streams and finishes: finalize arms the 3s persist timeout.
            streamer.accept(snapshot("p1", "text", "first"))
            streamer.accept(
                Event(
                    type = "message.part.updated",
                    properties =
                        EventProperties(
                            sessionId = "s1",
                            part = Part(id = "sf1", type = "step-finish", time = PartTime(start = 1, end = 9)),
                        ),
                ),
            )
            assertTrue(streamer.projection.value.pendingPersist)
            // Step boundary: streaming stops, but the turn keeps running so the
            // stop button does not flicker back to "send".
            assertFalse(streamer.liveState.value.streaming)
            assertTrue(streamer.projection.value.isGenerating)
            // Step 2 starts and streams before that timeout fires.
            streamer.accept(
                Event(
                    type = "message.part.updated",
                    properties = EventProperties(sessionId = "s1", part = Part(id = "ss2", type = "step-start")),
                ),
            )
            streamer.accept(snapshot("p2", "text", "second"))
            // Advance past the stale persist window; without the cancellation it
            // would clear step 2's live buffers.
            advanceUntilIdle()
            assertTrue(streamer.projection.value.isGenerating)
            assertFalse(streamer.projection.value.pendingPersist)
            assertEquals("second", streamer.liveState.value.response)
        }

    @Test
    fun `finalize on an empty stream is a no-op on live state`() =
        runTest {
            val source = EventSource { _, _ -> flowOf() }
            val streamer = SessionStreamer(source, this, { models }, noError) {}
            streamer.finalize()
            assertFalse(streamer.projection.value.pendingPersist)
            assertEquals("", streamer.liveState.value.response)
        }
}
