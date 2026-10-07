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
 * Pins the `/compact` wire contract exactly as the web UI sends it.
 *
 * Captured with Playwright by clicking `/compact` in a real session:
 *   POST /session/{id}/summarize
 *   header  x-opencode-directory: %2Fhome%2Fuser%2FDocuments   (URL-encoded)
 *   body    {"providerID":"deepseek","modelID":"deepseek-v4-flash"}  (no other keys)
 *   200     true                                                     (4 bytes, ~10-30 s)
 * followed by GET /session/{id}/todo.
 *
 * The app previously added an extra `auto: false` field and sent no directory
 * header, so this is the regression guard for the 1:1 fix.
 */
class CompactContractTest {
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
    fun `summarize posts the exact web body, header and path`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody("true").setHeader("Content-Type", "application/json"))

            val response =
                session.api.summarizeSession(
                    sessionId = "ses_abc",
                    directory = "%2Fhome%2Fuser%2FDocuments",
                    body =
                        SummarizeRequest(
                            providerID = "deepseek",
                            modelID = "deepseek-v4-flash",
                        ),
                )
            response.body()?.close()

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/session/ses_abc/compact", recorded.path)
            assertEquals(
                "%2Fhome%2Fuser%2FDocuments",
                recorded.getHeader("x-opencode-directory"),
            )
            assertEquals(
                "{}",
                recorded.body.readUtf8(),
            )
        }

    @Test
    fun `the response body is the literal true`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody("true").setHeader("Content-Type", "application/json"))

            // The endpoint returns a Retrofit Response; the payload is the literal
            // `true` body and a non-2xx is surfaced as !isSuccessful (not a throw),
            // so the app can read the server's error message.
            val response =
                session.api.summarizeSession(
                    sessionId = "ses_abc",
                    directory = null,
                    body = SummarizeRequest(providerID = "deepseek", modelID = "deepseek-v4-flash"),
                )

            assertTrue(response.isSuccessful)
            assertEquals("true", response.body()?.string())
            response.body()?.close()
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
                    body = SummarizeRequest(providerID = "deepseek", modelID = "deepseek-v4-flash"),
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
            server.enqueue(MockResponse().setBody("true").setHeader("Content-Type", "application/json"))

            val response =
                session.api.summarizeSession(
                    sessionId = "ses_abc",
                    directory = null,
                    body = SummarizeRequest(providerID = "deepseek", modelID = "deepseek-v4-flash"),
                )
            response.body()?.close()

            val recorded = server.takeRequest()
            assertTrue(
                "x-opencode-directory must be absent when the session has no directory",
                recorded.getHeader("x-opencode-directory") == null,
            )
        }
}
