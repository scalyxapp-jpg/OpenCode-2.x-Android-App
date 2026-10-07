package com.opencode.android.data

import com.opencode.android.domain.Message

/**
 * Small in-memory LRU of the newest messages per session.
 *
 * [MessageCache] reads and JSON-parses the tail from disk on every open, which
 * is noticeable on a heavy session and pointless when the same session is
 * reopened seconds later (the common case: home → chat → back → chat). Keeping
 * the parsed tail in memory makes a repeat open instant; the disk copy still
 * backs a cold start. Bounded by entry count so browsing many sessions cannot
 * grow the heap without limit.
 *
 * Pure (no Android types, no I/O) so the eviction rule is unit-tested.
 */
internal class MessageMemoryCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()

    // accessOrder = true: get() refreshes recency, so eviction drops the
    // least-recently-used session, not just the oldest inserted.
    private val entries = LinkedHashMap<String, List<Message>>(16, 0.75f, true)

    fun get(sessionId: String): List<Message>? = synchronized(lock) { entries[sessionId] }

    fun put(
        sessionId: String,
        messages: List<Message>,
    ) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            entries[sessionId] = messages
            val iterator = entries.entries.iterator()
            while (entries.size > maxEntries && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun remove(sessionId: String) {
        synchronized(lock) { entries.remove(sessionId) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 4
    }
}
