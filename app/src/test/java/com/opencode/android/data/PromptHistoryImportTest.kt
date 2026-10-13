package com.opencode.android.data

import com.opencode.android.domain.ContentPart
import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageTime
import com.opencode.android.domain.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backfill rules: the history is filled from what THIS app sent, so the server has to be
 * asked for the rest (web/TUI prompts, anything older). These are the rules for which
 * messages count and in which order they land in the history.
 */
class PromptHistoryImportTest {
    private fun user(text: String, at: Long = 1L, v2: Boolean = false) =
        if (v2) {
            Message(id = "m$at", type = "user", text = text, time = MessageTime(created = at))
        } else {
            Message(
                id = "m$at",
                role = "user",
                parts = listOf(Part(id = "p", type = "text", text = text)),
                time = MessageTime(created = at),
            )
        }

    private fun assistant(text: String) = Message(id = "a", type = "assistant", text = text)

    @Test
    fun `only user messages are imported, from both schemas`() {
        val messages =
            listOf(
                user("classic prompt", at = 1),
                assistant("an answer"),
                user("v2 prompt", at = 2, v2 = true),
            )
        assertEquals(listOf("classic prompt", "v2 prompt"), PromptHistoryImport.prompts(messages))
    }

    @Test
    fun `attachment-only turns are skipped`() {
        val messages = listOf(Message(id = "m", role = "user", content = listOf(ContentPart(type = "file"))))
        assertTrue(PromptHistoryImport.prompts(messages).isEmpty())
    }

    @Test
    fun `consecutive repeats collapse`() {
        val messages = listOf(user("same", at = 1), user("same", at = 2), user("other", at = 3))
        assertEquals(listOf("same", "other"), PromptHistoryImport.prompts(messages))
    }

    @Test
    fun `merge puts the newest prompt on top and keeps real times`() {
        val messages = listOf(user("first", at = 100), user("second", at = 200))
        val merged = PromptHistoryImport.merge(emptyList(), messages, "ses_1")
        assertEquals(listOf("second", "first"), merged.map { it.text })
        assertEquals(200L, merged.first().at)
        assertEquals("ses_1", merged.first().sessionId)
    }

    @Test
    fun `merge keeps what the app already recorded`() {
        val existing = listOf(PromptHistoryEntry("app prompt", "ses_1", 300L))
        val merged = PromptHistoryImport.merge(existing, listOf(user("from web", at = 200)), "ses_1")
        assertEquals(listOf("app prompt", "from web"), merged.map { it.text })
    }

    @Test
    fun `a missing timestamp still yields a stable order`() {
        val messages =
            listOf(
                Message(id = "1", role = "user", parts = listOf(Part(id = "a", type = "text", text = "one"))),
                Message(id = "2", role = "user", parts = listOf(Part(id = "b", type = "text", text = "two"))),
            )
        val merged = PromptHistoryImport.merge(emptyList(), messages, "ses_1")
        assertEquals(listOf("two", "one"), merged.map { it.text })
    }
}
