package com.opencode.android.data

import com.opencode.android.domain.SummarizeRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The message QUEUE contract, verified against the live server:
 *
 * ```
 * POST   /api/session/{id}/prompt            {"…","delivery":"queue"}   -> the inbox item
 * GET    /api/session/{id}/inbox                                        -> queued prompts
 * PATCH  /api/session/{id}/inbox/{inboxID}   {"delivery":"steer"}        -> 204
 * DELETE /api/session/{id}/inbox/{inboxID}                              -> 204
 * ```
 *
 * Delivery of the oldest queued prompt happens server-side when the turn ends;
 * the app only has to re-read the inbox (see the `session.inbox.changed` effect).
 */
class InboxQueueTest {
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

    @Test
    fun `the inbox is read with the session directory`() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"data":[{"id":"msg_1","sessionID":"ses_a","type":"user",
                           "payload":{"text":"queued text"},"delivery":"queue"}]}""",
                    ).setHeader("Content-Type", "application/json"),
            )

            val inbox = session.api.getInbox("ses_a", "/home/user/proj")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/session/ses_a/inbox", request.path)
            assertEquals("/home/user/proj", request.getHeader("x-opencode-directory"))
            assertEquals(1, inbox.size)
            assertEquals("msg_1", inbox.first().id)
            assertEquals("queued text", inbox.first().payload?.text)
            assertEquals("queue", inbox.first().delivery)
        }

    @Test
    fun `removing a queued prompt deletes its inbox item`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(204))

            val ok = session.api.cancelInboxItem("ses_a", "msg_1", null)

            assertTrue(ok)
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/session/ses_a/inbox/msg_1", request.path)
        }

    @Test
    fun `steering a queued prompt patches its delivery`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(204))

            val ok = session.api.steerInboxItem("ses_a", "msg_1", null)

            assertTrue(ok)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/session/ses_a/inbox/msg_1", request.path)
            assertEquals("""{"delivery":"steer"}""", request.body.readUtf8())
        }

    @Test
    fun `a failed queue change reports false instead of throwing`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

            assertEquals(false, session.api.cancelInboxItem("ses_a", "msg_1", null))
        }
}
