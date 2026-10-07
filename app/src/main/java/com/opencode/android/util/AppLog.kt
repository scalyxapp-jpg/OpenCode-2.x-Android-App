package com.opencode.android.util

import android.content.pm.ApplicationInfo

/**
 * Logging that is safe for user content, plus a bounded in-memory ring buffer
 * that is streamed to the guard proxy so the developer can read every line
 * without adb/USB.
 *
 * Rules learned from a real leak in this app:
 *
 *  1. **Content never reaches logcat in a release build.** Prompts, message
 *     parts, tool output and file paths are the user's data; `Log.d("... $text")`
 *     put them in a buffer that adb, a debugger or (on older/rooted devices) any
 *     `READ_LOGS` holder can read. [d]/[w] are no-ops for LOGCAT in release.
 *  2. **Errors still log to logcat in release**, but callers pass a message
 *     without user content.
 *
 * Everything (including release [d]/[w]) is recorded into the in-memory ring
 * buffer so it can be inspected on-device. The buffer is bounded, so it never
 * grows without limit. Nothing is uploaded anywhere automatically.
 *
 * Enabled from `Application.onCreate` via [init]; the flag mirrors the
 * debuggable attribute so no `BuildConfig` (disabled in this project) is needed.
 */
object AppLog {
    private const val MAX_BUFFER = 4000

    private val buffer = ArrayDeque<Entry>(MAX_BUFFER)
    private val lock = Any()
    private var nextSeq = 0L

    private data class Entry(
        val seq: Long,
        val line: String,
    )

    @Volatile
    private var enabled = false

    val isEnabled: Boolean get() = enabled

    fun init(applicationInfo: ApplicationInfo) {
        enabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun remember(
        tag: String,
        message: String,
    ) {
        synchronized(lock) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            nextSeq += 1
            buffer.addLast(Entry(nextSeq, "${System.currentTimeMillis()} [$tag] $message"))
        }
    }

    /** Snapshot of the ring buffer, oldest first. */
    fun recent(): List<String> = synchronized(lock) { buffer.map { it.line } }

    /**
     * Lines recorded after [afterSeq] plus the highest sequence included. The
     * uploader advances its cursor by the returned sequence so a flush never
     * re-sends the same line.
     */
    fun since(afterSeq: Long): Pair<Long, List<String>> =
        synchronized(lock) {
            val fresh = buffer.filter { it.seq > afterSeq }
            val highest = fresh.lastOrNull()?.seq ?: afterSeq
            highest to fresh.map { it.line }
        }

    fun clearBuffer() {
        synchronized(lock) { buffer.clear() }
    }

    /**
     * Always recorded and, in debug, also written to logcat. [message] must not
     * contain user content.
     */
    fun record(
        tag: String,
        message: () -> String,
    ) {
        val text = message()
        remember(tag, text)
        if (enabled) android.util.Log.d(tag, text)
    }

    /**
     * Recorded always (so the upload has the full picture); written to logcat
     * only in debug. [message] is a lambda so the string is not built when the
     * ring buffer is disabled — it is always on, so this always builds.
     */
    fun d(
        tag: String,
        message: () -> String,
    ) {
        val text = message()
        remember(tag, text)
        if (enabled) android.util.Log.d(tag, text)
    }

    /** Recorded always; logcat only in debug. */
    fun w(
        tag: String,
        message: () -> String,
    ) {
        val text = message()
        remember(tag, text)
        if (enabled) android.util.Log.w(tag, text)
    }

    /** Logs to logcat in every build; also recorded. Never pass user content. */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        // Attach the stack trace only in debug: in release an exception message
        // can embed prompts, URLs with tokens and file paths.
        if (throwable != null && enabled) {
            android.util.Log.e(tag, message, throwable)
        } else {
            android.util.Log.e(tag, message)
        }
        remember(tag, message)
    }
}
