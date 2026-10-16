package com.opencode.android.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech-to-text for the composer, in place.
 *
 * Why `SpeechRecognizer` and not the system dialog (`RecognizerIntent`): dictating must feel like
 * typing — the words land in the field the user is looking at, partial results included, instead
 * of a separate screen that hands text back afterwards.
 *
 * The device's own recognition service does the work (the app never sends audio anywhere itself),
 * so the microphone is only live between [start] and [stop]. Everything that can fail is reported
 * through [onError] instead of a silent nothing: unavailable service, permission missing, no match.
 */

/**
 * `SpeechRecognizer.ERROR_CLIENT` (5) - mirrored: reading the framework constant in a unit test
 * returns 0, and this one is worth naming in the retry rule.
 */
private const val CLIENT_ERROR = 5

/**
 * How long to wait before the single silent retry.
 *
 * The engine needs a moment to finish reconnecting (observed on the device: `raw = 11` while the
 * SODA engine was still reconnecting, and the very next attempt worked).
 */
private const val RETRY_DELAY_MS = 350L

internal class SpeechInput(
    private val context: Context,
) {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Incremented per attempt: a stale attempt's callbacks are dropped instead of reported. */
    private var generation = 0

    /** The user released the button: whatever the engine says now is noise. */
    private var stoppedByUser = false

    /** A final result was already delivered for the current attempt. */
    private var delivered = false

    /** Whether the device offers a recognition service at all. */

    /** Whether the app may record audio right now. */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts listening.
     *
     * [onPartial] fires while the user speaks (the composer shows it as a preview, never writes it
     * into the field), [onFinal] once with the recognized phrase, [onError] with a readable reason.
     * Calling it while already listening is a no-op.
     */
    fun start(
        options: com.opencode.android.util.SpeechOptions,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (com.opencode.android.util.SpeechError, String?) -> Unit,
        /**
         * Text the engine finalised WHILE the button is still held.
         *
         * Engines end an utterance on their own silence threshold even mid-hold; without this the
         * words spoken afterwards were lost - the reported "the last words are cut off".
         */
        onChunk: (String) -> Unit = {},
    ) {
        if (listening) return
        stoppedByUser = false
        delivered = false
        // One silent retry inside the same attempt: a busy/out-of-order engine (busy, or a call that
        // arrived while the previous attempt was still tearing down) is not worth a message while
        // the user is holding the button.
        var retried = false
        // Everything delivered for this attempt, so an error can tell "nothing at all" from "some
        // words already arrived" (the latter must never produce a failure message).
        var deliveredChars = 0
        var lastPartial = ""

        fun begin() {
            // Every sub-session gets its own generation. Tearing the previous engine down fires
            // ERROR_CLIENT on it, and without this the new attempt counted that as ITS failure -
            // which is why "speech recognition failed" appeared even though dictation had worked.
            generation += 1
            val mine = generation
            releaseEngine()
            val engine = createEngine() ?: createEngine()
            if (engine == null) {
                listening = false
                com.opencode.android.util.DictationLog
                    .state("no recognizer could be created")
                onError(com.opencode.android.util.SpeechError.Unavailable, null)
                return
            }
            recognizer = engine
            engine.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit

                    override fun onBeginningOfSpeech() = Unit

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        if (mine != generation) return
                        val classified =
                            com.opencode.android.util.SpeechErrorPolicy
                                .classify(error)
                        // Always-on diagnostics with the raw code: the app's own logger is silent in
                        // release builds, which is why the last report had an EMPTY log.
                        com.opencode.android.util.DictationLog.failure(
                            error = classified,
                            rawCode = error,
                            held = !stoppedByUser,
                            deliveredChars = deliveredChars,
                            partialChars = lastPartial.length,
                        )
                        // Anything already delivered (a chunk earlier in this attempt or a partial)
                        // means words arrived: a failure message would be wrong.
                        val somethingArrived = deliveredChars > 0
                        // Rescue what was already heard: an engine that fails at the end must not
                        // throw away the words it produced (and must not shout "failed" when it has
                        // something to show).
                        val rescue = lastPartial.trim()
                        if (com.opencode.android.util.SpeechErrorPolicy
                                .shouldRescuePartial(lastPartial, delivered)
                        ) {
                            delivered = true
                            com.opencode.android.util.DictationLog.state(
                                "error $error, rescuing the ${rescue.length}-char partial",
                            )
                            onChunk(rescue)
                            listening = false
                            onFinal("")
                            return
                        }
                        // Any transient or UNKNOWN code gets one silent retry, after a short pause:
                        // the engine is often still reconnecting from the previous session (raw 11
                        // on Samsung), and an immediate second call fails again. The delay also lets
                        // the reconnect/language pack finish.
                        if (!retried &&
                            !stoppedByUser &&
                            !delivered &&
                            com.opencode.android.util.SpeechErrorPolicy
                                .isRetryable(classified)
                        ) {
                            retried = true
                            com.opencode.android.util.DictationLog
                                .state("retrying once after $classified (raw $error) in ${RETRY_DELAY_MS}ms")
                            android.os
                                .Handler(android.os.Looper.getMainLooper())
                                .postDelayed(
                                    {
                                        // Still the same attempt AND the user still holds the button:
                                        // only then may a new recording start (push-to-talk rule).
                                        if (mine == generation && listening && !stoppedByUser) begin()
                                    },
                                    RETRY_DELAY_MS,
                                )
                            return
                        }
                        listening = false
                        // Words arrived in this attempt (a chunk or a partial)? Then a failure
                        // message would be a lie - the user can see their text.
                        val report =
                            !somethingArrived &&
                                com.opencode.android.util.SpeechErrorPolicy.shouldReport(
                                    error = classified,
                                    stale = false,
                                    stoppedByUser = stoppedByUser,
                                    delivered = delivered,
                                )
                        if (report) onError(classified, errorLabel(error))
                    }

                    override fun onResults(results: Bundle?) {
                        if (mine != generation) return
                        val text =
                            results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                                .trim()
                        if (text.isEmpty() && lastPartial.trim().isEmpty()) {
                            delivered = true
                            listening = false
                            onFinal("")
                            return
                        }
                        // Still holding? The engine just ended an utterance on its own threshold -
                        // insert what it heard and KEEP RECORDING, so the rest of the sentence is
                        // not lost (the reported truncation).
                        if (!stoppedByUser) {
                            val chunk = text.ifEmpty { lastPartial.trim() }
                            deliveredChars += chunk.length
                            com.opencode.android.util.DictationLog
                                .chunk(chunk.length)
                            if (chunk.isNotEmpty()) onChunk(chunk)
                            lastPartial = ""
                            if (text.isNotEmpty()) {
                                // A fresh engine for the next utterance; the attempt stays the same.
                                begin()
                                return
                            }
                        }
                        delivered = true
                        listening = false
                        onFinal(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (mine != generation) return
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                lastPartial = it
                                onPartial(it)
                            }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?,
                    ) = Unit
                },
            )
            listening = true
            runCatching { engine.startListening(recognitionIntent(options)) }
                .onFailure { failure ->
                    if (!retried && !stoppedByUser) {
                        retried = true
                        com.opencode.android.util.DictationLog.state(
                            "start failed (${failure.message}) - retrying once",
                        )
                        begin()
                        return@onFailure
                    }
                    listening = false
                    releaseEngine()
                    com.opencode.android.util.DictationLog.state(
                        "start failed twice: ${failure.message}",
                    )
                    onError(com.opencode.android.util.SpeechError.Failed, failure.message)
                }
        }
        begin()
    }

    /** The recognizer's own wording for an error code, used as the detail of a failure. */
    fun errorLabel(error: Int): String? =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio problem"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "network problem"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "the recognition service failed"
            else -> null
        }

    /**
     * The recognition request.
     *
     * Every flag comes from [com.opencode.android.util.SpeechRequestPolicy] (pure, tested): shorter
     * silence thresholds, explicit language, optional offline/quality/vocabulary hints. Nothing is
     * decided here.
     */
    private fun recognitionIntent(options: com.opencode.android.util.SpeechOptions): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            com.opencode.android.util.SpeechRequestPolicy
                .extras(options, android.os.Build.VERSION.SDK_INT)
                .forEach { (key, value) ->
                    // `Intent` has no `putExtra(String, Any)`: the platform's own overloads decide
                    // the wire type, so the policy's values are dispatched by type here.
                    when (value) {
                        is String -> {
                            putExtra(key, value)
                        }

                        is Int -> {
                            putExtra(key, value)
                        }

                        is Long -> {
                            putExtra(key, value)
                        }

                        is Boolean -> {
                            putExtra(key, value)
                        }

                        is Float -> {
                            putExtra(key, value)
                        }

                        is ArrayList<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            putExtra(key, value as ArrayList<String>)
                        }
                    }
                }
        }

    private fun createEngine(): SpeechRecognizer? = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()

    private fun releaseEngine() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    /**
     * Ends the attempt because the user released the button.
     *
     * `stopListening()` (not `cancel()`) asks the engine for what it heard - that result still
     * arrives and becomes the composer text. Everything else from this attempt (errors about the
     * ended session) is suppressed.
     */
    fun stop() {
        stoppedByUser = true
        if (!listening) return
        listening = false
        runCatching { recognizer?.stopListening() }
    }

    /** Releases the recognizer. Must be called when the composer leaves the composition. */
    fun destroy() {
        listening = false
        generation += 1
        releaseEngine()
    }
}
