package com.opencode.android.ui.explain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explain mode: which agent, which prompt, and how much artifact travels with it. */
class ExplainPolicyTest {
    @Test
    fun `code goes to the docs agent, output and prose to ask`() {
        assertEquals(ExplainPolicy.AGENT_DOCS, ExplainPolicy.agentFor(ExplainPolicy.Kind.CODE))
        assertEquals(ExplainPolicy.AGENT_ASK, ExplainPolicy.agentFor(ExplainPolicy.Kind.TOOL_OUTPUT))
        assertEquals(ExplainPolicy.AGENT_ASK, ExplainPolicy.agentFor(ExplainPolicy.Kind.TEXT))
    }

    @Test
    fun `the prompt carries only the snippet and forbids side effects`() {
        val prompt = ExplainPolicy.prompt(ExplainPolicy.Kind.CODE, "fun f() = 1", "ChatScreen.kt")
        assertTrue(prompt.contains("fun f() = 1"))
        assertTrue(prompt.contains("ChatScreen.kt"))
        assertTrue("no tools / no edits", prompt.contains("Do not use tools"))
    }

    @Test
    fun `a huge artifact is capped and reported as truncated`() {
        val big = "x".repeat(ExplainPolicy.MAX_SNIPPET_CHARS + 100)
        val prompt = ExplainPolicy.prompt(ExplainPolicy.Kind.TOOL_OUTPUT, big)
        assertTrue(prompt.contains("x".repeat(ExplainPolicy.MAX_SNIPPET_CHARS)))
        assertFalse(prompt.contains("x".repeat(ExplainPolicy.MAX_SNIPPET_CHARS + 1)))
        assertTrue(ExplainPolicy.truncated(big))
        assertFalse(ExplainPolicy.truncated("small"))
    }

    @Test
    fun `commands and tool calls go to the ask agent with a learning prompt`() {
        assertEquals(ExplainPolicy.AGENT_ASK, ExplainPolicy.agentFor(ExplainPolicy.Kind.COMMAND))
        assertEquals(ExplainPolicy.AGENT_ASK, ExplainPolicy.agentFor(ExplainPolicy.Kind.TOOL_CALL))

        // "So I can learn from it": the wording has to ask for the parts, the reason and the risk.
        val command = ExplainPolicy.prompt(ExplainPolicy.Kind.COMMAND, "git rebase -i HEAD~3", "shell command")
        assertTrue(command.contains("learn"))
        assertTrue(command.contains("what could go wrong"))
        val call = ExplainPolicy.prompt(ExplainPolicy.Kind.TOOL_CALL, "tool: grep", "tool call: grep")
        assertTrue(call.contains("learn"))
        assertTrue(call.contains("arguments"))
    }

    @Test
    fun `a written diff is explained as code, a command as a command, the rest as a call`() {
        // Code wins: an edit is usually the most interesting thing in a row.
        val edit =
            part(
                "edit",
                """{"status":"completed","input":{"path":"/p/a.kt"},
                    "metadata":{"files":[{"file":"a.kt","patch":"@@ -1,1 +1,1 @@\n-a\n+b\n"}]}}""",
            )
        val code = ExplainPolicy.target(edit)!!
        assertEquals(ExplainPolicy.Kind.CODE, code.kind)
        assertTrue(code.label.contains("code written by edit"))
        assertTrue(code.snippet.contains("@@ -1,1 +1,1 @@"))

        val shell = part("bash", """{"status":"completed","input":{"command":"ls -la"},"output":"total 4"}""")
        val target = ExplainPolicy.target(shell)!!
        assertEquals(ExplainPolicy.Kind.COMMAND, target.kind)
        // The command and its output both travel: the output is what makes the answer useful.
        assertTrue(target.snippet.startsWith("$ ls -la"))
        assertTrue(target.snippet.contains("total 4"))

        val read = part("read", """{"status":"completed","input":{"filePath":"/p/a.kt"},"output":"line"}""")
        val call = ExplainPolicy.target(read)!!
        assertEquals(ExplainPolicy.Kind.TOOL_CALL, call.kind)
        assertTrue(call.snippet.contains("tool: read"))
        assertTrue(call.label.contains("tool call: read"))
    }

    @Test
    fun `a row with nothing in it has nothing to explain`() {
        assertEquals(null, ExplainPolicy.target(part("read", """{"status":"running"}""")))
    }

    private fun part(
        name: String,
        state: String,
    ) = com.opencode.android.domain.Part(
        id = "p1",
        type = "tool",
        tool = name,
        name = name,
        state = kotlinx.serialization.json.Json.parseToJsonElement(state),
    )
}
