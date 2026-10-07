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
    private val models =
        listOf(
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
        messageId: String? = null,
    ) = EventProperties(
        partId = partId,
        field = field,
        delta = delta,
        part = part,
        info = info,
        status = status,
        error = error,
        messageId = messageId,
    )

    @Test
    fun `delta appends to the matching pending buffer and asks for a flush`() {
        val event =
            Event(
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
        val started =
            reduce(
                StreamState(),
                Event(
                    type = "message.part.updated",
                    properties = props(part = Part(id = "p1", type = "reasoning", text = "why", time = PartTime(start = 1))),
                ),
            )
        assertTrue(started.live.thinking)
        val delta =
            reduce(
                started.copy(effects = emptyList()),
                Event(type = "message.part.delta", properties = props(partId = "p1", field = null, delta = "!")),
            )
        assertEquals("!", delta.pendingReasoning)
    }

    @Test
    fun `text snapshot is authoritative and drops queued deltas`() {
        val state = StreamState(pendingText = "partial")
        val next =
            reduce(
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
        val next =
            reduce(
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
        val first =
            reduce(
                StreamState(),
                Event(type = "message.part.updated", properties = props(part = Part(id = "t1", type = "tool", tool = "bash"))),
            )
        assertEquals(1, first.live.parts.size)
        val second =
            reduce(
                first.copy(effects = emptyList()),
                Event(type = "message.part.updated", properties = props(part = Part(id = "t1", type = "tool", tool = "read"))),
            )
        assertEquals(1, second.live.parts.size)
        assertEquals(
            "read",
            second.live.parts
                .single()
                .tool,
        )
    }

    @Test
    fun `step-finish finalizes and reconciles with meta`() {
        val next =
            reduce(
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
    fun `a step boundary keeps the turn running and only stops streaming`() {
        // The composer's send/stop button reads `isGenerating`. A multi-step
        // turn fires step-finish after EVERY step, so clearing the running flag
        // there made the button flicker back to "send" mid-turn.
        val busy =
            reduce(
                StreamState(),
                Event(
                    type = "session.status",
                    properties =
                        props(
                            status =
                                com.opencode.android.domain
                                    .SessionStatus(type = "busy"),
                        ),
                ),
            )
        assertTrue(busy.isGenerating)

        val finished =
            reduce(
                busy.copy(effects = emptyList()),
                Event(
                    type = "message.part.updated",
                    properties = props(part = Part(id = "sf", type = "step-finish", time = PartTime(end = 9))),
                ),
            )
        assertTrue("step boundary must not end the turn", finished.isGenerating)
        assertEquals(false, finished.isStreaming)

        // Only the turn-level idle signal clears it.
        val idle =
            reduce(
                finished.copy(effects = emptyList()),
                Event(
                    type = "session.status",
                    properties =
                        props(
                            status =
                                com.opencode.android.domain
                                    .SessionStatus(type = "idle"),
                        ),
                ),
            )
        assertEquals(false, idle.isGenerating)
        assertTrue(idle.turnEnded)
        assertTrue(idle.effects.contains(StreamEffect.EndTurn))
    }

    @Test
    fun `session error resets live and surfaces the friendly message`() {
        val state =
            StreamState(
                live =
                    com.opencode.android.ui
                        .LiveStreamState(response = "half"),
                isGenerating = true,
            )
        val next =
            reduce(
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
        val next =
            reduce(
                StreamState(),
                Event(
                    type = "session.status",
                    properties =
                        props(
                            status =
                                com.opencode.android.domain
                                    .SessionStatus(type = "retry", message = "nope"),
                        ),
                ),
            )
        assertTrue(next.isGenerating)
        assertEquals("nope", next.statusError)
    }

    @Test
    fun `message updated resolves the selected model`() {
        val next =
            reduce(
                StreamState(),
                Event(
                    type = "message.updated",
                    properties =
                        props(
                            info =
                                com.opencode.android.domain.MessageInfo(
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
    fun `the user's echoed part is never rendered as the live assistant response`() {
        // Real server sequence (captured live): message.updated(user) ->
        // message.part.updated(text, messageID=user) -> message.updated(assistant)
        // -> step-start(assistant) -> text(assistant). Applying the user part put
        // the prompt into the live response buffer, so it appeared as an extra
        // assistant bubble until step-start reset it.
        val afterUserMsg =
            reduce(
                StreamState(),
                Event(
                    type = "message.updated",
                    properties =
                        props(
                            info =
                                com.opencode.android.domain.MessageInfo(
                                    id = "msg_user",
                                    role = "user",
                                ),
                        ),
                ),
            )
        val afterUserPart =
            reduce(
                afterUserMsg,
                Event(
                    type = "message.part.updated",
                    properties =
                        props(
                            part =
                                Part(
                                    id = "p1",
                                    type = "text",
                                    text = "my prompt",
                                    messageId = "msg_user",
                                ),
                        ),
                ),
            )
        assertEquals("", afterUserPart.live.response)

        // Assistant parts still stream normally.
        val afterAssistant =
            reduce(
                afterUserPart,
                Event(
                    type = "message.part.updated",
                    properties =
                        props(
                            part =
                                Part(
                                    id = "p2",
                                    type = "text",
                                    text = "hi",
                                    messageId = "msg_asst",
                                ),
                        ),
                ),
            )
        assertEquals("hi", afterAssistant.live.response)
    }

    @Test
    fun `a user message delta is ignored`() {
        val state =
            reduce(
                StreamState(),
                Event(
                    type = "message.updated",
                    properties =
                        props(
                            info =
                                com.opencode.android.domain.MessageInfo(
                                    id = "msg_user",
                                    role = "user",
                                ),
                        ),
                ),
            )
        val next =
            reduce(
                state,
                Event(
                    type = "message.part.delta",
                    properties =
                        props(
                            partId = "p1",
                            field = "text",
                            delta = "prompt",
                            messageId = "msg_user",
                        ),
                ),
            )
        assertEquals("", next.pendingText)
    }

    @Test
    fun `replaying the real turn sequence never puts the prompt in the live response`() {
        // Exact order captured from a live opencode 1.18.34 turn. The user's
        // text part is echoed by the server BEFORE the assistant's step-start;
        // the old reducer wrote it straight into live.response.
        var s = StreamState()

        fun step(
            type: String,
            properties: EventProperties,
        ) {
            s = reduce(s, Event(type = type, properties = properties))
        }

        step(
            "message.updated",
            props(
                info =
                    com.opencode.android.domain
                        .MessageInfo(id = "msg_user", role = "user"),
            ),
        )
        step(
            "message.part.updated",
            props(part = Part(id = "p_user", type = "text", text = "Reply with exactly: hi", messageId = "msg_user")),
        )
        assertEquals("", s.live.response)

        step(
            "message.updated",
            props(
                info =
                    com.opencode.android.domain
                        .MessageInfo(id = "msg_asst", role = "assistant"),
            ),
        )
        step(
            "session.status",
            props(
                status =
                    com.opencode.android.domain
                        .SessionStatus(type = "busy"),
            ),
        )
        step("message.part.updated", props(part = Part(id = "p_step", type = "step-start", messageId = "msg_asst")))
        step("message.part.updated", props(part = Part(id = "p_text", type = "text", text = "", messageId = "msg_asst")))
        step("message.part.delta", props(partId = "p_text", field = "text", delta = "hi", messageId = "msg_asst"))
        assertEquals("hi", s.pendingText)
        step("message.part.updated", props(part = Part(id = "p_text", type = "text", text = "hi", messageId = "msg_asst")))
        assertEquals("hi", s.live.response)

        step(
            "session.status",
            props(
                status =
                    com.opencode.android.domain
                        .SessionStatus(type = "idle"),
            ),
        )
        step("session.idle", props())
        assertTrue(s.turnEnded)
        assertEquals("hi", s.live.response)
    }

    @Test
    fun `connection events toggle sseConnected and reconcile on connect`() {
        val connected = reduce(StreamState(), Event(type = SseEventDecoder.SSE_CONNECTED))
        assertTrue(connected.sseConnected)
        assertTrue(connected.effects.contains(StreamEffect.Reconcile(includeMeta = true)))

        val dropped = reduce(connected.copy(effects = emptyList()), Event(type = SseEventDecoder.SSE_DISCONNECTED))
        assertEquals(false, dropped.sseConnected)
    }

    @Test
    fun `late message frames after session idle cannot re-arm generating`() {
        // Classic servers can deliver a trailing message.part.updated after
        // session.idle. That used to re-arm isGenerating and re-add the old tool
        // rows, leaving the session stuck on "generating" until the user aborted.
        val idle = reduce(StreamState(), Event(type = "session.idle"))
        assertTrue(idle.turnEnded)

        val late =
            reduce(
                idle.copy(effects = emptyList()),
                Event(
                    type = "message.part.updated",
                    properties =
                        props(
                            part = Part(id = "t1", type = "tool", tool = "bash", time = PartTime(start = 1)),
                        ),
                ),
            )
        assertEquals(idle.isGenerating, late.isGenerating)
        assertEquals(0, late.live.parts.size)

        // A new turn clears the guard.
        val busy =
            reduce(
                late.copy(effects = emptyList()),
                Event(
                    type = "session.status",
                    properties =
                        EventProperties(
                            status =
                                com.opencode.android.domain
                                    .SessionStatus(type = "busy"),
                        ),
                ),
            )
        assertEquals(false, busy.turnEnded)
        assertTrue(busy.isGenerating)

        val fresh =
            reduce(
                busy.copy(effects = emptyList()),
                Event(
                    type = "message.part.updated",
                    properties =
                        props(
                            part = Part(id = "t2", type = "tool", tool = "bash", time = PartTime(start = 2)),
                        ),
                ),
            )
        assertTrue(fresh.live.parts.any { it.id == "t2" })
    }

    @Test
    fun `late final text part after idle still shows the summary without re-arming`() {
        // The server can finalize the last text part AFTER session.idle. That
        // frame carries the summary (and recovers deltas dropped by the bounded
        // SSE buffer), so it must land even though the turn ended.
        val idle = reduce(StreamState(), Event(type = "session.idle"))
        assertTrue(idle.turnEnded)

        val late =
            reduce(
                idle.copy(effects = emptyList()),
                Event(
                    type = "message.part.updated",
                    properties =
                        props(
                            part =
                                Part(
                                    id = "txt1",
                                    type = "text",
                                    text = "Here is the summary",
                                    time = PartTime(start = 1, end = 2),
                                ),
                        ),
                ),
            )
        assertEquals("Here is the summary", late.live.response)
        // The turn is still over — the summary must not flip the session back
        // into "generating".
        assertEquals(false, late.isGenerating)
        assertTrue(late.turnEnded)
    }

    @Test
    fun `classic step-start resets the live step so tools do not accumulate`() {
        // Server 1.18.31 sends the classic schema: no session.next.step.started,
        // steps arrive as message.part.updated step-start parts. Without the
        // reset, every tool call of the whole turn piled up in live.parts.
        val afterTool =
            reduce(
                StreamState(),
                Event(
                    type = "message.part.updated",
                    properties =
                        props(
                            part = Part(id = "tool1", type = "tool", tool = "bash", time = PartTime(start = 1)),
                        ),
                ),
            )
        assertTrue(afterTool.live.parts.any { it.id == "tool1" })
        assertTrue(afterTool.isGenerating)

        val nextStep =
            reduce(
                afterTool.copy(effects = emptyList()),
                Event(
                    type = "message.part.updated",
                    properties = props(part = Part(id = "s2", type = "step-start")),
                ),
            )
        assertEquals(0, nextStep.live.parts.size)
        assertEquals("", nextStep.live.response)
        assertEquals("", nextStep.live.reasoning)
        assertTrue(nextStep.isGenerating)
    }

    @Test
    fun `question tool part requests a pending-question load`() {
        // The question tool blocks the turn. Its part can land before the
        // pending request is readable, so the reducer must nudge a load even
        // when the legacy question.asked bus event never reaches the app.
        val next =
            reduce(
                StreamState(),
                Event(
                    type = "message.part.updated",
                    properties = props(part = Part(id = "p1", type = "tool", tool = "question", time = PartTime(start = 1))),
                ),
            )
        assertTrue(next.effects.contains(StreamEffect.LoadPendingQuestions))
        assertTrue(next.isGenerating)
    }

    @Test
    fun `non-question tool part does not request a pending-question load`() {
        val next =
            reduce(
                StreamState(),
                Event(
                    type = "message.part.updated",
                    properties = props(part = Part(id = "p1", type = "tool", tool = "bash", time = PartTime(start = 1))),
                ),
            )
        assertEquals(false, next.effects.contains(StreamEffect.LoadPendingQuestions))
    }

    @Test
    fun `question asked event requests a pending-question load`() {
        val next = reduce(StreamState(), Event(type = "question.asked"))
        assertTrue(next.effects.contains(StreamEffect.LoadPendingQuestions))
        assertTrue(next.isGenerating)
    }

    @Test
    fun `question asked notifies as a question, not as done`() {
        val next =
            reduce(
                StreamState(),
                Event(
                    type = "question.asked",
                    properties = EventProperties(sessionId = "ses_1", questionId = "que_1"),
                ),
            )
        assertTrue(next.effects.any { it is StreamEffect.NotifyQuestion })
        assertTrue(next.effects.none { it == StreamEffect.NotifyDone })
    }

    @Test
    fun `real question asked frame decodes into the pending request`() {
        // Exact shape captured from the running server's /global/event stream.
        val raw =
            """{"directory":"/x","project":"global","payload":{"id":"evt_1","type":"question.asked",""" +
                """"properties":{"id":"que_1","sessionID":"ses_1","questions":[{"question":"Choose between Option A and Option B.","header":"Choose option","options":[{"label":"Option A","description":"Select Option A"},{"label":"Option B","description":"Select Option B"}]}],"tool":{"messageID":"msg_1","callID":"call_1"}}}}"""
        val event = SseEventDecoder.decode(raw)
        assertNotNull(event)
        assertEquals("question.asked", event!!.type)
        val next = reduce(StreamState(), event)
        val asked = next.effects.filterIsInstance<StreamEffect.QuestionAsked>().single()
        assertEquals("que_1", asked.question.id)
        assertEquals("ses_1", asked.question.sessionId)
        assertEquals(
            "Choose between Option A and Option B.",
            asked.question.questions
                .single()
                .question,
        )
        assertEquals(
            "Option A",
            asked.question.questions
                .single()
                .options
                .first()
                .label,
        )
        assertEquals("call_1", asked.question.tool?.callID)
    }

    @Test
    fun `question asked event carries the full pending request`() {
        val next =
            reduce(
                StreamState(),
                Event(
                    type = "question.asked",
                    properties =
                        EventProperties(
                            sessionId = "ses_1",
                            questionId = "que_1",
                            questions =
                                listOf(
                                    com.opencode.android.domain.QuestionItem(
                                        question = "Pick one",
                                        header = "Pick",
                                        options =
                                            listOf(
                                                com.opencode.android.domain
                                                    .QuestionOption(label = "A"),
                                                com.opencode.android.domain
                                                    .QuestionOption(label = "B"),
                                            ),
                                    ),
                                ),
                            tool =
                                com.opencode.android.domain.QuestionToolRef(
                                    messageID = "msg_1",
                                    callID = "call_1",
                                ),
                        ),
                ),
            )
        val asked = next.effects.filterIsInstance<StreamEffect.QuestionAsked>().single()
        assertEquals("que_1", asked.question.id)
        assertEquals("ses_1", asked.question.sessionId)
        assertEquals(
            "Pick one",
            asked.question.questions
                .single()
                .question,
        )
        assertEquals("call_1", asked.question.tool?.callID)
        assertTrue(next.effects.contains(StreamEffect.LoadPendingQuestions))
    }

    @Test
    fun `v2 question asked uses requestID and replies resolve the request`() {
        val asked =
            reduce(
                StreamState(),
                Event(
                    type = "question.v2.asked",
                    properties = EventProperties(sessionId = "ses_1", requestId = "que_2"),
                ),
            )
        assertEquals(
            "que_2",
            asked.effects
                .filterIsInstance<StreamEffect.QuestionAsked>()
                .single()
                .question.id,
        )

        val replied =
            reduce(
                StreamState(),
                Event(type = "question.v2.replied", properties = EventProperties(requestId = "que_2")),
            )
        assertEquals(
            "que_2",
            replied.effects
                .filterIsInstance<StreamEffect.QuestionResolved>()
                .single()
                .requestId,
        )
    }

    @Test
    fun `v2 question events request a pending-question load`() {
        // The server emits the v2 event names; the v1 names are defined but
        // never emitted, so both must be handled.
        val asked = reduce(StreamState(), Event(type = "question.v2.asked"))
        assertTrue(asked.effects.contains(StreamEffect.LoadPendingQuestions))
        assertTrue(asked.isGenerating)

        val replied = reduce(StreamState(), Event(type = "question.v2.replied"))
        assertTrue(replied.effects.contains(StreamEffect.LoadPendingQuestions))

        val rejected = reduce(StreamState(), Event(type = "question.v2.rejected"))
        assertTrue(rejected.effects.contains(StreamEffect.LoadPendingQuestions))
    }
}
