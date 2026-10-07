package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.Model
import com.opencode.android.domain.Part
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.domain.TodoItem
import com.opencode.android.ui.LiveStreamState
import com.opencode.android.util.resolveSessionModelRef
import kotlinx.serialization.json.JsonElement

/** Keeps only the tail of the live buffer (matches the pre-extraction VM rule). */
const val LIVE_TEXT_MAX_CHARS = 24_000

/** Bound on live tool parts: a long step can accumulate many (each holds state). */
const val LIVE_PARTS_MAX = 40

/** How many recent user-message ids the stream remembers to filter echoed parts. */
private const val MAX_TRACKED_USER_MESSAGES = 20

/**
 * Side effects a stream transition requests. The pure reducer never performs
 * them; [SessionStreamer] forwards the non-buffering ones and handles
 * [ScheduleFlush]/[Finalize] itself.
 */
sealed interface StreamEffect {
    /** A delta landed; schedule a coalesced flush. */
    data object ScheduleFlush : StreamEffect

    /**
     * One STEP finished (a turn has many steps: text → tool → text). Stop the
     * live stream and arm the persist timeout, but keep the turn's running
     * state: the composer must keep showing "stop" until the whole turn ends.
     */
    data object Finalize : StreamEffect

    /**
     * The whole TURN finished (`session.idle` / status idle / error). Clears
     * the turn-level running flag in addition to finalizing the live buffers.
     * This is the only signal allowed to flip the composer back to "send".
     */
    data object EndTurn : StreamEffect

    /** Re-read persisted messages (debounced by the caller). */
    data class Reconcile(
        val includeMeta: Boolean,
    ) : StreamEffect

    /** A single projected flag changed; owner applies exactly this field. */
    data class GeneratingChanged(
        val value: Boolean,
    ) : StreamEffect

    data class PendingPersistChanged(
        val value: Boolean,
    ) : StreamEffect

    data class CompactingChanged(
        val value: Boolean,
    ) : StreamEffect

    data class StatusErrorChanged(
        val value: String?,
    ) : StreamEffect

    data class SseConnectedChanged(
        val value: Boolean,
    ) : StreamEffect

    data class SelectedModelChanged(
        val value: String,
    ) : StreamEffect

    data object RefreshSessionModel : StreamEffect

    data object LoadVcsDiff : StreamEffect

    data object LoadPendingQuestions : StreamEffect

    /** A `question.asked` event delivered the full pending request. */
    data class QuestionAsked(
        val question: SessionQuestion,
    ) : StreamEffect

    /** A question was replied to/rejected; drop it from the pending list. */
    data class QuestionResolved(
        val requestId: String,
    ) : StreamEffect

    data object LoadPermissions : StreamEffect

    data object NotifyPermission : StreamEffect

    data object NotifyDone : StreamEffect

    data class NotifyError(
        val message: String?,
    ) : StreamEffect

    /** The agent is blocked on a question; notify so it can be answered. */
    data class NotifyQuestion(
        val question: SessionQuestion,
    ) : StreamEffect

    /**
     * The event stream (re)connected. Events that arrive exactly once — most
     * importantly `session.idle` — are lost if they land during a reconnect
     * gap, which left the UI on "generating…". The owner re-reads the session
     * status and clears the flag when the server says the turn is over.
     */
    data object ResyncSessionStatus : StreamEffect

    /** A `todo.updated` event carried the session's current todo list. */
    data class TodosUpdated(
        val todos: List<TodoItem>,
    ) : StreamEffect
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
    /**
     * TURN-level: the session has an in-flight turn. Set when a send leaves the
     * client or the server reports `busy`/`retry`, cleared ONLY on
     * `session.idle` / status `idle` / `session.error` (or an explicit abort).
     *
     * This is the single source of truth for "is this session running" and is
     * what the composer's send/stop button, the status row, the widget and the
     * polling/caching gates read. Step boundaries (`step-finish`) must never
     * clear it — that was the "button loses its state between steps" bug.
     */
    val isGenerating: Boolean = false,
    /**
     * STEP-level: the current step is actively producing content (text,
     * reasoning or tool output arriving). Drives only the streaming caret; it
     * legitimately drops at every `step-finish` while the turn keeps running.
     */
    val isStreaming: Boolean = false,
    val pendingPersist: Boolean = false,
    val isCompacting: Boolean = false,
    val statusError: String? = null,
    val sseConnected: Boolean = false,
    val selectedModel: String? = null,
    /**
     * True after the server reported the session idle. Late `message.*` frames
     * (the classic schema can deliver a final part after `session.idle`) must
     * not re-arm "generating" or re-add stale tool rows; a new turn clears it.
     */
    val turnEnded: Boolean = false,
    /**
     * Ids of USER messages seen this stream (bounded). The classic server emits
     * the user's own `message.part.updated`/`delta` frames; without this the
     * prompt was rendered as the live assistant response (and shown again as a
     * bubble) before the assistant's `step-start` reset the buffer.
     */
    val userMessageIds: List<String> = emptyList(),
    /** Effects of the LAST transition only; the streamer consumes and clears. */
    val effects: List<StreamEffect> = emptyList(),
) {
    fun projection(): StreamProjection =
        StreamProjection(
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
        // After the session went idle the server can still emit a trailing
        // `message.part.updated` carrying the just-finished text/reasoning part.
        // That frame is legitimate AND is the recovery path when streaming
        // deltas were dropped by the bounded SSE buffer — discarding it was why
        // the final summary sometimes only appeared after a manual stop.
        //
        // Only tool/step frames stay ignored: those are what used to re-arm
        // `isGenerating` and re-show the turn's old tool rows, leaving the
        // session stuck on "generating" until the user pressed stop.
        if (state.turnEnded) {
            val part = event.properties?.part
            if (event.type == "message.part.updated" &&
                part != null &&
                !state.isUserMessage(part.messageId) &&
                (part.type == "text" || part.type == "reasoning")
            ) {
                val live =
                    if (part.type == "text") {
                        state.live.copy(response = part.text?.let(::cap) ?: state.live.response)
                    } else {
                        state.live.copy(reasoning = part.text?.let(::cap) ?: state.live.reasoning)
                    }
                // Deliberately does NOT touch isGenerating: the turn is over,
                // this only makes the final text visible.
                return state.copy(live = live, effects = emptyList())
            }
            if (event.type == "message.part.updated" ||
                event.type == "message.part.delta" ||
                event.type == "message.updated"
            ) {
                return state.noEffects()
            }
        }
        return when (event.type) {
            SseEventDecoder.SSE_CONNECTED -> {
                state.copy(
                    sseConnected = true,
                    effects =
                        listOf(
                            StreamEffect.Reconcile(includeMeta = true),
                            StreamEffect.ResyncSessionStatus,
                        ),
                )
            }

            SseEventDecoder.SSE_DISCONNECTED -> {
                state.copy(
                    sseConnected = false,
                    effects = emptyList(),
                )
            }

            "message.part.delta" -> {
                val props = p ?: return state.noEffects()
                // The prompt's own parts are echoed back; never stream them into
                // the assistant response buffer.
                if (state.isUserMessage(props.messageId)) return state.noEffects()
                val delta = props.delta ?: return state.noEffects()
                val kind = props.partId?.let { state.partTypes[it] } ?: props.field
                val types =
                    if (kind != null && props.partId != null) {
                        state.partTypes + (props.partId to kind)
                    } else {
                        state.partTypes
                    }
                when (kind) {
                    "reasoning" -> {
                        state.copy(
                            partTypes = types,
                            pendingReasoning = state.pendingReasoning + delta,
                            isStreaming = true,
                            effects = listOf(StreamEffect.ScheduleFlush),
                        )
                    }

                    "text" -> {
                        state.copy(
                            partTypes = types,
                            pendingText = state.pendingText + delta,
                            isStreaming = true,
                            effects = listOf(StreamEffect.ScheduleFlush),
                        )
                    }

                    else -> {
                        state.copy(partTypes = types, effects = emptyList())
                    }
                }
            }

            "message.part.updated" -> {
                val part = p?.part ?: return state.noEffects()
                // A part of the user's own message (the prompt the server
                // echoes). Applying its text set the live assistant response to
                // the prompt until the assistant's step-start reset it.
                if (state.isUserMessage(part.messageId ?: p?.messageId)) return state.noEffects()
                val finished = part.time?.end != null
                val types = part.id?.let { state.partTypes + (it to part.type) } ?: state.partTypes
                when (part.type) {
                    "reasoning" -> {
                        state.copy(
                            partTypes = types,
                            pendingReasoning = "",
                            live =
                                state.live.copy(
                                    reasoning = part.text?.let(::cap) ?: state.live.reasoning,
                                    thinking = !finished,
                                ),
                            isGenerating = true,
                            isStreaming = true,
                            effects = emptyList(),
                        )
                    }

                    "text" -> {
                        state.copy(
                            partTypes = types,
                            pendingText = "",
                            live =
                                state.live.copy(
                                    thinking = false,
                                    response = part.text?.let(::cap) ?: state.live.response,
                                ),
                            isGenerating = true,
                            isStreaming = true,
                            statusError = null,
                            effects = emptyList(),
                        )
                    }

                    "tool" -> {
                        state.copy(
                            partTypes = types,
                            live = state.live.copy(thinking = false, parts = mergePart(state.live.parts, part)),
                            isGenerating = true,
                            isStreaming = true,
                            // The question tool blocks the turn until answered. Its
                            // part can land before the pending request is readable
                            // (and /global/event does not reliably forward the legacy
                            // question.asked bus event), so nudge a load here too.
                            effects =
                                if ((part.tool ?: part.name) == "question") {
                                    listOf(StreamEffect.LoadPendingQuestions)
                                } else {
                                    emptyList()
                                },
                        )
                    }

                    "step-start" -> {
                        state.copy(
                            partTypes = types,
                            // Classic event schema (this server sends no
                            // `session.next.step.started`): each step arrives as a
                            // `message.part.updated` with a step-start part. Without
                            // resetting here the live tool list accumulated every
                            // tool call of the whole turn — the "flooded with tool
                            // calls" after the summary while the agent kept going.
                            pendingText = "",
                            pendingReasoning = "",
                            live =
                                state.live.copy(
                                    response = "",
                                    reasoning = "",
                                    parts = emptyList(),
                                    thinking = false,
                                ),
                            isGenerating = true,
                            turnEnded = false,
                            isStreaming = true,
                            effects = emptyList(),
                        )
                    }

                    "step-finish" -> {
                        state.copy(
                            partTypes = types,
                            // The STEP is done, not the turn: stop the caret but
                            // keep `isGenerating` so the composer stays on "stop"
                            // until the server reports the turn idle.
                            isStreaming = false,
                            effects =
                                listOf(
                                    StreamEffect.Finalize,
                                    StreamEffect.Reconcile(includeMeta = true),
                                    StreamEffect.LoadPendingQuestions,
                                ),
                        )
                    }

                    "compaction" -> {
                        state.copy(
                            partTypes = types,
                            isCompacting = true,
                            isGenerating = true,
                            isStreaming = false,
                            effects = emptyList(),
                        )
                    }

                    else -> {
                        state.copy(partTypes = types, effects = emptyList())
                    }
                }
            }

            "message.updated" -> {
                val info = p?.info
                if (info?.role == "assistant") {
                    val modelId = info.model?.modelID ?: info.model?.id
                    val selected = resolveSessionModelRef(info.model, models)
                    state.copy(
                        live =
                            state.live.copy(
                                agent = info.agent ?: state.live.agent,
                                model = selected ?: modelId ?: state.live.model,
                            ),
                        isGenerating = true,
                        selectedModel = selected ?: state.selectedModel,
                        effects = emptyList(),
                    )
                } else if (info?.role == "user" && info.id != null) {
                    // Remember the user's message id so its echoed parts are not
                    // rendered as the assistant's live response.
                    state.copy(
                        userMessageIds = (state.userMessageIds + info.id).takeLast(MAX_TRACKED_USER_MESSAGES),
                        effects = emptyList(),
                    )
                } else {
                    state.noEffects()
                }
            }

            "session.status" -> {
                when (p?.status?.type) {
                    "busy" -> {
                        state.copy(
                            isGenerating = true,
                            turnEnded = false,
                            effects = emptyList(),
                        )
                    }

                    "retry" -> {
                        state.copy(
                            isGenerating = true,
                            turnEnded = false,
                            isStreaming = false,
                            statusError = p?.status?.message ?: "Request failed — retrying…",
                            effects = emptyList(),
                        )
                    }

                    "idle" -> {
                        state.copy(
                            isGenerating = false,
                            isStreaming = false,
                            turnEnded = true,
                            effects =
                                listOf(
                                    StreamEffect.EndTurn,
                                    StreamEffect.Reconcile(includeMeta = true),
                                    StreamEffect.NotifyDone,
                                ),
                        )
                    }

                    else -> {
                        state.noEffects()
                    }
                }
            }

            "session.idle" -> {
                state.copy(
                    isGenerating = false,
                    isStreaming = false,
                    turnEnded = true,
                    effects =
                        listOf(
                            StreamEffect.EndTurn,
                            StreamEffect.Reconcile(includeMeta = true),
                            StreamEffect.LoadPendingQuestions,
                            StreamEffect.NotifyDone,
                        ),
                )
            }

            "session.error" -> {
                val msg = friendlyError(p?.error)
                state.copy(
                    live = LiveStreamState(),
                    isGenerating = false,
                    isStreaming = false,
                    turnEnded = true,
                    statusError = msg,
                    // Clear the persist/compacting flags and finalize so the
                    // streamer cancels its persist timer: otherwise a prior
                    // step-finish's timer fires later and clears a subsequent
                    // turn's live buffers, and the UI stays stuck "compacting".
                    pendingPersist = false,
                    isCompacting = false,
                    effects =
                        listOf(
                            StreamEffect.EndTurn,
                            StreamEffect.NotifyError(msg),
                            StreamEffect.Reconcile(includeMeta = true),
                        ),
                )
            }

            "session.diff" -> {
                state.copy(effects = listOf(StreamEffect.LoadVcsDiff))
            }

            "todo.updated" -> {
                state.copy(effects = listOf(StreamEffect.TodosUpdated(p?.todos ?: emptyList())))
            }

            "session.updated" -> {
                state.copy(
                    effects =
                        listOf(
                            StreamEffect.RefreshSessionModel,
                            StreamEffect.Reconcile(includeMeta = true),
                        ),
                )
            }

            "permission.asked" -> {
                state.copy(
                    effects = listOf(StreamEffect.LoadPermissions, StreamEffect.NotifyPermission),
                )
            }

            "permission.replied" -> {
                state.copy(effects = listOf(StreamEffect.LoadPermissions))
            }

            // The server's question stack is v2: it emits `question.v2.asked`
            // (the v1 `question.asked` schema is defined but never emitted), so
            // listening only for v1 left the tool call hanging until the user
            // aborted the session.
            // The event payload carries the full request; the server's list
            // endpoints can stay empty while the question is pending, so the
            // card must be built from the event, not from a fetch.
            "question.asked", "question.v2.asked" -> {
                val requestId = p?.questionId ?: p?.requestId
                val asked =
                    requestId?.let {
                        SessionQuestion(
                            id = it,
                            sessionId = p?.sessionId,
                            questions = p?.questions ?: emptyList(),
                            tool = p?.tool,
                        )
                    }
                state.copy(
                    isGenerating = true,
                    isStreaming = false,
                    statusError = null,
                    effects =
                        buildList {
                            if (asked != null) {
                                add(StreamEffect.QuestionAsked(asked))
                                // "Needs input", not "finished": notify with
                                // the answer deep-link instead of the
                                // misleading "agent finished" alert.
                                add(StreamEffect.NotifyQuestion(asked))
                            } else {
                                add(StreamEffect.NotifyDone)
                            }
                            add(StreamEffect.LoadPendingQuestions)
                        },
                )
            }

            "question.replied", "question.rejected",
            "question.v2.replied", "question.v2.rejected",
            -> {
                val requestId = p?.requestId ?: p?.questionId
                state.copy(
                    effects =
                        buildList {
                            if (requestId != null) add(StreamEffect.QuestionResolved(requestId))
                            add(StreamEffect.LoadPendingQuestions)
                        },
                )
            }

            "session.next.step.started" -> {
                val modelId = event.data?.model?.id
                val selected = resolveSessionModelRef(event.data?.model, models)
                state.copy(
                    live =
                        state.live.copy(
                            response = "",
                            reasoning = "",
                            parts = emptyList(),
                            agent = event.data?.agent ?: state.live.agent,
                            model = selected ?: modelId ?: state.live.model,
                        ),
                    isGenerating = true,
                    isStreaming = true,
                    turnEnded = false,
                    selectedModel = selected ?: state.selectedModel,
                    effects = emptyList(),
                )
            }

            "session.next.reasoning.started" -> {
                state.copy(
                    live = state.live.copy(reasoning = "", thinking = true),
                    isStreaming = true,
                    effects = emptyList(),
                )
            }

            "session.next.reasoning.chunk" -> {
                state.copy(
                    pendingReasoning = state.pendingReasoning + (event.data?.text ?: ""),
                    isStreaming = true,
                    effects = listOf(StreamEffect.ScheduleFlush),
                )
            }

            "session.next.reasoning.ended" -> {
                state.copy(
                    live = state.live.copy(thinking = false),
                    effects = emptyList(),
                )
            }

            "session.next.text.started" -> {
                state.copy(
                    live = state.live.copy(thinking = false),
                    isGenerating = true,
                    isStreaming = true,
                    effects = emptyList(),
                )
            }

            "session.next.text.ended" -> {
                state.copy(
                    pendingText = state.pendingText + (event.data?.text ?: ""),
                    isStreaming = true,
                    effects = listOf(StreamEffect.ScheduleFlush),
                )
            }

            "session.next.step.ended" -> {
                state.copy(
                    // A STEP boundary, not a turn boundary (the turn ends on
                    // `session.idle` / `session.execution.succeeded`). Keep the
                    // turn running so the composer does not flip mid-turn.
                    isStreaming = false,
                    effects =
                        listOf(
                            StreamEffect.Finalize,
                            StreamEffect.Reconcile(includeMeta = true),
                            StreamEffect.LoadPendingQuestions,
                        ),
                )
            }

            "session.next.step.failed" -> {
                val msg = friendlyError(event.data?.error)
                state.copy(
                    // Do NOT clear isGenerating here: a failed step may be
                    // retried (`session.retry.scheduled`), and the authoritative
                    // turn end arrives as `session.execution.failed` →
                    // `session.error`. Clearing here made the stop button vanish
                    // while the server was still working.
                    live = LiveStreamState(),
                    isStreaming = false,
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
            "session.next.tool.input.ended",
            -> {
                state.copy(
                    effects =
                        listOf(
                            StreamEffect.Reconcile(includeMeta = true),
                            StreamEffect.LoadPendingQuestions,
                        ),
                )
            }

            "session.next.model.switched" -> {
                state.copy(effects = listOf(StreamEffect.Reconcile(includeMeta = false)))
            }

            else -> {
                state.noEffects()
            }
        }
    }

    /** Effects for each projected field that changed between two snapshots. */
    fun projectionEffects(
        previous: StreamProjection,
        current: StreamProjection,
    ): List<StreamEffect> =
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

    /** True when [messageId] belongs to a USER message seen on this stream. */
    private fun StreamState.isUserMessage(messageId: String?): Boolean = messageId != null && messageId in userMessageIds

    private fun cap(s: String): String = if (s.length > LIVE_TEXT_MAX_CHARS) s.takeLast(LIVE_TEXT_MAX_CHARS) else s

    private fun mergePart(
        parts: List<Part>,
        part: Part,
    ): List<Part> {
        // Bound the retained payload of each live part: a streaming tool part
        // carries the full output, and a tool storm of 40 such parts can hold
        // hundreds of MB even though only the first 20k chars are ever drawn.
        val capped =
            com.opencode.android.util.PayloadCaps
                .capPart(part)
        val idx = parts.indexOfFirst { it.id != null && it.id == capped.id }
        val merged =
            if (idx >= 0) {
                parts.toMutableList().also { it[idx] = capped }
            } else {
                parts + capped
            }
        return if (merged.size > LIVE_PARTS_MAX) merged.takeLast(LIVE_PARTS_MAX) else merged
    }
}
