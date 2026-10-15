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
    fun `the recognized phrase is polished before it lands`() {
        // Engines return lowercase, space-padded run-on text.
        assertEquals("Wrote tests", DictationPolicy.polish("  wrote   tests  "))
        assertEquals("Wrote the tests", DictationPolicy.polish("wrote the tests"))
        // Two spaces collapse to one: assert the exact shape, not just equality, so an invisible
        // character can never make this test lie (it did once while writing it).
        val collapsed = DictationPolicy.polish("wrote  tests")
        assertEquals("Wrote tests", collapsed)
        assertEquals(1, collapsed.count { it == ' ' })
        assertEquals("", DictationPolicy.polish("   "))
        // A leading path or flag is not capitalised into nonsense.
        assertEquals("/compact now", DictationPolicy.polish("/compact now"))
    }

    @Test
    fun `undo removes exactly what dictation appended`() {
        assertEquals("Please", DictationPolicy.undoAppend("Please wrote the tests", "wrote the tests"))
        assertEquals("", DictationPolicy.undoAppend("wrote the tests", " wrote the tests "))
        assertEquals("First line", DictationPolicy.undoAppend("First line\nsecond line", "second line"))
        // Typed text that merely looks similar is left alone.
        assertEquals("my own note", DictationPolicy.undoAppend("my own note", "something else"))
    }

    @Test
    fun `the preview shows what would be sent, without touching the field`() {
        assertEquals("Run the tests now", DictationPolicy.preview("Run the tests", "now"))
        assertEquals(null, DictationPolicy.listeningLabel("   "))
        assertEquals("so far", DictationPolicy.listeningLabel(" so far "))
    }
}
