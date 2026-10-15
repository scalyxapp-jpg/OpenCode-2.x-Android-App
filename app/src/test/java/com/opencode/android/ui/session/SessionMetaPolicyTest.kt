package com.opencode.android.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a poll tick re-reads the session metadata.
 *
 * The bug this pins: the server names a session from its first turn without touching
 * `time.updated`, and the poller only reacted to that timestamp — so the generated title never
 * arrived (the session stayed "new session").
 */
class SessionMetaPolicyTest {
    @Test
    fun `an arriving title is a trigger on its own`() {
        assertEquals(
            true,
            SessionMetaPolicy.needsMetaRefresh(
                serverTitle = "Berechtigungsprüfung im Login korrigieren",
                currentTitle = "New session",
                contentChanged = false,
            ),
        )
    }

    @Test
    fun `nothing is refreshed when nothing changed`() {
        assertEquals(
            false,
            SessionMetaPolicy.needsMetaRefresh(
                serverTitle = "Fix the login",
                currentTitle = "Fix the login",
                contentChanged = false,
            ),
        )
        // The server has no title yet (still generating, or a build that never names sessions):
        // never clear what we show.
        assertEquals(
            false,
            SessionMetaPolicy.needsMetaRefresh(serverTitle = null, currentTitle = "New session", contentChanged = false),
        )
        assertEquals(
            false,
            SessionMetaPolicy.needsMetaRefresh(serverTitle = "  ", currentTitle = "New session", contentChanged = false),
        )
        // A locally known title is not replaced by nothing.
        assertEquals(
            false,
            SessionMetaPolicy.needsMetaRefresh(serverTitle = null, currentTitle = null, contentChanged = false),
        )
    }

    @Test
    fun `new content refreshes regardless of the title`() {
        assertEquals(
            true,
            SessionMetaPolicy.needsMetaRefresh(serverTitle = "same", currentTitle = "same", contentChanged = true),
        )
    }
}
