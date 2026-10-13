package com.opencode.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt history rules (TUI parity: the TUI recalls previous prompts; these are the
 * rules for what is kept and how it is shown).
 */
class PromptHistoryPolicyTest {
    private fun entry(text: String, session: String = "ses_1", at: Long = 1L) =
        PromptHistoryEntry(text = text, sessionId = session, at = at)

    @Test
    fun `a recorded prompt lands on top`() {
        val list = PromptHistoryPolicy.record(listOf(entry("old")), "new", "ses_1", 2L)
        assertEquals(listOf("new", "old"), list.map { it.text })
    }

    @Test
    fun `resending the newest prompt moves it up instead of duplicating it`() {
        val list =
            PromptHistoryPolicy.record(
                listOf(entry("new", at = 2L), entry("old", at = 1L)),
                "new",
                "ses_1",
                3L,
            )
        assertEquals(listOf("new", "old"), list.map { it.text })
        assertEquals(3L, list.first().at)
    }

    @Test
    fun `the same prompt in another session is its own entry`() {
        val list =
            PromptHistoryPolicy.record(
                listOf(entry("same", "ses_a")),
                "same",
                "ses_b",
                5L,
            )
        assertEquals(2, list.size)
    }

    @Test
    fun `blank prompts are not recorded`() {
        val list = PromptHistoryPolicy.record(emptyList(), "   ", "ses_1", 1L)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `the history is capped and keeps the newest entries`() {
        var list = emptyList<PromptHistoryEntry>()
        repeat(PromptHistoryPolicy.MAX_ENTRIES + 10) { index ->
            list = PromptHistoryPolicy.record(list, "prompt $index", "ses_1", index.toLong())
        }
        assertEquals(PromptHistoryPolicy.MAX_ENTRIES, list.size)
        assertEquals("prompt 109", list.first().text)
    }

    @Test
    fun `the visible list is newest first, distinct and scoped`() {
        val entries =
            listOf(
                entry("a", "ses_1", 1L),
                entry("b", "ses_2", 3L),
                entry("a", "ses_2", 2L),
            )
        assertEquals(listOf("b", "a"), PromptHistoryPolicy.visible(entries, null, "").map { it.text })
        assertEquals(listOf("a"), PromptHistoryPolicy.visible(entries, "ses_1", "").map { it.text })
    }

    @Test
    fun `search filters case-insensitively`() {
        val entries = listOf(entry("Deploy the app"), entry("run tests"))
        assertEquals(
            listOf("Deploy the app"),
            PromptHistoryPolicy.visible(entries, null, "DEPLOY").map { it.text },
        )
    }
}

/** Persistence format: a plain JSON list; garbage must not break startup. */
class PromptHistoryCodecTest {
    @Test
    fun `round trips entries`() {
        val entries =
            listOf(
                PromptHistoryEntry(text = "do the thing", sessionId = "ses_1", at = 42L),
                PromptHistoryEntry(text = "and then this", sessionId = "ses_2", at = 43L),
            )
        assertEquals(entries, PromptHistoryCodec.decode(PromptHistoryCodec.encode(entries)))
    }

    @Test
    fun `garbage decodes to an empty history`() {
        assertEquals(emptyList<PromptHistoryEntry>(), PromptHistoryCodec.decode("[oops"))
        assertEquals(emptyList<PromptHistoryEntry>(), PromptHistoryCodec.decode(null))
    }
}
