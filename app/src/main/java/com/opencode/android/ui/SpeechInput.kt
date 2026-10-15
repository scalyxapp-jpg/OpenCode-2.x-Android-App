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
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String?) -> Unit,
    ) {
        if (listening) return
        if (!available()) {
            onError(null)
            return
        }
        val engine =
            recognizer
                ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                    .getOrNull()
                    ?.also { recognizer = it }
                ?: run {
                    onError(null)
                    return
                }
        engine.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listening = false
                    // ERROR_NO_MATCH / SPEECH_TIMEOUT are the everyday "nothing was said": no
                    // message spam for those, the button simply stops.
                    val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    onError(if (silent) null else errorLabel(error))
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
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        listening = true
        runCatching { engine.startListening(intent) }
            .onFailure {
                listening = false
                onError(it.message)
            }
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
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    /** Human wording for the recognizer's error codes. */
    private fun errorLabel(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio problem"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "network problem"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "the recognition service failed"
            else -> "error $error"
        }
}
