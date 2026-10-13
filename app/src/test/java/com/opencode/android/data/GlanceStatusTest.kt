package com.opencode.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ambient surfaces (widget, ongoing notification, tile) all derive from ONE fact set,
 * so the precedence and wording rules live here and are tested.
 */
class GlanceStatusTest {
    @Test
    fun `waiting for the human wins over running`() {
        val facts = GlanceFacts(running = true, step = 7, pendingDecisions = 1)
        assertEquals(GlanceKind.WAITING, GlanceStatus.kind(facts))
    }

    @Test
    fun `failures while running are stuck`() {
        assertEquals(GlanceKind.STUCK, GlanceStatus.kind(GlanceFacts(running = true, failures = 2)))
    }

    @Test
    fun `running without failures is running, idle is idle`() {
        assertEquals(GlanceKind.RUNNING, GlanceStatus.kind(GlanceFacts(running = true, step = 3)))
        assertEquals(GlanceKind.IDLE, GlanceStatus.kind(GlanceFacts()))
    }

    @Test
    fun `progress names the step, the tool and the elapsed seconds`() {
        val facts = GlanceFacts(running = true, step = 7, elapsedMs = 42_000, toolName = "Shell")
        assertEquals("Step 7 · Shell · 42s", GlanceStatus.progress(facts))
    }

    @Test
    fun `an idle session has no progress line`() {
        assertNull(GlanceStatus.progress(GlanceFacts()))
    }

    @Test
    fun `cost is formatted or absent, never guessed`() {
        assertEquals("$3.06", GlanceStatus.cost(3.0612))
        assertNull(GlanceStatus.cost(null))
    }

    @Test
    fun `the surface is deduplicated in ten second steps, not per second`() {
        val a = GlanceFacts(sessionId = "s", running = true, step = 2, elapsedMs = 1_000)
        // One second later: same surface (no wake-up for the widget).
        assertTrue(GlanceStatus.sameSurface(a, a.copy(elapsedMs = 2_000, updatedAt = 999L)))
        // Ten seconds later: the visible line changed, so a refresh is worth it.
        assertFalse(GlanceStatus.sameSurface(a, a.copy(elapsedMs = 11_000)))
        // A new pending decision always changes the surface.
        assertFalse(GlanceStatus.sameSurface(a, a.copy(pendingDecisions = 1)))
    }
}
