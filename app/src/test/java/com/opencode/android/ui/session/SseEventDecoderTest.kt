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
}
