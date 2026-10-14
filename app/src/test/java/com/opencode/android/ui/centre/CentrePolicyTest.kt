package com.opencode.android.ui.centre

import com.opencode.android.data.AuditEvent
import com.opencode.android.data.AuditKind
import com.opencode.android.data.AuditPolicy
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.QuestionItem
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.SessionTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules behind the cockpit screen.
 *
 * Both are pure on purpose: what the user sees FIRST (a running turn before an idle one)
 * and what counts as "needs you" are the whole value of the screen, so they are pinned
 * here instead of only being visible in the UI.
 */
class CentrePolicyTest {
    private fun event(
        id: String,
        kind: AuditKind = AuditKind.Stop,
        at: Long = 1_000L,
        title: String = "t",
    ) = AuditEvent(id = id, at = at, kind = kind, title = title)

    // --- audit log ---------------------------------------------------------

    @Test
    fun `the audit log keeps the newest event first and caps itself`() {
        val many = (1..AuditPolicy.MAX_EVENTS + 5).map { event("e$it") }
        val capped = many.fold(emptyList<AuditEvent>()) { acc, e -> AuditPolicy.add(acc, e) }
        assertEquals(AuditPolicy.MAX_EVENTS, capped.size)
        // Folding 1…405: the last added one is on top, the oldest five fell off.
        assertEquals("e405", capped.first().id)
        assertEquals("e6", capped.last().id)
    }

    @Test
    fun `the summary counts events and stops`() {
        assertEquals("No events yet", AuditPolicy.summary(emptyList()))
        // A stop is worth naming even when it is the only event.
        assertEquals("1 event · 1 stop", AuditPolicy.summary(listOf(event("a"))))
        assertEquals(
            "3 events · 2 stops",
            AuditPolicy.summary(listOf(event("a"), event("b"), event("c", AuditKind.Revert))),
        )
        // StopAll counts as a stop as well: both end turns.
        assertEquals(
            "2 events · 2 stops",
            AuditPolicy.summary(listOf(event("a"), event("b", AuditKind.StopAll))),
        )
        assertEquals("1 event", AuditPolicy.summary(listOf(event("a", AuditKind.Fork))))
    }

    @Test
    fun `the export is markdown with a timestamp per event`() {
        val text = AuditPolicy.export(listOf(event("a", AuditKind.Fork, at = 1_700_000_000_000L, title = "Fix bug")))
        assertTrue(text.startsWith("## Audit"))
        assertTrue(text.contains("**2023-11-14 23:13** · Forked: Fix bug"))
        assertEquals("## Audit\n\nNo events recorded.\n", AuditPolicy.export(emptyList()))
    }

    @Test
    fun `every kind has a label`() {
        AuditKind.entries.forEach { assertTrue(it.name, AuditPolicy.label(it).isNotBlank()) }
    }

    @Test
    fun `counting a window is inclusive of the start and exclusive of the end`() {
        val events = listOf(event("a", at = 10L), event("b", at = 20L), event("c", at = 30L))
        assertEquals(2, AuditPolicy.countBetween(events, fromMs = 10L, toMs = 30L))
    }

    // --- rows --------------------------------------------------------------

    @Test
    fun `running sessions come first, everything else newest-first`() {
        val rows =
            listOf(
                CentreRow("idle-old", "A", updatedAt = 10L),
                CentreRow("busy", "B", running = true, updatedAt = 5L),
                CentreRow("idle-new", "C", updatedAt = 30L),
                CentreRow("retry", "D", retrying = true, updatedAt = 20L),
            )
        assertEquals(listOf("retry", "busy", "idle-new", "idle-old"), CentrePolicy.sort(rows).map { it.sessionId })
        assertEquals(listOf("retry", "busy"), CentrePolicy.runningIds(rows))
    }

    @Test
    fun `server statuses decide running and retrying`() {
        val sessions =
            listOf(
                Session(id = "s1", title = "Fix the bug", time = SessionTime(updated = 5L), cost = 1.5),
                // No title: the id is the fallback, never an empty row.
                Session(id = "s2"),
            )
        val rows =
            CentrePolicy.rows(
                sessions,
                mapOf(
                    "s1" to SessionStatus(type = "busy"),
                    "s2" to SessionStatus(type = "retry"),
                ),
            )
        assertEquals(listOf("s1", "s2"), rows.map { it.sessionId })
        assertEquals("Fix the bug", rows[0].title)
        assertEquals(true, rows[0].running)
        assertEquals(false, rows[0].retrying)
        assertEquals(true, rows[1].retrying)
        assertEquals("s2", rows[1].title)
        assertEquals(1.5, rows[0].cost!!, 0.0001)
    }

    // --- needs you ---------------------------------------------------------

    @Test
    fun `permissions questions and waiting sends all land in needs you`() {
        val needs =
            CentrePolicy.needs(
                permissions =
                    listOf(
                        PermissionRequest(id = "p1", sessionId = "s1", title = "Run tests", description = "bash"),
                        // Without a session there is nothing to open: skipped.
                        PermissionRequest(id = "p2", sessionId = null, title = "orphan"),
                    ),
                questions =
                    listOf(
                        SessionQuestion(
                            id = "q1",
                            sessionId = "s2",
                            questions =
                                listOf(
                                    QuestionItem(question = "Which database?"),
                                    QuestionItem(question = "And?"),
                                    QuestionItem(question = "More?"),
                                ),
                        ),
                    ),
                outboxCounts = mapOf("s3" to 2, "s4" to 0),
            )
        assertEquals(listOf(NeedKind.Permission, NeedKind.Question, NeedKind.Outbox), needs.map { it.kind })
        assertEquals("Run tests", needs[0].title)
        assertEquals("bash", needs[0].detail)
        assertEquals("Which database?", needs[1].title)
        assertEquals("3 questions", needs[1].detail)
        assertEquals("Waiting to send", needs[2].title)
        assertEquals("2 messages", needs[2].detail)
        // A session without waiting sends does not show up.
        assertEquals(emptyList<String>(), needs.filter { it.sessionId == "s4" }.map { it.title })
    }

    @Test
    fun `the cost label matches the glance surfaces`() {
        assertEquals("$1.23", CentrePolicy.costLabel(1.234))
        assertEquals("$0.00", CentrePolicy.costLabel(0.0))
        assertNull(CentrePolicy.costLabel(null))
    }
}
