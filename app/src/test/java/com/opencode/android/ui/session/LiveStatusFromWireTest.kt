package com.opencode.android.ui.session

import com.opencode.android.domain.Model
import com.opencode.android.ui.liveStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproduces the real OpenCode 2.0.26 wire frames (captured from a live turn) and
 * asserts what the live status row derives from them.
 *
 * This server sends NO `message.part.updated`: tools arrive as `session.tool.*`,
 * text as `session.text.delta`, reasoning as `session.reasoning.delta`. The test
 * pins that a running tool becomes a TOOL status instead of "waiting".
 */
class LiveStatusFromWireTest {
    private val models =
        listOf(Model(id = "deepseek-v4-flash", name = "V4 Flash", providerId = "deepseek"))

    private val trace =
        listOf(
            """{"type":"session.step.started","data":{"sessionID":"ses_1","agent":"build","model":{"id":"deepseek-v4-flash","providerID":"deepseek"},"assistantMessageID":"msg_1","started":1}}""",
            """{"type":"session.tool.input.started","data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1","name":"shell"}}""",
            """{"type":"session.tool.input.ended","data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1","text":"{\"command\": \"ls -la\"}"}}""",
            """{"type":"session.tool.called","data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1","input":{"command":"ls -la"},"executed":false}}""",
        )

    @Test
    fun `a running tool from the real wire frames yields a TOOL status`() {
        var state = StreamState()
        for (raw in trace) {
            val event = V2EventNormalizer.normalize(raw)
            requireNotNull(event) { "frame did not normalise: $raw" }
            println(
                "normalised type=${event.type} sessionId=${event.properties?.sessionId} " +
                    "deliver=${SseFilter.shouldDeliver(event, "ses_1")}",
            )
            state = StreamReducer.reduce(state, event, models) { null }
        }
        println(
            "normalised tool events -> parts=" +
                state.live.parts.map {
                    it.tool to
                        com.opencode.android.ui
                            .toolStatus(it)
                },
        )

        val status =
            liveStatus(
                connected = true,
                compacting = false,
                thinking = state.live.thinking,
                waiting =
                    state.live.response.isBlank() &&
                        state.live.reasoning.isBlank() &&
                        state.live.parts.isEmpty(),
                parts = state.live.parts,
                streamingChars = state.live.response.length,
            )
        println("derived status=$status")
        assertEquals(com.opencode.android.ui.LiveStatusKind.TOOL, status.kind)
    }
}
