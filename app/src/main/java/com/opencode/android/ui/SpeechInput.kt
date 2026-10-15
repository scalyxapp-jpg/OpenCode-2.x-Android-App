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
internal class SpeechInput(
    private val context: Context,
) {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Whether the device offers a recognition service at all. */
    fun available(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

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
        /** 0..1 input level while the user speaks, for a level meter. */
        onLevel: (Float) -> Unit = {},
    ) {
        if (listening) return
        // Fresh engine per attempt. Reusing one instance after an error or a cancel is what made
        // dictation work "sometimes": a wedged recognizer accepts no further start.
        releaseEngine()
        // `isRecognitionAvailable()` is deliberately NOT a gate: on some builds (Samsung in
        // particular) it answers false while a recognizer can be created fine. It is only used for
        // the wording when creation genuinely fails.
        val engine = createEngine() ?: createEngine()
        if (engine == null) {
            onError(com.opencode.android.util.SpeechError.Unavailable, null)
            return
        }
        recognizer = engine
        engine.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    // The platform reports roughly -2..10 dBFS; normalise for a meter.
                    onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listening = false
                    // Classified, not guessed: "heard nothing" stays silent, "still busy" invites a
                    // retry, and only a real failure talks about the service. The previous version
                    // collapsed the silent case into "no recognition available" - which told users
                    // their device cannot dictate when it can.
                    onError(
                        com.opencode.android.util.SpeechErrorPolicy
                            .classify(error),
                        errorLabel(error),
                    )
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val text =
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                    onFinal(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(onPartial)
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
                listening = false
                releaseEngine()
                onError(com.opencode.android.util.SpeechError.Failed, failure.message)
            }
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

    /** Stops listening; a result may still arrive (the recognizer returns what it heard). */
    fun stop() {
        if (!listening) return
        listening = false
        runCatching { recognizer?.stopListening() }
    }

    /** Releases the recognizer. Must be called when the composer leaves the composition. */
    fun destroy() {
        listening = false
        releaseEngine()
    }
}
