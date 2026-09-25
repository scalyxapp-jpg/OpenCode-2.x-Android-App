package com.opencode.android.util

import com.opencode.android.util.RefreshCoalescer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming policies were inline in `ChatViewModel` and therefore untested.
 * Each one guards a failure that is invisible in normal use: janky flushes, a
 * refresh that never lands, or disk churn during a busy turn.
 */
class StreamPoliciesTest {

    // --- LiveFlushPolicy ---------------------------------------------------

    @Test
    fun `flush window widens as the buffer grows`() {
        assertEquals(LiveFlushPolicy.SHORT_MS, LiveFlushPolicy.windowMs(0))
        assertEquals(LiveFlushPolicy.SHORT_MS, LiveFlushPolicy.windowMs(1_200))
        assertEquals(LiveFlushPolicy.MEDIUM_MS, LiveFlushPolicy.windowMs(1_201))
        assertEquals(LiveFlushPolicy.MEDIUM_MS, LiveFlushPolicy.windowMs(3_000))
        assertEquals(LiveFlushPolicy.LONG_MS, LiveFlushPolicy.windowMs(3_001))
        assertEquals(LiveFlushPolicy.LONG_MS, LiveFlushPolicy.windowMs(500_000))
    }

    // --- RefreshCoalescer --------------------------------------------------

    @Test
    fun `first request starts a batch`() {
        val c = RefreshCoalescer(maxWaitMs = 1_000)
        assertEquals(Decision.Start, c.onRequest(now = 0, jobActive = false))
    }

    @Test
    fun `requests inside the ceiling restart the debounce`() {
        val c = RefreshCoalescer(maxWaitMs = 1_000)
        c.onRequest(now = 0, jobActive = false)
        assertEquals(Decision.Restart, c.onRequest(now = 400, jobActive = true))
        assertEquals(Decision.Restart, c.onRequest(now = 999, jobActive = true))
    }

    @Test
    fun `requests past the ceiling are not restarted so the job can fire`() {
        val c = RefreshCoalescer(maxWaitMs = 1_000)
        c.onRequest(now = 0, jobActive = false)
        assertEquals(Decision.KeepRunning, c.onRequest(now = 1_000, jobActive = true))
        assertEquals(Decision.KeepRunning, c.onRequest(now = 5_000, jobActive = true))
    }

    @Test
    fun `an inactive job always starts a fresh batch`() {
        val c = RefreshCoalescer(maxWaitMs = 1_000)
        c.onRequest(now = 0, jobActive = false)
        // Even long past the ceiling: no job is running, so a new batch begins.
        assertEquals(Decision.Start, c.onRequest(now = 10_000, jobActive = false))
        // ...and the ceiling is measured from that new start.
        assertEquals(Decision.Restart, c.onRequest(now = 10_500, jobActive = true))
    }

    // --- WriteThrottle -----------------------------------------------------

    @Test
    fun `throttle allows the first write then blocks inside the interval`() {
        val t = WriteThrottle(minIntervalMs = 2_000)
        assertTrue(t.allow(now = 0))
        assertFalse(t.allow(now = 1_999))
        assertTrue(t.allow(now = 2_000))
    }

    @Test
    fun `force always writes and resets the interval`() {
        val t = WriteThrottle(minIntervalMs = 2_000)
        assertTrue(t.allow(now = 0))
        assertTrue(t.allow(now = 100, force = true))
        // The forced write moved the window: still blocked just after it.
        assertFalse(t.allow(now = 1_500))
        assertTrue(t.allow(now = 2_100))
    }
}
