package com.opencode.android.util

/**
 * Page sizes to try when fetching a session's newest messages, largest first.
 *
 * `GET /session/{id}/message` returns the newest N messages IN FULL — every
 * tool output and diff. A long, tool-heavy session can therefore return tens of
 * megabytes for a single page (measured: ~114 MB at `limit=30`), which exceeds
 * the app's message-body cap and is rejected. The old fallback only retried one
 * smaller page (10), so if that page was also heavy the conversation rendered
 * empty.
 *
 * Walking the ladder guarantees the app finds a page that fits: each step drops
 * to a smaller tail until even a single newest message is tried. The visible
 * result is the newest messages that fit under the cap instead of a blank
 * screen.
 *
 * Pure so the boundary rule is unit-tested.
 */
object MessagePaging {
    /** Progressively smaller tails, ending at 1 so a heavy page still shows something. */
    private val LADDER = listOf(30, 15, 10, 5, 3, 1)

    /**
     * [limit] first (so an explicit "load older" depth is honoured), then the
     * ladder entries strictly smaller than it. Never asks for MORE than the
     * caller requested — a smaller page is only ever a fallback.
     */
    fun pageLadder(limit: Int): List<Int> {
        val effective = if (limit > 0) limit else LADDER.first()
        return (listOf(effective) + LADDER.filter { it < effective }).distinct()
    }
}
