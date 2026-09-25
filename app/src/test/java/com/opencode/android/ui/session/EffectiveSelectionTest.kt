package com.opencode.android.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveSelectionTest {

    private fun ui(
        model: String = "local/model",
        agent: String = "local-agent",
        variant: String = "local-variant",
        modelPending: Boolean = false,
        agentPending: Boolean = false,
    ) = UiSelection(model, agent, variant, modelPending, agentPending)

    @Test
    fun `server wins when nothing is pending`() {
        val result = effectiveSelection(
            server = ServerSelection("server/model", "server-agent", "server-variant"),
            ui = ui(),
        )
        assertEquals("server/model", result.model)
        assertEquals("server-agent", result.agent)
        assertEquals("server-variant", result.variant)
    }

    @Test
    fun `pending model beats the server, including the variant`() {
        val result = effectiveSelection(
            server = ServerSelection("server/model", "server-agent", "server-variant"),
            ui = ui(model = "local/model", variant = "local-variant", modelPending = true),
        )
        assertEquals("local/model", result.model)
        assertEquals("server-agent", result.agent)
        assertEquals("local-variant", result.variant)
    }

    @Test
    fun `pending agent beats the server but leaves the model`() {
        val result = effectiveSelection(
            server = ServerSelection("server/model", "server-agent", "server-variant"),
            ui = ui(agent = "local-agent", agentPending = true),
        )
        assertEquals("server/model", result.model)
        assertEquals("local-agent", result.agent)
        assertEquals("server-variant", result.variant)
    }

    @Test
    fun `a server that omits fields falls back to the local selection`() {
        val result = effectiveSelection(
            server = ServerSelection(null, null, null),
            ui = ui(),
        )
        assertEquals("local/model", result.model)
        assertEquals("local-agent", result.agent)
        assertEquals("local-variant", result.variant)
    }
}
