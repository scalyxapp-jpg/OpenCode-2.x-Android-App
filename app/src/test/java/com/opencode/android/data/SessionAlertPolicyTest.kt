package com.opencode.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The background step alerts must be *occasional*: the user asked for
 * intermediate notifications, not one per step of a 20-step turn.
 */
class SessionAlertPolicyTest {
    private val session = "ses_1"
    private fun seen(vararg labels: String): SessionAlertPolicy.State =
        labels.fold(SessionAlertPolicy.State()) { state, label ->
            SessionAlertPolicy.toolSeen(state, session, label)
        }

    @Test
    fun `the first finished step alerts with the work it did`() {
        val (_, tools) = SessionAlertPolicy.stepEnded(seen("Shell · ls -la", "Read · a.kt"), session, 1_000L)
        assertEquals(listOf("Shell · ls -la", "Read · a.kt"), tools)
    }

    @Test
    fun `a second step inside the window stays quiet`() {
        val (first, firstTools) = SessionAlertPolicy.stepEnded(seen("Shell · ls"), session, 1_000L)
        assertNotNull(firstTools)
        val (_, secondTools) =
            SessionAlertPolicy.stepEnded(
                SessionAlertPolicy.toolSeen(first, session, "Read · b.kt"),
                session,
                1_000L + SessionAlertPolicy.INTERVAL_MS - 1,
            )
        assertNull("throttled inside the window", secondTools)
    }

    @Test
    fun `a later step alerts again`() {
        val (first, _) = SessionAlertPolicy.stepEnded(seen("Shell · ls"), session, 1_000L)
        val (_, tools) =
            SessionAlertPolicy.stepEnded(
                SessionAlertPolicy.toolSeen(first, session, "Read · b.kt"),
                session,
                1_000L + SessionAlertPolicy.INTERVAL_MS,
            )
        assertEquals(listOf("Read · b.kt"), tools)
    }

    @Test
    fun `a turn cannot exceed the cap`() {
        var state = SessionAlertPolicy.State()
        var now = 0L
        var alerts = 0
        repeat(10) {
            now += SessionAlertPolicy.INTERVAL_MS
            state = SessionAlertPolicy.toolSeen(state, session, "Shell · run$it")
            val (next, tools) = SessionAlertPolicy.stepEnded(state, session, now)
            state = next
            if (tools != null) alerts += 1
        }
        assertEquals(SessionAlertPolicy.MAX_PER_TURN, alerts)
    }

    @Test
    fun `a step without any tool is skipped once something was reported`() {
        var (state, first) = SessionAlertPolicy.stepEnded(seen("Shell · ls"), session, 1_000L)
        assertNotNull(first)
        // Nothing new happened: no second heads-up.
        val (next, second) =
            SessionAlertPolicy.stepEnded(state, session, 1_000L + SessionAlertPolicy.INTERVAL_MS)
        assertNull(second)
        state = next
        assertEquals(1, state.notifiedPerTurn[session])
    }

    @Test
    fun `consecutive identical tools collapse into one label`() {
        val state = seen("Shell · ls", "Shell · ls")
        assertEquals(listOf("Shell · ls"), state.pendingTools[session])
    }

    @Test
    fun `a new turn alerts immediately and forgets the old counters`() {
        val (state, _) = SessionAlertPolicy.stepEnded(seen("Shell · ls"), session, 1_000L)
        val reset = SessionAlertPolicy.turnEnded(state, session)
        assertNull(reset.notifiedPerTurn[session])
        assertNull(reset.lastNotifiedAt[session])
        val (_, tools) = SessionAlertPolicy.stepEnded(seen("Read · a.kt"), session, 1_010L)
        assertEquals(listOf("Read · a.kt"), tools)
    }
}
