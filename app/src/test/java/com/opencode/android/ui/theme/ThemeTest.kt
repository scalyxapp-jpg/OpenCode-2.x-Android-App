package com.opencode.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The brand identity is black / white / wine red. These assertions fail loudly
 * if someone swaps in a dynamic (wallpaper) scheme or an unrelated accent.
 */
class ThemeTest {

    @Test
    fun `light scheme uses brand wine red as the accent`() {
        assertEquals(Wine700, LightColors.primary)
        assertEquals(Wine700, LightColors.surfaceTint)
    }

    @Test
    fun `dark scheme uses the readable wine tone as the accent`() {
        assertEquals(Wine300, DarkColors.primary)
        assertEquals(Wine300, DarkColors.surfaceTint)
    }

    @Test
    fun `neutral surfaces stay neutral, not purple`() {
        // Red and blue channels of a neutral grey must be within a hair of each
        // other; a large spread means the surface drifted off-brand.
        fun spread(c: androidx.compose.ui.graphics.Color): Float =
            kotlin.math.abs(c.red - c.blue)

        assertEquals(true, spread(LightColors.surface) < 0.02f)
        assertEquals(true, spread(DarkColors.surface) < 0.02f)
        assertEquals(true, spread(LightColors.surfaceContainerHigh) < 0.02f)
        assertEquals(true, spread(DarkColors.surfaceContainerHigh) < 0.02f)
    }

    @Test
    fun `error red is distinguishable from the wine accent`() {
        assertNotEquals(LightColors.primary, LightColors.error)
        assertNotEquals(DarkColors.primary, DarkColors.error)
    }

    @Test
    fun `dark and light accents differ`() {
        assertNotEquals(LightColors.primary, DarkColors.primary)
    }
}

/**
 * WCAG contrast guard. The palette was audited by hand once; without a test the
 * next colour tweak silently reintroduces unreadable text (the outline tone
 * already sat at 4.26:1 before it was corrected to 4.58:1).
 */
class ThemeContrastTest {

    private fun luminance(c: androidx.compose.ui.graphics.Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun ratio(
        a: androidx.compose.ui.graphics.Color,
        b: androidx.compose.ui.graphics.Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAa(
        name: String,
        fg: androidx.compose.ui.graphics.Color,
        bg: androidx.compose.ui.graphics.Color,
    ) {
        val r = ratio(fg, bg)
        org.junit.Assert.assertTrue("$name contrast $r below 4.5", r >= 4.5)
    }

    @Test
    fun `light scheme text pairs meet WCAG AA`() {
        val c = LightColors
        assertAa("onSurface/surface", c.onSurface, c.surface)
        assertAa("onSurfaceVariant/surface", c.onSurfaceVariant, c.surface)
        assertAa("onSurfaceVariant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant)
        assertAa("onSurfaceVariant/surfaceContainerHighest", c.onSurfaceVariant, c.surfaceContainerHighest)
        assertAa("primary/surface", c.primary, c.surface)
        assertAa("onPrimary/primary", c.onPrimary, c.primary)
        assertAa("error/surface", c.error, c.surface)
        assertAa("onError/error", c.onError, c.error)
        assertAa("outline/surface", c.outline, c.surface)
    }

    @Test
    fun `dark scheme text pairs meet WCAG AA`() {
        val c = DarkColors
        assertAa("onSurface/surface", c.onSurface, c.surface)
        assertAa("onSurfaceVariant/surface", c.onSurfaceVariant, c.surface)
        assertAa("onSurfaceVariant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant)
        assertAa("onSurfaceVariant/surfaceContainerHighest", c.onSurfaceVariant, c.surfaceContainerHighest)
        assertAa("primary/surface", c.primary, c.surface)
        assertAa("onPrimary/primary", c.onPrimary, c.primary)
        assertAa("error/surface", c.error, c.surface)
        assertAa("onError/error", c.onError, c.error)
        assertAa("outline/surface", c.outline, c.surface)
    }
}
