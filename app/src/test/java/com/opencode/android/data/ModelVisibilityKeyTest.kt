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
}
