package com.opencode.android.util

/**
 * Pure reconnect/liveness rules for the SSE stream, extracted from `SseClient`.
 *
 * These guard failures that are invisible in normal use: a flapping server
 * causing a tight reconnect loop (battery drain), a half-open TCP connection
 * that never reports an error (dead stream masquerading as "connected"), and a
 * logcat flood on repeated reconnects.
 *
 * Deliberately free of Android/OkHttp types: the arithmetic is the part worth
 * testing, so it lives here and runs as a plain JVM unit test.
 */
object ReconnectPolicy {
    /** Liveness watchdog tick. */
    const val WATCHDOG_TICK_MS = 3_000L

    /**
     * Heartbeat arrives roughly every 10 s, so three missed beats means the
     * stream is dead even though the socket was never closed.
     */
    const val WATCHDOG_TIMEOUT_MS = 12_000L

    /**
     * A connection that lasted at least this long is treated as "it worked,
     * this is a fresh outage" and resets the backoff; anything shorter keeps
     * backing off so a flapping server cannot induce a tight reconnect loop.
     */
    const val STABLE_CONNECTION_MS = 3_000L

    /** A flapping link must not flood logcat; log the first few only. */
    const val MAX_RECONNECT_LOGS = 5

    /** Exponential ceiling with full jitter (arithmetic owned by [RetryPolicy]). */
    fun backoffMs(attempt: Int, jitter: Long): Long = RetryPolicy.backoffMs(attempt, jitter)

    /** True when a connection lived long enough to reset the backoff counter. */
    fun isStable(livedMs: Long): Boolean = livedMs >= STABLE_CONNECTION_MS

    /** True when silence has lasted past the watchdog window. */
    fun watchdogExpired(idleMs: Long): Boolean = idleMs > WATCHDOG_TIMEOUT_MS

    /** True while reconnect logging is still under the flood cap. */
    fun shouldLogReconnect(logCount: Int): Boolean = logCount < MAX_RECONNECT_LOGS
}
