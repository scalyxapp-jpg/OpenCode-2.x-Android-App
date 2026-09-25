package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the reconnect/liveness rules extracted from `SseClient`. A regression
 * here is invisible in normal use and shows up as battery drain (tight
 * reconnect loop) or a permanently dead stream (missing watchdog).
 */
class ReconnectPolicyTest {

    @Test
    fun `backoff delegates to the retry arithmetic`() {
        assertEquals(RetryPolicy.backoffMs(2, 123L), ReconnectPolicy.backoffMs(2, 123L))
        assertTrue(ReconnectPolicy.backoffMs(1, 0L) in RetryPolicy.MIN_MS..RetryPolicy.MAX_MS)
        assertTrue(ReconnectPolicy.backoffMs(9, 999L) <= RetryPolicy.MAX_MS)
    }

    @Test
    fun `a connection is stable only at or past the threshold`() {
        assertFalse(ReconnectPolicy.isStable(ReconnectPolicy.STABLE_CONNECTION_MS - 1))
        assertTrue(ReconnectPolicy.isStable(ReconnectPolicy.STABLE_CONNECTION_MS))
        assertTrue(ReconnectPolicy.isStable(60_000L))
    }

    @Test
    fun `watchdog fires only past the timeout`() {
        assertFalse(ReconnectPolicy.watchdogExpired(ReconnectPolicy.WATCHDOG_TIMEOUT_MS))
        assertTrue(ReconnectPolicy.watchdogExpired(ReconnectPolicy.WATCHDOG_TIMEOUT_MS + 1))
    }

    @Test
    fun `reconnect logging is capped`() {
        assertTrue(ReconnectPolicy.shouldLogReconnect(0))
        assertTrue(ReconnectPolicy.shouldLogReconnect(ReconnectPolicy.MAX_RECONNECT_LOGS - 1))
        assertFalse(ReconnectPolicy.shouldLogReconnect(ReconnectPolicy.MAX_RECONNECT_LOGS))
        assertFalse(ReconnectPolicy.shouldLogReconnect(100))
    }
}
