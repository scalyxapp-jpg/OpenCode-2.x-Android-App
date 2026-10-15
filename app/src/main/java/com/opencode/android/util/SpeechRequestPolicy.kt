package com.opencode.android.util

import android.speech.RecognizerIntent

/**
 * How a dictation request is tuned.
 *
 * Everything that changes recognition quality or latency is decided here instead of inside the
 * listener: which engine is preferred, how long a pause counts as "done", what vocabulary the
 * recognizer is biased towards. Pure, so the flags can be unit-tested (the `RecognizerIntent`
 * keys are compile-time constants and safe to use on the JVM).
 */
data class SpeechOptions(
    /** `EXTRA_PREFER_OFFLINE`: no network round trip (faster, private), sometimes less accurate. */
    val preferOffline: Boolean = false,
    /** Ask for punctuation/casing quality where the platform supports it (API 33+). */
    val optimizeQuality: Boolean = true,
    /** Language tag for the recognizer; `null` keeps the device default. */
    val languageTag: String? = null,
    /** Words the recognizer should expect (domain vocabulary, session title, …). */
    val biasing: List<String> = emptyList(),
)

object SpeechRequestPolicy {
    /**
     * Silence thresholds are deliberately NOT sent.
     *
     * They were the "react faster" idea from before push-to-talk: 850 ms of silence made the engine
     * call the utterance complete — which cut dictation off in the middle of a sentence and dropped
     * the ending (reported: "it truncates towards the end"). With push-to-talk the ENGINE must not
     * decide when the recording ends; the user's finger does, and `stopListening()` then finalises
     * promptly. The platform defaults stay untouched, because they are tuned per engine and cannot
     * be improved by a guess from here.
     */
    const val DICTATION_END_IS_USER_CONTROLLED = true

    /**
     * Vocabulary the recognizer is biased towards.
     *
     * A dictation feature inside a coding client hears the same words every day — product names,
     * tools, git verbs. Biasing them is the cheapest real accuracy win for exactly those words,
     * and it costs nothing when the platform ignores the hint.
     */
    val DOMAIN_WORDS: List<String> =
        listOf(
            "opencode",
            "OpenCode",
            "Kotlin",
            "Gradle",
            "Compose",
            "Android",
            "adb",
            "logcat",
            "commit",
            "push",
            "pull request",
            "refactor",
            "Container",
            "Dashboard",
            "Kommando",
            "Zentrale",
            "Session",
            "Prompt",
            "Rebase",
            "MCP",
            "Plugin",
        )

    /**
     * The extras for a recognition request.
     *
     * [apiLevel] gates the newer hints: `EXTRA_ENABLE_FORMATTING` and `EXTRA_BIASING_STRINGS` only
     * exist from API 33, and sending unknown extras to an older engine is allowed but pointless —
     * being explicit keeps the intent small and the behaviour predictable.
     */
    fun extras(
        options: SpeechOptions,
        apiLevel: Int,
        base: List<String> = emptyList(),
    ): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map[RecognizerIntent.EXTRA_LANGUAGE_MODEL] = RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        map[RecognizerIntent.EXTRA_PARTIAL_RESULTS] = true
        // One result: the engine's best hypothesis. More results cost latency and the app never
        // showed the alternatives, so it would be paid for nothing.
        map[RecognizerIntent.EXTRA_MAX_RESULTS] = 1

        options.languageTag?.takeIf { it.isNotBlank() }?.let {
            map[RecognizerIntent.EXTRA_LANGUAGE] = it
        }
        if (options.preferOffline) {
            map[RecognizerIntent.EXTRA_PREFER_OFFLINE] = true
        }
        if (apiLevel >= 33) {
            if (options.optimizeQuality) {
                map[RecognizerIntent.EXTRA_ENABLE_FORMATTING] =
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY
            }
            val vocabulary = (options.biasing + base).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (vocabulary.isNotEmpty()) {
                map[RecognizerIntent.EXTRA_BIASING_STRINGS] = ArrayList(vocabulary)
            }
        }
        return map
    }

    /**
     * Words worth biasing for a session: its title, which usually names the work being done
     * ("Berechtigungsprüfung im Login") and therefore the words the user will dictate.
     *
     * Kept short and de-duplicated: a biasing list is a hint, and a long one dilutes it.
     */
    fun biasingFor(
        sessionTitle: String?,
        limit: Int = 12,
    ): List<String> =
        sessionTitle
            ?.split(Regex("[^\\p{L}\\p{N}+#._-]+"))
            ?.map { it.trim() }
            ?.filter { it.length >= 4 && it.toDoubleOrNull() == null }
            ?.distinct()
            ?.take(limit)
            .orEmpty()
}
