package com.opencode.android.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventDecoderTest {
    @Test
    fun `decodes the payload envelope shape`() {
        val json =
            """{"payload":{"id":"evt_1","type":"session.status","properties":{"sessionID":"s1"}}}"""
        val event = SseEventDecoder.decode(json)
        assertEquals("session.status", event?.type)
        assertEquals("s1", event?.properties?.sessionId)
    }

    @Test
    fun `malformed frame decodes to null instead of throwing`() {
        assertNull(SseEventDecoder.decode("not json at all"))
        assertNull(SseEventDecoder.decode(""))
        assertNull(SseEventDecoder.decode("""{"payload":null}"""))
    }

    @Test
    fun `a permission request carries its request id`() {
        // The ambient "Waiting for you · N" badge and the auto-approve path both need the
        // request id: the event used to keep only the session id, so the badge could never
        // increment and unattended auto-approval had nothing to answer.
        val asked =
            SseEventDecoder.decode(
                """{"type":"permission.asked","data":{"sessionID":"s1","id":"per_1"}}""",
            )
        assertEquals("permission.asked", asked?.type)
        assertEquals("s1", asked?.properties?.sessionId)
        assertEquals("per_1", asked?.properties?.requestId)

        val replied =
            SseEventDecoder.decode(
                """{"type":"permission.replied","data":{"sessionID":"s1","id":"per_1"}}""",
            )
        assertEquals("permission.replied", replied?.type)
        assertEquals("per_1", replied?.properties?.requestId)
    }

    @Test
    fun `a delivered queued prompt surfaces as an inbox change`() {
        // The queue lives in the session INBOX, not in the message list: the chat
        // re-reads it when the server delivers / drops / steers an item.
        for (type in listOf("session.inbox.delivered", "session.inbox.cancelled", "session.inbox.delivery.changed")) {
            val event =
                SseEventDecoder.decode(
                    """{"type":"$type","data":{"sessionID":"s1"}}""",
                )
            assertEquals("session.inbox.changed", event?.type)
            assertEquals("s1", event?.properties?.sessionId)
        }
    }
}
