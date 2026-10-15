package com.opencode.android.util

import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flags a dictation request carries.
 *
 * This is where quality and latency are decided, so it is pinned here instead of living inside a
 * listener callback: shorter silence thresholds (the platform default of ~2 s is the biggest
 * "why is it so slow" factor), an explicit language, offline preference, formatting quality and
 * the vocabulary bias.
 */
class SpeechRequestPolicyTest {
    private fun extras(
        options: SpeechOptions = SpeechOptions(),
        apiLevel: Int = 34,
    ) = SpeechRequestPolicy.extras(options, apiLevel)

    @Test
    fun `silence thresholds are not sent, so the engine cannot end a dictation early`() {
        val map = extras()
        assertEquals(true, map[RecognizerIntent.EXTRA_PARTIAL_RESULTS])
        assertEquals(1, map[RecognizerIntent.EXTRA_MAX_RESULTS])
        // The old 850 ms "complete" threshold cut sentences off mid-dictation; with push-to-talk the
        // release ends the recording, so the platform defaults stay untouched.
        assertEquals(null, map[RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS])
        assertEquals(null, map[RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS])
        assertEquals(null, map[RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS])
    }

    @Test
    fun `quality flags only travel where the platform knows them`() {
        // API 33+ gets punctuation/casing quality and the vocabulary hint.
        val modern = extras(SpeechOptions(biasing = listOf("opencode", "Gradle")))
        assertEquals(RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY, modern[RecognizerIntent.EXTRA_ENABLE_FORMATTING])
        assertEquals(listOf("opencode", "Gradle"), modern[RecognizerIntent.EXTRA_BIASING_STRINGS])

        // Older engines get neither key: unknown extras only bloat the intent.
        val old = extras(SpeechOptions(biasing = listOf("opencode")), apiLevel = 32)
        assertEquals(null, old[RecognizerIntent.EXTRA_ENABLE_FORMATTING])
        assertEquals(null, old[RecognizerIntent.EXTRA_BIASING_STRINGS])
    }

    @Test
    fun `offline and language are opt-in`() {
        val plain = extras()
        assertEquals(null, plain[RecognizerIntent.EXTRA_PREFER_OFFLINE])
        assertEquals(null, plain[RecognizerIntent.EXTRA_LANGUAGE])

        val tuned = extras(SpeechOptions(preferOffline = true, languageTag = "de-DE"))
        assertEquals(true, tuned[RecognizerIntent.EXTRA_PREFER_OFFLINE])
        assertEquals("de-DE", tuned[RecognizerIntent.EXTRA_LANGUAGE])
        // A blank tag must not be sent as an empty language.
        assertEquals(null, extras(SpeechOptions(languageTag = "  "))[RecognizerIntent.EXTRA_LANGUAGE])
    }

    @Test
    fun `the vocabulary stays a hint`() {
        // Blank entries and duplicates are dropped; a short list keeps the bias sharp.
        val map =
            SpeechRequestPolicy.extras(
                SpeechOptions(biasing = listOf(" Gradle ", "", "Gradle", "Kotlin")),
                apiLevel = 34,
                base = listOf("Kotlin", "Gradle"),
            )
        assertEquals(listOf("Gradle", "Kotlin"), map[RecognizerIntent.EXTRA_BIASING_STRINGS])
    }

    @Test
    fun `the session title supplies the words of the current work`() {
        assertEquals(
            listOf("Berechtigungsprüfung", "Login", "korrigieren"),
            SpeechRequestPolicy.biasingFor("Berechtigungsprüfung im Login korrigieren"),
        )
        // Short filler words and pure numbers are not worth a hint.
        assertEquals(listOf("Tests"), SpeechRequestPolicy.biasingFor("den 42 Tests"))
        assertEquals(emptyList<String>(), SpeechRequestPolicy.biasingFor(null))
        assertEquals(3, SpeechRequestPolicy.biasingFor("eins zwei drei vier fünf", limit = 3).size)
    }
}
