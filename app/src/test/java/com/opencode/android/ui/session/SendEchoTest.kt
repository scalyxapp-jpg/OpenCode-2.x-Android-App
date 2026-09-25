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

    @Test
    fun `no uploads leaves the prompt unchanged`() {
        assertEquals("hello", buildPromptText("hello", emptyList()))
    }

    @Test
    fun `uploaded paths are appended under an Attached files header`() {
        assertEquals(
            "hello\n\nAttached files:\n- /tmp/a.png\n- /tmp/b.pdf",
            buildPromptText("hello", listOf("/tmp/a.png", "/tmp/b.pdf")),
        )
    }

    @Test
    fun `attachment-only turn does not start with a blank line`() {
        assertEquals(
            "Attached files:\n- /tmp/a.png",
            buildPromptText("", listOf("/tmp/a.png")),
        )
    }
}
