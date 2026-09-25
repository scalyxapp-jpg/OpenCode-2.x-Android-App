package com.opencode.android.util

import com.opencode.android.util.MessageEcho.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The echo rule decides whether the just-sent bubble survives a refresh. Getting
 * it wrong is a visible flicker (row disappears and returns) or a duplicate
 * (the local row is kept forever because the text never matches).
 */
class MessageEchoTest {

    @Test
    fun `a local row with no server counterpart is kept`() {
        val local = listOf(Row("local_1", "user", "hello"))
        val server = listOf(Row("msg_a", "assistant", "hi"))
        assertEquals(listOf("hello"), MessageEcho.pendingEchoes(local, server).map { it.text })
    }

    @Test
    fun `a local row is dropped once the server echoes the same text`() {
        val local = listOf(Row("local_1", "user", "hello"))
        val server = listOf(Row("msg_b", "user", "hello"))
        assertTrue(MessageEcho.pendingEchoes(local, server).isEmpty())
    }

    @Test
    fun `only local ids are considered echoes`() {
        val local = listOf(Row("msg_real", "user", "hello"))
        assertTrue(MessageEcho.pendingEchoes(local, emptyList()).isEmpty())
        assertTrue(MessageEcho.isLocal(Row("local_9", "user", "x")))
        assertFalse(MessageEcho.isLocal(Row("msg_9", "user", "x")))
        assertFalse(MessageEcho.isLocal(Row(null, "user", "x")))
    }

    @Test
    fun `an assistant row with the same text does not count as the echo`() {
        // The server assigns its own id; only a USER row with the same text
        // proves the message was persisted.
        val local = listOf(Row("local_1", "user", "hello"))
        val server = listOf(Row("msg_c", "assistant", "hello"))
        assertEquals(1, MessageEcho.pendingEchoes(local, server).size)
    }

    @Test
    fun `an empty local text is never kept`() {
        // A blank echo would render as an empty bubble forever.
        val local = listOf(Row("local_1", "user", "   "))
        assertTrue(MessageEcho.pendingEchoes(local, emptyList()).isEmpty())
    }
}
