package com.opencode.android.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Open tabs used to live in a plain `remember`, so rotating the device closed
 * every tab. They are now saveable; these guard the flatten/restore round-trip.
 */
class OpenTabSaverTest {

    @Test
    fun `flatten and unflatten round-trip`() {
        val tabs = listOf(
            OpenTab("ses_1", "First"),
            OpenTab("ses_2", "Second - 2026-09-17"),
        )
        assertEquals(tabs, unflattenTabs(flattenTabs(tabs)))
    }

    @Test
    fun `empty list round-trips`() {
        assertEquals(emptyList<OpenTab>(), unflattenTabs(flattenTabs(emptyList())))
    }

    @Test
    fun `odd trailing element is dropped instead of crashing`() {
        assertEquals(listOf(OpenTab("ses_1", "A")), unflattenTabs(listOf("ses_1", "A", "orphan")))
    }

    @Test
    fun `saver round-trips through its save and restore`() {
        val tabs = listOf(OpenTab("ses_1", "First"))
        val saved = with(OpenTabListSaver) { SaverScope { true }.save(tabs) }
        assertEquals(tabs, OpenTabListSaver.restore(saved!!))
    }

    @Test
    fun `saver restores an empty payload to no tabs`() {
        val saved = with(OpenTabListSaver) { SaverScope { true }.save(emptyList()) }
        // An empty tab set may save as null or an empty list; either way the
        // restored value must be no tabs (never a crash).
        val restored = saved?.let { OpenTabListSaver.restore(it) } ?: emptyList()
        assertEquals(emptyList<OpenTab>(), restored)
    }
}
