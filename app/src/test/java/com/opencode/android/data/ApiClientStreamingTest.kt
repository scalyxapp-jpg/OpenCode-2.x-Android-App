package com.opencode.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real HTTP path of [BackendSession.getMessagesStreamed].
 *
 * This is the code that replaced `ResponseBody.string()` and caused the OOM
 * crashes when a heavy session returned ~14 MB: the streamed decode plus the
 * bounded page size are the actual fix, and neither is reachable from a pure
 * function test. A local server lets the decode, the empty response and the
 * malformed response be checked for real.
 */
class ApiClientStreamingTest {

    private lateinit var server: MockWebServer
    private lateinit var session: BackendSession

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        session = BackendSession()
        session.setBaseUrl(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun messageJson(id: String, role: String, text: String) = """
        {"info":{"id":"$id","role":"$role"},
         "parts":[{"id":"p_$id","type":"text","text":"$text"}]}
    """.trimIndent()

    @Test
    fun `decodes a message list from the response stream`() = runBlocking {
        val body = "[${messageJson("m1", "user", "hello")},${messageJson("m2", "assistant", "hi")}]"
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))

        val result = session.getMessagesStreamed("ses_test", 60)

        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals("m1", result[0].info?.id)
        assertEquals("user", result[0].info?.role)
        assertEquals("hello", result[0].parts.first().text)
        assertEquals("assistant", result[1].info?.role)
    }

    @Test
    fun `passes the page size through as the limit query parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json"))

        session.getMessagesStreamed("ses_test", 60)

        val request = server.takeRequest()
        assertTrue(
            "expected limit=60, got ${request.path}",
            request.path!!.contains("limit=60"),
        )
        assertTrue(request.path!!.contains("/session/ses_test/message"))
    }

    @Test
    fun `an empty list stays empty instead of throwing`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json"))

        val result = session.getMessagesStreamed("ses_test", 60)

        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `a large payload decodes without buffering it as a string`() = runBlocking {
        // 400 messages with a 1 KB body each ≈ 400 KB — the shape that used to
        // be held twice (String + object graph).
        val chunk = "x".repeat(1_000)
        val body = "[" + (1..400).joinToString(",") { messageJson("m$it", "assistant", chunk) } + "]"
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))

        val result = session.getMessagesStreamed("ses_test", 400)

        assertEquals(400, result!!.size)
        assertEquals(1_000, result.last().parts.first().text!!.length)
    }

    @Test(expected = Exception::class)
    fun `a malformed body surfaces as an error, not a silent empty list`() {
        // Silently returning empty here would blank a populated conversation.
        runBlocking {
            server.enqueue(
                MockResponse().setBody("not json at all").setHeader("Content-Type", "application/json"),
            )
            session.getMessagesStreamed("ses_test", 60)
        }
    }

    @Test(expected = Exception::class)
    fun `a server error surfaces as an error`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            session.getMessagesStreamed("ses_test", 60)
        }
    }
}
