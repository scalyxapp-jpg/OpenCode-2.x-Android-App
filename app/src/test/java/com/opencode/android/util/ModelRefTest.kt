package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Test
import com.opencode.android.domain.Model

/** Guards the "nvidia/nvidia/…" double-prefix bug. */
class ModelRefTest {

    @Test
    fun `prepends the provider when the id is bare`() {
        assertEquals("deepseek/deepseek-v4-flash", sessionModelRef("deepseek-v4-flash", "deepseek"))
    }

    @Test
    fun `does not double a provider already in the id`() {
        assertEquals(
            "nvidia/nemotron-3-super-120b-a12b",
            sessionModelRef("nvidia/nemotron-3-super-120b-a12b", "nvidia"),
        )
    }

    @Test
    fun `resolves nvidia catalog id without stripping required prefix`() {
        val models = listOf(
            Model(
                id = "nvidia/nemotron-3-super-120b-a12b",
                providerId = "nvidia",
            ),
        )
        assertEquals(
            "nvidia" to "nvidia/nemotron-3-super-120b-a12b",
            resolveModelRef("nvidia/nemotron-3-super-120b-a12b", models),
        )
    }

    @Test
    fun `resolves bare catalog id for providers with bare ids`() {
        val models = listOf(Model(id = "deepseek-v4-pro", providerId = "deepseek"))
        assertEquals(
            "deepseek" to "deepseek-v4-pro",
            resolveModelRef("deepseek/deepseek-v4-pro", models),
        )
    }
    @Test
    fun `returns the id when there is no provider`() {
        assertEquals("some/model", sessionModelRef("some/model", null))
    }

    @Test
    fun `returns empty for a null id`() {
        assertEquals("", sessionModelRef(null, "nvidia"))
    }

    @Test
    fun `session model preserves provider and qualified model id`() {
        val models = listOf(
            Model(
                id = "nvidia/nemotron-3-super-120b-a12b",
                providerId = "nvidia",
            ),
        )
        assertEquals(
            "nvidia/nemotron-3-super-120b-a12b",
            resolveSessionModelRef(
                com.opencode.android.domain.SessionModel(
                    id = "nvidia/nemotron-3-super-120b-a12b",
                    providerID = "nvidia",
                ),
                models,
            ),
        )
    }

    @Test
    fun `same model id resolves to selected provider`() {
        val models = listOf(
            Model(id = "nvidia/nemotron-3-super-120b-a12b", providerId = "openrouter"),
            Model(id = "nvidia/nemotron-3-super-120b-a12b", providerId = "nvidia"),
        )
        assertEquals(
            "nvidia" to "nvidia/nemotron-3-super-120b-a12b",
            resolveModelRef("nvidia/nemotron-3-super-120b-a12b", models),
        )
        assertEquals(
            "openrouter" to "nvidia/nemotron-3-super-120b-a12b",
            resolveModelRef("openrouter/nvidia/nemotron-3-super-120b-a12b", models),
        )
    }

    @Test
    fun `session model keeps selected provider for same model name`() {
        val models = listOf(
            Model(id = "space-bunny-free", providerId = "opencode-zen"),
            Model(id = "space-bunny-free", providerId = "opencode-go"),
        )
        assertEquals(
            "opencode-zen/space-bunny-free",
            resolveSessionModelRef(
                com.opencode.android.domain.SessionModel(
                    modelID = "space-bunny-free",
                    providerID = "opencode-zen",
                ),
                models,
            ),
        )
    }

    @Test
    fun `friendly name resolves via the provider-qualified id`() {
        val models = listOf(Model(id = "nvidia/nemotron-3-super-120b-a12b", name = "Nemotron 3 Super"))
        assertEquals(
            "Nemotron 3 Super",
            friendlyModelName(models, "nvidia/nemotron-3-super-120b-a12b"),
        )
    }

    @Test
    fun `friendly name resolves via the bare id`() {
        val models = listOf(Model(id = "nvidia/nemotron-3-super-120b-a12b", name = "Nemotron 3 Super"))
        assertEquals("Nemotron 3 Super", friendlyModelName(models, "nemotron-3-super-120b-a12b"))
    }

    @Test
    fun `friendly name falls back to the ref when unknown`() {
        assertEquals("x/y", friendlyModelName(emptyList(), "x/y"))
        assertEquals("", friendlyModelName(emptyList(), null))
    }
}
