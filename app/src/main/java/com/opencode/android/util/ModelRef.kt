package com.opencode.android.util

import com.opencode.android.domain.Model
import com.opencode.android.domain.SessionModel

/**
 * Compatibility shim: the model-ref arithmetic now lives in [ModelSelection].
 * Kept so existing call sites keep compiling while the strangler migration
 * moves them to ModelSelection one at a time.
 */
fun sessionModelRef(id: String?, provider: String?): String =
    ModelSelection.sessionRef(id, provider)

/** Resolves a selected ref to the exact model id expected by the server. */
fun resolveModelRef(ref: String, models: List<Model>): Pair<String, String> =
    ModelSelection.resolve(ref, models)

/** Resolves server session model metadata to the exact catalog selection ref. */
fun resolveSessionModelRef(model: SessionModel?, models: List<Model>): String? =
    ModelSelection.resolveSession(model, models)

/**
 * Human-friendly model label for a ref ("provider/model" or bare id): the
 * catalog display name when it resolves, otherwise the ref itself. Keeps the
 * header, the Review tab and the context dialog showing the SAME name.
 */
fun friendlyModelName(models: List<Model>, ref: String?): String =
    ModelSelection.friendlyName(models, ref)
