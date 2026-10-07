package com.opencode.android.util

import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageInfo
import com.opencode.android.domain.MessageTime
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The locally built cursor must byte-for-byte match the server's, otherwise a
 * fallback cursor would be rejected and "load older" would break differently.
 */
class MessageCursorTest {
    @Test
    fun `matches a real server cursor`() {
        // From GET /session/{id}/message?limit=10 -> x-next-cursor
        val expected = "eyJpZCI6Im1zZ18xMDlhYWZmMWQwMDFoUjhxZlowZmoyZFVBYiIsInRpbWUiOjE3OTExNjM1NjM4MDZ9"
        val message =
            Message(
                id = "msg_109aaff1d001hR8qfZ0fj2dUAb",
                time = MessageTime(created = 1791163563806L),
            )
        assertEquals(expected, MessageCursor.forMessage(message))
    }

    @Test
    fun `falls back to the nested info id and time`() {
        val message =
            Message(
                info = MessageInfo(id = "msg_x", time = MessageTime(created = 123L)),
            )
        val cursor = MessageCursor.forMessage(message)
        assertNotNull(cursor)
        assertEquals("""{"id":"msg_x","time":123}""", decode(cursor!!))
    }

    @Test
    fun `returns null without an id or time`() {
        assertNull(MessageCursor.forMessage(Message(id = "m")))
        assertNull(MessageCursor.forMessage(Message(time = MessageTime(created = 1L))))
    }

    private fun decode(cursor: String): String = (cursor + "=".repeat((4 - cursor.length % 4) % 4)).decodeBase64()!!.utf8()
}
