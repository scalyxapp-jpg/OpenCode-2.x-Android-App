package com.opencode.android.ui.centre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the Central Intelligence.
 *
 * These are the part of the Centre that is worth testing: what the app NOTICES. Every rule is
 * deliberately conservative - a false "stuck" alarm on a turn that is merely thinking hard would
 * destroy trust in the whole panel - so the tests pin both directions (fires / stays quiet).
 */
class CentreInsightsTest {
    private val now = 1_800_000_000_000L

    init {
        CentreInsights.evaluationClockAt(now)
    }

    private fun run(
        id: String = "ses_1",
        lastActivityMs: Long = now - 60_000,
        retrying: Boolean = false,
        attempt: Int? = null,
        queueDepth: Int = 0,
        tokens: Long = 0L,
        limit: Long = 0L,
    ) = RunningSignal(
        sessionId = id,
        title = "Fix the bug",
        lastActivityMs = lastActivityMs,
        retrying = retrying,
        retryAttempt = attempt,
        retryMessage = "rate limited",
        queueDepth = queueDepth,
        tokens = tokens,
        tokenLimit = limit,
    )

    @Test
    fun `everything quiet produces exactly one reassuring line`() {
        val insights = CentreInsights.evaluate(CentreSignals())
        assertEquals(1, insights.size)
        assertEquals("All clear", insights.single().title)
        assertEquals(Severity.Info, insights.single().severity)
    }

    @Test
    fun `a running turn with activity is not called stuck`() {
        // 9 minutes of silence is still inside the threshold: long tool calls happen.
        val insights = CentreInsights.evaluate(CentreSignals(running = listOf(run(lastActivityMs = now - 9 * 60_000))))
        assertTrue(insights.none { it.id.startsWith("stuck") })
    }

    @Test
    fun `a running turn without progress offers the stop action`() {
        val insights = CentreInsights.evaluate(CentreSignals(running = listOf(run(lastActivityMs = now - 20 * 60_000))))
        val stuck = insights.first { it.id.startsWith("stuck") }
        assertEquals(Severity.Warning, stuck.severity)
        assertEquals("No progress for 20 min", stuck.title)
        assertEquals("Fix the bug", stuck.detail)
        assertEquals(InsightAction.StopSession, stuck.action)
        assertEquals("ses_1", stuck.sessionId)
    }

    @Test
    fun `an unknown last activity never reports stuck`() {
        val insights = CentreInsights.evaluate(CentreSignals(running = listOf(run(lastActivityMs = 0L))))
        assertTrue(insights.none { it.id.startsWith("stuck") })
    }

    @Test
    fun `a retry loop is named with its attempt and reason`() {
        // Attempt 1 is a hiccup; from the second attempt on it is a loop.
        val once = CentreInsights.evaluate(CentreSignals(running = listOf(run(retrying = true, attempt = 1))))
        assertTrue(once.none { it.id.startsWith("retry") })

        val twice = CentreInsights.evaluate(CentreSignals(running = listOf(run(retrying = true, attempt = 2))))
        val retry = twice.first { it.id.startsWith("retry") }
        assertEquals("Retrying (attempt 2)", retry.title)
        assertTrue(retry.detail.contains("rate limited"))
        assertEquals(InsightAction.OpenSession, retry.action)
    }

    @Test
    fun `queued prompts behind a turn are offered for steering`() {
        val insights = CentreInsights.evaluate(CentreSignals(running = listOf(run(queueDepth = 3))))
        val queued = insights.first { it.id.startsWith("queued") }
        assertEquals("3 prompts waiting behind this turn", queued.title)
        assertEquals(InsightAction.SteerQueue, queued.action)
        // Singular reads correctly.
        val one = CentreInsights.evaluate(CentreSignals(running = listOf(run(queueDepth = 1))))
        assertEquals("1 prompt waiting behind this turn", one.first { it.id.startsWith("queued") }.title)
    }

    @Test
    fun `context pressure names the fill level and offers compaction`() {
        val below = CentreInsights.evaluate(CentreSignals(running = listOf(run(tokens = 800, limit = 1_000))))
        assertTrue(below.none { it.id.startsWith("context") })

        val above = CentreInsights.evaluate(CentreSignals(running = listOf(run(tokens = 900, limit = 1_000))))
        val pressure = above.first { it.id.startsWith("context") }
        assertEquals("Context 90% full", pressure.title)
        assertEquals(InsightAction.CompactSession, pressure.action)
        assertTrue(pressure.detail.contains("/compact"))
        // No window known -> no guess.
        val unknown = CentreInsights.evaluate(CentreSignals(running = listOf(run(tokens = 900, limit = 0))))
        assertTrue(unknown.none { it.id.startsWith("context") })
    }

    @Test
    fun `a cost spike needs both the factor and the floor`() {
        // 2.5× the average, but only cents: not worth a line.
        val small = CentreInsights.evaluate(CentreSignals(costToday = 0.5, costBaselinePerDay = 0.2))
        assertTrue(small.none { it.id == "cost" })

        val spike = CentreInsights.evaluate(CentreSignals(costToday = 6.0, costBaselinePerDay = 2.0))
        val cost = spike.first { it.id == "cost" }
        assertEquals("Today costs $6.00", cost.title)
        assertTrue(cost.detail.contains("3.0×"))
        assertEquals(Severity.Notice, cost.severity)

        // Without a baseline there is nothing to compare against.
        assertTrue(CentreInsights.evaluate(CentreSignals(costToday = 9.0)).none { it.id == "cost" })
    }

    @Test
    fun `waiting work, orphaned requests and unsent prompts are all named`() {
        val insights =
            CentreInsights.evaluate(
                CentreSignals(
                    needsYou = 2,
                    orphaned = 5,
                    outboxWaiting = 1,
                    mcpTotal = 4,
                    mcpConnected = 3,
                ),
            )
        assertEquals("1 prompt could not be sent", insights.first { it.id == "outbox" }.title)
        assertEquals("2 sessions waiting for you", insights.first { it.id == "needs" }.title)
        assertEquals("5 requests from deleted sessions", insights.first { it.id == "orphaned" }.title)
        assertEquals("1 MCP server not connected", insights.first { it.id == "mcp" }.title)
    }

    @Test
    fun `a dirty project is actionable only when a session can open it`() {
        // The Changes tab lives in a session, so the insight carries its newest session id.
        val withTarget =
            CentreInsights.evaluate(
                CentreSignals(
                    dirtyProjects = listOf(DirtyProject("proj · main · 2 files (+4/−1)", "ses_9")),
                ),
            ).first { it.id == "dirty" }
        assertEquals(InsightAction.OpenChanges, withTarget.action)
        assertEquals("ses_9", withTarget.sessionId)
        assertTrue(withTarget.canRun)
        assertTrue(withTarget.detail.contains("2 files"))

        // Without a session the statement stays, but the button must not appear - a visible
        // "Open" that does nothing is what made the Centre look broken.
        val withoutTarget =
            CentreInsights.evaluate(
                CentreSignals(dirtyProjects = listOf(DirtyProject("proj · main · 1 file (+1/−0)"))),
            ).first { it.id == "dirty" }
        assertEquals(InsightAction.None, withoutTarget.action)
        assertEquals(false, withoutTarget.canRun)
    }

    @Test
    fun `an action without a session is not offered`() {
        // Belt and braces: canRun is what the screen gates the button on.
        assertTrue(Insight("a", Severity.Warning, "t", "d", sessionId = "ses_1", action = InsightAction.StopSession).canRun)
        assertEquals(
            false,
            Insight("a", Severity.Warning, "t", "d", sessionId = null, action = InsightAction.StopSession).canRun,
        )
        assertEquals(false, Insight("a", Severity.Info, "t", "d", action = InsightAction.None).canRun)
    }

    @Test
    fun `severity decides the order, and actionable lines come first inside a severity`() {
        val insights =
            CentreInsights.evaluate(
                CentreSignals(
                    running = listOf(run(lastActivityMs = now - 30 * 60_000)),
                    needsYou = 1,
                    orphaned = 2,
                ),
            )
        assertEquals(Severity.Warning, insights.first().severity)
        // Inside one severity: something the user can do before pure information.
        val notices = insights.filter { it.severity == Severity.Notice }
        assertTrue(notices.indexOfFirst { it.action != InsightAction.None } <= notices.indexOfFirst { it.id == "needs" })
        // Info severity last.
        assertEquals(Severity.Info, insights.last().severity)
    }
}
