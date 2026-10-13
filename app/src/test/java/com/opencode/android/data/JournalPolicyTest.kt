package com.opencode.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision journal rules: per project, newest first, one row per decision, and the export
 * reads like a log — while the hand-over is recorded so a row can show it.
 */
class JournalPolicyTest {
    private fun entry(
        id: String,
        dir: String = "/p/one",
        at: Long = 1L,
        text: String = "text $id",
    ) = JournalEntry(id = id, projectDir = dir, text = text, createdAt = at)

    @Test
    fun `the journal is scoped to one project and newest first`() {
        val entries = listOf(entry("a", at = 1), entry("b", at = 3), entry("c", dir = "/p/two", at = 9))
        assertEquals(listOf("b", "a"), JournalPolicy.visible(entries, "/p/one").map { it.id })
    }

    @Test
    fun `capturing keeps the per-project cap`() {
        var entries = emptyList<JournalEntry>()
        repeat(JournalPolicy.MAX_ENTRIES_PER_PROJECT + 5) { i ->
            entries = JournalPolicy.add(entries, entry("e$i", at = i.toLong()))
        }
        val mine = entries.filter { it.projectDir == "/p/one" }
        assertEquals(JournalPolicy.MAX_ENTRIES_PER_PROJECT, mine.size)
        assertTrue("newest kept", mine.any { it.id == "e${JournalPolicy.MAX_ENTRIES_PER_PROJECT + 4}" })
    }

    @Test
    fun `handing a decision over is recorded once per session`() {
        val entries = JournalPolicy.markInjected(listOf(entry("a")), "a", "ses_1")
        assertEquals(listOf("ses_1"), entries.first().injectedInto)
        val again = JournalPolicy.markInjected(entries, "a", "ses_1")
        assertEquals(listOf("ses_1"), again.first().injectedInto)
        val second = JournalPolicy.markInjected(again, "a", "ses_2")
        assertEquals(listOf("ses_1", "ses_2"), second.first().injectedInto)
    }

    @Test
    fun `search filters decisions`() {
        val entries =
            listOf(
                entry("a", text = "Keep storage local"),
                entry("b", text = "Use Gradle offline"),
            )
        assertEquals(
            listOf("a"),
            JournalPolicy.visible(entries, "/p/one", "storage").map { it.id },
        )
    }

    @Test
    fun `the project key falls back instead of returning nothing`() {
        // A session from the offline cache has no directory; a null key dropped the decision
        // silently (the reported "decisions are not persisted").
        assertEquals("/p/one", JournalPolicy.projectKey("/p/one", "/p/one/sub"))
        assertEquals("/p/one", JournalPolicy.projectKey(null, "/p/one"))
        assertEquals("", JournalPolicy.projectKey(null, null))
        assertEquals("", JournalPolicy.projectKey("   ", "  "))
    }

    @Test
    fun `global decisions never disappear from a project view`() {
        val entries =
            listOf(
                JournalEntry(id = "g", projectDir = "", text = "global", createdAt = 5L),
                JournalEntry(id = "p", projectDir = "/p/one", text = "project", createdAt = 6L),
                JournalEntry(id = "o", projectDir = "/p/two", text = "other", createdAt = 7L),
            )
        // A project shows its own decisions plus the global ones (nothing captured is hidden).
        assertEquals(
            listOf("p", "g"),
            JournalPolicy.visible(entries, "/p/one", "").map { it.id },
        )
        // Without a project context (offline session) everything is shown.
        assertEquals(
            listOf("o", "p", "g"),
            JournalPolicy.visible(entries, null, "").map { it.id },
        )
        assertEquals(
            listOf("o", "p", "g"),
            JournalPolicy.visible(entries, "", "").map { it.id },
        )
    }

    @Test
    fun `the export is markdown and oldest first`() {
        val entries =
            listOf(
                entry("a", at = 1_700_000_000_000, text = "first"),
                entry("b", at = 1_700_100_000_000, text = "second"),
            )
        val markdown = JournalPolicy.export(entries, "projekt")
        assertTrue(markdown.startsWith("## Decisions — projekt"))
        assertTrue(markdown.indexOf("first") < markdown.indexOf("second"))
        assertEquals("", JournalPolicy.export(emptyList(), "projekt"))
    }
}
