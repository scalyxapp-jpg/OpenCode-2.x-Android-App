package com.opencode.android.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRequestBuilderTest {

    private fun build(
        agent: String = "build",
        providerId: String = "deepseek",
        modelId: String = "deepseek-v4-flash",
        variant: String = "default",
        finalText: String = "hello",
    ) = buildPromptAsyncRequest(
        messageId = "msg_1",
        partId = "prt_1",
        agent = agent,
        providerId = providerId,
        modelId = modelId,
        variant = variant,
        finalText = finalText,
    )

    @Test
    fun `builds the full web-shaped body`() {
        val body = build(variant = "high")
        assertEquals("msg_1", body.messageID)
        assertEquals("build", body.agent)
        assertEquals("deepseek", body.model?.providerID)
        assertEquals("deepseek-v4-flash", body.model?.modelID)
        assertEquals("high", body.variant)
        assertEquals(1, body.parts.size)
        assertEquals("prt_1", body.parts.first().id)
        assertEquals("hello", body.parts.first().text)
    }

    @Test
    fun `blank agent is omitted`() {
        assertNull(build(agent = "").agent)
    }

    @Test
    fun `default variant is omitted`() {
        assertNull(build(variant = "default").variant)
    }

    @Test
    fun `blank model id omits the model`() {
        assertNull(build(modelId = "").model)
    }

    @Test
    fun `attachment-only turn drops the text part`() {
        val body = build(finalText = "")
        assertTrue(body.parts.isEmpty())
    }
}
