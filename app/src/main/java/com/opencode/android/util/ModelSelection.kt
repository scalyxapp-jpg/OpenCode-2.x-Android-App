package com.opencode.android.util

import com.opencode.android.domain.Model
import com.opencode.android.domain.SessionModel

/**
 * ModelSelection (cross-cutting #8): the concept covering the currently
 * selected provider/model/variant/agent. This module owns parse/qualify/
 * resolve/label, the server-vs-remembered precedence and the generation/guard
 * bookkeeping that used to be scattered across `ChatViewModel` and [ModelRef].
 *
 * Pure: no Android/Compose dependency, so precedence and staleness are
 * unit-testable without a device.
 */
object ModelSelection {

    // --- ref arithmetic (moved from ModelRef.kt) ---------------------------

    /**
     * Builds the provider-qualified model ref ("provider/model") from a
     * session's model fields. A session id can already carry the provider
     * prefix, so a naive "provider/id" doubled it.
     */
    fun sessionRef(id: String?, provider: String?): String = when {
        id.isNullOrBlank() -> ""
        provider.isNullOrBlank() -> id
        id.startsWith("$provider/") -> id
        else -> "$provider/$id"
    }

    /** Resolves a selected ref to the exact (provider, modelId) the server expects. */
    fun resolve(ref: String, models: List<Model>): Pair<String, String> {
        val separator = ref.indexOf('/')
        val requestedProvider = ref.takeIf { separator > 0 }?.substring(0, separator)
        val requestedId = if (separator > 0) ref.substring(separator + 1) else ref
        val entry = models.firstOrNull { model ->
            val providerMatches = requestedProvider == null || model.providerId == requestedProvider
            providerMatches && (
                model.id == ref ||
                    model.id == requestedId ||
                    sessionRef(model.id, model.providerId) == ref ||
                    (requestedProvider == null && model.id.substringAfter('/') == requestedId)
                )
        }
        if (entry != null) {
            val provider = entry.providerId?.takeIf { it.isNotBlank() } ?: "opencode"
            return provider to entry.id
        }
        val parts = ref.split("/", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0] to parts[1]
        } else {
            "opencode" to ref
        }
    }

    /** Resolves server session model metadata to the exact catalog selection ref. */
    fun resolveSession(model: SessionModel?, models: List<Model>): String? {
        if (model == null) return null
        val provider = model.providerID ?: model.provider
        val rawId = model.modelID ?: model.id ?: model.model ?: return null
        if (rawId.isBlank()) return null
        val (resolvedProvider, resolvedId) = resolve(sessionRef(rawId, provider), models)
        return sessionRef(resolvedId, resolvedProvider)
    }

    /**
     * Human-friendly model label for a ref ("provider/model" or bare id): the
     * catalog display name when it resolves, otherwise the ref itself.
     */
    fun friendlyName(models: List<Model>, ref: String?): String {
        if (ref.isNullOrBlank()) return ""
        val match = models.firstOrNull { it.id == ref }
            ?: models.firstOrNull { it.id.substringAfter('/') == ref.substringAfter('/') }
        return match?.name?.takeIf { it.isNotBlank() } ?: ref
    }

    /** Qualifies a picker value to "provider/model" using the catalog. */
    fun qualify(model: String, models: List<Model>): String {
        if (model.isBlank() || model.contains('/')) return model
        val entry = models.firstOrNull {
            it.id == model || it.id.substringAfter('/') == model
        } ?: return model
        return sessionRef(entry.id, entry.providerId)
    }

    /** Exact (provider, modelId) for a ref, used before an API call. */
    fun parse(ref: String, models: List<Model>): Pair<String, String> = resolve(ref, models)

    // --- precedence --------------------------------------------------------

    /**
     * Load-time precedence: the server's session model wins; the remembered
     * local selection is the fallback. (ChatViewModel.loadSession.)
     */
    fun loadPrecedence(serverModel: String?, rememberedModel: String?): String? =
        serverModel ?: rememberedModel

    /**
     * Send-time model precedence: while a local selection is still in flight
     * (`pending`) it wins; otherwise the server's value wins over the local one.
     */
    fun modelPrecedence(pending: Boolean, serverModel: String?, current: String): String =
        if (pending) current else serverModel ?: current

    fun agentPrecedence(pending: Boolean, serverAgent: String?, current: String): String =
        if (pending) current else serverAgent ?: current

    fun variantPrecedence(pending: Boolean, serverVariant: String?, current: String): String =
        if (pending) current else serverVariant ?: current

    /**
     * Reconstructs the remembered selection ref, or null when the store has no
     * usable provider+model pair.
     */
    fun rememberedRef(
        rememberedProvider: String?,
        rememberedModelId: String?,
        models: List<Model>,
    ): String? {
        if (rememberedProvider.isNullOrBlank() || rememberedModelId.isNullOrBlank()) return null
        val raw = sessionRef(rememberedModelId, rememberedProvider)
        val (provider, modelId) = resolve(raw, models)
        return sessionRef(modelId, provider)
    }
}

/**
 * HTTP seam for persisting a selection to the server. A fake is used in tests;
 * the production adapter wraps `ApiClient` and returns the guard revision the
 * server echoes (or null when the header is absent).
 */
interface SelectionGateway {
    /** @return the `X-Session-Guard-Revision` echoed by the server, or null. */
    suspend fun setModel(sessionId: String, model: String, variant: String): Long?

    suspend fun setAgent(sessionId: String, agent: String): Long?
}

/**
 * Owns the in-flight selection bookkeeping that used to be inline in
 * `ChatViewModel`: per-sync generations (so a stale completion cannot clear a
 * newer pending flag) and the per-session guard-revision map.
 */
class SelectionGuard {
    private var modelGeneration = 0L
    private var agentGeneration = 0L
    private val guardRevisions = mutableMapOf<String, Long>()

    fun beginModelSync(): Long = ++modelGeneration
    fun beginAgentSync(): Long = ++agentGeneration
    fun isLatestModel(generation: Long): Boolean = generation == modelGeneration
    fun isLatestAgent(generation: Long): Boolean = generation == agentGeneration

    fun recordGuardRevision(sessionId: String, revision: Long) {
        guardRevisions[sessionId] = revision
    }

    fun guardRevision(sessionId: String): Long? = guardRevisions[sessionId]

    fun clearGuardRevision(sessionId: String) {
        guardRevisions.remove(sessionId)
    }

    /** Keep the map bounded to the active session (called on session switch). */
    fun retainOnly(sessionId: String) {
        guardRevisions.keys.retainAll { it == sessionId }
    }

    fun clearAll() {
        guardRevisions.clear()
    }

    fun snapshot(): Map<String, Long> = guardRevisions.toMap()
}
