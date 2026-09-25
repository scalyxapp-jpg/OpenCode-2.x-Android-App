package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Session-list timestamps: a wrong boundary here is immediately visible, and
 * the clock-skew case (a timestamp in the future) must never render a negative
 * age.
 */
class RelativeTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `under a minute reads just now`() {
        assertEquals("just now", RelativeTime.label(now, now))
        assertEquals("just now", RelativeTime.label(now - 59_999, now))
    }

    @Test
    fun `a future timestamp is clamped to just now`() {
        assertEquals("just now", RelativeTime.label(now + 5 * 60_000, now))
    }

    @Test
    fun `minutes hours and days`() {
        assertEquals("1m ago", RelativeTime.label(now - 60_000, now))
        assertEquals("59m ago", RelativeTime.label(now - 59L * 60_000, now))
        assertEquals("1h ago", RelativeTime.label(now - 3_600_000, now))
        assertEquals("23h ago", RelativeTime.label(now - 23L * 3_600_000, now))
        assertEquals("1d ago", RelativeTime.label(now - 86_400_000, now))
        assertEquals("6d ago", RelativeTime.label(now - 6L * 86_400_000, now))
    }

    @Test
    fun `beyond a week falls back to an absolute date`() {
        assertNull(RelativeTime.label(now - 7L * 86_400_000, now))
        assertNull(RelativeTime.label(now - 400L * 86_400_000, now))
    }
}
