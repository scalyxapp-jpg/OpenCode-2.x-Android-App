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

    /**
     * Whether a reported error should actually reach the user.
     *
     * The "speech recognition failed although it worked" report came from noise that is *expected*:
     * releasing the button makes the engine emit `ERROR_CLIENT` (or "no match") for the attempt the
     * user just ended, and a stale attempt can answer after a new one started. None of that is
     * worth a message.
     *
     * Reported only when the attempt is still the current one, the user did NOT end it, nothing was
     * delivered yet, and the reason is worth telling ([isReportable]).
     */
    /**
     * Whether the partial the engine already produced should be used instead of reporting a failure.
     *
     * An engine that fails at the END of an utterance has usually heard most of it. Throwing that
     * away to print "failed" is the worst of both worlds: the user gets neither their words nor a
     * useful message. Only when nothing was heard does the failure stand.
     */
    fun shouldRescuePartial(
        partial: String,
        delivered: Boolean,
    ): Boolean = !delivered && partial.trim().isNotEmpty()

    /**
     * Whether the attempt deserves ONE silent retry before the user is told anything.
     *
     * Learned from the device log: Samsung's engine answered `raw = 11` (outside the documented
     * AOSP range 1..9, `ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`) while the engine was still
     * reconnecting from the previous session — and the very next attempt four seconds later worked
     * perfectly. So an unknown/OEM code is *transient* by definition here: retrying is right, a
     * message is wrong.
     *
     * Never retried: `NoSpeech` (nothing to retry), `Permission` (needs the user) and `Unavailable`
     * (the device really has no recognizer - creating another one immediately cannot help; the
     * creation path already retries once).
     */
    fun isRetryable(error: SpeechError): Boolean =
        when (error) {
            SpeechError.NoSpeech, SpeechError.Permission, SpeechError.Unavailable -> false
            SpeechError.Busy, SpeechError.Network, SpeechError.Failed -> true
        }

    fun shouldReport(
        error: SpeechError,
        stale: Boolean,
        stoppedByUser: Boolean,
        delivered: Boolean,
    ): Boolean = !stale && !stoppedByUser && !delivered && isReportable(error)
}
