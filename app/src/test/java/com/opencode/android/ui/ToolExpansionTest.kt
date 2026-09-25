package com.opencode.android.ui

import com.opencode.android.domain.Part
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the "Expand shell/edit tool parts" settings, which
 * previously did nothing because ToolCallRow always started collapsed.
 */
class ToolExpansionTest {

    private fun part(tool: String) = Part(id = "p1", type = "tool", tool = tool)

    @Test
    fun `shell tools follow the shell setting`() {
        assertTrue(defaultToolExpanded(part("bash"), shellExpanded = true, editExpanded = false))
        assertTrue(defaultToolExpanded(part("shell"), shellExpanded = true, editExpanded = false))
        assertFalse(defaultToolExpanded(part("bash"), shellExpanded = false, editExpanded = true))
    }

    @Test
    fun `edit tools follow the edit setting`() {
        assertTrue(defaultToolExpanded(part("edit"), shellExpanded = false, editExpanded = true))
        assertTrue(defaultToolExpanded(part("write"), shellExpanded = false, editExpanded = true))
        assertTrue(defaultToolExpanded(part("apply_patch"), shellExpanded = false, editExpanded = true))
    }

    @Test
    fun `unrelated tools stay collapsed`() {
        assertFalse(defaultToolExpanded(part("grep"), shellExpanded = true, editExpanded = true))
    }
}
