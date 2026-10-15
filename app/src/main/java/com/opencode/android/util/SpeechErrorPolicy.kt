package com.opencode.android.util

/**
 * Why a dictation attempt ended without text.
 *
 * The distinction matters more than it looks: "nothing was said" is an everyday event (a pause, a
 * quiet room, a mis-tap) and must stay silent, while "no recognition on this device" is a
 * statement about the hardware. Conflating the two told users their phone cannot dictate when it
 * can — the bug that prompted this type.
 */
enum class SpeechError {
    /** Nothing was heard (`ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT`): say nothing, just stop. */
    NoSpeech,

    /** No recognizer could be created at all. */
    Unavailable,

    /** The recognizer was still busy with a previous attempt. */
    Busy,

    /** The recognition service had a network problem. */
    Network,

    /** The app may not record audio right now. */
    Permission,

    /** Anything else, with the recognizer's own wording. */
    Failed,
}

/**
 * Maps a `SpeechRecognizer` error code to [SpeechError].
 *
 * The codes are mirrored as constants on purpose: reading `SpeechRecognizer.ERROR_*` in a plain JVM
 * test would silently return 0 for every field (the framework stubs return defaults), which would
 * make the classification untestable. The values are part of the platform ABI and stable since
 * API 8; the comments name the framework constant each one belongs to.
 */
object SpeechErrorPolicy {
    const val NETWORK_TIMEOUT = 1 // SpeechRecognizer.ERROR_NETWORK_TIMEOUT
    const val NETWORK = 2 // SpeechRecognizer.ERROR_NETWORK
    const val AUDIO = 3 // SpeechRecognizer.ERROR_AUDIO
    const val SERVER = 4 // SpeechRecognizer.ERROR_SERVER
    const val CLIENT = 5 // SpeechRecognizer.ERROR_CLIENT
    const val SPEECH_TIMEOUT = 6 // SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    const val NO_MATCH = 7 // SpeechRecognizer.ERROR_NO_MATCH
    const val BUSY = 8 // SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    const val INSUFFICIENT_PERMISSIONS = 9 // SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS

    fun classify(code: Int): SpeechError =
        when (code) {
            // Heard nothing / heard nothing in time: silent.
            NO_MATCH, SPEECH_TIMEOUT -> SpeechError.NoSpeech

            // The engine is occupied - retrying is the user's move, not a device verdict.
            BUSY -> SpeechError.Busy

            NETWORK, NETWORK_TIMEOUT -> SpeechError.Network

            INSUFFICIENT_PERMISSIONS -> SpeechError.Permission

            // SERVER / AUDIO / CLIENT and anything unknown: keep the recognizer's own reason.
            else -> SpeechError.Failed
        }

    /** Only the reportable cases get a message; [SpeechError.NoSpeech] never does. */
    fun isReportable(error: SpeechError): Boolean = error != SpeechError.NoSpeech
}
