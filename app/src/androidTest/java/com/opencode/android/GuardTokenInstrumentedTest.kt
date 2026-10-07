package com.opencode.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencode.android.data.BackendSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the optional guard token against a token-enabled proxy.
 *
 * The project ships no default host or token. Point the test at your own
 * instance running with `SESSION_GUARD_TOKEN` set:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.guardTokenUrl=http://10.0.2.2:8944 \
 *   -Pandroid.testInstrumentationRunnerArguments.guardToken=my-token
 * ```
 *
 * Skipped (assumption) when the URL is absent or that proxy is unreachable, so
 * normal CI stays green.
 */
@RunWith(AndroidJUnit4::class)
class GuardTokenInstrumentedTest {
    private val args = InstrumentationRegistry.getArguments()

    private val guardTokenUrl: String? =
        args.getString("guardTokenUrl")?.takeIf { it.isNotBlank() }

    private val guardToken: String =
        args.getString("guardToken")?.takeIf { it.isNotBlank() } ?: "e2e-token"

    @Test
    fun tokenProtectsGuardEndpoints() =
        runBlocking {
            assumeTrue("guardTokenUrl instrumentation argument not provided", guardTokenUrl != null)
            val session = BackendSession()
            val originalUrl = session.currentBaseUrl()
            try {
                session.setBaseUrl(guardTokenUrl!!)
                val reachable = runCatching { session.api.sessionGuardHealth() }.getOrNull()
                assumeTrue("token proxy not reachable", reachable?.isSuccessful == true)

                session.setGuardTokenProvider { guardToken }
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
