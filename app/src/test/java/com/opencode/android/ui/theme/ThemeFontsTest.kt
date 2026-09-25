package com.opencode.android.ui.theme

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Appearance settings are now applied. These guard the font resolution helpers:
 * a blank setting must fall back to the platform default (never crash, never
 * change the type scale), and a custom name must produce a distinct typography.
 */
class ThemeFontsTest {

    @Test
    fun `blank font name resolves to null`() {
        assertNull(deviceFontFamily(""))
        assertNull(deviceFontFamily("   "))
    }

    @Test
    fun `blank sans font keeps the base typography`() {
        assertSame(OpenCodeTypography, openCodeTypography(""))
    }

    @Test
    fun `custom sans font produces a distinct typography`() {
        val custom = openCodeTypography("sans-serif-condensed")
        assertTrue(custom !== OpenCodeTypography)
        // The family is applied to the body style used by message text.
        assertTrue(custom.bodyMedium.fontFamily != null)
    }
}
