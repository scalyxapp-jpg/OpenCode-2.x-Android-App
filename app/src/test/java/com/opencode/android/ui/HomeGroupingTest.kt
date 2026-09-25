package com.opencode.android.ui

import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session grouping with pinning. A pinned session must appear in the "Pinned"
 * group AND be removed from its date bucket — otherwise it renders twice, which
 * is immediately visible in the list.
 */
class HomeGroupingTest {

    private fun session(id: String, updated: Long) =
        Session(id = id, title = id, time = SessionTime(updated = updated))

    private val now = System.currentTimeMillis()

    @Test
    fun `a pinned session leads the list and leaves its date bucket`() {
        val sessions = listOf(
            session("a", now),
            session("b", now),
        )
        val groups = groupSessions(sessions, pinnedIds = setOf("b"))
        assertEquals(listOf("Pinned", "Today"), groups.map { it.first })
        assertEquals(listOf("b"), groups.first { it.first == "Pinned" }.second.map { it.id })
        assertEquals(listOf("a"), groups.first { it.first == "Today" }.second.map { it.id })
    }

    @Test
    fun `with nothing pinned the date buckets are unchanged`() {
        val sessions = listOf(session("a", now))
        val groups = groupSessions(sessions, pinnedIds = emptySet())
        assertEquals(listOf("Today"), groups.map { it.first })
    }

    @Test
    fun `no pinned group is emitted when the pinned id is absent from the list`() {
        val groups = groupSessions(listOf(session("a", now)), pinnedIds = setOf("zzz"))
        assertTrue(groups.none { it.first == "Pinned" })
    }
}
