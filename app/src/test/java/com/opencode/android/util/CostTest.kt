package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** A raw Double printed as "$2.1897245279999997" is not a price. */
class CostTest {

    @Test
    fun `rounds a long double to cents`() {
        assertEquals("$2.19", formatCost(2.1897245279999997))
    }

    @Test
    fun `keeps exact cents`() {
        assertEquals("$11.25", formatCost(11.25))
    }

    @Test
    fun `calls out sub-cent amounts`() {
        assertEquals("<$0.01", formatCost(0.004))
        assertEquals("<$0.01", formatCost(0.0))
    }
}
