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
        data class Failed(val message: String) : State
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    // Shared by concurrent load() callers so a burst of them issues one fetch.
    private var inflight: Deferred<Unit>? = null

    /** Models of connected providers only — what the pickers may offer. */
    val connectedProviders: List<ProviderEntry>
        get() = (_state.value as? State.Ready)?.let { ready ->
            ready.providers.filter { ready.connectedIds.contains(it.id) }
        }.orEmpty()

    /** True if the given provider/model pair is available among connected providers. */
    fun isAvailable(providerId: String, modelId: String): Boolean {
        val state = _state.value as? State.Ready ?: return false
        return state.providers.any { provider ->
            provider.id == providerId &&
                state.connectedIds.contains(provider.id) &&
                provider.hasModel(modelId)
        }
    }

    /**
     * Loads the catalog once. Concurrent callers share the same in-flight load
     * and a Ready catalog is not refetched; call [invalidate] to force a
     * refresh (e.g. after connecting/disconnecting a provider).
     */
    suspend fun load() {
        val job = mutex.withLock {
            val current = inflight
            when {
                current != null -> current
                _state.value is State.Ready -> null
                else -> scope.async { doFetch() }.also { inflight = it }
            }
        }
        job?.await()
        if (job != null) {
            mutex.withLock { if (inflight === job) inflight = null }
        }
    }

    private suspend fun doFetch() {
        _state.value = State.Loading
        _state.value = try {
            val resp = fetch()
            State.Ready(resp.all, resp.connected.toSet())
        } catch (e: Exception) {
            State.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /** Drops the cache; the next [load] refetches. */
    fun invalidate() {
        _state.value = State.Loading
    }

    /**
     * Fire-and-forget warm-up. Called right after a successful backend
     * connection so the ~6 MB catalog is already parsed by the time the user
     * opens Settings → Providers/Models or a chat model picker.
     */
    fun prefetch() {
        scope.launch { load() }
    }
}

/**
 * GET /provider keys models as provider/model, while prompt requests carry the
 * bare model id. Match both forms so a valid server model is not rejected by
 * the client-side availability guard.
 */
internal fun ProviderEntry.hasModel(modelId: String): Boolean {
    val requestedId = modelId.substringAfter('/')
    return models.keys.any { catalogId ->
        catalogId == modelId || catalogId.substringAfter('/') == requestedId
    }
}
