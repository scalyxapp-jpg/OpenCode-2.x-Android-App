package com.opencode.android.data

import com.opencode.android.domain.ProviderEntry
import com.opencode.android.domain.ProvidersResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the provider/model catalog cache and its load lifecycle.
 *
 * `GET /provider` is the only authoritative source for the *connected* provider
 * set, but the payload is large (~5.9 MB for 200+ providers) and parsing it on
 * device takes tens of seconds. The result is fetched once per process and
 * shared by Settings and the chat, so re-fetching on every tab switch or session
 * open no longer leaves the UI empty for a minute.
 *
 * The fetcher and the scope are injected, so a test drives the whole lifecycle
 * with a fake fetcher and a test dispatcher — no Retrofit, no Android runtime.
 *
 * [state] starts at [State.Loading]: the first [load] has not run yet, but every
 * consumer treats "not yet loaded" as "show a spinner".
 */
class ProviderDirectory(
    private val fetch: suspend () -> ProvidersResponse,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Loading : State

        data class Ready(
            val providers: List<ProviderEntry>,
            val connectedIds: Set<String>,
        ) : State

        data class Failed(
            val message: String,
        ) : State
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    // Shared by concurrent load() callers so a burst of them issues one fetch.
    // Together with [inflightGeneration] it also ties the in-flight fetch to the
    // generation it was started under, so a load() after invalidate() never
    // reuses a fetch that predates the bump.
    private var inflight: Deferred<Unit>? = null
    private var inflightGeneration: Long = -1L

    // Bumped by invalidate(). A load() that started before the bump must not
    // reuse the (now-stale) inflight/Ready state, or the forced refresh is
    // silently skipped and the catalog stays on Loading forever.
    private val invalidation =
        java.util.concurrent.atomic
            .AtomicLong(0L)

    // When the Ready catalog was fetched; drives [invalidateIfStale].
    @Volatile
    private var loadedAtMs: Long = 0L

    /** Models of connected providers only — what the pickers may offer. */
    val connectedProviders: List<ProviderEntry>
        get() =
            (_state.value as? State.Ready)
                ?.let { ready ->
                    ready.providers.filter { ready.connectedIds.contains(it.id) }
                }.orEmpty()

    /** True if the given provider/model pair is available among connected providers. */
    fun isAvailable(
        providerId: String,
        modelId: String,
    ): Boolean {
        val state = _state.value as? State.Ready ?: return false
        return state.providers.any { provider ->
            provider.id == providerId &&
                state.connectedIds.contains(provider.id) &&
                provider.hasModel(modelId)
        }
    }

    /**
     * True only when the catalog is LOADED and the pair is genuinely absent.
     *
     * Callers that BLOCK an action on the result must use this, not [isAvailable]:
     * while the catalog was still Loading (or after a failed fetch) [isAvailable]
     * also answers false, so a perfectly valid model looked "not available on the
     * server" and the send was cancelled. An unknown catalog must let the request
     * through — the server is the authority.
     */
    fun isKnownMissing(
        providerId: String,
        modelId: String,
    ): Boolean = _state.value is State.Ready && !isAvailable(providerId, modelId)

    /**
     * Loads the catalog once. Concurrent callers share the same in-flight load
     * and a Ready catalog is not refetched; call [invalidate] to force a
     * refresh (e.g. after connecting/disconnecting a provider).
     */
    suspend fun load() {
        val generation = invalidation.get()
        val job =
            mutex.withLock {
                val current = inflight
                // Reuse the in-flight fetch ONLY when it was started under the
                // same generation. `stillCurrent` (comparing our own generation
                // with the current one) was not enough: after invalidate() an old
                // fetch is still non-null, so a fresh load() returned it and the
                // forced refresh was dropped.
                val reuseInflight = current != null && inflightGeneration == generation
                when {
                    reuseInflight -> {
                        current
                    }

                    _state.value is State.Ready && invalidation.get() == generation -> {
                        null
                    }

                    else -> {
                        scope.async { doFetch(generation) }.also {
                            inflight = it
                            inflightGeneration = generation
                        }
                    }
                }
            }
        try {
            job?.await()
        } finally {
            // Must run even when await() is cancelled: otherwise inflight keeps
            // pointing at a dead deferred and every later load() awaits it and
            // never refetches.
            if (job != null) {
                mutex.withLock { if (inflight === job) inflight = null }
            }
        }
    }

    private suspend fun doFetch(generation: Long) {
        // Publish only while this fetch is still the current generation: an
        // invalidate() mid-fetch means a newer result is (or will be) in flight,
        // and letting the older one land afterwards would resurrect stale data.
        if (invalidation.get() == generation) _state.value = State.Loading
        val next =
            try {
                val resp = fetch()
                State.Ready(resp.all, resp.connected.toSet())
            } catch (e: Exception) {
                State.Failed(e.message ?: e::class.java.simpleName)
            }
        if (invalidation.get() == generation) {
            if (next is State.Ready) loadedAtMs = System.currentTimeMillis()
            _state.value = next
        }
    }

    /** Drops the cache; the next [load] refetches. */
    fun invalidate() {
        // Bump the generation so a concurrent/previous inflight load is not
        // reused by the next load() — that short-circuit left the catalog
        // stuck on Loading and never refetched.
        invalidation.incrementAndGet()
        _state.value = State.Loading
    }

    /**
     * Re-fetches when the cached catalog is older than [maxAgeMs].
     *
     * Providers can be added, removed or authenticated in the TUI, the web UI or
     * `opencode.json` while this app is running. The catalog used to be fetched
     * once per process, so those changes never showed up until the app was
     * restarted — the provider surfaces now call this on entry and pick up the
     * new state without paying for a full ~6 MB fetch on every open.
     *
     * Returns true when the cache was dropped (the caller then triggers the
     * fetch, typically via [load], so the result can be awaited).
     */
    fun invalidateIfStale(maxAgeMs: Long): Boolean {
        val fresh =
            _state.value is State.Ready &&
                System.currentTimeMillis() - loadedAtMs < maxAgeMs
        if (fresh) return false
        invalidate()
        return true
    }

    /** [invalidateIfStale] + a background refetch, for callers that cannot await. */
    fun refreshIfStale(maxAgeMs: Long) {
        if (invalidateIfStale(maxAgeMs)) prefetch()
    }

    /**
     * Fire-and-forget warm-up. Called right after a successful backend
     * connection so the ~6 MB catalog is already parsed by the time the user
     * opens Settings → Providers/Models or a chat model picker.
     */
    fun prefetch() {
        scope.launch { load() }
    }

    companion object {
        /**
         * How long a fetched catalog may be reused before the provider surfaces
         * refresh it. Short enough to reflect TUI/config edits, long enough that
         * tab switches and session opens stay cheap.
         */
        const val DEFAULT_TTL_MS = 120_000L
    }
}

/**
 * GET /provider keys models as provider/model, while prompt requests carry the
 * bare model id. Match both forms so a valid server model is not rejected by
 * the client-side availability guard.
 */
internal fun ProviderEntry.hasModel(modelId: String): Boolean {
    // Compare with the PROVIDER prefix normalised off BOTH sides. A model id can
    // also carry a namespace of its own (Nvidia: "z-ai/glm-5.3"), so stripping
    // everything before the first slash — the old rule — turned such an id into a
    // different model and made the availability guard reject a model the server
    // had just offered.
    val requested = modelId.removePrefix("$id/")
    return models.keys.any { it.removePrefix("$id/") == requested }
}
