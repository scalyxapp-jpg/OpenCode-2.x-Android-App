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
    private val models =
        listOf(
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

    @Test
    fun `a bare model id carrying its own namespace resolves to the real provider`() {
        // Nvidia serves ids like "z-ai/glm-5.3" and
        // "deepseek-ai/deepseek-v4.1-flash": the model's OWN namespace, not a
        // provider prefix. Splitting at the first slash sent provider "z-ai".
        val catalog =
            listOf(
                Model(id = "z-ai/glm-5.3", name = "GLM 5.3", providerId = "nvidia"),
                Model(id = "deepseek-v4-flash", name = "V4 Flash", providerId = "deepseek"),
            )

        assertEquals("nvidia" to "z-ai/glm-5.3", ModelSelection.resolve("z-ai/glm-5.3", catalog))
        assertEquals(
            "nvidia" to "z-ai/glm-5.3",
            ModelSelection.resolve("nvidia/z-ai/glm-5.3", catalog),
        )
        assertEquals("GLM 5.3", ModelSelection.friendlyName(catalog, "nvidia/z-ai/glm-5.3"))
        assertEquals("nvidia/z-ai/glm-5.3", ModelSelection.qualify("z-ai/glm-5.3", catalog))
        // A genuinely unknown ref still falls back to the historical split.
        assertEquals("mystery" to "model", ModelSelection.resolve("mystery/model", catalog))
    }

    @Test
    fun `same model id under two providers stays selectable and distinguishable`() {
        val shared = "deepseek-v4-flash"
        val catalog =
            listOf(
                Model(id = shared, name = "DeepSeek V4 Flash", providerId = "opencode-go"),
                Model(id = shared, name = "DeepSeek V4 Flash", providerId = "deepseek"),
            )
        val providers =
            listOf(
                com.opencode.android.domain
                    .ProviderEntry(id = "opencode-go", name = "OpenCode Go"),
                com.opencode.android.domain
                    .ProviderEntry(id = "deepseek", name = "DeepSeek"),
            )

        // Selection: the same bare id under both providers must resolve per provider.
        assertEquals(
            "opencode-go" to shared,
            ModelSelection.resolve("opencode-go/$shared", catalog),
        )
        assertEquals(
            "deepseek" to shared,
            ModelSelection.resolve("deepseek/$shared", catalog),
        )
        // A row is "selected" only for the provider that is actually active, even
        // when the session named the model bare.
        assertTrue(ModelSelection.sameModel(catalog, "deepseek/$shared", "deepseek/$shared"))
        assertFalse(ModelSelection.sameModel(catalog, "deepseek/$shared", "opencode-go/$shared"))
        assertTrue("bare ref still matches its qualified row", ModelSelection.sameModel(catalog, shared, "opencode-go/$shared"))

        // Display: the shared name gains a provider suffix so it is unambiguous.
        assertEquals(
            "DeepSeek V4 Flash · DeepSeek",
            ModelSelection.activeLabel(catalog, "deepseek/$shared", providers),
        )
        // A unique name is left alone.
        val unique = listOf(Model(id = "only-one", name = "Unique", providerId = "deepseek"))
        assertEquals("Unique", ModelSelection.activeLabel(unique, "deepseek/only-one", providers))
    }

    @Test
    fun `an explicit provider is not rewritten by a tail match`() {
        // A bare catalog entry (the flattened /api/model list) carries no
        // providerId; resolving a provider-qualified ref must keep that provider
        // instead of falling back to the hardcoded default.
        val bare = listOf(Model(id = "gpt-5", name = "GPT-5", providerId = null))
        assertEquals("opencode" to "gpt-5", ModelSelection.resolve("gpt-5", bare))
        assertEquals("mine" to "gpt-5", ModelSelection.resolve("mine/gpt-5", bare))

        // And a ref naming a provider that does not offer that tail must not be
        // silently routed to the provider that does.
        val catalog = listOf(Model(id = "gpt-5", name = "GPT-5", providerId = "opencode"))
        assertEquals("mine" to "gpt-5", ModelSelection.resolve("mine/gpt-5", catalog))
    }

    @Test
    fun `a leading slash cannot produce an empty provider`() {
        assertEquals("opencode" to "model", ModelSelection.resolve("/model", models))
        assertEquals("opencode" to "trailing", ModelSelection.resolve("trailing/", models))
    }
}
