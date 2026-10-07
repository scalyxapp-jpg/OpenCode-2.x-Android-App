package com.opencode.android.data

import com.opencode.android.domain.Message
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the message decoder against the live server shape.
 *
 * The payload is a **sanitized capture** from `GET /session/{id}/message`
 * (opencode 1.x): five assistant messages with step-start / reasoning / text /
 * tool / step-finish parts. Long command/output/text payloads are redacted to
 * `<field-Nchars>` placeholders and all machine-specific values are generic —
 * the wire SHAPE is preserved exactly.
 *
 * Why it matters: a single field the model cannot decode makes
 * `decodeFromStream<List<Message>>` throw, and because `loadMessages` turns any
 * exception into an empty page, the whole session then opened blank — the
 * "session stays empty" report. Decoding the captured bytes pins that contract.
 */
class RealServerPayloadDecodeTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Test
    fun `decodes a captured server message page`() {
        val text =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("server_message_page.json")) {
                "server_message_page.json test resource missing"
            }.bufferedReader().use { it.readText() }

        val messages = json.decodeFromString<List<Message>>(text)

        assertEquals(5, messages.size)
        // The web schema nests the metadata under `info`.
        messages.forEach { m ->
            assertNotNull(m.info?.id)
            assertEquals("assistant", m.info?.role)
        }
        val partTypes =
            messages
                .flatMap { it.parts }
                .map { it.type }
                .toSet()
        assertTrue("reasoning part should decode", "reasoning" in partTypes)
        assertTrue("tool part should decode", "tool" in partTypes)
        assertTrue("text part should decode", "text" in partTypes)
        assertTrue("step-finish part should decode", "step-finish" in partTypes)
    }
}
