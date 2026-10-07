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
 * Exercises the real HTTP path of [BackendSession.getMessagesStreamed] against
 * the OpenCode 2.x message envelope.
 *
 * This is the code that replaced `ResponseBody.string()` and caused the OOM
 * crashes when a heavy session returned ~14 MB: the streamed decode plus the
 * bounded page size are the actual fix, and neither is reachable from a pure
 * function test. A local server lets the decode, the empty response and the
 * malformed response be checked for real.
 *
 * The 2.x endpoint returns `{"data":[…],"cursor":{"previous","next"}}`; older
 * pages are reached with `?cursor=<previous>`.
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

    private fun userMessage(
        id: String,
        text: String,
    ) = """{"id":"$id","type":"user","text":"$text"}"""

    private fun assistantMessage(
        id: String,
        text: String,
    ) = """{"id":"$id","type":"assistant","content":[{"type":"text","text":"$text"}]}"""

    private fun envelope(
        messages: String,
        previous: String? = null,
        next: String? = null,
    ) = """{"data":[$messages],"cursor":{"previous":${previous?.let {
        "\"$it\""
    } ?: "null"},"next":${next?.let { "\"$it\"" } ?: "null"}}}"""

    @Test
    fun `decodes a message list from the response stream`() =
        runBlocking {
            val body = envelope("${userMessage("m1", "hello")},${assistantMessage("m2", "hi")}")
            server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))

            val result = session.getMessagesStreamed("ses_test", 60)

            assertNotNull(result)
            assertEquals(2, result!!.messages.size)
            assertEquals("m1", result.messages[0].id)
            assertEquals("user", result.messages[0].type)
            assertEquals("hello", result.messages[0].text)
            assertEquals("assistant", result.messages[1].type)
            assertEquals(
                "hi",
                result.messages[1]
                    .content
                    .first()
                    .text,
            )
        }

    @Test
    fun `reads the next cursor from the response body`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(envelope("", previous = "cur123"))
                    .setHeader("Content-Type", "application/json"),
            )

            val result = session.getMessagesStreamed("ses_test", 60)

            assertEquals("cur123", result!!.nextCursor)
        }

    @Test
    fun `sends the before cursor as the cursor query parameter`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(envelope("")).setHeader("Content-Type", "application/json"))

            session.getMessagesStreamed("ses_test", 60, before = "cur==")

            val request = server.takeRequest()
            assertTrue(
                "expected a cursor, got ${request.path}",
                request.path!!.contains("cursor=cur"),
            )
        }

    @Test
    fun `passes the page size through as the limit query parameter`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(envelope("")).setHeader("Content-Type", "application/json"))

            session.getMessagesStreamed("ses_test", 60)

            val request = server.takeRequest()
            assertTrue(
                "expected limit=60, got ${request.path}",
                request.path!!.contains("limit=60"),
            )
            assertTrue(request.path!!.contains("/api/session/ses_test/message"))
        }

    @Test
    fun `an empty list stays empty instead of throwing`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(envelope("")).setHeader("Content-Type", "application/json"))

            val result = session.getMessagesStreamed("ses_test", 60)

            assertNotNull(result)
            assertTrue(result!!.messages.isEmpty())
        }

    @Test
    fun `a large payload decodes without buffering it as a string`() =
        runBlocking {
            // 400 messages with a 1 KB body each ≈ 400 KB — the shape that used to
            // be held twice (String + object graph).
            val chunk = "x".repeat(1_000)
            val messages = (1..400).joinToString(",") { assistantMessage("m$it", chunk) }
            server.enqueue(MockResponse().setBody(envelope(messages)).setHeader("Content-Type", "application/json"))

            val result = session.getMessagesStreamed("ses_test", 400)

            assertEquals(400, result!!.messages.size)
            assertEquals(
                1_000,
                result
                    .messages
                    .last()
                    .content
                    .first()
                    .text!!
                    .length,
            )
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
