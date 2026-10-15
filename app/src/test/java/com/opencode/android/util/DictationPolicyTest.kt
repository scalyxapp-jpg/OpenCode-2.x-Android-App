package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How dictated text merges into the composer.
 *
 * The cases that look trivial here are exactly the ones that read wrong in practice: a missing
 * space, a swallowed blank line, or a recognition that only returned whitespace wiping the field.
 */
class DictationPolicyTest {
    @Test
    fun `dictating into an empty field just writes the text`() {
        assertEquals("Wrote the tests", DictationPolicy.appendFinal("", "  Wrote the tests  "))
        assertEquals("", DictationPolicy.appendFinal("", "   "))
    }

    @Test
    fun `dictating after typing continues the sentence with one space`() {
        assertEquals(
            "Please wrote the tests",
            DictationPolicy.appendFinal("Please", "wrote the tests"),
        )
        // Trailing whitespace does not become a double space.
        assertEquals(
            "Please wrote the tests",
            DictationPolicy.appendFinal("Please   ", "wrote the tests"),
        )
    }

    @Test
    fun `dictating after a line break starts a new line`() {
        assertEquals(
            "First line\nsecond line",
            DictationPolicy.appendFinal("First line\n", "second line"),
        )
    }

    @Test
    fun `the preview shows what would be sent, without touching the field`() {
        assertEquals("Run the tests now", DictationPolicy.preview("Run the tests", "now"))
        assertEquals(null, DictationPolicy.listeningLabel("   "))
        assertEquals("so far", DictationPolicy.listeningLabel(" so far "))
    }
}
