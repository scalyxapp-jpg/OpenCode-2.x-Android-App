package com.opencode.android.data

import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageListResponse
import com.opencode.android.domain.Part
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * ApiClient replaces its Retrofit instance when the backend URL changes. The
 * repository must resolve the api per call; a captured instance kept pointing
 * at the previous server after a backend switch.
 */
class ChatRepositoryApiProviderTest {
    @Test
    fun `api is resolved on every call, not captured once`() =
        runBlocking {
            var resolutions = 0
            val repo =
                ChatRepository(
                    apiProvider = {
                        resolutions++
                        throw IllegalStateException("no api configured for this test")
                    },
                )

            repeat(2) { runCatching { repo.session("ses_1") } }

            assertEquals(2, resolutions)
        }

    @Test
    fun `falls back to a smaller page when the requested page is rejected`() =
        runBlocking {
            // The server re-serves the whole newest-N tail; a large page can exceed
            // the body cap (streamer returns null). The repository must retry with a
            // small tail instead of leaving the conversation empty.
            val ten = List(10) { Message(id = "m$it") }
            val repo =
                ChatRepository(
                    apiProvider = { throw IllegalStateException("no api") },
                    streamedMessages = { _, limit, _ -> if (limit == 30) null else MessagePage(ten, null) },
                )

            assertEquals(10, repo.loadMessages("ses_1", 30).messages.size)
        }

    @Test
    fun `returns empty when every page size is rejected`() =
        runBlocking {
            val repo =
                ChatRepository(
                    apiProvider = { throw IllegalStateException("no api") },
                    streamedMessages = { _, _, _ -> null },
                )

            assertEquals(0, repo.loadMessages("ses_1", 30).messages.size)
        }

    @Test
    fun `falls back to the legacy schema when the web message list is empty`() =
        runBlocking {
            // Newer runs expose their history only through the legacy
            // `/api/session/{id}/message` (content[]) endpoint while the web
            // `/session/{id}/message` (parts[]) list is empty. Without this
            // fallback the session opened completely blank.
            val legacy =
                listOf(
                    Message(id = "legacy1", role = "user", parts = listOf(Part(type = "text", text = "hi"))),
                    Message(id = "legacy2", role = "assistant", parts = listOf(Part(type = "text", text = "hello"))),
                )
            val api =
                Proxy.newProxyInstance(
                    OpenCodeApi::class.java.classLoader,
                    arrayOf(OpenCodeApi::class.java),
                    InvocationHandler { _, method, _ ->
                        if (method.name == "getApiMessages") {
                            MessageListResponse(data = legacy)
                        } else {
                            throw UnsupportedOperationException(method.name)
                        }
                    },
                ) as OpenCodeApi
            val repo =
                ChatRepository(
                    apiProvider = { api },
                    streamedMessages = { _, _, _ -> MessagePage(emptyList(), "C1") },
                )

            val page = repo.loadMessages("ses_1", 1)

            assertEquals(2, page.messages.size)
            assertEquals("C1", page.nextCursor)
        }

    @Test
    fun `forwards the before cursor and returns the next cursor`() =
        runBlocking {
            var seenBefore: String? = "unset"
            val page = MessagePage(List(3) { Message(id = "o$it") }, nextCursor = "C2")
            val repo =
                ChatRepository(
                    apiProvider = { throw IllegalStateException("no api") },
                    streamedMessages = { _, _, before ->
                        seenBefore = before
                        page
                    },
                )

            val result = repo.loadMessages("ses_1", 30, before = "C1")

            assertEquals("C1", seenBefore)
            assertEquals("C2", result.nextCursor)
            assertEquals(3, result.messages.size)
        }
}
