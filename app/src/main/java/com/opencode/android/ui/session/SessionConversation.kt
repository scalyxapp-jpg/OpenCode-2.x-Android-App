package com.opencode.android.ui.session

import com.opencode.android.data.SessionTransport
import com.opencode.android.domain.Model
import com.opencode.android.ui.LiveStreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Candidate 1 — SessionConversation.
 *
 * The module that runs one OpenCode session: it accepts [SessionCommand]s,
 * drives the [SessionStreamer] over an [EventSource], and exposes the
 * conversation flags as a [StateFlow] the ViewModel projects into `ChatUiState`.
 *
 * `ChatUiState` stays the single UI truth; this module never touches Compose or
 * Android. The actual HTTP work for send/interrupt/retry is reached through
 * [ConversationPort] so those existing paths keep running unchanged while the
 * streaming + reconcile workflows are extracted first.
 */
sealed interface SessionCommand {
    /** Open [sessionId] at [baseUrl] and begin streaming it. */
    data class Load(val sessionId: String, val baseUrl: String) : SessionCommand
    data object Send : SessionCommand
    data object Interrupt : SessionCommand
    data object Retry : SessionCommand
}

/** Conversation flags owned by this module; projected into `ChatUiState`. */
data class ConversationState(
    val sessionId: String? = null,
    val isGenerating: Boolean = false,
    val pendingPersist: Boolean = false,
    val isCompacting: Boolean = false,
    val statusError: String? = null,
    val sseConnected: Boolean = false,
    val selectedModel: String? = null,
)

/**
 * Work the conversation needs but does not own yet. Implemented by the
 * ViewModel (or a fake in tests) so send/interrupt/retry/reload keep their
 * existing code paths during the strangler migration.
 */
interface ConversationPort {
    fun send()
    fun retry()
    fun reconcile(includeMeta: Boolean)
    fun loadPendingQuestions()
    fun loadPermissions()
    fun notifyPermission()
    fun notifyDone()
    fun notifyError(message: String?)
    fun notifyInterruptFailed(message: String?)
    fun refreshMessages(sessionId: String)
    fun refreshSessionModel()
    fun loadVcsDiff()

    fun setGenerating(value: Boolean)
    fun setPendingPersist(value: Boolean)
    fun setCompacting(value: Boolean)
    fun setStatusError(value: String?)
    fun setSseConnected(value: Boolean)
    fun setSelectedModel(value: String)
}

class SessionConversation(
    source: EventSource,
    scope: CoroutineScope,
    models: () -> List<Model>,
    friendlyError: (JsonElement?) -> String?,
    private val transport: SessionTransport,
    private val port: ConversationPort,
) {
    private val scope = scope
    val streamer = SessionStreamer(source, scope, models, friendlyError, ::handleEffect)

    private val _commands = MutableSharedFlow<SessionCommand>(extraBufferCapacity = 16)

    /** Observers (e.g. analytics/tests) can see commands without changing control flow. */
    val commands: SharedFlow<SessionCommand> = _commands.asSharedFlow()

    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    val liveState: StateFlow<LiveStreamState> get() = streamer.liveState

    /**
     * Runs [command] immediately (control flow is synchronous, matching the old
     * ViewModel) and publishes it to [commands] observers.
     */
    fun dispatch(command: SessionCommand) {
        _commands.tryEmit(command)
        when (command) {
            is SessionCommand.Load -> {
                // Reset conversation flags for the new session; the ViewModel
                // re-seeds isGenerating/selected* from the loaded session.
                _state.value = ConversationState(sessionId = command.sessionId)
                streamer.start(command.sessionId, command.baseUrl)
            }
            SessionCommand.Send -> port.send()
            SessionCommand.Interrupt -> interrupt()
            SessionCommand.Retry -> port.retry()
        }
    }

    /**
     * The interrupt workflow: finalize the live stream first so the partial
     * answer stays on screen (plainly clearing isGenerating hid it), then ask
     * the server to abort, falling back to the older interrupt endpoint, and
     * finally reload the messages so the aborted state is shown.
     */
    private fun interrupt() {
        finalize()
        val sessionId = _state.value.sessionId ?: return
        scope.launch {
            try {
                // Web stop action: POST /session/{id}/abort -> 200 "true".
                val response = transport.abort(sessionId)
                if (!response.isSuccessful) {
                    transport.interrupt(sessionId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                port.notifyInterruptFailed(e.message)
            } finally {
                port.refreshMessages(sessionId)
            }
        }
    }

    fun stop() = streamer.stop()

    fun resetLive() = streamer.resetLive()

    fun finalize() = streamer.finalize()

    fun completePersist() = streamer.completePersist()

    private fun handleEffect(effect: StreamEffect) {
        when (effect) {
            is StreamEffect.Reconcile -> port.reconcile(effect.includeMeta)
            StreamEffect.LoadPendingQuestions -> port.loadPendingQuestions()
            StreamEffect.LoadPermissions -> port.loadPermissions()
            StreamEffect.NotifyPermission -> port.notifyPermission()
            StreamEffect.NotifyDone -> port.notifyDone()
            is StreamEffect.NotifyError -> port.notifyError(effect.message)
            StreamEffect.RefreshSessionModel -> port.refreshSessionModel()
            StreamEffect.LoadVcsDiff -> port.loadVcsDiff()
            is StreamEffect.GeneratingChanged -> {
                _state.update { it.copy(isGenerating = effect.value) }
                port.setGenerating(effect.value)
            }
            is StreamEffect.PendingPersistChanged -> {
                _state.update { it.copy(pendingPersist = effect.value) }
                port.setPendingPersist(effect.value)
            }
            is StreamEffect.CompactingChanged -> {
                _state.update { it.copy(isCompacting = effect.value) }
                port.setCompacting(effect.value)
            }
            is StreamEffect.StatusErrorChanged -> {
                _state.update { it.copy(statusError = effect.value) }
                port.setStatusError(effect.value)
            }
            is StreamEffect.SseConnectedChanged -> {
                _state.update { it.copy(sseConnected = effect.value) }
                port.setSseConnected(effect.value)
            }
            is StreamEffect.SelectedModelChanged -> {
                _state.update { it.copy(selectedModel = effect.value) }
                port.setSelectedModel(effect.value)
            }
            StreamEffect.ScheduleFlush, StreamEffect.Finalize -> Unit
        }
    }
}
