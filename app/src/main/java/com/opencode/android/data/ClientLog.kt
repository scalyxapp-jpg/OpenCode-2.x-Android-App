package com.opencode.android.data

import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Forwards client-side errors to the server log (`POST /log`).
 *
 * Why: a failure on the device (a swallowed exception, a failed send) left no
 * trace on the host, so diagnosing it meant asking the user for logcat. The
 * server's `/log` endpoint accepts `{service, level, message}` and records it
 * next to the server's own logs.
 *
 * Best-effort and rate-limited: a crash loop must not turn into a request loop,
 * and a log call must never throw into the caller.
 */
object ClientLog {
    private const val SERVICE = "opencode-android"
    private const val MAX_PER_MINUTE = 20
    private const val DEDUPE_WINDOW_MS = 5_000L

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + com.opencode.android.util.LogAndSwallow)

    @Volatile
    private var apiProvider: (() -> OpenCodeApi)? = null

    private val recent = mutableMapOf<String, Long>()
    private val windowStart = Array(1) { 0L }
    private var windowCount = 0

    /** Installed once the backend session exists (MainActivity / AppModule). */
    fun install(provider: () -> OpenCodeApi) {
        apiProvider = provider
    }

    fun report(
        level: String,
        message: String,
    ) {
        val provider = apiProvider ?: return
        // Exception text can embed prompts, URLs with tokens and file paths, so
        // forward only a short, single-line summary rather than the raw string.
        val sanitized = sanitize(message)
        if (sanitized.isBlank()) return
        if (!allow(sanitized)) return
        scope.launch {
            try {
                provider()
                    .postLog(
                        JsonObject(
                            mapOf(
                                "service" to JsonPrimitive(SERVICE),
                                "level" to JsonPrimitive(level),
                                "message" to JsonPrimitive(sanitized),
                            ),
                        ),
                    ).close()
            } catch (e: Exception) {
                // Never recurse into reporting a reporting failure.
                AppLog.w(APP_LOG_TAG) { "ClientLog: post failed: ${e.message}" }
            }
        }
    }

    /**
     * Reduces free-form error text to a short single line so prompts, URLs and
     * file paths embedded in exception messages do not persist in server logs.
     */
    private fun sanitize(message: String): String =
        message
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(300)

    /** Dedupe identical messages and cap the rate; returns true when allowed. */
    private fun allow(message: String): Boolean =
        synchronized(recent) {
            val now = System.currentTimeMillis()
            val last = recent[message]
            if (last != null && now - last < DEDUPE_WINDOW_MS) return false
            recent[message] = now
            if (recent.size > 200) {
                recent.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
            }
            if (now - windowStart[0] > 60_000L) {
                windowStart[0] = now
                windowCount = 0
            }
            if (windowCount >= MAX_PER_MINUTE) return false
            windowCount++
            true
        }
}
