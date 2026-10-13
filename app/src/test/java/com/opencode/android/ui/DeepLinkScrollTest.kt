package com.opencode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A tapped notification names a message. With `reverseLayout = true` the LazyColumn
 * index of that message is the number of items declared before the messages plus
 * its position in the (newest-first) list.
 */
class DeepLinkScrollTest {
    private val ids = listOf("msg_new", "msg_mid", "msg_old")

    @Test
    fun `the newest message sits right above the live section`() {
        assertEquals(4, deepLinkScrollIndex(ids, "msg_new", liveItemCount = 4))
    }

    @Test
    fun `without a live section the index is the position`() {
        assertEquals(2, deepLinkScrollIndex(ids, "msg_old", liveItemCount = 0))
    }

    @Test
    fun `an unloaded message yields no target`() {
        assertNull(deepLinkScrollIndex(ids, "msg_gone", liveItemCount = 4))
        assertNull(deepLinkScrollIndex(ids, null, liveItemCount = 4))
        assertNull(deepLinkScrollIndex(ids, "", liveItemCount = 4))
    }
}
