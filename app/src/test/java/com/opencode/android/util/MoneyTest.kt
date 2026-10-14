package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared money formatter.
 *
 * Cost used to be formatted in two places (`GlanceStore` and the Centre) - which is how two
 * surfaces end up showing the same number differently. Both delegate here now, so the shape is
 * pinned once.
 */
class MoneyTest {
    @Test
    fun `costs are dollars with two decimals`() {
        assertEquals("$1.23", Money.usd(1.234))
        assertEquals("$0.00", Money.usd(0.0))
        assertEquals("$12.00", Money.usd(12.0))
        assertNull(Money.usd(null))
    }

    @Test
    fun `a trend needs value and a non-zero baseline`() {
        assertEquals("+45% vs. 7-day average", Money.trend(2.9, 2.0, "7-day average"))
        assertEquals("-25% vs. 7-day average", Money.trend(1.5, 2.0, "7-day average"))
        // A percentage against zero (or against nothing) is noise, not information.
        assertNull(Money.trend(1.0, 0.0, "7-day average"))
        assertNull(Money.trend(null, 2.0, "7-day average"))
        assertNull(Money.trend(1.0, null, "7-day average"))
    }
}
