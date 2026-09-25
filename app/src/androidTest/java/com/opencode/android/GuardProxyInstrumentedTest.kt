package com.opencode.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opencode.android.data.BackendSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live smoke test for the optional session-guard proxy.
 *
 * Skipped (assumption) when the proxy is not reachable, so CI without the
 * Tailscale host stays green. Run on a device that can reach the proxy:
 * `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class GuardProxyInstrumentedTest {

    @Test
    fun guardHealthIsReachableAndReportsMetrics() = runBlocking {
        val session = BackendSession()
        session.setBaseUrl("http://192.168.1.100:8932")
        val response = runCatching { session.api.sessionGuardHealth() }.getOrNull()
        assumeTrue("guard proxy not reachable", response?.isSuccessful == true)
        val body = response?.body()
        assertTrue(body?.ok == true)
        assertTrue(body?.service?.contains("session-guard") == true)
    }
}
