package com.opencode.android.util

/**
 * Pure, Android-free policies for the streaming pipeline. They used to be
 * inline inside `ChatViewModel`, where none of the arithmetic could be tested
 * without a running server — and a regression in any of them is invisible in
 * normal use (it shows up as jank, a starved reload, or disk churn).
 */

/**
 * Adaptive coalescing window for the live text/reasoning flush.
 *
 * A growing live `Text` gets more expensive to measure on every flush
 * (measured: ~48 ms median frame for a short reply vs ~69 ms for a long one),
 * so short replies flush at ~2 frames for an immediate feel and long buffers
 * widen the window so each flush does more, less often.
 */
object LiveFlushPolicy {
    const val SHORT_MS = 16L
    const val MEDIUM_MS = 33L
    const val LONG_MS = 50L

    const val MEDIUM_ABOVE = 1_200
    const val LONG_ABOVE = 3_000

    fun windowMs(liveLength: Int): Long = when {
        liveLength > LONG_ABOVE -> LONG_MS
        liveLength > MEDIUM_ABOVE -> MEDIUM_MS
        else -> SHORT_MS
    }
}

/**
 * Trailing-debounce scheduler with a hard ceiling.
 *
 * A plain cancel-and-restart debounce is starved by a continuous event stream
 * (every event pushes the timer out, so the persisted turn never lands). Once
 * the oldest pending request is older than [maxWaitMs] the in-flight job is
 * left to fire instead of being restarted.
 *
 * Time is injected so the behaviour is deterministic under test.
 */
class RefreshCoalescer(
    private val maxWaitMs: Long,
) {
    sealed interface Decision {
        /** No batch pending: start one now. */
        data object Start : Decision

        /** Batch pending and still inside the ceiling: restart the timer. */
        data object Restart : Decision

        /** Batch pending but past the ceiling: let the in-flight job fire. */
        data object KeepRunning : Decision
    }

    private var batchStart = 0L

    /**
     * [jobActive] is the caller's own "a scheduled job is still running" flag.
     * Taking it as an argument instead of tracking it here means the policy
     * cannot desync from the job (a cancelled job used to leave it stuck
     * "active" and starve the next refresh).
     */
    fun onRequest(now: Long, jobActive: Boolean): Decision {
        if (!jobActive) {
            batchStart = now
            return Decision.Start
        }
        return if (now - batchStart < maxWaitMs) Decision.Restart else Decision.KeepRunning
    }
}

/**
 * Minimum-interval throttle for expensive writes (the offline cache re-encodes
 * up to 4 MB per write, and a busy turn fires the refresh dozens of times).
 * [force] bypasses the interval — used to guarantee the final state is written.
 */
class WriteThrottle(
    private val minIntervalMs: Long,
) {
    // null = nothing written yet. A 0 sentinel would make the very first call
    // look like it happened "0 ms ago" and wrongly block it.
    private var lastAt: Long? = null

    fun allow(now: Long, force: Boolean = false): Boolean {
        if (force) {
            lastAt = now
            return true
        }
        val last = lastAt
        if (last != null && now - last < minIntervalMs) return false
        lastAt = now
        return true
    }
}
