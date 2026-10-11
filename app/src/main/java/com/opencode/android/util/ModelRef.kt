package com.opencode.android.util

import com.opencode.android.domain.Model
import com.opencode.android.domain.ProviderEntry
import com.opencode.android.domain.SessionModel

/**
 * Compatibility shim: the model-ref arithmetic now lives in [ModelSelection].
 * Kept so existing call sites keep compiling while the strangler migration
 * moves them to ModelSelection one at a time.
 */
fun sessionModelRef(
    id: String?,
    provider: String?,
): String = ModelSelection.sessionRef(id, provider)

/** Resolves a selected ref to the exact model id expected by the server. */
fun resolveModelRef(
    ref: String,
    models: List<Model>,
): Pair<String, String> = ModelSelection.resolve(ref, models)

/** Resolves server session model metadata to the exact catalog selection ref. */
fun resolveSessionModelRef(
    model: SessionModel?,
    models: List<Model>,
): String? = ModelSelection.resolveSession(model, models)

/**
 * Human-friendly model label for a ref ("provider/model" or bare id): the
 * catalog display name when it resolves, otherwise the ref itself. Keeps the
 * header, the Review tab and the context dialog showing the SAME name.
 */
fun friendlyModelName(
    models: List<Model>,
    ref: String?,
): String = ModelSelection.friendlyName(models, ref)

/**
 * True when two refs point at the same catalog entry. Provider-aware, so a bare
 * session ref and the picker's provider-qualified ref still match.
 */
fun sameModelRefs(
    models: List<Model>,
    a: String?,
    b: String?,
): Boolean = ModelSelection.sameModel(models, a, b)

/**
 * Label for the ACTIVE model, with a provider suffix when that display name is
 * shared by several providers ("DeepSeek V4 Flash · opencode-go"), so the same
 * name stays unambiguous.
 */
fun activeModelLabel(
    models: List<Model>,
    ref: String?,
    providers: List<ProviderEntry> = emptyList(),
): String = ModelSelection.activeLabel(models, ref, providers)
