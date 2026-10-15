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
    fun `a trailing spoken full stop becomes punctuation`() {
        // Punctuation only; capitalisation is polish's job (checked below).
        assertEquals("bitte teste den parser.", DictationPolicy.applySpokenPunctuation("bitte teste den parser punkt"))
        assertEquals("did it pass?", DictationPolicy.applySpokenPunctuation("did it pass fragezeichen"))
        assertEquals("run it now!", DictationPolicy.applySpokenPunctuation("run it now ausrufezeichen"))
        assertEquals("note:", DictationPolicy.applySpokenPunctuation("note doppelpunkt"))
        assertEquals("tests, then commit.", DictationPolicy.applySpokenPunctuation("tests, then commit period"))
    }

    @Test
    fun `the word 'point' inside a sentence is left alone`() {
        // The ambiguous case that makes a naive rule unusable: "der Punkt ist wichtig" means *the
        // point*, not a full stop. Only a TRAILING mark is converted.
        assertEquals(
            "der punkt ist wichtig",
            DictationPolicy.applySpokenPunctuation("der punkt ist wichtig"),
        )
        assertEquals(
            "ein komma setzen",
            DictationPolicy.applySpokenPunctuation("ein komma setzen"),
        )
    }

    @Test
    fun `line commands work anywhere`() {
        assertEquals("erste zeile\nzweite zeile", DictationPolicy.applySpokenPunctuation("erste zeile neue zeile zweite zeile"))
        assertEquals("done\nnext", DictationPolicy.applySpokenPunctuation("done new line next"))
        // Doubled breaks collapse to one.
        assertEquals("done\nnext", DictationPolicy.applySpokenPunctuation("done neue zeile new line next"))
    }

    @Test
    fun `the pipeline runs punctuation then polish`() {
        assertEquals(
            "Bitte teste den parser.",
            DictationPolicy.finalize("  bitte   teste den parser punkt  ", spokenPunctuation = true),
        )
        // Switched off: the words stay as they were spoken.
        assertEquals(
            "Bitte teste den parser punkt",
            DictationPolicy.finalize("bitte teste den parser punkt", spokenPunctuation = false),
        )
    }

    @Test
    fun `an empty final result falls back to the last partial`() {
        // "Spoke, released, nothing appeared" - on-device engines do this; the partials already
        // heard the words.
        assertEquals("Bitte teste den Parser", DictationPolicy.fallbackFromPartial(" Bitte teste den Parser "))
        assertEquals("", DictationPolicy.fallbackFromPartial(null))
        assertEquals("", DictationPolicy.fallbackFromPartial("   "))
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
        // Every sentence and line starts with a capital, not only the first one.
        assertEquals("Erste zeile. Zweite zeile", DictationPolicy.polish("erste zeile. zweite zeile"))
        assertEquals("Erste zeile\nZweite zeile", DictationPolicy.polish("erste zeile\nzweite zeile"))
        assertEquals("Fertig? Ja!", DictationPolicy.polish("fertig? ja!"))
        // A leading path or flag is not capitalised into nonsense.
        assertEquals("/compact now", DictationPolicy.polish("/compact now"))
    }
}
