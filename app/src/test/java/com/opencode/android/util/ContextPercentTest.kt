package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the context-usage percentage shown in the session header. */
class ContextPercentTest {

    @Test
    fun `computes the share of the window`() {
        assertEquals(45, contextPercent(totalTokens = 450_000, limit = 1_000_000))
    }

    @Test
    fun `an unknown limit is null (never a misleading 0)`() {
        assertNull(contextPercent(totalTokens = 450_000, limit = null))
        assertNull(contextPercent(totalTokens = 450_000, limit = 0))
        assertNull(contextPercent(totalTokens = 450_000, limit = -5))
    }

    @Test
    fun `clamps above 100`() {
        assertEquals(100, contextPercent(totalTokens = 2_000_000, limit = 1_000_000))
    }

    @Test
    fun `a genuinely empty context is 0`() {
        assertEquals(0, contextPercent(totalTokens = 0, limit = 1_000_000))
    }
}
