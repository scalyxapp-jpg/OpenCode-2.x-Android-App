package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The truncation note used to be an inline German literal in the UI. It is now
 * built here so it is language-consistent and testable.
 */
class ToolTextTest {

    @Test
    fun `suffix reports the hidden character count`() {
        val s = ToolText.truncationSuffix(4_200)
        assertTrue(s.contains("4200"))
        assertTrue(s.startsWith("…"))
        assertTrue(s.contains("copy"))
    }

    @Test
    fun `suffix is stable for zero`() {
        assertEquals(ToolText.truncationSuffix(0), ToolText.truncationSuffix(0))
    }
}
