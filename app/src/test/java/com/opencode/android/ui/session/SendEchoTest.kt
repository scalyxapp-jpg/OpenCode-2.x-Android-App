package com.opencode.android.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SendEchoTest {

    @Test
    fun `plain text has no attachment marker`() {
        assertEquals("hello", buildDisplayText("hello", emptyList()))
    }

    @Test
    fun `one attachment appends a marker on a new line`() {
        assertEquals("hello\n[Attached: a.png]", buildDisplayText("hello", listOf("a.png")))
    }

    @Test
    fun `several attachments each get a marker`() {
        assertEquals(
            "hello\n[Attached: a.png]\n[Attached: b.pdf]",
            buildDisplayText("hello", listOf("a.png", "b.pdf")),
        )
    }

    @Test
    fun `attachment-only turn has no leading blank line`() {
        assertEquals("[Attached: a.png]", buildDisplayText("", listOf("a.png")))
    }

    @Test
    fun `optimistic echo is a user text row with the given id and time`() {
        val echo = buildOptimisticEcho(
            sessionId = "ses_1",
            messageId = "local:42",
            displayText = "hi",
            createdAt = 123L,
        )
        assertEquals("local:42", echo.id)
        assertEquals("ses_1", echo.sessionId)
        assertEquals("user", echo.role)
        assertEquals(123L, echo.time?.created)
        assertEquals("hi", echo.parts.first().text)
    }
}
