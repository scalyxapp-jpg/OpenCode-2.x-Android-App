package com.opencode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Badge parity with the web Review panel (A/M/D/R). */
class VcsStatusBadgeTest {

    @Test
    fun `maps the server status vocabulary`() {
        assertEquals("A", vcsStatusBadge("added"))
        assertEquals("M", vcsStatusBadge("modified"))
        assertEquals("D", vcsStatusBadge("deleted"))
        assertEquals("R", vcsStatusBadge("renamed"))
        assertEquals("A", vcsStatusBadge("untracked"))
    }

    @Test
    fun `is case insensitive`() {
        assertEquals("A", vcsStatusBadge("ADDED"))
        assertEquals("M", vcsStatusBadge("Modified"))
    }

    @Test
    fun `unknown statuses fall back to their first letter`() {
        assertEquals("C", vcsStatusBadge("copied"))
        assertEquals("?", vcsStatusBadge("?"))
    }

    @Test
    fun `empty status does not crash`() {
        assertEquals("", vcsStatusBadge(""))
    }
}
