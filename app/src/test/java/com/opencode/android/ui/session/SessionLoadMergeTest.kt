package com.opencode.android.ui.session

import com.opencode.android.domain.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLoadMergeTest {
    private val merge: (List<Message>, List<Message>) -> List<Message> = { a, b -> a + b }

    private fun m(id: String) = Message(id = id)

    @Test
    fun `a transiently empty load never blanks what is on screen`() {
        val current = listOf(m("a"), m("b"))
        val result = SessionLoadMerge.resolve(current, emptyList(), sameSession = true, merge = merge)
        assertEquals(current, result)
    }

    @Test
    fun `an empty load on a session switch keeps the cached tail`() {
        // Switching paints the cache first; a failed/empty network page must not
        // wipe it (the session used to open permanently empty).
        val cached = listOf(m("cached"))
        val result = SessionLoadMerge.resolve(cached, emptyList(), sameSession = false, merge = merge)
        assertEquals(cached, result)
    }

    @Test
    fun `an empty load on an empty screen stays empty`() {
        val result = SessionLoadMerge.resolve(emptyList(), emptyList(), sameSession = false, merge = merge)
        assertEquals(emptyList<Message>(), result)
    }

    @Test
    fun `a non-empty page replaces another session`() {
        val current = listOf(m("old"))
        val loaded = listOf(m("new"))
        val result = SessionLoadMerge.resolve(current, loaded, sameSession = false, merge = merge)
        assertEquals(loaded, result)
    }

    @Test
    fun `a non-empty page merges into the same session`() {
        val current = listOf(m("older"))
        val loaded = listOf(m("newest"))
        val result = SessionLoadMerge.resolve(current, loaded, sameSession = true, merge = merge)
        assertEquals(listOf(m("older"), m("newest")), result)
    }
}
