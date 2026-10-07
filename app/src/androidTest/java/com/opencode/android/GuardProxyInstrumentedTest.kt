package com.opencode.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencode.android.data.BackendSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live smoke test for the optional session-guard proxy.
 *
 * The guard proxy is an external, self-hosted component; the project ships no
 * default host. Point the test at your own instance:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.guardUrl=http://10.0.2.2:8932
 * ```
 *
 * Skipped (assumption) when no URL is given or the proxy is unreachable, so CI
 * without a proxy stays green.
 */
@RunWith(AndroidJUnit4::class)
class GuardProxyInstrumentedTest {
    private val guardUrl: String? =
        InstrumentationRegistry
            .getArguments()
            .getString("guardUrl")
            ?.takeIf { it.isNotBlank() }

    @Test
    fun guardHealthIsReachableAndReportsMetrics() =
        runBlocking {
            assumeTrue("guardUrl instrumentation argument not provided", guardUrl != null)
            val session = BackendSession()
            session.setBaseUrl(guardUrl!!)
            val response = runCatching { session.api.sessionGuardHealth() }.getOrNull()
            assumeTrue("guard proxy not reachable", response?.isSuccessful == true)
            val body = response?.body()
            assertTrue(body?.ok == true)
            assertTrue(body?.service?.contains("session-guard") == true)
        }
}
