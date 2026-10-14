package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The label the background notifications show for a finished tool call. */
class ToolTextTest {
    @Test
    fun `the truncation note reads like the server and the TUI`() {
        // Wording taken from the opencode bundle ("... output truncated ...", "omitted N
        // characters"), so a capped row reads like the TUI instead of an app invention.
        val note = ToolText.truncationSuffix(1_234)
        assertEquals("… output truncated … (omitted 1234 characters)", note)
    }

    @Test
    fun `known tools are named like the reference clients`() {
        // Names taken from the server's string catalogue (`ui.tool.*` in the opencode
        // bundle): the app used its own "Search"/"List"/"Subagent"/"Web" names, which
        // appear nowhere there.
        assertEquals("Shell", ToolText.toolTitle("bash"))
        assertEquals("Read", ToolText.toolTitle("read"))
        assertEquals("Edit", ToolText.toolTitle("multiedit"))
        assertEquals("Write", ToolText.toolTitle("write"))
        assertEquals("Patch", ToolText.toolTitle("apply_patch"))
        assertEquals("Grep", ToolText.toolTitle("grep"))
        assertEquals("Glob", ToolText.toolTitle("glob"))
        assertEquals("List", ToolText.toolTitle("list"))
        assertEquals("Task", ToolText.toolTitle("task"))
        assertEquals("Skill", ToolText.toolTitle("skill"))
        assertEquals("Webfetch", ToolText.toolTitle("webfetch"))
        assertEquals("Web Search", ToolText.toolTitle("websearch"))
    }

    @Test
    fun `unknown tools keep their id`() {
        assertEquals("mytool", ToolText.toolTitle("mytool"))
        assertEquals("Tool", ToolText.toolTitle(null))
    }

    @Test
    fun `the label carries the work, collapsed and capped`() {
        assertEquals("Shell · ls -la", ToolText.notificationLabel("bash", "ls -la"))
        assertEquals("Read", ToolText.notificationLabel("read", null))
        val long = "x".repeat(200)
        val label = ToolText.notificationLabel("bash", long)
        assertEquals(48, label.substringAfter("· ").length)
        assertEquals(true, label.endsWith("…"))
    }

    @Test
    fun `newlines in a command do not break the one-line heads-up`() {
        assertEquals("Shell · echo a b", ToolText.notificationLabel("bash", "echo a\n  b"))
    }
}
