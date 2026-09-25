package com.opencode.android.ui

import com.opencode.android.domain.Part
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tool-storm grouping. The live tool column renders one row per entry, so a
 * wrong grouping is visible as either a wall of identical rows (nothing merged)
 * or a lost call (merged too aggressively).
 */
class ToolCoalesceTest {

    private fun tool(name: String, id: String) = Part(id = id, type = "tool", tool = name)

    @Test
    fun `consecutive same-tool calls collapse into one entry`() {
        val parts = listOf(tool("read", "1"), tool("read", "2"), tool("read", "3"))
        val out = coalesceConsecutiveTools(parts)
        assertEquals(1, out.size)
        assertEquals(3, out[0].second)
        // The newest part is kept: it carries the current status.
        assertEquals("3", out[0].first.id)
    }

    @Test
    fun `different tools in between are not merged`() {
        val parts = listOf(tool("read", "1"), tool("bash", "2"), tool("read", "3"))
        val out = coalesceConsecutiveTools(parts)
        assertEquals(3, out.size)
        assertEquals(listOf(1, 1, 1), out.map { it.second })
    }

    @Test
    fun `non-consecutive repeats stay separate`() {
        val parts = listOf(tool("read", "1"), tool("read", "2"), tool("bash", "3"), tool("read", "4"))
        val out = coalesceConsecutiveTools(parts)
        assertEquals(3, out.size)
        assertEquals(listOf(2, 1, 1), out.map { it.second })
    }

    @Test
    fun `a part without a tool name never merges`() {
        val parts = listOf(Part(id = "1", type = "tool"), Part(id = "2", type = "tool"))
        assertEquals(2, coalesceConsecutiveTools(parts).size)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, coalesceConsecutiveTools(emptyList()).size)
    }
}
