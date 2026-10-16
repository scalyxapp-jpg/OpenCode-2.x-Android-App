package com.opencode.android.util

/**
 * Turns dictated text into clean text.
 *
 * Speech engines return bare words: no punctuation, lowercase, sometimes with runs of spaces. Two
 * things make that usable in a prompt:
 *
 *  - **spoken punctuation** ("… Punkt" / "… period", "neue Zeile" / "new line"), and
 *  - **polish**: first letter capitalised, runs of spaces collapsed.
 *
 * The punctuation rule is deliberately conservative because spoken punctuation words are ambiguous
 * in natural speech: "der Punkt ist wichtig" means *the point*, not a full stop. Sentence marks are
 * therefore only converted at the **end** of the phrase (where a full stop is what one means), while
 * line commands are unambiguous and work anywhere. Nothing else is guessed.
 *
 * Pure, so every wording case is unit-tested.
 */
object DictationPolicy {
    /** Sentence marks: only at the end of the phrase, and they attach to the previous word. */
    private val TRAILING_MARKS: List<Pair<Regex, String>> =
        listOf(
            Regex("\\s+(punkt|period)\\.?$", RegexOption.IGNORE_CASE) to ".",
            Regex("\\s+(fragezeichen|question mark)$", RegexOption.IGNORE_CASE) to "?",
            Regex("\\s+(ausrufezeichen|exclamation mark)$", RegexOption.IGNORE_CASE) to "!",
            Regex("\\s+(doppelpunkt|colon)$", RegexOption.IGNORE_CASE) to ":",
            Regex("\\s+(strichpunkt|semikolon|semicolon)$", RegexOption.IGNORE_CASE) to ";",
        )

    /** Line commands: unambiguous, converted wherever they appear. */
    private val LINE_BREAKS: List<Regex> =
        listOf(
            Regex("\\s*\\b(neue zeile|zeilenumbruch|new line|line break)\\b\\s*", RegexOption.IGNORE_CASE),
            Regex("\\s*\\b(neuer absatz|neuen absatz|new paragraph|paragraph)\\b\\s*", RegexOption.IGNORE_CASE),
        )

    /**
     * Replaces spoken punctuation with the real thing.
     *
     * [language] is accepted but not used to restrict the vocabulary on purpose: people dictate
     * German sentences that contain English tool names, and both word lists are unambiguous in the
     * positions this rule converts.
     */
    fun applySpokenPunctuation(text: String): String {
        var out = text.trim()
        if (out.isEmpty()) return out
        // Line commands first: they change the shape of the text the marks attach to.
        LINE_BREAKS.forEach { pattern -> out = pattern.replace(out, "\n") }
        TRAILING_MARKS.forEach { (pattern, mark) ->
            if (pattern.containsMatchIn(out)) {
                out = pattern.replace(out, mark)
            }
        }
        // Collapse the line breaks the replacements may have doubled up.
        return out
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    }

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
     * Capitalises the first letter and collapses runs of spaces.
     *
     * Runs of spaces come from engines pausing; `AppSettingsStore` or `/compact` are not touched
     * beyond that.
     */
    fun polish(recognized: String): String {
        val collapsed =
            recognized
                .trim()
                .replace(Regex("[ \\t]{2,}"), " ")
        if (collapsed.isEmpty()) return collapsed
        // Every sentence and every line starts with a capital: a dictated multi-sentence prompt read
        // "erste zeile. zweite zeile" before, which looks like one broken sentence. Purely
        // orthographic - no word is changed.
        val sentenceStart = Regex("(^|[.!?][ \\n]|\\n)(\\p{Ll})")
        return sentenceStart.replace(collapsed) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
    }

    /**
     * The whole pipeline for one recognized phrase: punctuation, then polish.
     *
     * [continuesSentence] is true when the phrase is appended to text that does NOT end a sentence
     * (no `.`, `!`, `?`, `:` or line break). Then the first letter stays as the engine returned it:
     * capitalising every chunk wrote "ein Test Du wartest jetzt" instead of "ein Test du wartest
     * jetzt" — visible in the dictated text that came out of the device test.
     */
    fun finalize(
        recognized: String,
        spokenPunctuation: Boolean,
        continuesSentence: Boolean = false,
    ): String {
        val punctuated = if (spokenPunctuation) applySpokenPunctuation(recognized) else recognized.trim()
        val polished = polish(punctuated)
        if (!continuesSentence || polished.isEmpty()) return polished
        return polished.replaceFirstChar { it.lowercaseChar() }
    }

    /** Whether [existing] text ends mid-sentence, i.e. the next phrase continues it. */
    fun continuesSentence(existing: String): Boolean {
        // Only spaces/tabs are trimmed: trimEnd() also removed a trailing line break, so text ending
        // with "\n" counted as a continued sentence and the next phrase was not capitalised (found by
        // the test).
        val tail = existing.trimEnd(' ', '\t')
        if (tail.isEmpty()) return false
        return !(
            tail.endsWith(".") || tail.endsWith("!") || tail.endsWith("?") ||
                tail.endsWith(":") || tail.endsWith("\n")
        )
    }

    /**
     * What to insert when the engine delivered no final result.
     *
     * "Spoke, released, nothing appeared" is the most annoying dictation failure, and on-device
     * engines do return an empty final after good partials. The last partial seen while holding is
     * the best available answer, so it is used instead of dropping the user's words.
     */
    fun fallbackFromPartial(lastPartial: String?): String = lastPartial?.trim().orEmpty()
}
