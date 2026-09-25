package com.opencode.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackendSession state and header wiring. Plain JVM: no Android runtime is
 * needed because the interceptor's guard-token source is injected.
 */
class BackendSessionTest {

    @Test
    fun `defaults to the configured backend with an api`() {
        val session = BackendSession()

        assertEquals(BackendSession.DEFAULT_BASE_URL, session.currentBaseUrl())
        assertFalse(session.hasAuth())
        assertNotNull(session.api)
    }

    @Test
    fun `setBaseUrl clears previously configured auth`() {
        val session = BackendSession()
        session.setAuth("opencode", "secret")
        assertTrue(session.hasAuth())

        session.setBaseUrl("http://other.test:4096")

        assertFalse(session.hasAuth())
        assertEquals("http://other.test:4096", session.currentBaseUrl())
    }

    @Test
    fun `setBackend swaps the url and clears auth when credentials are absent`() {
        val session = BackendSession()
        session.setAuth("opencode", "secret")

        session.setBackend("http://new.test:4096", null, null)

        assertEquals("http://new.test:4096", session.currentBaseUrl())
        assertFalse(session.hasAuth())
    }

    @Test
    fun `guard token provider feeds the request header`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("{\"healthy\":true}")
                .setHeader("Content-Type", "application/json"),
        )
        server.start()
        try {
            val session = BackendSession()
            session.setBackend(server.url("/").toString(), null, null)
            session.setGuardTokenProvider { "tok-123" }

            session.api.health()

            val recorded = server.takeRequest()
            assertEquals("tok-123", recorded.getHeader("X-Session-Guard-Token"))
        } finally {
            server.shutdown()
        }
    }
}
