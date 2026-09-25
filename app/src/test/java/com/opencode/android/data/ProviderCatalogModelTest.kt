package com.opencode.android.data

import com.opencode.android.domain.Model
import com.opencode.android.domain.ProviderEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards provider-catalog key matching for prompt requests. */
class ProviderCatalogModelTest {

    private val provider = ProviderEntry(
        id = "nvidia",
        models = mapOf(
            "nvidia/nemotron-3-super-120b-a12b" to Model(
                id = "nvidia/nemotron-3-super-120b-a12b",
            ),
        ),
    )

    @Test
    fun `bare model id matches provider-qualified catalog key`() {
        assertTrue(provider.hasModel("nemotron-3-super-120b-a12b"))
    }

    @Test
    fun `qualified model id matches provider-qualified catalog key`() {
        assertTrue(provider.hasModel("nvidia/nemotron-3-super-120b-a12b"))
    }

    @Test
    fun `different model does not match`() {
        assertFalse(provider.hasModel("nemotron-3-ultra-550b-a55b"))
    }
}
