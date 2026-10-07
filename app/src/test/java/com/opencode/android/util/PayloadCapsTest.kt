package com.opencode.android.util

import com.opencode.android.domain.ContentPart
import com.opencode.android.domain.Message
import com.opencode.android.domain.Part
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ingestion cap must bound retained tool payloads without touching small ones. */
class PayloadCapsTest {
    private fun big(n: Int): String = "x".repeat(n)

    @Test
    fun `small payloads are returned unchanged`() {
        val message =
            Message(
                id = "m1",
                text = "hello",
                parts = listOf(Part(id = "p1", type = "text", text = "short")),
            )
        val messages = listOf(message)
        assertSame(messages, PayloadCaps.capMessages(messages))
        assertSame(message, PayloadCaps.capMessage(message))
    }

    @Test
    fun `oversized part text is truncated`() {
        val part = Part(id = "p1", type = "text", text = big(PayloadCaps.MAX_PART_TEXT_CHARS + 50))
        val capped = PayloadCaps.capPart(part)
        assertTrue(capped.text!!.length < part.text!!.length)
        assertTrue(capped.text!!.startsWith("x".repeat(100)))
        assertTrue(capped.text!!.contains("truncated"))
    }

    @Test
    fun `oversized tool state output is truncated`() {
        val part =
            Part(
                id = "p1",
                type = "tool",
                tool = "bash",
                state = JsonObject(mapOf("output" to JsonPrimitive(big(PayloadCaps.MAX_TOOL_OUTPUT_CHARS + 10)))),
            )
        val capped = PayloadCaps.capPart(part)
        val output = ((capped.state as JsonObject)["output"] as JsonPrimitive).content
        assertTrue(output.length <= PayloadCaps.MAX_TOOL_OUTPUT_CHARS)
        assertTrue(output.contains("truncated"))
    }

    @Test
    fun `oversized metadata diff is truncated`() {
        val part =
            Part(
                id = "p1",
                type = "tool",
                tool = "edit",
                state =
                    JsonObject(
                        mapOf(
                            "metadata" to
                                JsonObject(
                                    mapOf(
                                        "diff" to JsonPrimitive(big(PayloadCaps.MAX_TOOL_DIFF_CHARS + 10)),
                                        "filepath" to JsonPrimitive("/x/y.kt"),
                                    ),
                                ),
                        ),
                    ),
            )
        val capped = PayloadCaps.capPart(part)
        val metadata = (capped.state as JsonObject)["metadata"] as JsonObject
        val diff = (metadata["diff"] as JsonPrimitive).content
        assertTrue(diff.length < PayloadCaps.MAX_TOOL_DIFF_CHARS + 100)
        assertTrue(diff.contains("truncated"))
        // Unrelated fields survive.
        assertEquals("/x/y.kt", (metadata["filepath"] as JsonPrimitive).content)
    }

    @Test
    fun `oversized message text and content parts are truncated`() {
        val message =
            Message(
                id = "m1",
                text = big(PayloadCaps.MAX_PART_TEXT_CHARS + 5),
                content =
                    listOf(
                        ContentPart(type = "text", text = big(PayloadCaps.MAX_PART_TEXT_CHARS + 5)),
                    ),
            )
        val capped = PayloadCaps.capMessage(message)
        assertTrue(capped.text!!.length < message.text!!.length)
        assertTrue(
            capped.content
                .single()
                .text!!
                .length <
                message.content
                    .single()
                    .text!!
                    .length,
        )
    }

    @Test
    fun `capMessages only replaces the list when something changed`() {
        val clean = Message(id = "a", parts = listOf(Part(id = "p", type = "text", text = "ok")))
        val dirty =
            Message(
                id = "b",
                parts = listOf(Part(id = "q", type = "text", text = big(PayloadCaps.MAX_PART_TEXT_CHARS + 1))),
            )
        val cleanInput = listOf(clean)
        assertSame(cleanInput, PayloadCaps.capMessages(cleanInput))
        val result = PayloadCaps.capMessages(listOf(clean, dirty))
        assertEquals(2, result.size)
        assertSame(clean, result[0])
        assertTrue(result[1] !== dirty)
    }
}
