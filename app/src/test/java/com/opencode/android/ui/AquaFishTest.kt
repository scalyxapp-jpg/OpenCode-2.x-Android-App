package com.opencode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the Aqua backdrop crash: a canvas shorter than twice a
 * fish used to make `coerceIn(size, h - size)` throw an empty-range
 * IllegalArgumentException.
 */
class AquaFishTest {
    @Test
    fun `clamps a fish inside a normal canvas`() {
        assertEquals(5f, clampFishY(-100f, size = 5f, h = 100f), 0.001f)
        assertEquals(95f, clampFishY(1000f, size = 5f, h = 100f), 0.001f)
        assertEquals(40f, clampFishY(40f, size = 5f, h = 100f), 0.001f)
    }

    @Test
    fun `short canvas does not throw and pins the fish`() {
        // h (30) < 2 * size (40): the naive range would invert.
        val y = clampFishY(50f, size = 20f, h = 30f)
        assertEquals(20f, y, 0.001f)
    }

    @Test
    fun `zero-height first layout frame does not throw`() {
        val y = clampFishY(0f, size = 12f, h = 0f)
        assertEquals(12f, y, 0.001f)
    }
}
