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
}
