package com.opencode.android.ui

import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSessionFilterTest {

    private val guarded = Session(
        id = "ses_guard",
        title = "Guarded session",
        sessionGuard = SessionGuard(revision = 1),
    )
    private val plain = Session(id = "ses_plain", title = "Plain session")

    @Test
    fun `only guarded keeps guarded sessions`() {
        val result = filterSessions(listOf(guarded, plain), query = "", onlyGuarded = true)
        assertEquals(listOf("ses_guard"), result.map { it.id })
    }

    @Test
    fun `only guarded off keeps everything`() {
        val result = filterSessions(listOf(guarded, plain), query = "", onlyGuarded = false)
        assertEquals(listOf("ses_guard", "ses_plain"), result.map { it.id })
    }

    @Test
    fun `query still filters within guarded set`() {
        val result = filterSessions(listOf(guarded, plain), query = "plain", onlyGuarded = true)
        assertEquals(emptyList<String>(), result.map { it.id })
    }
}
