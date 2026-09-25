package com.opencode.android.ui.session

/**
 * Candidate 1 strangler: the pure part of the send path.
 *
 * `performSend` used to resolve "which model/agent/variant does this request
 * actually use?" inline, mixing server state, optimistic local selections and
 * two pending flags across ~30 lines of nested `if`s. The decision has no
 * Android, network or UI dependency, so it lives here where it can be tested
 * without a running server — and the ViewModel keeps only the IO around it.
 */
data class ServerSelection(
    val model: String?,
    val agent: String?,
    val variant: String?,
)

data class UiSelection(
    val model: String,
    val agent: String,
    val variant: String,
    val modelPending: Boolean,
    val agentPending: Boolean,
)

data class EffectiveSelection(
    val model: String,
    val agent: String,
    val variant: String,
)

/**
 * A selection the user just made locally ([UiSelection.modelPending] /
 * [agentPending]) wins until the server has acknowledged it; otherwise the
 * server's value is authoritative and falls back to the local one when the
 * server omits it. Variant follows the model: a pending model change also
 * carries the pending variant.
 */
fun effectiveSelection(server: ServerSelection, ui: UiSelection): EffectiveSelection =
    EffectiveSelection(
        model = if (ui.modelPending) ui.model else server.model ?: ui.model,
        agent = if (ui.agentPending) ui.agent else server.agent ?: ui.agent,
        variant = if (ui.modelPending) ui.variant else server.variant ?: ui.variant,
    )
