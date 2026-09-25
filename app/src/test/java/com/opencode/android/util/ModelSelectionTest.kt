package com.opencode.android.util

import com.opencode.android.domain.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate 8 — ModelSelection. Pins the parse/qualify/resolve/label rules and
 * the server-vs-remembered precedence plus the generation/staleness guard that
 * used to live inline in `ChatViewModel`.
 */
class ModelSelectionTest {

    private val models = listOf(
        Model(id = "deepseek-v4-flash", name = "DeepSeek V4 Flash", providerId = "deepseek"),
        Model(id = "nvidia/nemotron-3-super", name = "Nemotron", providerId = "nvidia"),
        Model(id = "gpt-5", name = "GPT-5", providerId = "opencode"),
    )

    @Test
    fun `qualifies a bare id to provider-slash-model`() {
        assertEquals("deepseek/deepseek-v4-flash", ModelSelection.qualify("deepseek-v4-flash", models))
        assertEquals("opencode/gpt-5", ModelSelection.qualify("gpt-5", models))
    }

    @Test
    fun `already-qualified and unknown refs pass through`() {
        assertEquals("x/y", ModelSelection.qualify("x/y", models))
        assertEquals("unknown", ModelSelection.qualify("unknown", models))
    }

    @Test
    fun `session ref does not double a provider already in the id`() {
        assertEquals("nvidia/nemotron-3-super", ModelSelection.sessionRef("nvidia/nemotron-3-super", "nvidia"))
        assertEquals("deepseek/deepseek-v4-flash", ModelSelection.sessionRef("deepseek-v4-flash", "deepseek"))
        assertEquals("bare", ModelSelection.sessionRef("bare", null))
        assertEquals("", ModelSelection.sessionRef(null, "deepseek"))
    }

    @Test
    fun `resolve finds the catalog entry`() {
        assertEquals("nvidia" to "nvidia/nemotron-3-super", ModelSelection.resolve("nvidia/nemotron-3-super", models))
        assertEquals("deepseek" to "deepseek-v4-flash", ModelSelection.resolve("deepseek-v4-flash", models))
    }

    @Test
    fun `friendly name falls back to the ref`() {
        assertEquals("DeepSeek V4 Flash", ModelSelection.friendlyName(models, "deepseek/deepseek-v4-flash"))
        assertEquals("mystery/model", ModelSelection.friendlyName(models, "mystery/model"))
        assertEquals("", ModelSelection.friendlyName(models, null))
    }

    @Test
    fun `load precedence prefers the server model over the remembered one`() {
        assertEquals("server/m", ModelSelection.loadPrecedence("server/m", "remembered/r"))
        assertEquals("remembered/r", ModelSelection.loadPrecedence(null, "remembered/r"))
        assertNull(ModelSelection.loadPrecedence(null, null))
    }

    @Test
    fun `send precedence keeps a pending local choice and otherwise trusts the server`() {
        assertEquals("local", ModelSelection.modelPrecedence(true, "server", "local"))
        assertEquals("server", ModelSelection.modelPrecedence(false, "server", "local"))
        assertEquals("local", ModelSelection.modelPrecedence(false, null, "local"))
        assertEquals("local", ModelSelection.agentPrecedence(true, "serverAgent", "local"))
        assertEquals("serverAgent", ModelSelection.agentPrecedence(false, "serverAgent", "local"))
        assertEquals("local", ModelSelection.variantPrecedence(true, "high", "local"))
        assertEquals("high", ModelSelection.variantPrecedence(false, "high", "local"))
    }

    @Test
    fun `remembered ref rebuilds the qualified key or null when incomplete`() {
        assertEquals(
            "deepseek/deepseek-v4-flash",
            ModelSelection.rememberedRef("deepseek", "deepseek-v4-flash", models),
        )
        assertNull(ModelSelection.rememberedRef(null, "deepseek-v4-flash", models))
        assertNull(ModelSelection.rememberedRef("deepseek", null, models))
    }

    @Test
    fun `stale model sync cannot clear a newer pending flag`() {
        val guard = SelectionGuard()
        val first = guard.beginModelSync()
        assertTrue(guard.isLatestModel(first))
        val second = guard.beginModelSync()
        assertFalse(guard.isLatestModel(first))
        assertTrue(guard.isLatestModel(second))
    }

    @Test
    fun `guard revisions are tracked per session and can be pruned`() {
        val guard = SelectionGuard()
        guard.recordGuardRevision("s1", 7L)
        guard.recordGuardRevision("s2", 9L)
        assertEquals(7L, guard.guardRevision("s1") ?: -1L)
        guard.retainOnly("s1")
        assertNull(guard.guardRevision("s2"))
        assertEquals(7L, guard.guardRevision("s1") ?: -1L)
        guard.clearGuardRevision("s1")
        assertNull(guard.guardRevision("s1"))
    }
}
