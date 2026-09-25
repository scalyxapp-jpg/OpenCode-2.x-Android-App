package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.Model
import com.opencode.android.domain.Part
import com.opencode.android.ui.LiveStreamState
import com.opencode.android.util.resolveSessionModelRef
import kotlinx.serialization.json.JsonElement

/** Keeps only the tail of the live buffer (matches the pre-extraction VM rule). */
const val LIVE_TEXT_MAX_CHARS = 24_000

/** Bound on live tool parts: a long step can accumulate many (each holds state). */
const val LIVE_PARTS_MAX = 40

/**
 * Side effects a stream transition requests. The pure reducer never performs
 * them; [SessionStreamer] forwards the non-buffering ones and handles
 * [ScheduleFlush]/[Finalize] itself.
 */
sealed interface StreamEffect {
    /** A delta landed; schedule a coalesced flush. */
    data object ScheduleFlush : StreamEffect

    /** The turn finished; stop the live stream and arm the persist timeout. */
    data object Finalize : StreamEffect

    /** Re-read persisted messages (debounced by the caller). */
    data class Reconcile(val includeMeta: Boolean) : StreamEffect

    /** A single projected flag changed; owner applies exactly this field. */
    data class GeneratingChanged(val value: Boolean) : StreamEffect
    data class PendingPersistChanged(val value: Boolean) : StreamEffect
    data class CompactingChanged(val value: Boolean) : StreamEffect
    data class StatusErrorChanged(val value: String?) : StreamEffect
    data class SseConnectedChanged(val value: Boolean) : StreamEffect
    data class SelectedModelChanged(val value: String) : StreamEffect

    data object RefreshSessionModel : StreamEffect
    data object LoadVcsDiff : StreamEffect
    data object LoadPendingQuestions : StreamEffect
    data object LoadPermissions : StreamEffect
    data object NotifyPermission : StreamEffect
    data object NotifyDone : StreamEffect
    data class NotifyError(val message: String?) : StreamEffect
}

/** Generation/connection fields the stream owns that project into ChatUiState. */
data class StreamProjection(
    val isGenerating: Boolean = false,
    val pendingPersist: Boolean = false,
    val isCompacting: Boolean = false,
    val statusError: String? = null,
    val sseConnected: Boolean = false,
    val selectedModel: String? = null,
)

/**
 * Full pure state of one session's live stream: committed [live] buffers, the
 * pending delta accumulators, the part->type map, and the projected flags.
 */
data class StreamState(
    val live: LiveStreamState = LiveStreamState(),
    val pendingReasoning: String = "",
    val pendingText: String = "",
    val partTypes: Map<String, String> = emptyMap(),
    val isGenerating: Boolean = false,
    val pendingPersist: Boolean = false,
    val isCompacting: Boolean = false,
    val statusError: String? = null,
    val sseConnected: Boolean = false,
    val selectedModel: String? = null,
    /** Effects of the LAST transition only; the streamer consumes and clears. */
    val effects: List<StreamEffect> = emptyList(),
) {
    fun projection(): StreamProjection = StreamProjection(
        isGenerating = isGenerating,
        pendingPersist = pendingPersist,
        isCompacting = isCompacting,
        statusError = statusError,
        sseConnected = sseConnected,
        selectedModel = selectedModel,
    )
}

/**
 * The pure part of [SessionStreamer]: an event sequence to a state transition,
 * with no transport, coroutine, or Android dependency. Safe to unit-test by
 * feeding a list of [Event]s.
 */
object StreamReducer {
    fun reduce(
        state: StreamState,
        event: Event,
        models: List<Model>,
        friendlyError: (JsonElement?) -> String?,
    ): StreamState {
        val p = event.properties
        return when (event.type) {
            SseEventDecoder.SSE_CONNECTED -> state.copy(
                sseConnected = true,
                effects = listOf(StreamEffect.Reconcile(includeMeta = true)),
            )

            SseEventDecoder.SSE_DISCONNECTED -> state.copy(
                sseConnected = false,
                effects = emptyList(),
            )

            "message.part.delta" -> {
                val props = p ?: return state.noEffects()
                val delta = props.delta ?: return state.noEffects()
                val kind = props.partId?.let { state.partTypes[it] } ?: props.field
                val types = if (kind != null && props.partId != null) {
                    state.partTypes + (props.partId to kind)
                } else {
                    state.partTypes
                }
                when (kind) {
                    "reasoning" -> state.copy(
                        partTypes = types,
                        pendingReasoning = state.pendingReasoning + delta,
                        effects = listOf(StreamEffect.ScheduleFlush),
                    )
                    "text" -> state.copy(
                        partTypes = types,
                        pendingText = state.pendingText + delta,
                        effects = listOf(StreamEffect.ScheduleFlush),
                    )
                    else -> state.copy(partTypes = types, effects = emptyList())
                }
            }

            "message.part.updated" -> {
                val part = p?.part ?: return state.noEffects()
                val finished = part.time?.end != null
                val types = part.id?.let { state.partTypes + (it to part.type) } ?: state.partTypes
                when (part.type) {
                    "reasoning" -> state.copy(
                        partTypes = types,
                        pendingReasoning = "",
                        live = state.live.copy(
                            reasoning = part.text?.let(::cap) ?: state.live.reasoning,
                            thinking = !finished,
                        ),
                        isGenerating = true,
                        effects = emptyList(),
                    )
                    "text" -> state.copy(
                        partTypes = types,
                        pendingText = "",
                        live = state.live.copy(
                            thinking = false,
                            response = part.text?.let(::cap) ?: state.live.response,
                        ),
                        isGenerating = true,
                        statusError = null,
                        effects = emptyList(),
                    )
                    "tool" -> state.copy(
                        partTypes = types,
                        live = state.live.copy(thinking = false, parts = mergePart(state.live.parts, part)),
                        isGenerating = true,
                        effects = emptyList(),
                    )
                    "step-finish" -> state.copy(
                        partTypes = types,
                        effects = listOf(
                            StreamEffect.Finalize,
                            StreamEffect.Reconcile(includeMeta = true),
                            StreamEffect.LoadPendingQuestions,
                        ),
                    )
                    "compaction" -> state.copy(
                        partTypes = types,
                        isCompacting = true,
                        isGenerating = true,
                        effects = emptyList(),
                    )
                    else -> state.copy(partTypes = types, effects = emptyList())
                }
            }

            "message.updated" -> {
                val info = p?.info
                if (info?.role == "assistant") {
                    val modelId = info.model?.modelID ?: info.model?.id
                    val selected = resolveSessionModelRef(info.model, models)
                    state.copy(
                        live = state.live.copy(
                            agent = info.agent ?: state.live.agent,
                            model = selected ?: modelId ?: state.live.model,
                        ),
                        isGenerating = true,
                        selectedModel = selected ?: state.selectedModel,
                        effects = emptyList(),
                    )
                } else {
                    state.noEffects()
                }
            }

            "session.status" -> when (p?.status?.type) {
                "busy" -> state.copy(isGenerating = true, effects = emptyList())
                "retry" -> state.copy(
                    isGenerating = true,
                    statusError = p?.status?.message ?: "Request failed — retrying…",
                    effects = emptyList(),
                )
                "idle" -> state.copy(
                    effects = listOf(
                        StreamEffect.Finalize,
                        StreamEffect.Reconcile(includeMeta = true),
                        StreamEffect.NotifyDone,
                    ),
                )
                else -> state.noEffects()
            }

            "session.idle" -> state.copy(
                effects = listOf(
                    StreamEffect.Finalize,
                    StreamEffect.Reconcile(includeMeta = true),
                    StreamEffect.LoadPendingQuestions,
                    StreamEffect.NotifyDone,
                ),
            )

            "session.error" -> {
                val msg = friendlyError(p?.error)
                state.copy(
                    live = LiveStreamState(),
                    isGenerating = false,
                    statusError = msg,
                    effects = listOf(
                        StreamEffect.NotifyError(msg),
                        StreamEffect.Reconcile(includeMeta = true),
                    ),
                )
            }

            "session.diff" -> state.copy(effects = listOf(StreamEffect.LoadVcsDiff))

            "session.updated" -> state.copy(
                effects = listOf(
                    StreamEffect.RefreshSessionModel,
                    StreamEffect.Reconcile(includeMeta = true),
                ),
            )

            "permission.asked" -> state.copy(
                effects = listOf(StreamEffect.LoadPermissions, StreamEffect.NotifyPermission),
            )
            "permission.replied" -> state.copy(effects = listOf(StreamEffect.LoadPermissions))

            "question.asked" -> state.copy(
                isGenerating = true,
                statusError = null,
                effects = listOf(StreamEffect.LoadPendingQuestions, StreamEffect.NotifyDone),
            )
            "question.replied", "question.rejected" ->
                state.copy(effects = listOf(StreamEffect.LoadPendingQuestions))

            "session.next.step.started" -> {
                val modelId = event.data?.model?.id
                val selected = resolveSessionModelRef(event.data?.model, models)
                state.copy(
                    live = state.live.copy(
                        response = "",
                        reasoning = "",
                        parts = emptyList(),
                        agent = event.data?.agent ?: state.live.agent,
                        model = selected ?: modelId ?: state.live.model,
                    ),
                    isGenerating = true,
                    selectedModel = selected ?: state.selectedModel,
                    effects = emptyList(),
                )
            }
            "session.next.reasoning.started" -> state.copy(
                live = state.live.copy(reasoning = "", thinking = true),
                effects = emptyList(),
            )
            "session.next.reasoning.chunk" -> state.copy(
                pendingReasoning = state.pendingReasoning + (event.data?.text ?: ""),
                effects = listOf(StreamEffect.ScheduleFlush),
            )
            "session.next.reasoning.ended" -> state.copy(
                live = state.live.copy(thinking = false),
                effects = emptyList(),
            )
            "session.next.text.started" -> state.copy(
                live = state.live.copy(thinking = false),
                isGenerating = true,
                effects = emptyList(),
            )
            "session.next.text.ended" -> state.copy(
                pendingText = state.pendingText + (event.data?.text ?: ""),
                effects = listOf(StreamEffect.ScheduleFlush),
            )
            "session.next.step.ended" -> state.copy(
                effects = listOf(
                    StreamEffect.Finalize,
                    StreamEffect.Reconcile(includeMeta = true),
                    StreamEffect.LoadPendingQuestions,
                    StreamEffect.NotifyDone,
                ),
            )
            "session.next.step.failed" -> {
                val msg = friendlyError(event.data?.error)
                state.copy(
                    live = LiveStreamState(),
                    isGenerating = false,
                    statusError = msg,
                    effects = listOf(StreamEffect.Reconcile(includeMeta = true)),
                )
            }
            "session.next.tool.failed" -> {
                val msg = friendlyError(event.data?.error)
                state.copy(
                    statusError = msg ?: state.statusError,
                    effects = listOf(StreamEffect.Reconcile(includeMeta = false)),
                )
            }
            "session.next.tool.called", "session.next.tool.success",
            "session.next.tool.input.ended" -> state.copy(
                effects = listOf(
                    StreamEffect.Reconcile(includeMeta = true),
                    StreamEffect.LoadPendingQuestions,
                ),
            )
            "session.next.model.switched" ->
                state.copy(effects = listOf(StreamEffect.Reconcile(includeMeta = false)))

            else -> state.noEffects()
        }
    }

    /** Effects for each projected field that changed between two snapshots. */
    fun projectionEffects(previous: StreamProjection, current: StreamProjection): List<StreamEffect> =
        buildList {
            if (previous.isGenerating != current.isGenerating) {
                add(StreamEffect.GeneratingChanged(current.isGenerating))
            }
            if (previous.pendingPersist != current.pendingPersist) {
                add(StreamEffect.PendingPersistChanged(current.pendingPersist))
            }
            if (previous.isCompacting != current.isCompacting) {
                add(StreamEffect.CompactingChanged(current.isCompacting))
            }
            if (previous.statusError != current.statusError) {
                add(StreamEffect.StatusErrorChanged(current.statusError))
            }
            if (previous.sseConnected != current.sseConnected) {
                add(StreamEffect.SseConnectedChanged(current.sseConnected))
            }
            if (previous.selectedModel != current.selectedModel && current.selectedModel != null) {
                add(StreamEffect.SelectedModelChanged(current.selectedModel))
            }
        }

    private fun StreamState.noEffects(): StreamState = copy(effects = emptyList())

    private fun cap(s: String): String =
        if (s.length > LIVE_TEXT_MAX_CHARS) s.takeLast(LIVE_TEXT_MAX_CHARS) else s

    private fun mergePart(parts: List<Part>, part: Part): List<Part> {
        val idx = parts.indexOfFirst { it.id != null && it.id == part.id }
        val merged = if (idx >= 0) {
            parts.toMutableList().also { it[idx] = part }
        } else {
            parts + part
        }
        return if (merged.size > LIVE_PARTS_MAX) merged.takeLast(LIVE_PARTS_MAX) else merged
    }
}
