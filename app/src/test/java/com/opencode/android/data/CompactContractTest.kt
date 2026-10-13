package com.opencode.android.data

import com.opencode.android.domain.SummarizeRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the `/compact` wire contract exactly as the web UI sends it.
 *
 * Re-captured live with Playwright by typing `/compact` into a real session:
 *
 * ```
 * POST /api/session/{id}/compact
 * body  {"id":"msg_135bc24b1001DlsVl1rtnlpzmo"}      <- CLIENT-generated id, nothing else
 * 200   {"data":{"id":"msg_…","sessionID":"ses_…","time":{…},"type":"compaction",
 *                "payload":{},"delivery":"steer"}}
 * ```
 *
 * The transcript then shows the compaction message the server stores:
 * `{type:"compaction", status:"running"|"completed"|"failed", reason, summary, tokens}`.
 *
 * The app used to send an EMPTY body (so the server minted the id) plus a
 * URL-encoded directory header, and threw the answer away — which is why the
 * command had no visible output. These tests are the regression guard.
 */
class CompactContractTest {
    private lateinit var server: MockWebServer
    private lateinit var session: BackendSession

    private val queuedBody =
        """{"data":{"id":"msg_client1","sessionID":"ses_abc",
           "time":{"created":1791902885076},"type":"compaction","payload":{},
           "delivery":"steer"}}"""

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

    @Test
    fun `compact posts the client-generated id and nothing else`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody(queuedBody).setHeader("Content-Type", "application/json"))

            val response =
                session.api.summarizeSession(
                    sessionId = "ses_abc",
                    directory = "/home/user/Documents",
                    body = SummarizeRequest(id = "msg_client1"),
                )
            response.body()

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/session/ses_abc/compact", recorded.path)
            // The web sends the path AS IS; URL-encoding made the server look for a
            // location literally named "%2Fhome%2F…".
            assertEquals("/home/user/Documents", recorded.getHeader("x-opencode-directory"))
            assertEquals("""{"id":"msg_client1"}""", recorded.body.readUtf8())
        }

    @Test
    fun `the answer is the pending compaction message, not a boolean`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody(queuedBody).setHeader("Content-Type", "application/json"))

            val response =
                session.api.summarizeSession(
                    sessionId = "ses_abc",
                    directory = null,
                    body = SummarizeRequest(id = "msg_client1"),
                )

            assertTrue(response.isSuccessful)
            val queued = response.body()?.data
            assertEquals("msg_client1", queued?.id)
            assertEquals("compaction", queued?.type)
        }

    @Test
    fun `a completed compaction message carries status summary and usage`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":{"id":"msg_client1","sessionID":"ses_abc",
                           "type":"compaction","status":"completed","reason":"manual",
                           "model":{"id":"deepseek-v4-flash","providerID":"deepseek"},
                           "summary":"## Objective\n- ship it",
                           "tokens":{"input":16565,"output":92,"reasoning":48,
                                     "cache":{"read":18432,"write":0}}}}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val response =
                session.api.summarizeSession(
                    sessionId = "ses_abc",
                    directory = null,
                    body = SummarizeRequest(id = "msg_client1"),
                )

            val message = response.body()?.data
            assertEquals("completed", message?.status)
            assertEquals("manual", message?.reason)
            assertTrue("the summary drives the transcript block", message?.summary?.contains("Objective") == true)
            assertEquals(18432L, message?.tokens?.cache?.read)
        }

    @Test
    fun `an error response stays readable instead of throwing`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"name":"UnknownError","data":{"message":"Unexpected server error."}}""")
                    .setHeader("Content-Type", "application/json"),
            )

            val response =
                session.api.summarizeSession(
                    sessionId = "ses_abc",
                    directory = null,
                    body = SummarizeRequest(id = "msg_client1"),
                )

            assertEquals(500, response.code())
            assertEquals(
                "Unexpected server error.",
                com.opencode.android.util.serverErrorMessage(
                    response.errorBody()?.string(),
                    "fallback",
                ),
            )
        }

    @Test
    fun `a missing directory omits the header entirely`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody(queuedBody).setHeader("Content-Type", "application/json"))

            session.api.summarizeSession(
                sessionId = "ses_abc",
                directory = null,
                body = SummarizeRequest(id = "msg_client1"),
            )

            assertNull(server.takeRequest().getHeader("x-opencode-directory"))
        }
}
