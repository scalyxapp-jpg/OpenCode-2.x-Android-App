package com.opencode.android.data

import com.opencode.android.domain.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The in-memory LRU that lets a reopened session skip the disk read + JSON
 * parse. Bounded so browsing many sessions cannot grow the heap.
 */
class MessageMemoryCacheTest {
    private fun msgs(vararg ids: String) = ids.map { Message(id = it) }

    @Test
    fun `put then get returns the same list`() {
        val cache = MessageMemoryCache()
        cache.put("ses_a", msgs("m1", "m2"))
        assertEquals(listOf("m1", "m2"), cache.get("ses_a")?.map { it.id })
    }

    @Test
    fun `empty lists are not cached`() {
        val cache = MessageMemoryCache()
        cache.put("ses_a", emptyList())
        assertNull(cache.get("ses_a"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `evicts the least-recently-used entry`() {
        val cache = MessageMemoryCache(maxEntries = 2)
        cache.put("a", msgs("a1"))
        cache.put("b", msgs("b1"))
        // Touch "a" so "b" becomes the LRU, then insert a third entry.
        cache.get("a")
        cache.put("c", msgs("c1"))

        assertEquals(2, cache.size())
        assertNull(cache.get("b"))
        assertEquals(listOf("a1"), cache.get("a")?.map { it.id })
        assertEquals(listOf("c1"), cache.get("c")?.map { it.id })
    }

    @Test
    fun `putting the same session again replaces the tail`() {
        val cache = MessageMemoryCache()
        cache.put("a", msgs("old"))
        cache.put("a", msgs("new1", "new2"))
        assertEquals(listOf("new1", "new2"), cache.get("a")?.map { it.id })
        assertEquals(1, cache.size())
    }

    @Test
    fun `remove and clear evict`() {
        val cache = MessageMemoryCache()
        cache.put("a", msgs("a1"))
        cache.put("b", msgs("b1"))
        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals(1, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
    }
}
