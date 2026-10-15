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
    ) {
        if (listening) return
        // Bump BEFORE touching the old engine: its callbacks must not be attributed to this attempt.
        generation += 1
        val mine = generation
        stoppedByUser = false
        delivered = false
        // One silent retry inside the same attempt: a busy/out-of-order engine (busy, or a call that
        // arrived while the previous attempt was still tearing down) is not worth a message while
        // the user is holding the button.
        var retried = false

        fun begin() {
            releaseEngine()
            val engine = createEngine() ?: createEngine()
            if (engine == null) {
                listening = false
                com.opencode.android.util.AppLog.e(
                    com.opencode.android.util.APP_LOG_TAG,
                    "dictation: no recognizer could be created",
                )
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
                        val classified = com.opencode.android.util.SpeechErrorPolicy.classify(error)
                        // Every failure is logged WITH its raw code: the log is the only way to tell
                        // "the engine was busy" from "the network conked out" afterwards.
                        com.opencode.android.util.AppLog.w(
                            com.opencode.android.util.APP_LOG_TAG,
                        ) { "dictation: $classified (raw code $error, held=${!stoppedByUser})" }
                        val retryable =
                            classified == com.opencode.android.util.SpeechError.Busy ||
                                error == CLIENT_ERROR
                        if (!retried && !stoppedByUser && !delivered && retryable) {
                            retried = true
                            com.opencode.android.util.AppLog.d(
                                com.opencode.android.util.APP_LOG_TAG,
                            ) { "dictation: retrying once after $classified" }
                            begin()
                            return
                        }
                        listening = false
                        if (com.opencode.android.util.SpeechErrorPolicy
                                .shouldReport(
                                    error = classified,
                                    stale = false,
                                    stoppedByUser = stoppedByUser,
                                    delivered = delivered,
                                )
                        ) {
                            onError(classified, errorLabel(error))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (mine != generation) return
                        listening = false
                        delivered = true
                        val text =
                            results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                        com.opencode.android.util.AppLog.d(
                            com.opencode.android.util.APP_LOG_TAG,
                        ) { "dictation: final result with ${text.length} chars" }
                        onFinal(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (mine != generation) return
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(onPartial)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )
            listening = true
            runCatching { engine.startListening(recognitionIntent(options)) }
                .onFailure { failure ->
                    if (!retried && !stoppedByUser) {
                        retried = true
                        com.opencode.android.util.AppLog.w(
                            com.opencode.android.util.APP_LOG_TAG,
                        ) { "dictation: start failed (${failure.message}) - retrying once" }
                        begin()
                        return@onFailure
                    }
                    listening = false
                    releaseEngine()
                    com.opencode.android.util.AppLog.e(
                        com.opencode.android.util.APP_LOG_TAG,
                        "dictation: start failed twice: ${failure.message}",
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
