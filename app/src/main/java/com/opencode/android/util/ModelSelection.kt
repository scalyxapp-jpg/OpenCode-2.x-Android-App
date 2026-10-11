package com.opencode.android.util

import com.opencode.android.domain.Model
import com.opencode.android.domain.ProviderEntry
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
    /** Provider used when a ref cannot be qualified (matches the old fallback). */
    private const val DEFAULT_PROVIDER = "opencode"

    // --- ref arithmetic (moved from ModelRef.kt) ---------------------------

    /**
     * Builds the provider-qualified model ref ("provider/model") from a
     * session's model fields. A session id can already carry the provider
     * prefix, so a naive "provider/id" doubled it.
     */
    fun sessionRef(
        id: String?,
        provider: String?,
    ): String =
        when {
            id.isNullOrBlank() -> ""
            provider.isNullOrBlank() -> id
            id.startsWith("$provider/") -> id
            else -> "$provider/$id"
        }

    /**
     * Finds the catalog entry a ref points at. Order matters:
     * 1. provider-qualified ("provider/modelId") — wins when the same model id is
     *    offered by several providers,
     * 2. the ref IS a catalog model id — a model id may carry a namespace of its
     *    own (Nvidia: "z-ai/glm-5.3"), and a blanket split at the first slash
     *    used to read that as provider "z-ai",
     * 3. legacy tail match ("nemotron-…" for "nvidia/nemotron-…").
     */
    private fun find(
        models: List<Model>,
        ref: String,
    ): Model? {
        models.firstOrNull { sessionRef(it.id, it.providerId) == ref }?.let { return it }
        models.firstOrNull { it.id == ref }?.let { return it }
        // 3. legacy tail match — ONLY for a bare ref. A ref that names a provider
        // explicitly ("foo/gpt-5") must not be silently rewritten to whichever
        // provider happens to offer the same tail model, or the prompt is routed
        // to a provider the user did not choose.
        if (ref.contains('/')) return null
        return models.firstOrNull { it.id.substringAfter('/') == ref }
    }

    /** Resolves a selected ref to the exact (provider, modelId) the server expects. */
    fun resolve(
        ref: String,
        models: List<Model>,
    ): Pair<String, String> {
        val trimmed = ref.trim()
        find(models, trimmed)?.let { entry ->
            val catalogProvider = entry.providerId?.takeIf { it.isNotBlank() }
            // A bare catalog entry (the flattened /api/model list carries no
            // provider) must not be forced onto a hardcoded default when the ref
            // itself names one.
            val provider = catalogProvider ?: refProvider(trimmed) ?: DEFAULT_PROVIDER
            return provider to entry.id
        }
        // Unknown to the local catalog: keep the historical split so a model the
        // server knows but has not indexed can still be sent.
        val separator = trimmed.indexOf('/')
        return if (separator > 0 && separator < trimmed.length - 1) {
            trimmed.substring(0, separator) to trimmed.substring(separator + 1)
        } else {
            // "/model" and "model/" must not yield an empty provider/model.
            DEFAULT_PROVIDER to trimmed.trim('/')
        }
    }

    /** Provider named by a "provider/model" ref, or null when there is none. */
    private fun refProvider(ref: String): String? {
        val separator = ref.indexOf('/')
        if (separator <= 0 || separator == ref.length - 1) return null
        return ref.substring(0, separator).takeIf { it.isNotBlank() }
    }

    /** Resolves server session model metadata to the exact catalog selection ref. */
    fun resolveSession(
        model: SessionModel?,
        models: List<Model>,
    ): String? {
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
    fun friendlyName(
        models: List<Model>,
        ref: String?,
    ): String {
        if (ref.isNullOrBlank()) return ""
        return find(models, ref)?.name?.takeIf { it.isNotBlank() } ?: ref
    }

    /** Qualifies a picker value to "provider/model" using the catalog. */
    fun qualify(
        model: String,
        models: List<Model>,
    ): String {
        if (model.isBlank()) return model
        val entry = find(models, model) ?: return model
        return sessionRef(entry.id, entry.providerId)
    }

    /**
     * True when two refs point at the same catalog entry.
     *
     * A plain string compare is not enough: the session payload can name the model
     * bare ("deepseek-v4-flash") while the picker holds the provider-qualified ref
     * ("opencode-go/deepseek-v4-flash"), and then NO row looked selected even
     * though one was. Both sides are resolved against the catalog first.
     */
    fun sameModel(
        models: List<Model>,
        a: String?,
        b: String?,
    ): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        val left = find(models, a) ?: return false
        val right = find(models, b) ?: return false
        return left.id == right.id && left.providerId == right.providerId
    }

    /**
     * Label for the ACTIVE model ("DeepSeek V4 Flash").
     *
     * The provider is appended when that display name is offered by more than one
     * provider, so two providers serving the same name stay distinguishable
     * ("DeepSeek V4 Flash · opencode-go"). Unambiguous names are left alone, so the
     * common case reads exactly as before.
     */
    fun activeLabel(
        models: List<Model>,
        ref: String?,
        providers: List<ProviderEntry> = emptyList(),
    ): String {
        if (ref.isNullOrBlank()) return ""
        val entry = find(models, ref) ?: return ref
        val label = entry.name?.takeIf { it.isNotBlank() } ?: entry.id
        val shared = models.count { (it.name?.takeIf { name -> name.isNotBlank() } ?: it.id) == label } > 1
        val provider = entry.providerId
        if (!shared || provider.isNullOrBlank()) return label
        val providerLabel =
            providers.firstOrNull { it.id == provider }?.name?.takeIf { it.isNotBlank() }
                ?: provider
        return "$label · $providerLabel"
    }

    /** Exact (provider, modelId) for a ref, used before an API call. */
    fun parse(
        ref: String,
        models: List<Model>,
    ): Pair<String, String> = resolve(ref, models)

    // --- precedence --------------------------------------------------------

    /**
     * Load-time precedence: the server's session model wins; the remembered
     * local selection is the fallback. (ChatViewModel.loadSession.)
     */
    fun loadPrecedence(
        serverModel: String?,
        rememberedModel: String?,
    ): String? = serverModel ?: rememberedModel

    /**
     * Send-time model precedence: while a local selection is still in flight
     * (`pending`) it wins; otherwise the server's value wins over the local one.
     */
    fun modelPrecedence(
        pending: Boolean,
        serverModel: String?,
        current: String,
    ): String = if (pending) current else serverModel ?: current

    fun agentPrecedence(
        pending: Boolean,
        serverAgent: String?,
        current: String,
    ): String = if (pending) current else serverAgent ?: current

    fun variantPrecedence(
        pending: Boolean,
        serverVariant: String?,
        current: String,
    ): String = if (pending) current else serverVariant ?: current

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
    suspend fun setModel(
        sessionId: String,
        model: String,
        variant: String,
    ): Long?

    suspend fun setAgent(
        sessionId: String,
        agent: String,
    ): Long?
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

    fun recordGuardRevision(
        sessionId: String,
        revision: Long,
    ) {
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
