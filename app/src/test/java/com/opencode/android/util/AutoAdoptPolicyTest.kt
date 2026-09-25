package com.opencode.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAdoptPolicyTest {

    @Test
    fun `adopts drift once when enabled`() {
        assertTrue(AutoAdoptPolicy.shouldAdopt(true, true, 4L, emptySet()))
    }

    @Test
    fun `does not adopt without drift or without the setting`() {
        assertFalse(AutoAdoptPolicy.shouldAdopt(false, true, 4L, emptySet()))
        assertFalse(AutoAdoptPolicy.shouldAdopt(true, false, 4L, emptySet()))
    }

    @Test
    fun `does not adopt the same revision twice`() {
        assertFalse(AutoAdoptPolicy.shouldAdopt(true, true, 4L, setOf(4L)))
    }
}
