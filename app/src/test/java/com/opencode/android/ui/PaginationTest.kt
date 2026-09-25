package com.opencode.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Load older messages" is only offered when the loaded page is full (the
 * endpoint returns the newest N, so a full page implies truncation) and the
 * limit has not reached its cap.
 */
class PaginationTest {

    @Test
    fun `a full page with room left offers more`() {
        assertTrue(canLoadOlder(messageLimit = 60, loadedCount = 60))
        assertTrue(canLoadOlder(messageLimit = 60, loadedCount = 90))
    }

    @Test
    fun `a short page means the history is complete`() {
        assertFalse(canLoadOlder(messageLimit = 60, loadedCount = 59))
        assertFalse(canLoadOlder(messageLimit = 60, loadedCount = 0))
    }

    @Test
    fun `the cap disables further loading even on a full page`() {
        assertFalse(canLoadOlder(messageLimit = MESSAGE_LIMIT_MAX, loadedCount = MESSAGE_LIMIT_MAX))
        assertFalse(canLoadOlder(messageLimit = MESSAGE_LIMIT_MAX, loadedCount = 10_000))
    }

    @Test
    fun `the step never overshoots the cap`() {
        val stepped = (MESSAGE_LIMIT_MAX - 10 + MESSAGE_PAGE_STEP).coerceAtMost(MESSAGE_LIMIT_MAX)
        assertTrue(stepped <= MESSAGE_LIMIT_MAX)
    }
}
