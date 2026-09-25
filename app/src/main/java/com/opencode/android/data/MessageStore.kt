package com.opencode.android.data

import com.opencode.android.domain.Message

/**
 * The offline message cache, as a seam.
 *
 * [MessageCache] is the on-disk implementation; tests (and any future
 * in-memory variant) can provide their own. Without an interface the cache was
 * an `object` reached directly from the ViewModel, so nothing could be swapped
 * in a unit test.
 */
interface MessageStore {
    /** Returns the cached tail, or null when there is nothing usable. */
    suspend fun read(sessionId: String): List<Message>?

    /** Persists the newest slice of the conversation. Best-effort. */
    suspend fun write(sessionId: String, messages: List<Message>)

    /** Drops every cached conversation (e.g. when switching backend). */
    suspend fun clearAll()
}
