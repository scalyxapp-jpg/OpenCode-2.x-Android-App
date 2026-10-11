package com.opencode.android.util

import com.opencode.android.domain.Model
import com.opencode.android.domain.ProviderEntry

/**
 * Share of the context window in use, clamped to 0..100. Null when the limit is
 * unknown (0/blank) — the caller then shows a neutral placeholder instead of a
 * misleading "0%".
 */
fun contextPercent(
    totalTokens: Long,
    limit: Long?,
): Int? = limit?.takeIf { it > 0 }?.let { ((totalTokens * 100) / it).coerceIn(0, 100).toInt() }

/**
 * Context window for a (provider, model) pair, taken from the provider catalog.
 *
 * Provider-aware on purpose: the same model id is offered by several providers
 * (24 such ids on a stock server) and their windows differ, so matching on the
 * id alone reported the limit of whichever provider happened to come first in
 * the catalog — the usage ring then showed a wrong percentage for the model the
 * user had actually selected.
 *
 * [provider] is preferred but optional: a session payload may omit it, and then
 * the first id match is used (the previous behaviour).
 */
fun contextLimitFor(
    providers: List<ProviderEntry>,
    provider: String?,
    modelId: String?,
): Long? {
    if (modelId.isNullOrBlank()) return null

    fun Model.matchesRequested() = id == modelId || model == modelId
    val entries =
        providers.flatMap { group ->
            group.models.values.map { model ->
                (model.providerId?.takeIf { it.isNotBlank() } ?: group.id) to model
            }
        }
    val hit =
        provider
            ?.takeIf { it.isNotBlank() }
            ?.let { wanted -> entries.firstOrNull { (entryProvider, model) -> entryProvider == wanted && model.matchesRequested() } }
            ?: entries.firstOrNull { (_, model) -> model.matchesRequested() }
    return hit
        ?.second
        ?.limit
        ?.context
        ?.takeIf { it > 0 }
}
