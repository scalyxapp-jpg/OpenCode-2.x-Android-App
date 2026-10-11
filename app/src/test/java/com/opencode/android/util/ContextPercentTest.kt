package com.opencode.android.util

import com.opencode.android.domain.Model
import com.opencode.android.domain.ModelLimit
import com.opencode.android.domain.ProviderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the context-usage percentage shown in the session header. */
class ContextPercentTest {
    @Test
    fun `computes the share of the window`() {
        assertEquals(45, contextPercent(totalTokens = 450_000, limit = 1_000_000))
    }

    @Test
    fun `an unknown limit is null (never a misleading 0)`() {
        assertNull(contextPercent(totalTokens = 450_000, limit = null))
        assertNull(contextPercent(totalTokens = 450_000, limit = 0))
        assertNull(contextPercent(totalTokens = 450_000, limit = -5))
    }

    @Test
    fun `clamps above 100`() {
        assertEquals(100, contextPercent(totalTokens = 2_000_000, limit = 1_000_000))
    }

    @Test
    fun `context limit is looked up for the selected provider, not the first match`() {
        // The same model id is offered by several providers with different windows
        // (24 such ids on a stock server). Matching on the id alone returned
        // whichever provider came first, so the usage ring showed a wrong %.
        val shared = "deepseek-v4-flash"
        val providers =
            listOf(
                ProviderEntry(
                    id = "opencode-go",
                    models =
                        mapOf(
                            shared to Model(id = shared, providerId = "opencode-go", limit = ModelLimit(context = 200_000)),
                        ),
                ),
                ProviderEntry(
                    id = "deepseek",
                    models =
                        mapOf(
                            shared to Model(id = shared, providerId = "deepseek", limit = ModelLimit(context = 1_000_000)),
                        ),
                ),
            )

        assertEquals(1_000_000L, contextLimitFor(providers, "deepseek", shared))
        assertEquals(200_000L, contextLimitFor(providers, "opencode-go", shared))
        // An unknown provider still resolves rather than returning null.
        assertEquals(200_000L, contextLimitFor(providers, null, shared))
        // A provider that does not offer the model falls back to a match instead of
        // reporting a wrong limit as if it were the selected one.
        assertEquals(200_000L, contextLimitFor(providers, "somewhere-else", shared))
        assertNull(contextLimitFor(providers, "deepseek", "unknown-model"))
    }

    @Test
    fun `context limit falls back to the group provider when the model omits it`() {
        val providers =
            listOf(
                ProviderEntry(
                    id = "nvidia",
                    models =
                        mapOf(
                            "z-ai/glm-5.3" to Model(id = "z-ai/glm-5.3", limit = ModelLimit(context = 131_072)),
                        ),
                ),
            )
        assertEquals(131_072L, contextLimitFor(providers, "nvidia", "z-ai/glm-5.3"))
    }

    @Test
    fun `a genuinely empty context is 0`() {
        assertEquals(0, contextPercent(totalTokens = 0, limit = 1_000_000))
    }
}
