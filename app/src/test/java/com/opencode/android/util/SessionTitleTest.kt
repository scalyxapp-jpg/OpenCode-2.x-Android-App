package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Server titles carry a " - <ISO timestamp>"; the UI must not show it. */
class SessionTitleTest {

    @Test
    fun `strips the trailing ISO timestamp`() {
        assertEquals(
            "New session",
            sessionDisplayTitle("New session - 2026-09-17T19:03:56.256Z"),
        )
    }

    @Test
    fun `keeps a normal title untouched`() {
        assertEquals(
            "OC ANDROID APP SKILLFUL ANALYSIS",
            sessionDisplayTitle("OC ANDROID APP SKILLFUL ANALYSIS"),
        )
    }

    @Test
    fun `does not strip a trailing dash that is not a timestamp`() {
        assertEquals("Fix bug -", sessionDisplayTitle("Fix bug -"))
    }

    @Test
    fun `returns the original when stripping would empty it`() {
        assertEquals("- 2026-09-17T19:03:56.256Z", sessionDisplayTitle("- 2026-09-17T19:03:56.256Z"))
    }
}
