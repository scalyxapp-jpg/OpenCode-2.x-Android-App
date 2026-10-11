package com.opencode.android.ui.session

import com.opencode.android.domain.CommandEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "/" command handling: every server command must be reachable, and a
 * "/name args" line must run through the server's command endpoint (which expands
 * the template) instead of being posted as literal text.
 */
class SlashCommandTest {
    private val commands =
        listOf(
            CommandEntry(name = "test", description = "Run tests"),
            CommandEntry(name = "review", description = "Review"),
            CommandEntry(name = "compact", description = "built-in", source = "builtin"),
        )

    @Test
    fun `server command with arguments is detected`() {
        val parsed = parseSlashCommand("/test --coverage", commands)
        assertEquals("test", parsed?.name)
        assertEquals("--coverage", parsed?.arguments)
    }

    @Test
    fun `bare server command has empty arguments`() {
        assertEquals(SlashCommand("review", ""), parseSlashCommand("/review", commands))
    }

    @Test
    fun `client built-ins stay on the client`() {
        assertNull(parseSlashCommand("/compact", commands))
    }

    @Test
    fun `unknown or non-command text stays ordinary input`() {
        assertNull(parseSlashCommand("/etc/hosts", commands))
        assertNull(parseSlashCommand("/nope", commands))
        assertNull(parseSlashCommand("hello", commands))
        assertNull(parseSlashCommand("/", commands))
        assertNull(parseSlashCommand("/ test", commands))
    }

    @Test
    fun `filtering never truncates and prefers prefix matches`() {
        assertEquals(3, filterCommands(commands, "").size)
        assertEquals(listOf("review"), filterCommands(commands, "rev").map { it.name })
        assertEquals(
            listOf("coverage", "code"),
            filterCommands(
                listOf(CommandEntry("coverage"), CommandEntry("code")),
                "co",
            ).map { it.name },
        )
        assertTrue(filterCommands(commands, "zzz").isEmpty())
    }
}
