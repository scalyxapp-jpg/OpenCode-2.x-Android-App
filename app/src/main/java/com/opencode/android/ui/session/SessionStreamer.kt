package com.opencode.android.ui.session

import com.opencode.android.domain.Event
import com.opencode.android.domain.Model
import com.opencode.android.ui.LiveStreamState
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.LiveFlushPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Owns one session's live streaming state: consumes an [EventSource], applies
 * [StreamReducer], owns the coalesced delta flush and the persist timeout, and
 * emits the transport-independent side effects back to its owner.
 *
 * Extracted from `ChatViewModel.startSse`. The reconcile debounce
 * (`RefreshCoalescer`) deliberately stays with the owner — it is a message-cache
 * concern, not a stream concern.
 */
class SessionStreamer(
    private val source: EventSource,
    private val scope: CoroutineScope,
    private val models: () -> List<Model>,
    private val friendlyError: (JsonElement?) -> String?,
    private val onSignal: (StreamEffect) -> Unit,
) {
    private val _liveState = MutableStateFlow(LiveStreamState())
    val liveState: StateFlow<LiveStreamState> = _liveState.asStateFlow()

    private val _projection = MutableStateFlow(StreamProjection())
    val projection: StateFlow<StreamProjection> = _projection.asStateFlow()

    private var state = StreamState()
    private var candidate: Job? = null
    private var flushJob: Job? = null
    private var persistTimeoutJob: Job? = null
    private var activeSession: String? = null

    /** Starts consuming [sessionId]; any prior stream is cancelled. */
    fun start(sessionId: String, baseUrl: String) {
        stop()
        activeSession = sessionId
        state = StreamState()
        publish()
        candidate = scope.launch {
            try {
                source.stream(baseUrl, sessionId).collect { event ->
                    if (activeSession != sessionId) return@collect
                    if (!SseFilter.shouldDeliver(event, sessionId)) return@collect
                    accept(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "SessionStreamer: stream error: ${e.message}")
                onSignal(StreamEffect.NotifyError(e.message))
            }
        }
    }

    /** Cancels the stream and any pending flush/timeout. */
    fun stop() {
        candidate?.cancel()
        candidate = null
        flushJob?.cancel()
        flushJob = null
        persistTimeoutJob?.cancel()
        persistTimeoutJob = null
        activeSession = null
    }

    /** Clears the live buffers immediately (send/interrupt/session switch). */
    fun resetLive() {
        flushJob?.cancel()
        flushJob = null
        state = state.copy(
            live = LiveStreamState(),
            pendingReasoning = "",
            pendingText = "",
            partTypes = emptyMap(),
        )
        publish()
    }

    /**
     * Synchronously applies one event. Exposed so a caller that already owns the
     * event flow (or a test) can drive the streamer without the [EventSource]
     * adapter.
     */
    fun accept(event: Event) {
        val before = state.projection()
        state = StreamReducer.reduce(state, event, models(), friendlyError)
        for (effect in state.effects) {
            when (effect) {
                StreamEffect.ScheduleFlush -> scheduleFlush()
                StreamEffect.Finalize -> finalize()
                else -> onSignal(effect)
            }
        }
        publish()
        val after = state.projection()
        if (after != before) StreamReducer.projectionEffects(before, after).forEach(onSignal)
    }

    /** Matches the old `finalizeStream()`: stop live, arm the persist timeout. */
    fun finalize() {
        val live = state.live
        if (live.response.isBlank() && live.reasoning.isBlank() && live.parts.isEmpty()) {
            state = state.copy(
                live = LiveStreamState(),
                isGenerating = false,
                pendingPersist = false,
                isCompacting = false,
            )
            publish()
            emitFlowFlags()
            return
        }
        state = state.copy(isGenerating = false, pendingPersist = true, isCompacting = false)
        publish()
        emitFlowFlags()
        persistTimeoutJob?.cancel()
        persistTimeoutJob = scope.launch {
            delay(PERSIST_TIMEOUT_MS)
            completePersist()
        }
    }

    /** Called when the persisted assistant message lands (or after the timeout). */
    fun completePersist() {
        if (!state.pendingPersist) return
        val before = state.projection()
        persistTimeoutJob?.cancel()
        state = state.copy(live = LiveStreamState(), pendingPersist = false)
        publish()
        val after = state.projection()
        if (after != before) StreamReducer.projectionEffects(before, after).forEach(onSignal)
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        val liveLen = state.pendingText.length + state.live.response.length
        val windowMs = LiveFlushPolicy.windowMs(liveLen)
        val sessionId = activeSession
        flushJob = scope.launch {
            delay(windowMs)
            if (activeSession != sessionId) return@launch
            flushNow()
        }
    }

    private fun flushNow() {
        val reasoning = state.pendingReasoning
        val text = state.pendingText
        if (reasoning.isEmpty() && text.isEmpty()) return
        val before = state.projection()
        state = state.copy(
            pendingReasoning = "",
            pendingText = "",
            live = state.live.copy(
                reasoning = capLive(state.live.reasoning + reasoning),
                response = capLive(state.live.response + text),
                thinking = reasoning.isNotEmpty(),
            ),
            statusError = null,
        )
        publish()
        val after = state.projection()
        if (after != before) StreamReducer.projectionEffects(before, after).forEach(onSignal)
    }

    private fun publish() {
        _liveState.value = state.live
        _projection.value = state.projection()
    }

    /** Force the flow-control flags into the owner (finalize is authoritative). */
    private fun emitFlowFlags() {
        onSignal(StreamEffect.GeneratingChanged(state.isGenerating))
        onSignal(StreamEffect.PendingPersistChanged(state.pendingPersist))
        onSignal(StreamEffect.CompactingChanged(state.isCompacting))
    }

    private fun capLive(s: String): String =
        if (s.length > LIVE_TEXT_MAX_CHARS) s.takeLast(LIVE_TEXT_MAX_CHARS) else s

    companion object {
        const val PERSIST_TIMEOUT_MS = 3_000L
    }
}
