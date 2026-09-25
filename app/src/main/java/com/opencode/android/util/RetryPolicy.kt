package com.opencode.android.util

/**
 * Reconnect backoff policy for the SSE stream.
 *
 * Deliberately free of Android types: the arithmetic is the part worth testing,
 * so it lives here and runs as a plain JVM unit test.
 *
 * Policy: exponential ceiling with FULL JITTER over the upper half of the
 * window, floored so a flapping link cannot spin. Resetting the attempt counter
 * is the caller's job and must only happen after a connection proved stable —
 * resetting on any clean close is what produced a tight reconnect loop.
 */
object RetryPolicy {
    const val BASE_MS = 250L
    const val MAX_MS = 8_000L
    const val MIN_MS = 250L
    const val MAX_SHIFT = 6

    /** Exponential ceiling for a 1-based [attempt], capped at [MAX_MS]. */
    fun ceilingMs(attempt: Int): Long =
        (BASE_MS shl (attempt - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_MS)

    /**
     * Full-jitter delay: uniformly in `[ceiling/2, ceiling]`, floored at
     * [MIN_MS] and capped at [MAX_MS].
     *
     * [jitter] may be any value from the caller's RNG (including negative) — it
     * is mapped into range rather than trusted, so the result is always valid.
     */
    fun backoffMs(attempt: Int, jitter: Long): Long {
        val ceiling = ceilingMs(attempt)
        val half = ceiling / 2
        val span = (ceiling - half).coerceAtLeast(1L)
        val value = half + Math.floorMod(jitter, span)
        return value.coerceIn(MIN_MS, MAX_MS)
    }
}
