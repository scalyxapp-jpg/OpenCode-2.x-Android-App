package com.opencode.android.util

/**
 * How dictated text lands in the composer.
 *
 * Speech recognition delivers partial results while you talk and a final one when it is done. The
 * composer must not end up with "Wrote the testsWrote the tests." or with a missing space after a
 * sentence, and dictating twice must read like one sentence — that is what this rule decides.
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
     * The text as it would look if the running partial result were final.
     *
     * Used for the live preview only - the field itself is not written until the recognition is
     * final, so a misheard partial cannot overwrite the user's typing.
     */
    fun preview(
        current: String,
        partial: String,
    ): String = appendFinal(current, partial)

    /**
     * Cleans a recognized phrase before it is appended.
     *
     * Recognition returns lowercase run-on text ("wrote the tests") while the field may open a
     * sentence — so the first letter is capitalised. Runs of spaces inside the phrase are collapsed
     * (engines emit them around pauses), line breaks are left alone, and a code-ish token like
     * `AppSettingsStore` is not touched beyond that.
     */
    fun polish(recognized: String): String {
        val collapsed =
            recognized
                .trim()
                .replace(Regex("[ \t]{2,}"), " ")
        if (collapsed.isEmpty()) return collapsed
        val first = collapsed.first()
        return if (first.isLetter()) collapsed.replaceFirstChar { it.uppercaseChar() } else collapsed
    }

    /**
     * Removes exactly what dictation appended, for the "Undo" action.
     *
     * Only the tail is touched, and only when it really ends with the appended text: an undo must
     * never eat what the user typed by hand.
     */
    fun undoAppend(
        current: String,
        appended: String,
    ): String {
        val tail = appended.trim()
        if (tail.isEmpty()) return current
        val trimmed = current.trimEnd()
        return when {
            trimmed == tail -> ""
            trimmed.endsWith(" $tail") -> trimmed.dropLast(tail.length + 1)
            trimmed.endsWith("\n$tail") -> trimmed.dropLast(tail.length + 1)
            else -> current
        }
    }

    /** Short status line for the listening state, so the control says what it is doing. */
    fun listeningLabel(partial: String?): String? = partial?.trim()?.takeIf { it.isNotEmpty() }
}
