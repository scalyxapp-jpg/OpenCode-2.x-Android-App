package com.opencode.android.util

import android.content.pm.ApplicationInfo

/**
 * Logging that is safe for user content.
 *
 * Two rules, learned from a real leak in this app:
 *
 *  1. **Content never reaches logcat in a release build.** Prompts, message
 *     parts, tool output and file paths are the user's data; `Log.d("... $text")`
 *     put them in a buffer that adb, a debugger or (on older/rooted devices) any
 *     `READ_LOGS` holder can read. [d]/[w] are no-ops in release.
 *  2. **Errors still log in release**, but callers pass a message without user
 *     content — an exception message is fine, the payload it failed on is not.
 *
 * Enabled from `Application.onCreate` via [init]; the flag mirrors the
 * debuggable attribute so no `BuildConfig` (disabled in this project) is needed.
 */
object AppLog {
    @Volatile
    private var enabled = false

    val isEnabled: Boolean get() = enabled

    fun init(applicationInfo: ApplicationInfo) {
        enabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** Debug-only. [message] is a lambda so the string is not built in release. */
    fun d(tag: String, message: () -> String) {
        if (enabled) android.util.Log.d(tag, message())
    }

    /** Debug-only warning. */
    fun w(tag: String, message: () -> String) {
        if (enabled) android.util.Log.w(tag, message())
    }

    /** Logs in every build. Never pass user content here. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            android.util.Log.e(tag, message, throwable)
        } else {
            android.util.Log.e(tag, message)
        }
    }
}
