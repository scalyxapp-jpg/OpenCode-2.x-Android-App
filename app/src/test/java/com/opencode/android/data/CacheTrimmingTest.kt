package com.opencode.android.data

import com.opencode.android.domain.ContentPart
import com.opencode.android.domain.Message
import com.opencode.android.domain.Part
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trimming is what keeps the offline cache under its size cap (M1/L1). A
 * regression silently disables the cache for exactly the heavy sessions it
 * exists for, so the rule is pinned here.
 */
class CacheTrimmingTest {

    private fun message(
        parts: List<Part> = emptyList(),
        content: List<ContentPart> = emptyList(),
    ) = Message(id = "m1", role = "assistant", parts = parts, content = content)

    @Test
    fun `tool state is dropped`() {
        val trimmed = CacheTrimming.trim(
            listOf(message(parts = listOf(Part(id = "p1", type = "tool", state = JsonPrimitive("x".repeat(500_000)))))),
        )
        assertNull(trimmed.single().parts.single().state)
    }

    @Test
    fun `legacy content is kept when there are no parts`() {
        // A legacy-only message has content but no parts; dropping the content
        // used to cache an empty bubble for it.
        val trimmed = CacheTrimming.trim(
            listOf(message(content = listOf(ContentPart(type = "text", text = "legacy")))),
        )
        assertEquals("legacy", trimmed.single().content.single().text)
    }

    @Test
    fun `duplicate content is dropped when parts are present`() {
        val trimmed = CacheTrimming.trim(
            listOf(
                message(
                    parts = listOf(Part(id = "p1", type = "text", text = "modern")),
                    content = listOf(ContentPart(type = "text", text = "modern")),
                ),
            ),
        )
        assertTrue(trimmed.single().content.isEmpty())
        assertEquals("modern", trimmed.single().parts.single().text)
    }

    @Test
    fun `long part text is capped and short text is untouched`() {
        val long = "a".repeat(CacheTrimming.MAX_PART_TEXT + 5_000)
        val short = "hello"
        val trimmed = CacheTrimming.trim(
            listOf(
                message(
                    parts = listOf(
                        Part(id = "p1", type = "text", text = long),
                        Part(id = "p2", type = "text", text = short),
                    ),
                ),
            ),
        )
        assertEquals(CacheTrimming.MAX_PART_TEXT, trimmed.single().parts[0].text!!.length)
        assertEquals(short, trimmed.single().parts[1].text)
    }

    @Test
    fun `message identity and ordering are preserved`() {
        val input = listOf(
            message(parts = listOf(Part(id = "a", type = "text", text = "one"))),
            message(parts = listOf(Part(id = "b", type = "text", text = "two"))),
        ).mapIndexed { i, m -> m.copy(id = "m$i") }

        val trimmed = CacheTrimming.trim(input)
        assertEquals(listOf("m0", "m1"), trimmed.map { it.id })
        assertEquals("assistant", trimmed.first().role)
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(CacheTrimming.trim(emptyList()).isEmpty())
    }
}
