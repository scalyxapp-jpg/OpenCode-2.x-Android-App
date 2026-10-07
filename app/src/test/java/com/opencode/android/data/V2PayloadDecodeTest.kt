package com.opencode.android.data

import com.opencode.android.domain.MessageListResponse
import com.opencode.android.domain.SessionListResponse
import com.opencode.android.ui.session.SseEventDecoder
import com.opencode.android.ui.session.V2EventNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the app's decoders against the OpenCode 2.x wire shapes captured from the
 * live server (`/api/session`, `/api/session/{id}/message`, `/api/event`).
 *
 * A single field the model cannot decode makes the whole page decode fail, and
 * `loadMessages` then opens a blank session — the exact regression this guards.
 */
class V2PayloadDecodeTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    @Test
    fun `decodes a v2 session list with nested location`() {
        val text =
            """
            {"data":[
              {"id":"ses_1","projectID":"p1","agent":"build",
               "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"low"},
               "cost":0.01,
               "tokens":{"input":10,"output":2,"reasoning":3,"cache":{"read":5,"write":0}},
               "time":{"created":1,"updated":2},
               "title":"Hello",
               "location":{"directory":"/home/user/project"}}
            ],"cursor":{"previous":"a","next":"b"}}
            """.trimIndent()

        val page = json.decodeFromString(SessionListResponse.serializer(), text)

        assertEquals(1, page.data.size)
        val s = page.data.first()
        assertEquals("ses_1", s.id)
        assertEquals("/home/user/project", s.location?.directory)
        assertEquals("build", s.agent)
        assertEquals("deepseek", s.model?.providerID)
        assertEquals("b", page.cursor?.next)
    }

    @Test
    fun `decodes a v2 message page including reasoning, text and tool parts`() {
        val text =
            """
            {"data":[
              {"id":"m1","type":"user","time":{"created":1},"text":"hi","files":[],"agents":[]},
              {"id":"m2","type":"assistant","time":{"created":2,"streamed":3},"agent":"build",
               "model":{"id":"m","providerID":"pp","variant":"low"},
               "content":[
                 {"type":"reasoning","text":"thinking"},
                 {"type":"text","text":"hello"},
                 {"type":"tool","id":"call_1","name":"read",
                  "state":{"status":"completed","input":{"path":"a"},"content":[{"type":"text","text":"ok"}]},
                  "time":{"created":3,"completed":4}}
               ],
               "cost":0.002,
               "tokens":{"input":5,"output":6,"reasoning":7,"cache":{"read":8,"write":0}}}
            ],"cursor":{"previous":"older","next":null}}
            """.trimIndent()

        val page = json.decodeFromString(MessageListResponse.serializer(), text)

        assertEquals(2, page.data.size)
        val user = page.data[0]
        assertEquals("user", user.type)
        assertEquals("hi", user.text)
        val assistant = page.data[1]
        assertEquals("assistant", assistant.type)
        assertEquals("pp", assistant.model?.providerID)
        assertEquals(listOf("reasoning", "text", "tool"), assistant.content.map { it.type })
        assertEquals("hello", assistant.content[1].text)
        assertEquals("call_1", assistant.content[2].id)
        assertEquals("read", assistant.content[2].name)
        assertEquals("older", page.cursor?.previous)
    }

    @Test
    fun `normalises v2 step, text, tool and status events`() {
        // session.step.started carries the model/agent the turn runs with.
        val started =
            SseEventDecoder.decode(
                """{"id":"e1","type":"session.step.started","data":{"sessionID":"ses_1","assistantMessageID":"msg_1","agent":"build","model":{"id":"m","providerID":"pp","variant":"low"}}}""",
            )
        assertEquals("session.next.step.started", started?.type)
        assertEquals("ses_1", started?.properties?.sessionId)
        assertEquals("build", started?.data?.agent)
        assertEquals("pp", started?.data?.model?.providerID)

        // session.text.delta streams into the existing delta branch.
        val delta =
            SseEventDecoder.decode(
                """{"id":"e2","type":"session.text.delta","data":{"sessionID":"ses_1","assistantMessageID":"msg_1","textID":"p1","delta":"Hel"}}""",
            )
        assertEquals("message.part.delta", delta?.type)
        assertEquals("text", delta?.properties?.field)
        assertEquals("Hel", delta?.properties?.delta)
        assertEquals("msg_1", delta?.properties?.messageId)

        // session.tool.success maps onto a completed tool part.
        val tool =
            SseEventDecoder.decode(
                """{"id":"e3","type":"session.tool.success","data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1","content":[{"type":"text","text":"ok"}],"metadata":{"status":"completed"}}}""",
            )
        assertEquals("message.part.updated", tool?.type)
        assertEquals("tool", tool?.properties?.part?.type)
        assertEquals("call_1", tool?.properties?.part?.id)
        assertTrue(
            tool
                ?.properties
                ?.part
                ?.state
                .toString()
                .contains("completed"),
        )

        // session.status running is mapped to the app's "busy".
        val status =
            SseEventDecoder.decode(
                """{"id":"e4","type":"session.status","data":{"sessionID":"ses_1","status":{"type":"running"}}}""",
            )
        assertEquals("session.status", status?.type)
        assertEquals("busy", status?.properties?.status?.type)

        // An idle turn finishes.
        val idle = SseEventDecoder.decode("""{"id":"e5","type":"session.idle","data":{"sessionID":"ses_1"}}""")
        assertEquals("session.idle", idle?.type)

        // Comments / non-event frames and unknown types do not throw.
        assertNull(V2EventNormalizer.normalize("not json"))
    }
}
