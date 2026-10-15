package com.opencode.android.util

/**
 * Always-on diagnostics for dictation.
 *
 * [AppLog] is deliberately silent in release builds (it is gated on `FLAG_DEBUGGABLE`), which is
 * right for verbose developer output — but it meant a *user-reported* dictation failure left no
 * trace at all, and the log was empty exactly when it was needed. This logger writes to logcat
 * unconditionally.
 *
 * Never logs recognized text: dictation content is private, and a diagnosis only needs the error
 * code, the state and the lengths. Tag: `OpenCodeDictation`.
 */
object DictationLog {
    const val TAG = "OpenCodeDictation"

    fun state(message: String) {
        android.util.Log.i(TAG, message)
    }

    /** A failure with the recognizer's raw code - the part a diagnosis needs. */
    fun failure(
        error: Any,
        rawCode: Int?,
        held: Boolean,
        deliveredChars: Int,
        partialChars: Int,
    ) {
        android.util.Log.w(
            TAG,
            "failure=$error raw=${rawCode ?: "-"} held=$held deliveredChars=$deliveredChars partialChars=$partialChars",
        )
    }

    /** A chunk that was inserted while the button stayed held. */
    fun chunk(length: Int) {
        android.util.Log.i(TAG, "chunk of $length chars inserted (still holding)")
    }
}
