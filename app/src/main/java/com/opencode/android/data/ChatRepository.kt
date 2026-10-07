package com.opencode.android.data

import com.opencode.android.domain.Message
import com.opencode.android.domain.Session
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.domain.VcsInfo
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.MessagePaging
import com.opencode.android.util.PayloadCaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    // Resolved per call, NOT captured: the backend swaps its Retrofit instance
    // when the URL changes, and a frozen reference kept pointing at the
    // previous server (session/messages/VCS all hit the old host after a
    // backend switch).
    private val apiProvider: () -> OpenCodeApi,
    private val messageStore: MessageStore = MessageCache,
    // Streamed message decode lives on BackendSession; injected so this class
    // never reaches the legacy `ApiClient` facade. Null in tests that only
    // exercise the api-provider seam. The third parameter is the `before`
    // cursor for fetching an older page.
    private val streamedMessages: (suspend (String, Int?, String?) -> MessagePage?)? = null,
) {
    private val api: OpenCodeApi get() = apiProvider()

    // --- offline cache -----------------------------------------------------

    suspend fun cachedMessages(sessionId: String): List<Message>? = messageStore.read(sessionId)

    suspend fun cacheMessages(
        sessionId: String,
        messages: List<Message>,
    ) = messageStore.write(sessionId, messages)

    suspend fun clearCache() = messageStore.clearAll()

    // --- session -----------------------------------------------------------

    suspend fun session(sessionId: String): Session = api.getSession(sessionId).data

    /** Null when the server has no full-session payload (it omits `directory`). */
    suspend fun fullSession(sessionId: String): Session? =
        try {
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
    suspend fun loadMessages(
        sessionId: String,
        limit: Int,
        before: String? = null,
    ): MessagePage {
        val streamer = streamedMessages
        if (streamer != null) {
            // The endpoint re-serves the whole newest-N tail and older messages
            // can be huge, so a page can be rejected as over the body cap
            // (getMessagesStreamed returns null). Walk down to a smaller tail
            // until one fits, so a fresh open always shows SOMETHING instead of
            // an empty conversation; each smaller limit is a subset of the
            // larger one, so no visible message is skipped.
            val limits = MessagePaging.pageLadder(limit)
            var skippedCursor: String? = null
            var nextCursor: String? = null
            var oversized = false
            var emptyConfirmed = false
            for (pageLimit in limits) {
                for (attempt in 1..3) {
                    val fetched =
                        try {
                            // Streamed decode: never materialises the body as a String.
                            streamer(sessionId, pageLimit, before)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Structured concurrency: a cancelled caller must
                            // stay cancelled, not be turned into an empty page.
                            throw e
                        } catch (e: Exception) {
                            AppLog.e(APP_LOG_TAG, "getMessages failed (attempt $attempt): ${e.message}")
                            null
                        }
                    // null means the request failed / OutOfMemoryError: stop
                    // retrying this page size and fall through to the smaller one.
                    if (fetched == null) break
                    // Keep the newest page's cursor even when it came back
                    // empty, so the legacy fallback (and an older-page walk)
                    // still knows where the web history continues.
                    if (nextCursor == null) nextCursor = fetched.nextCursor
                    if (fetched.messages.isNotEmpty()) {
                        return MessagePage(
                            capPayloads(fetched.messages.sortedBy { messageTime(it) }),
                            fetched.nextCursor,
                        )
                    }
                    if (fetched.tooLarge) {
                        // Remember where to continue, then try a smaller size —
                        // the page may still fit at limit=1.
                        skippedCursor = fetched.nextCursor
                        oversized = true
                        break
                    }
                    // A non-oversized empty page means the web endpoint
                    // genuinely has no messages for this session; a smaller
                    // page size would be empty too. After the transient-empty
                    // retries, stop walking the ladder and let the caller fall
                    // back to the legacy schema.
                    if (attempt == 3) emptyConfirmed = true
                    if (attempt < 3) delay(300L * attempt)
                }
                if (emptyConfirmed || oversized) break
            }
            // The web endpoint (`/session/{id}/message` → {info,parts}) returned
            // nothing. Newer runs expose their history ONLY through the legacy
            // endpoint (`/api/session/{id}/message` → content[]); fetching just
            // the web schema left such a session completely empty on open. Merge
            // it in so both schemas load. Skip when the web page was OVERSIZED
            // (a second huge fetch would risk the OOM this ladder exists to
            // avoid) and for older pages (legacy has no `before` cursor).
            if (!oversized && before == null) {
                val legacy = legacyMessages(sessionId, limit)
                if (legacy.isNotEmpty()) {
                    return MessagePage(capPayloads(legacy.sortedBy { messageTime(it) }), nextCursor)
                }
            }
            // Every size was rejected or empty. Surface the oversized page's
            // cursor so an older-page walk can skip PAST it instead of stopping.
            return MessagePage(emptyList(), skippedCursor ?: nextCursor)
        }

        return MessagePage(capPayloads(legacyMessages(sessionId, limit).sortedBy { messageTime(it) }), null)
    }

    /** Legacy `content[]` schema; never throws, empty on failure. */
    private suspend fun legacyMessages(
        sessionId: String,
        limit: Int,
    ): List<Message> =
        try {
            api.getApiMessages(sessionId, limit = limit).data
        } catch (e: OutOfMemoryError) {
            AppLog.e(APP_LOG_TAG, "getApiMessages OOM — keeping previous messages")
            emptyList()
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "getApiMessages failed: ${e.message}")
            emptyList()
        }

    /**
     * Bounds retained tool output/diff/text before the messages reach UI state.
     * Runs off the main thread because it rewrites large JSON state objects.
     */
    private suspend fun capPayloads(messages: List<Message>): List<Message> =
        withContext(Dispatchers.Default) { PayloadCaps.capMessages(messages) }

    // --- version control ---------------------------------------------------

    suspend fun vcs(directory: String): VcsInfo = api.getVcs(directory)

    suspend fun vcsDiff(directory: String): List<VcsDiffFile> = api.getVcsDiff(directory = directory)

    // --- helpers (were private in the ViewModel) ---------------------------

    private fun messageTime(m: Message): Long = m.time?.created ?: m.info?.time?.created ?: 0L

    /** Union by message id; entries without an id are kept as-is. */
    internal fun mergeMessages(
        first: List<Message>,
        second: List<Message>,
    ): List<Message> {
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
