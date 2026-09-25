package com.opencode.android.data

import com.opencode.android.domain.Message
import com.opencode.android.domain.Session
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.domain.VcsInfo
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.delay

/**
 * Data access for a chat session: the session itself, its messages, the offline
 * cache and the VCS diff.
 *
 * This is the seam the review asked for. The logic lived inline in
 * `ChatViewModel` (46 functions mixing network, cache, streaming and UI state),
 * so nothing could be tested without a running server. Dependencies are
 * constructor parameters with production defaults, which lets a test pass a
 * fake API and an in-memory [MessageStore]; Hilt supplies the real ones.
 */
class ChatRepository(
    // Resolved per call, NOT captured: ApiClient swaps its Retrofit instance
    // when the backend URL changes, and a frozen reference kept pointing at the
    // previous server (session/messages/VCS all hit the old host after a
    // backend switch).
    private val apiProvider: () -> OpenCodeApi = { ApiClient.api },
    private val messageStore: MessageStore = MessageCache,
) {
    private val api: OpenCodeApi get() = apiProvider()

    // --- offline cache -----------------------------------------------------

    suspend fun cachedMessages(sessionId: String): List<Message>? =
        messageStore.read(sessionId)

    suspend fun cacheMessages(sessionId: String, messages: List<Message>) =
        messageStore.write(sessionId, messages)

    suspend fun clearCache() = messageStore.clearAll()

    // --- session -----------------------------------------------------------

    suspend fun session(sessionId: String): Session = api.getSession(sessionId).data

    /** Null when the server has no full-session payload (it omits `directory`). */
    suspend fun fullSession(sessionId: String): Session? = try {
        api.getSessionFull(sessionId)
    } catch (e: Exception) {
        AppLog.d(APP_LOG_TAG) { "fullSession failed: ${e.message}" }
        null
    }

    // --- messages ----------------------------------------------------------

    /**
     * The newest [limit] messages, oldest first.
     *
     * Retries a couple of times because on reconnect the server can briefly
     * return `[]` while it reconstructs the session. Returns empty — never
     * throws — so the caller keeps whatever it already shows instead of
     * blanking the conversation.
     */
    suspend fun loadMessages(sessionId: String, limit: Int): List<Message> {
        var web: List<Message> = emptyList()
        for (attempt in 1..3) {
            val fetched = try {
                // Streamed decode: never materialises the body as a String.
                ApiClient.getMessagesStreamed(sessionId, limit)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "getMessages failed (attempt $attempt): ${e.message}")
                null
            }
            // null means the streamed decode hit OutOfMemoryError. Do not retry:
            // memory is already exhausted.
            if (fetched == null) return emptyList()
            web = fetched
            if (web.isNotEmpty()) break
            if (attempt < 3) delay(300L * attempt)
        }
        if (web.isNotEmpty()) return web.sortedBy { messageTime(it) }

        val legacy = try {
            api.getApiMessages(sessionId, limit = limit).data
        } catch (e: OutOfMemoryError) {
            AppLog.e(APP_LOG_TAG, "getApiMessages OOM — keeping previous messages")
            emptyList()
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "getApiMessages failed: ${e.message}")
            emptyList()
        }
        return mergeMessages(web, legacy)
    }

    // --- version control ---------------------------------------------------

    suspend fun vcs(directory: String): VcsInfo = api.getVcs(directory)

    suspend fun vcsDiff(directory: String): List<VcsDiffFile> =
        api.getVcsDiff(directory = directory)

    // --- helpers (were private in the ViewModel) ---------------------------

    private fun messageTime(m: Message): Long =
        m.time?.created ?: m.info?.time?.created ?: 0L

    /** Union by message id; entries without an id are kept as-is. */
    internal fun mergeMessages(first: List<Message>, second: List<Message>): List<Message> {
        val byId = LinkedHashMap<String, Message>()
        for (m in first + second) {
            val key = m.id ?: m.info?.id ?: continue
            byId[key] = m
        }
        val withoutId = (first + second).filter { it.id == null && it.info?.id == null }
        return (byId.values + withoutId).sortedBy {
            it.time?.created ?: it.info?.time?.created ?: 0L
        }
    }
}
