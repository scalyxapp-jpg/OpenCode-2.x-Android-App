package com.opencode.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opencode.android.data.ApiClient
import com.opencode.android.data.BackendSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the optional guard token against a token-enabled proxy.
 *
 * Requires a proxy on port 8944 with SESSION_GUARD_TOKEN=e2e-token. Skipped when
 * that proxy is not reachable, so normal CI stays green.
 */
@RunWith(AndroidJUnit4::class)
class GuardTokenInstrumentedTest {

    @Test
    fun tokenProtectsGuardEndpoints() = runBlocking {
        val session = BackendSession.shared()
        val originalUrl = session.currentBaseUrl()
        try {
            session.setBaseUrl("http://192.168.1.100:8944")
            val reachable = runCatching { session.api.sessionGuardHealth() }.getOrNull()
            assumeTrue("token proxy not reachable", reachable?.isSuccessful == true)

            session.setGuardTokenProvider { "e2e-token" }
            val authorized = session.api.sessionGuardMetrics()
            assertEquals(200, authorized.code())

            session.setGuardTokenProvider { "" }
            val unauthorized = session.api.sessionGuardMetrics()
            assertEquals(401, unauthorized.code())
        } finally {
            session.setGuardTokenProvider { "" }
            session.setBaseUrl(originalUrl)
        }
    }
}
