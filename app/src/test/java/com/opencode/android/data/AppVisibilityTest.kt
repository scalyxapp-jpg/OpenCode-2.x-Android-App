package com.opencode.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background alerts must not fire while the user is looking at the app, so the
 * visibility transitions drive them. Tested through the counter (no Android
 * runtime needed).
 */
class AppVisibilityTest {
    @Test
    fun `the app is in the background once no activity is started`() {
        AppVisibility.onStopped()
        assertFalse(AppVisibility.isForeground)
        assertTrue(AppVisibility.isBackground)
    }

    @Test
    fun `a started activity makes it foreground again`() {
        AppVisibility.onStopped()
        AppVisibility.onStarted()
        assertTrue(AppVisibility.isForeground)
    }

    @Test
    fun `the listener is told about the transitions, not the updates`() {
        // Order-independent: start from a known "background" state. The object is
        // a process singleton, so a previous test may have left it foreground.
        AppVisibility.onStopped()
        val seen = mutableListOf<Boolean>()
        AppVisibility.onVisibilityChanged = { seen += it }
        try {
            AppVisibility.onStarted()
            AppVisibility.onStarted() // second activity: still foreground, no event
            AppVisibility.onStopped() // still one activity running
            AppVisibility.onStopped() // now background
            AppVisibility.onStarted()
        } finally {
            AppVisibility.onVisibilityChanged = null
        }
        assertTrue("expected true once, then false, then true: $seen", seen == listOf(true, false, true))
    }
}
