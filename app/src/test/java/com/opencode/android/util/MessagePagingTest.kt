package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message-page ladder keeps a long, tool-heavy session from rendering
 * empty: the requested page is tried first, then progressively smaller tails
 * down to a single message.
 */
class MessagePagingTest {
    @Test
    fun `default page falls through to single-message tail`() {
        assertEquals(listOf(30, 15, 10, 5, 3, 1), MessagePaging.pageLadder(30))
    }

    @Test
    fun `explicit depth is tried first and never duplicated`() {
        assertEquals(listOf(120, 30, 15, 10, 5, 3, 1), MessagePaging.pageLadder(120))
        assertEquals(listOf(10, 5, 3, 1), MessagePaging.pageLadder(10))
    }

    @Test
    fun `non-positive limit still yields the safe ladder`() {
        assertEquals(listOf(30, 15, 10, 5, 3, 1), MessagePaging.pageLadder(0))
        assertEquals(listOf(30, 15, 10, 5, 3, 1), MessagePaging.pageLadder(-5))
    }

    @Test
    fun `ladder is strictly descending and ends at one`() {
        val ladder = MessagePaging.pageLadder(60)
        assertEquals(ladder.sortedDescending(), ladder)
        assertEquals(1, ladder.last())
        assertTrue("ladder must shrink", ladder.first() > ladder.last())
    }
}
