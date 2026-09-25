package com.opencode.android.data

import com.opencode.android.domain.ProviderEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

/**
 * Legacy view of the provider catalog, kept so existing call sites keep
 * compiling. All caching and load lifecycle live in [ProviderDirectory];
 * inject that directly in new code.
 */
object ProviderCatalog {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Ready(
            val providers: List<ProviderEntry>,
            val connectedIds: Set<String>,
        ) : State
        data class Failed(val message: String) : State
    }

    // Application-lifetime scope for the adapter view; a prefetch must survive
    // the screen that started it (the backend picker navigates away at once).
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val directory: ProviderDirectory get() = ProviderDirectory.shared()

    // Idle is the pre-subscription value; once the directory emits it is one of
    // Loading/Ready/Failed.
    val state: StateFlow<State> = directory.state
        .map { it.toCatalogState() }
        .stateIn(catalogScope, SharingStarted.Eagerly, State.Idle)

    /** Models of connected providers only — what the pickers may offer. */
    val connectedProviders: List<ProviderEntry>
        get() = directory.connectedProviders

    /** Returns true if the given provider/model pair is available among connected providers. */
    fun isModelAvailable(providerId: String, modelId: String): Boolean =
        directory.isAvailable(providerId, modelId)

    /**
     * Loads the catalog once; concurrent callers share the in-flight load.
     * Pass [force] to refresh (e.g. after connecting/disconnecting a provider).
     */
    suspend fun load(force: Boolean = false) {
        if (force) directory.invalidate()
        directory.load()
    }

    /** Drops the cache; the next [load] refetches. */
    fun invalidate() = directory.invalidate()

    /**
     * Fire-and-forget warm-up. Called right after a successful backend
     * connection so the ~6 MB catalog is already parsed by the time the user
     * opens Settings → Providers/Models or a chat model picker.
     */
    fun prefetch() = directory.prefetch()
}

private fun ProviderDirectory.State.toCatalogState(): ProviderCatalog.State = when (this) {
    is ProviderDirectory.State.Loading -> ProviderCatalog.State.Loading
    is ProviderDirectory.State.Ready -> ProviderCatalog.State.Ready(providers, connectedIds)
    is ProviderDirectory.State.Failed -> ProviderCatalog.State.Failed(message)
}
