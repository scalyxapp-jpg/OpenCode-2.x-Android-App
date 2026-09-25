package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backoff arithmetic guards against a tight reconnect loop (H3). These
 * tests pin the bounds, because a regression here is invisible in normal use
 * and only shows up as battery drain / request storms.
 */
class RetryPolicyTest {

    @Test
    fun `ceiling grows exponentially then caps`() {
        assertEquals(250L, RetryPolicy.ceilingMs(1))
        assertEquals(500L, RetryPolicy.ceilingMs(2))
        assertEquals(1_000L, RetryPolicy.ceilingMs(3))
        assertEquals(2_000L, RetryPolicy.ceilingMs(4))
        assertEquals(4_000L, RetryPolicy.ceilingMs(5))
        assertEquals(RetryPolicy.MAX_MS, RetryPolicy.ceilingMs(6))
        assertEquals(RetryPolicy.MAX_MS, RetryPolicy.ceilingMs(7))
    }

    @Test
    fun `ceiling never exceeds the maximum for extreme attempts`() {
        assertEquals(RetryPolicy.MAX_MS, RetryPolicy.ceilingMs(50))
        assertEquals(RetryPolicy.MAX_MS, RetryPolicy.ceilingMs(Int.MAX_VALUE))
    }

    @Test
    fun `ceiling treats non-positive attempts as the first attempt`() {
        assertEquals(RetryPolicy.BASE_MS, RetryPolicy.ceilingMs(0))
        assertEquals(RetryPolicy.BASE_MS, RetryPolicy.ceilingMs(-3))
    }

    @Test
    fun `jitter stays within the upper half of the window`() {
        for (attempt in 1..8) {
            val ceiling = RetryPolicy.ceilingMs(attempt)
            val half = ceiling / 2
            for (jitter in listOf(0L, 1L, 7L, 123_456L, Long.MAX_VALUE, -1L, -9_999L)) {
                val value = RetryPolicy.backoffMs(attempt, jitter)
                assertTrue(
                    "attempt=$attempt jitter=$jitter value=$value",
                    value in half..ceiling,
                )
            }
        }
    }

    @Test
    fun `delay never drops below the floor`() {
        // A short first ceiling must still not spin.
        val value = RetryPolicy.backoffMs(1, 0L)
        assertTrue(value >= RetryPolicy.MIN_MS)
    }

    @Test
    fun `delay never exceeds the maximum`() {
        val value = RetryPolicy.backoffMs(99, Long.MAX_VALUE)
        assertTrue(value <= RetryPolicy.MAX_MS)
    }

    @Test
    fun `negative jitter is mapped instead of producing a negative delay`() {
        val value = RetryPolicy.backoffMs(4, Long.MIN_VALUE)
        assertTrue(value > 0)
        assertTrue(value <= RetryPolicy.MAX_MS)
    }
}
