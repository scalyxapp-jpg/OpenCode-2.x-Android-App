package com.opencode.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The share-sheet hand-off slot is one-shot and ignores empty shares. */
class PendingShareTest {
    @After
    fun clear() {
        PendingShare.consume()
    }

    @Test
    fun `text share is consumed exactly once`() {
        PendingShare.set("hello from another app", emptyList())
        val shared = PendingShare.consume()
        assertEquals("hello from another app", shared?.text)
        assertNull(PendingShare.consume())
    }

    @Test
    fun `blank text with no images is ignored`() {
        PendingShare.set("   ", emptyList())
        assertNull(PendingShare.consume())
    }

    @Test
    fun `image-only share carries the cached uri`() {
        PendingShare.set(null, listOf("file:///cache/shared_1.png"))
        val shared = PendingShare.consume()
        assertNull(shared?.text)
        assertEquals(listOf("file:///cache/shared_1.png"), shared?.imageUris)
    }
}
