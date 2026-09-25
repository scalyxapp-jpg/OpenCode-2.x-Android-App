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
    const val WATCHDOG_TICK_MS = 5_000L

    /**
     * The server emits `server.heartbeat` roughly every 13–15 s (measured), not
     * every 10 s. A 12 s window fired *between* two heartbeats, so the app
     * reconnected every ~12 s, and an event that lands once (like
     * `session.idle`) was lost in the reconnect gap — the UI then sat on
     * "generating…" forever. Keep the window comfortably above two heartbeat
     * intervals so a live stream is never mistaken for a dead one.
     */
    const val WATCHDOG_TIMEOUT_MS = 35_000L

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
