package com.opencode.android.data

import com.opencode.android.domain.Model
import com.opencode.android.domain.ProviderEntry
import com.opencode.android.domain.ProvidersResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle and single-flight behaviour of the provider catalog cache. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderDirectoryTest {
    private fun response(vararg ids: String): ProvidersResponse =
        ProvidersResponse(
            all =
                ids.map { id ->
                    ProviderEntry(id = id, models = mapOf("$id/main" to Model(id = "$id/main")))
                },
            connected = ids.toList(),
        )

    @Test
    fun `load success becomes Ready with connected providers`() =
        runTest {
            val directory =
                ProviderDirectory(
                    fetch = { response("nvidia", "openai") },
                    scope = backgroundScope,
                )

            directory.load()

            assertTrue(directory.state.value is ProviderDirectory.State.Ready)
            assertEquals(listOf("nvidia", "openai"), directory.connectedProviders.map { it.id })
            assertTrue(directory.isAvailable("nvidia", "main"))
            assertFalse(directory.isAvailable("nvidia", "missing"))
        }

    @Test
    fun `load failure becomes Failed with message`() =
        runTest {
            val directory =
                ProviderDirectory(
                    fetch = { throw IllegalStateException("catalog boom") },
                    scope = backgroundScope,
                )

            directory.load()

            val state = directory.state.value
            assertTrue(state is ProviderDirectory.State.Failed)
            assertEquals("catalog boom", (state as ProviderDirectory.State.Failed).message)
            assertFalse(directory.isAvailable("nvidia", "main"))
        }

    @Test
    fun `concurrent loads share one fetch`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val directory =
                ProviderDirectory(
                    fetch = {
                        calls++
                        gate.await()
                        response("nvidia")
                    },
                    scope = this,
                )

            launch { directory.load() }
            launch { directory.load() }
            runCurrent()

            assertEquals(1, calls)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, calls)
            assertTrue(directory.state.value is ProviderDirectory.State.Ready)
        }

    @Test
    fun `invalidate drops Ready so the next load refetches`() =
        runTest {
            var calls = 0
            val directory =
                ProviderDirectory(
                    fetch = {
                        calls++
                        response("nvidia")
                    },
                    scope = backgroundScope,
                )

            directory.load()
            assertTrue(directory.state.value is ProviderDirectory.State.Ready)

            directory.invalidate()
            assertFalse(directory.state.value is ProviderDirectory.State.Ready)

            directory.load()

            assertEquals(2, calls)
            assertTrue(directory.state.value is ProviderDirectory.State.Ready)
        }

    @Test
    fun `invalidateIfStale keeps a fresh catalog and refetches a stale one`() =
        runTest {
            var calls = 0
            val directory =
                ProviderDirectory(
                    fetch = {
                        calls++
                        response("nvidia")
                    },
                    scope = backgroundScope,
                )

            // Nothing loaded yet is always stale.
            assertTrue(directory.invalidateIfStale(60_000))
            directory.load()
            assertEquals(1, calls)

            // Freshly loaded and inside the window: the cache is kept, so a tab
            // switch does not refetch the ~6 MB catalog.
            assertFalse(directory.invalidateIfStale(60_000))
            directory.load()
            assertEquals(1, calls)

            // Outside the window (TTL 0): dropped and refetched, so provider changes
            // made in the TUI/web/config show up.
            assertTrue(directory.invalidateIfStale(0))
            directory.load()
            assertEquals(2, calls)
            assertTrue(directory.state.value is ProviderDirectory.State.Ready)
        }
}
