package com.opencode.android.util

/**
 * How dictated text lands in the composer.
 *
 * Speech recognition delivers one final phrase per push-to-talk attempt. The composer must not end
 * up with "Wrote the testsWrote the tests." or a missing space after a sentence, and an empty
 * result must never eat what the user typed.
 *
 * Pure, so the wording logic is unit-tested instead of being buried in a listener callback.
 */
object DictationPolicy {
    /**
     * Appends a recognized phrase to what is already in the field.
     *
     * One space between the parts, never two, and an empty result changes nothing (a recognition
     * that produced only whitespace must not eat the user's text).
     */
    fun appendFinal(
        current: String,
        recognized: String,
    ): String {
        val add = recognized.trim()
        if (add.isEmpty()) return current
        val base = current.trimEnd()
        if (base.isEmpty()) return add
        // Keep an explicit line break the user typed: dictating after it starts a new line.
        val endsWithBreak = current.endsWith("\n")
        return if (endsWithBreak) "$base\n$add" else "$base $add"
    }

    /**
     * Cleans a recognized phrase before it is appended.
     *
     * Recognition returns lowercase run-on text ("wrote the tests") while the field may open a
     * sentence — so the first letter is capitalised. Runs of spaces inside the phrase are collapsed
     * (engines emit them around pauses), line breaks are left alone, and a code-ish token like
     * `/compact` or `AppSettingsStore` is not touched beyond that.
     */
    fun polish(recognized: String): String {
        val collapsed =
            recognized
                .trim()
                .replace(Regex("[ \\t]{2,}"), " ")
        if (collapsed.isEmpty()) return collapsed
        val first = collapsed.first()
        return if (first.isLetter()) collapsed.replaceFirstChar { it.uppercaseChar() } else collapsed
    }
}
