package com.opencode.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the model-visibility key mismatch: Settings → Models
 * used to write "provider/provider/model" while the pickers read
 * "provider/model", so a toggle in one screen had no effect in the other.
 */
class ModelVisibilityKeyTest {
    @Test
    fun `bare and provider-qualified ids resolve to the same key`() {
        assertEquals(
            "deepseek/deepseek-v4-flash",
            ModelVisibilityStore.visibilityKey("deepseek", "deepseek-v4-flash"),
        )
        assertEquals(
            "deepseek/deepseek-v4-flash",
            ModelVisibilityStore.visibilityKey("deepseek", "deepseek/deepseek-v4-flash"),
        )
    }

    @Test
    fun `provider prefix is never duplicated`() {
        assertEquals(
            "anthropic/claude-3-5",
            ModelVisibilityStore.visibilityKey("anthropic", "anthropic/claude-3-5"),
        )
    }

    @Test
    fun `a model namespace is preserved instead of being stripped`() {
        // Nvidia model ids carry their own namespace ("z-ai/glm-5.3"). Stripping
        // everything before the first slash produced a key the web/TUI never
        // wrote, so hides made elsewhere had no effect here.
        assertEquals(
            "nvidia/z-ai/glm-5.3",
            ModelVisibilityStore.visibilityKey("nvidia", "z-ai/glm-5.3"),
        )
        assertEquals(
            "nvidia/z-ai/glm-5.3",
            ModelVisibilityStore.visibilityKey("nvidia", "nvidia/z-ai/glm-5.3"),
        )
        assertEquals(
            "nvidia/deepseek-ai/deepseek-v4.1-flash",
            ModelVisibilityStore.visibilityKey("nvidia", "deepseek-ai/deepseek-v4.1-flash"),
        )
    }
}
