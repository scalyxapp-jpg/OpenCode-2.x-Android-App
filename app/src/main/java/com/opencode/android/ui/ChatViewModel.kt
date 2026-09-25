package com.opencode.android.ui
import com.opencode.android.util.UserMessages
import com.opencode.android.R
import androidx.compose.runtime.Immutable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.data.BackendSession
import com.opencode.android.data.OpenCodeApi
import com.opencode.android.data.ModelVisibilityStore
import com.opencode.android.data.SseClient
import com.opencode.android.ui.session.ConversationPort
import com.opencode.android.ui.session.PromptSendResult
import com.opencode.android.ui.session.PromptSender
import com.opencode.android.ui.session.ServerSelection
import com.opencode.android.ui.session.SessionCommand
import com.opencode.android.ui.session.SessionConversation
import com.opencode.android.ui.session.UiSelection
import com.opencode.android.ui.session.buildDisplayText
import com.opencode.android.ui.session.buildOptimisticEcho
import com.opencode.android.ui.session.buildPromptAsyncRequest
import com.opencode.android.ui.session.buildPromptText
import com.opencode.android.ui.session.effectiveSelection
import com.opencode.android.util.ModelSelection
import com.opencode.android.domain.Agent
import com.opencode.android.domain.ContextUsage
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.Message
import com.opencode.android.domain.Model
import com.opencode.android.domain.ModelRef
import com.opencode.android.domain.ModelRefRequest
import com.opencode.android.domain.ForkRequest
import com.opencode.android.domain.ModelVariant
import com.opencode.android.domain.PromptInput
import com.opencode.android.domain.PromptRequest
import com.opencode.android.domain.RevertRequest
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionTimeUpdate
import com.opencode.android.domain.SessionUpdateRequest
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.UploadResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.opencode.android.data.LastSessionStore
import com.opencode.android.domain.BUILTIN_COMMANDS
import com.opencode.android.domain.CommandEntry
import com.opencode.android.domain.McpEntry
import com.opencode.android.domain.SummarizeRequest
import com.opencode.android.domain.Part
import com.opencode.android.domain.PermissionReplyRequest
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.ProviderEntry
import com.opencode.android.domain.QuestionReplyRequest
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.domain.TodoItem
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.util.AppLog
import com.opencode.android.util.resolveModelRef
import com.opencode.android.util.resolveSessionModelRef
import com.opencode.android.data.ChatRepository

/** Matches the proxy's SESSION_GUARD_MAX_UPLOAD default. */
private const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

@Immutable
data class Attachment(
    val uri: String,
    val name: String,
    val mime: String? = null,
    val size: Long? = null,
)

@Immutable
data class FileViewerState(
    val path: String,
    val content: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

// High-frequency streaming state, deliberately kept OUT of ChatUiState.
// Token deltas flush several times per second; folding them into ChatUiState
// recomposed the whole ChatScreen — including every visible markdown
// MessageBubble — on every flush, which measured ~70% janky frames (gfxinfo)
// while a response streamed. A dedicated StateFlow is collected only by the
// live section, so a token update recomposes just that small subtree.
// @Immutable lets Compose skip recomposing consumers when the instance is
// unchanged; the app never mutates these lists in place (always copies).
@Immutable
data class LiveStreamState(
    val response: String = "",
    val reasoning: String = "",
    // True while the model is reasoning (web shows a "Thinking" indicator).
    val thinking: Boolean = false,
    val agent: String? = null,
    val model: String? = null,
    // Live tool parts of the in-flight step, rendered immediately so tool rows
    // appear without waiting for a full message reload.
    val parts: List<Part> = emptyList(),
)

@Immutable
data class ChatUiState(
    val session: Session? = null,
    val messages: List<Message> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val models: List<Model> = emptyList(),
    // Full provider catalog (GET /provider) for the "Manage models" dialog.
    val providerGroups: List<ProviderEntry> = emptyList(),
    // "provider/model" -> visible (client-side, mirrors web localStorage).
    val modelVisibility: Map<String, Boolean> = emptyMap(),
    val variants: List<ModelVariant> = listOf(
        ModelVariant("default", "Default"),
        ModelVariant("high", "High"),
        ModelVariant("max", "Max"),
    ),
    val selectedAgent: String = "build",
    val selectedModel: String = "",
    val selectedVariant: String = "default",
    val contextUsage: ContextUsage? = null,
    val contextInfo: ContextInfo? = null,
    val files: List<FileEntry> = emptyList(),
    val commands: List<CommandEntry> = emptyList(),
    // "/mcp": configured MCP servers (name + status), alphabetical.
    val mcpServers: List<McpEntry> = emptyList(),
    val mcpDialogVisible: Boolean = false,
    val pendingQuestions: List<SessionQuestion> = emptyList(),
    // Tool-permission prompts awaiting an answer (web: Deny/Allow always/Allow once).
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val inputText: String = "",
    val attachments: List<Attachment> = emptyList(),
    val fileViewer: FileViewerState? = null,
    val isGenerating: Boolean = false,
    val isUploading: Boolean = false,
    val uploadDone: Int = 0,
    val uploadTotal: Int = 0,
    // Attachment URIs currently being uploaded, so each chip can show its own
    // spinner (uploads run in parallel).
    val uploadingUris: Set<String> = emptySet(),
    // "/compact" is running (server summarizes the session).
    val isCompacting: Boolean = false,
    // The model finished but the persisted assistant message has not landed in
    // `messages` yet. The live content stays on screen until it does, so the
    // answer never blanks out and reappears a network round-trip later.
    val pendingPersist: Boolean = false,
    // True while the loaded tail is still the full requested page, i.e. the
    // session very likely has older messages the user can pull in.
    val canLoadOlder: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Provider/usage error shown under the composer (e.g. "Free usage exceeded…").
    val statusError: String? = null,
    // Session todo list (GET /session/{id}/todo), shown above the composer.
    val todos: List<TodoItem> = emptyList(),
    // Review/Changes tab: git branch + changed files (GET /vcs, /vcs/diff).
    val vcsBranch: String? = null,
    val vcsDiff: List<VcsDiffFile> = emptyList(),
    // SSE connection health (drives a subtle "reconnecting" hint).
    val sseConnected: Boolean = false,
    // Session-guard proxy status and conflict UX.
    val guardEnabled: Boolean = false,
    val guardHealthy: Boolean = false,
    val guardAvgMs: Double? = null,
    val guardConflict: Boolean = false,
)

@Immutable
data class ContextInfo(
    val provider: String? = null,
    val model: String? = null,
    val contextLimit: Long? = null,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val totalCost: Double = 0.0,
    val sessionCreated: Long? = null,
    val lastActivity: Long? = null,
)

@dagger.hilt.android.lifecycle.HiltViewModel
class ChatViewModel @javax.inject.Inject constructor(
    private val repo: ChatRepository,
    // Resolved per call (the backend URL can change mid-session), so this is a
    // Provider rather than a captured instance.
    private val apiProvider: javax.inject.Provider<OpenCodeApi>,
    private val backendSession: BackendSession,
    private val providerDirectory: com.opencode.android.data.ProviderDirectory,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    private val api: OpenCodeApi get() = apiProvider.get()

    // Guard revisions for the active session(s); owned by the conversation
    // workflows through the transport.
    private val selectionGuard = com.opencode.android.util.SelectionGuard()
    private val autoAdoptedGuardRevisions = mutableSetOf<Long>()

    // Transport seam for the workflows SessionConversation owns (interrupt).
    private val sessionTransport = com.opencode.android.data.BackendSessionTransport(
        session = backendSession,
        guard = selectionGuard,
    )

    // Guarded prompt_async state machine (409 retry, readable error, close).
    private val promptSender = PromptSender(
        sendPromptAsync = { sessionId, body, guardRevision ->
            api.sendPromptAsync(sessionId, body, guardRevision)
        },
        guardRevision = { sessionId -> selectionGuard.guardRevision(sessionId) },
        clearGuardRevision = { sessionId -> selectionGuard.clearGuardRevision(sessionId) },
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Candidate 1/2 strangler: SessionConversation owns the stream + reconcile
    // workflow; ChatUiState stays the single UI truth and this ViewModel
    // projects the conversation's projection into it.
    private val conversationPort = object : ConversationPort {
        override fun send() = performSend()
        override fun retry() = performRetry()

        override fun notifyInterruptFailed(message: String?) {
            AppLog.e(APP_LOG_TAG, "abort failed: $message")
            UserMessages.post(R.string.could_not_interrupt, message ?: "")
        }

        override fun refreshMessages(sessionId: String) = this@ChatViewModel.refreshMessages(sessionId)

        override fun reconcile(includeMeta: Boolean) {
            _uiState.value.session?.id?.let { scheduleRefresh(it, includeMeta) }
        }

        override fun loadPendingQuestions() = this@ChatViewModel.loadPendingQuestions()
        override fun loadPermissions() = this@ChatViewModel.loadPermissions()

        override fun notifyPermission() {
            com.opencode.android.data.Notifier.permission(
                "Permission required",
                "The agent needs your approval to continue",
                _uiState.value.session?.id,
            )
        }

        override fun notifyDone() = notifyAgentDone()

        override fun notifyError(message: String?) {
            com.opencode.android.data.Notifier.error(
                "OpenCode error",
                message ?: "The session reported an error",
                _uiState.value.session?.id,
            )
        }

        override fun refreshSessionModel() {
            _uiState.value.session?.id?.let { refreshSessionModel(it) }
        }

        override fun loadVcsDiff() {
            _uiState.value.session?.directory?.let { loadVcsDiff(it) }
        }

        override fun setGenerating(value: Boolean) {
            _uiState.update { it.copy(isGenerating = value) }
        }

        override fun setPendingPersist(value: Boolean) {
            _uiState.update { it.copy(pendingPersist = value) }
        }

        override fun setCompacting(value: Boolean) {
            _uiState.update { it.copy(isCompacting = value) }
        }

        override fun setStatusError(value: String?) {
            _uiState.update { it.copy(statusError = value) }
        }

        override fun setSseConnected(value: Boolean) {
            _uiState.update { it.copy(sseConnected = value) }
        }

        override fun setSelectedModel(value: String) {
            _uiState.update { it.copy(selectedModel = value) }
        }
    }

    private val conversation = SessionConversation(
        source = SseClient,
        scope = viewModelScope,
        models = { _uiState.value.models },
        friendlyError = ::friendlyError,
        transport = sessionTransport,
        port = conversationPort,
    )

    // Streaming buffers live in their own flow (see LiveStreamState) so token
    // flushes do not recompose the whole screen. Owned by SessionStreamer.
    val liveState: StateFlow<LiveStreamState> = conversation.liveState

    // Server-global agent list, fetched once per process (see loadSession).
    private var cachedAgents: List<com.opencode.android.domain.Agent>? = null
    // Tracked so a fast session switch cancels the previous load instead of
    // letting two loads race and write interleaved state (last writer won,
    // which could show session A's messages under session B's title).
    private var sessionJob: Job? = null

    fun loadSession(sessionId: String) {
        // Opening a different session starts from the small default tail again;
        // re-loading the same one keeps whatever depth the user pulled in.
        if (_uiState.value.session?.id != sessionId) {
            messageLimit = messagePageSize
        }
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            // Paint the cached tail first so a cold start shows the conversation
            // immediately instead of a blank screen while the network answers.
            // The authoritative load below replaces it a moment later. On a
            // session switch this also clears the previous conversation instead
            // of leaving it on screen under the new title.
            val switching = _uiState.value.session?.id != sessionId
            if (switching) {
                // Never carry the previous session's live buffers (or the
                // pending-persist flag) into the new one.
                conversation.resetLive()
                _uiState.update { it.copy(pendingPersist = false) }
                // Keep the revision map bounded to the active session.
                selectionGuard.retainOnly(sessionId)
                autoAdoptedGuardRevisions.clear()
            }
            if (switching || _uiState.value.messages.isEmpty()) {
                _uiState.update { it.copy(
                    messages = repo.cachedMessages(sessionId)
                        ?: emptyList(),
                    canLoadOlder = false,
                ) }
            }
            // Start live streaming BEFORE the history load. Previously this ran
            // only after every initial request succeeded, so a slow or failing
            // load (large session, flaky link) meant NO live updates at all and
            // the user had to leave and re-enter to see remote (web/TUI) turns.
            startSse(sessionId)
            startPolling(sessionId)
            try {
                // The session header, its full payload (for `directory`), the
                // message page and the agent list are independent requests.
                // Awaiting them one after another cost three extra round-trips
                // on every session open; they run in parallel now.
                val sessionBase: Session
                val fullSession: Session?
                val messages: List<Message>
                val agents: List<Agent>
                coroutineScope {
                    val sessionD = async { repo.session(sessionId) }
                    // /api/session omits `directory`; the web endpoint includes it.
                    val fullD = async {
                        try {
                            repo.fullSession(sessionId)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val messagesD = async {
                        repo.loadMessages(sessionId, messageLimit)
                    }
                    val agentsD = async {
                        // Server-global and rarely changes: fetch once per process.
                        cachedAgents ?: try {
                            api.getAgents().data.also {
                                if (it.isNotEmpty()) cachedAgents = it
                            }
                        } catch (e: Exception) {
                            AppLog.e(APP_LOG_TAG, "getAgents failed: ${e.message}")
                            UserMessages.post(R.string.could_not_load_agents, "${e.message}")
                            emptyList()
                        }
                    }
                    sessionBase = sessionD.await()
                    fullSession = fullD.await()
                    messages = messagesD.await()
                    agents = agentsD.await()
                }
                val session = if (fullSession != null && !fullSession.directory.isNullOrBlank()) {
                    sessionBase.copy(
                        directory = fullSession.directory,
                        path = fullSession.path ?: sessionBase.path,
                    )
                } else {
                    // /api/session omits `directory`. Without it the Changes
                    // tab and VCS diff silently stop working, so fall back to
                    // the remembered project before giving up.
                    sessionBase.copy(
                        directory = sessionBase.directory
                            ?: LastSessionStore.directory(),
                    )
                }
                AppLog.d(APP_LOG_TAG) { "loadSession: session=${session.id} dir=${session.directory} agent=${session.agent} model=${session.model?.id}" }
                // Remember the project so the home screen reopens on it after a
                // process death instead of an arbitrary one. Only when known:
                // /api/session omits `directory`, so a failed full-session fetch
                // yields null here and must not erase the stored value.
                session.directory?.let {
                    LastSessionStore.saveDirectory(it)
                }
                AppLog.d(APP_LOG_TAG) { "loadSession: ${messages.size} messages loaded" }
                // Refresh the offline copy for the next cold start.
                if (messages.isNotEmpty()) {
                    repo.cacheMessages(sessionId, messages)
                }
                // (A "First message" dump used to log every part's text here.
                // That is the user's conversation content and must never reach
                // logcat — only counts are safe.)
                AppLog.d(APP_LOG_TAG) {
                    val first = messages.firstOrNull()
                    "loadSession: first role=${first?.role} parts=${first?.parts?.size ?: 0}"
                }
                // Authoritative model catalog from GET /provider (same as web).
                // No hardcoded models: the server list is the source of truth.
                // Only CONNECTED providers: a model whose provider has no
                // credentials can't be used, so it must not be offered (same
                // rule the web and Settings → Models apply).
                // Served from the shared cache — /provider is ~6 MB and is
                // fetched once per process, not on every session open.
                providerDirectory.load()
                val providerGroups = providerDirectory.connectedProviders
                val models = providerGroups.flatMap { provider ->
                    provider.models.values.map { m ->
                        Model(
                            id = m.id,
                            name = m.name,
                            providerId = m.providerId ?: provider.id,
                        )
                    }
                }.ifEmpty {
                    try {
                        api.getModels().data
                    } catch (e2: Exception) {
                        AppLog.e(APP_LOG_TAG, "getModels failed: ${e2.message}")
                        UserMessages.post(R.string.could_not_load_models, "${e2.message}")
                        emptyList()
                    }
                }
                session.sessionGuard?.revision?.let { revision ->
                    selectionGuard.recordGuardRevision(session.id, revision)
                }

                // Server session model is authoritative. A selection made in
                // web/TUI must win over stale Android localStorage.
                val remembered = ModelVisibilityStore.selection(session.id)
                val serverModel = resolveSessionModelRef(session.model, models)
                val rememberedRaw = remembered?.let { r ->
                    if (!r.providerId.isNullOrBlank() && !r.modelId.isNullOrBlank()) {
                        com.opencode.android.util.sessionModelRef(r.modelId, r.providerId)
                    } else {
                        null
                    }
                }
                // Repair stale local picks against the exact provider catalog.
                val rememberedModel = rememberedRaw?.let { raw ->
                    val (provider, modelId) = resolveModelRef(raw, models)
                    com.opencode.android.util.sessionModelRef(modelId, provider)
                }
                // Never blank a populated view of the SAME session with an empty
                // load (the OOM path returns empty; a transient [] also happens
                // on reconnect). Switching to a different session still replaces.
                // A newer loadSession() cancels this one; bail out before writing
                // state so the switch cannot be overwritten by the stale load.
                currentCoroutineContext().ensureActive()
                // Atomic read-modify-write: the previous `val snapshot =
                // _uiState.value` + `snapshot.copy(...)` could overwrite state
                // written while the network calls above were in flight.
_uiState.update { current ->
                     val safeMessages =
                         if (messages.isEmpty() &&
                             current.session?.id == session.id &&
                             current.messages.isNotEmpty()
                         ) {
                             current.messages
                         } else {
                             messages
                         }
                     current.copy(
                         session = session,
                         messages = safeMessages,
                         canLoadOlder = canLoadOlder(messageLimit, safeMessages.size),
                         agents = agents,
                         models = models,
                         providerGroups = providerGroups,
                         modelVisibility = ModelVisibilityStore.visibilityMap(),
                         selectedAgent = session.agent ?: remembered?.agent ?: "build",
                         selectedModel = serverModel ?: rememberedModel ?: "",
                         selectedVariant = session.model?.variant ?: remembered?.variant ?: "default",
                         isLoading = false,
                         isGenerating = session.status?.type == "busy" || session.status?.type == "retry"
                     )
                 }
                 // Health probe must not delay the first paint; it only
                 // decorates the guard banner and runs off the load path.
                 requestGuardStatus(sessionId)
                 // Mirror web session-open sequence: files for the session
                // directory, "/" commands and pending agent questions.
                // When the session payload has no directory, fall back to the
                // SERVE HOST's home (GET /path) — never a hardcoded dev path.
                val filesDir = session.directory ?: serverHome()
                if (filesDir != null) loadFiles(filesDir)
                loadCommands(session.directory)
                loadPendingQuestions()
                loadPermissions()
                loadTodos(sessionId)
                // Token/context figures for the top-bar usage chip; without
                // this the chip stays "…" until the first turn ends.
                loadContext()
                // No eager VCS load: GET /vcs/diff ships every changed file
                // WITH full patch (~1 MB) and times out on big sessions. The
                // data only feeds the Changes tab, so it loads on demand
                // when the tab opens (see ChatScreen) + on session.diff
                // events, never on plain chat open.
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Navigation/refresh races cancel in-flight loads constantly.
                // Rethrow: swallowing it sets a bogus "could not load" error
                // state for a session that loads fine on the next attempt.
                throw e
            } catch (e: Exception) {
                // Message only: a cancelled request surfaces as
                // "Socket closed" and a full stack is pure noise here.
                AppLog.e(APP_LOG_TAG, "loadSession ERROR: ${e.message}")
                UserMessages.post(R.string.could_not_load_session, "${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    error = e.message ?: "Session could not be loaded",
                ) }
            }
        }
    }

    // Single-flight, but SKIP rather than cancel. The Review tab triggers this
    // on every message-count change; cancelling the previous call meant that
    // during a busy turn the figures were restarted before they could ever
    // finish, so the panel stayed on the old numbers indefinitely. Skipping
    // lets the current load complete and the next trigger picks up the newer
    // state.
    private var contextJob: Job? = null
    // A trigger that arrived while a load was running. The old code dropped it,
    // so the final (post-turn) figures could stay stale indefinitely.
    private var contextReloadPending = false

    fun loadContext() {
        if (_uiState.value.session == null) return
        if (contextJob?.isActive == true) {
            contextReloadPending = true
            return
        }
        contextJob = viewModelScope.launch {
            while (isActive) {
                contextReloadPending = false
                loadContextOnce()
                if (!contextReloadPending) break
            }
        }
    }

    private suspend fun loadContextOnce() {
        val session = _uiState.value.session ?: return
        try {
                // The session object carries the authoritative aggregates:
                //   { cost, tokens: { input, output, reasoning,
                //                     cache: { read, write } } }
                // The legacy /api/session/{id}/context and /history endpoints
                // the Review tab used to read return {"data": []} on current
                // servers — which is why every figure showed 0 and never moved.
                // Verified against the live server; the web reads the session
                // object the same way.
                val full = api.getSessionFull(session.id)

                val messages = _uiState.value.messages
                val roleOf = { m: Message -> m.role ?: m.info?.role ?: m.type }
                // Web parity: the context window shows the usage of the LAST
                // assistant message, not the session's cumulative aggregate
                // (which after a long session can be many times the window and
                // made the app read "100%" where the web read "9%").
                val lastAssistant = messages
                    .filter { roleOf(it) == "assistant" }
                    .maxByOrNull {
                        it.time?.created ?: it.info?.time?.created ?: 0L
                    }
                val tokens = lastAssistant?.tokens
                    ?: lastAssistant?.info?.tokens
                    ?: full.tokens
                val input = tokens?.input ?: 0L
                val output = tokens?.output ?: 0L
                val reasoning = tokens?.reasoning ?: 0L
                val cacheRead = tokens?.cache?.read ?: 0L
                val cacheWrite = tokens?.cache?.write ?: 0L

                val modelId = full.model?.id ?: session.model?.id ?: ""
                // The provider catalog loads asynchronously; when it is not ready
                // yet (or was just invalidated on a reconnect) the limit lookup
                // returns null and the ring flashed to 0%. Reuse the last known
                // limit instead of dropping to unknown.
                val previous = _uiState.value.contextInfo
                val info = ContextInfo(
                    // Provider can be absent on the session payload; derive it
                    // from the provider-qualified model id when possible so the
                    // dialog does not show "—" for a known model.
                    provider = (full.model?.provider ?: session.model?.provider)
                        ?.takeIf { it.isNotBlank() }
                        ?: modelId.takeIf { it.contains('/') }?.substringBefore('/'),
                    model = modelId,
                    contextLimit = contextLimitFor(modelId) ?: previous?.contextLimit,
                    // Web "Total Tokens" = the last assistant message's
                    // input+output+reasoning+cache(read+write) — i.e. the
                    // tokens currently occupying the context window.
                    totalTokens = input + output + reasoning + cacheRead + cacheWrite,
                    inputTokens = input,
                    outputTokens = output,
                    reasoningTokens = reasoning,
                    cacheRead = cacheRead,
                    cacheWrite = cacheWrite,
                    userMessages = messages.count { roleOf(it) == "user" },
                    assistantMessages = messages.count { roleOf(it) == "assistant" },
                    totalCost = full.cost ?: 0.0,
                    sessionCreated = full.time?.created ?: session.time?.created,
                    lastActivity = full.time?.updated ?: session.time?.updated,
                )
                // The top-bar "N ctx" chip still reads the (often empty)
                // context list; keep it best-effort without failing the rest.
                val context = try {
                    api.getContext(session.id)
                } catch (_: Exception) {
                    null
                }
                // Guard against a session switch mid-fetch (see runRefresh).
                if (_uiState.value.session?.id != session.id) return
                // Never flash to 0%: a momentary empty read (messages not loaded
                // yet, or a provider hiccup) must not overwrite good figures for
                // the same session/model. Tokens only ever grow within a session.
                if (info.totalTokens == 0L &&
                    (previous?.totalTokens ?: 0L) > 0L &&
                    previous?.model == info.model
                ) {
                    return
                }
                _uiState.update { it.copy(
                    contextUsage = context ?: it.contextUsage,
                    contextInfo = info,
                ) }
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "loadContext failed: ${e.message}")
            UserMessages.post(R.string.could_not_load_context, "${e.message}")
        }
    }

    // Context window for a model. The provider catalog is authoritative
    // (GET /provider → models[..].limit.context), which is what the web UI
    // renders as "Context Limit". When the catalog has not loaded yet we return
    // null (unknown) instead of fabricating a limit.
    private fun contextLimitFor(modelId: String): Long? =
        _uiState.value.providerGroups
            .asSequence()
            .flatMap { it.models.values.asSequence() }
            .firstOrNull { it.id == modelId || it.model == modelId }
            ?.limit?.context
            ?.takeIf { it > 0 }

    fun loadFiles(path: String) {
        viewModelScope.launch {
            try {
                // /file takes a RELATIVE path + an absolute `directory` base.
                val files = api.getFiles(path = ".", directory = path)
                _uiState.update { it.copy(files = files) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "loadFiles failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_files, "${e.message}")
            }
        }
    }

    /**
     * The serve host's home directory (GET /path), used as the file-browser
     * seed when a session does not carry a `directory`. Returns null when the
     * server cannot be reached, so no hardcoded path is ever used.
     */
    private suspend fun serverHome(): String? = try {
        api.getPathInfo().home?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        AppLog.e(APP_LOG_TAG, "serverHome failed: ${e.message}")
        UserMessages.post(R.string.could_not_load_files, "${e.message}")
        null
    }

    // Web composer: "/" lists commands for the session directory.
    fun loadCommands(directory: String? = null) {
        viewModelScope.launch {
            try {
                val server = api.getCommands(directory)
                // Built-ins first, then the server commands; a server command
                // with the same name wins (dedupe by name, builtin kept only
                // when the server does not define it).
                val names = server.map { it.name }.toSet()
                val commands = BUILTIN_COMMANDS.filterNot { it.name in names } + server
                _uiState.update { it.copy(commands = commands) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "loadCommands failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_commands, "${e.message}")
                // Even if the server list fails, the client built-ins work.
                if (_uiState.value.commands.isEmpty()) {
                    _uiState.update { it.copy(commands = BUILTIN_COMMANDS) }
                }
            }
        }
    }

    // "/mcp" (web: "Toggle MCPs"): open the MCP dialog and refresh the list.
    fun openMcpDialog() {
        _uiState.update { it.copy(mcpDialogVisible = true) }
        loadMcp()
    }

    fun closeMcpDialog() {
        _uiState.update { it.copy(mcpDialogVisible = false) }
    }

    fun loadMcp() {
        val directory = _uiState.value.session?.directory
        viewModelScope.launch {
            try {
                val servers = api.getMcpServers(directory)
                    .map { (name, s) -> McpEntry(name, s.status ?: "disabled") }
                    .sortedBy { it.name }
                _uiState.update { it.copy(mcpServers = servers) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "loadMcp failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_mcp, "${e.message}")
            }
        }
    }

    // Only one MCP toggle reconciliation runs at a time; a new toggle cancels
    // the previous poll so two jobs cannot race and overwrite each other.
    private var mcpToggleJob: Job? = null

    fun toggleMcp(name: String, enable: Boolean) {
        val target = if (enable) "connected" else "disabled"
        // Optimistic: the server flips an MCP server's status asynchronously
        // (the MCP client may take >10s to connect/disconnect), so show the
        // intended state immediately.
        _uiState.update { current -> current.copy(
            mcpServers = current.mcpServers.map {
                if (it.name == name) it.copy(status = target) else it
            },
        ) }
        mcpToggleJob?.cancel()
        mcpToggleJob = viewModelScope.launch {
            try {
                if (enable) {
                    api.connectMcp(name).close()
                } else {
                    api.disconnectMcp(name).close()
                }
                // Poll until the server agrees (max ~30s). While unconfirmed,
                // keep the optimistic status for THIS server so a stale
                // intermediate re-fetch cannot snap the switch back; other
                // servers are reconciled normally.
                repeat(60) {
                    kotlinx.coroutines.delay(500)
                    val directory = _uiState.value.session?.directory
                    val map = try {
                        api.getMcpServers(directory)
                    } catch (_: Exception) {
                        null
                    } ?: return@repeat
                    val confirmed = map[name]?.status == target
                    val servers = map
                        .map { (n, s) ->
                            val status = if (!confirmed && n == name) {
                                target
                            } else {
                                s.status ?: "disabled"
                            }
                            McpEntry(n, status)
                        }
                        .sortedBy { it.name }
                    _uiState.update { it.copy(mcpServers = servers) }
                    if (confirmed) return@launch
                }
                loadMcp()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "toggleMcp failed: ${e.message}")
                UserMessages.post(R.string.mcp_toggle_failed, "${e.message}")
                loadMcp()
            }
        }
    }

    fun authenticateMcp(name: String) {
        viewModelScope.launch {
            try {
                api.authenticateMcp(name).close()
                loadMcp()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "authenticateMcp failed: ${e.message}")
                UserMessages.post(R.string.mcp_auth_failed, "${e.message}")
            }
        }
    }

    // "/compact": summarize the session to shrink the context window.
    fun compactSession() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isCompacting) return
        // Web parity: /compact summarizes with the model currently selected in
        // the composer, so prefer that model's provider from the catalog and
        // only fall back to the session's stored model.
        val modelRef = _uiState.value.selectedModel
            .ifBlank { com.opencode.android.util.sessionModelRef(session.model?.id, session.model?.provider ?: session.model?.providerID) }
        if (modelRef.isBlank()) {
            _uiState.update { it.copy(statusError = "No model selected") }
            return
        }
        // Catalog model ids are authoritative. NVIDIA exposes ids that already
        // include the provider prefix; other providers expose bare ids.
        val modelEntry = _uiState.value.models.firstOrNull {
            com.opencode.android.util.sessionModelRef(it.id, it.providerId) == modelRef
        } ?: _uiState.value.models.firstOrNull { it.id == modelRef }
        val provider = modelEntry?.providerId
            ?: session.model?.provider
            ?: session.model?.providerID
            ?: modelRef.substringBefore('/', "")
        val modelId = modelEntry?.id
            ?: session.model?.id?.takeIf { it.isNotBlank() }
            ?: modelRef.substringAfter('/', modelRef)
        _uiState.update { it.copy(isCompacting = true, statusError = null) }
        viewModelScope.launch {
            try {
                val response = api.summarizeSession(
                    session.id,
                    // URL-encoded, exactly as the web sends it.
                    session.directory?.let {
                        java.net.URLEncoder.encode(it, Charsets.UTF_8.name())
                    },
                    SummarizeRequest(providerID = provider, modelID = modelId),
                )
                if (!response.isSuccessful) {
                    val message = com.opencode.android.util.serverErrorMessage(
                        response.errorBody()?.string(),
                        "Compact failed (HTTP ${response.code()})",
                    )
                    AppLog.e(APP_LOG_TAG, "compactSession failed: HTTP ${response.code()} $message")
                    UserMessages.post(R.string.compact_failed, "HTTP ${response.code()} $message")
                    _uiState.update { it.copy(statusError = message) }
                    return@launch
                }
                response.body()?.close()
                // Compaction appends a summary part + resets the window. The web
                // refetches the session todo right after; we additionally reload
                // the messages and the context figures so the chip updates at
                // once instead of waiting for the next SSE tick.
                loadTodos(session.id)
                refreshMessages(session.id)
                // The context figures are derived from the message list, so the
                // reload must land BEFORE loadContext() — otherwise the chip
                // keeps the pre-compaction number.
                messagesJob?.join()
                loadContext()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "compactSession failed: ${e.message}")
                UserMessages.post(R.string.compact_failed, "${e.message}")
                _uiState.update { it.copy(
                    statusError = com.opencode.android.util.serverErrorMessage(e.message, "Compact failed"),
                ) }
            } finally {
                _uiState.update { it.copy(isCompacting = false) }
            }
        }
    }

    // Web agent questions: session-scoped pending requests the user must answer
    // (GET /api/session/{id}/question). Drives the question banner and the
    // inline question cards on question tool calls.
    // Tool-permission prompts for the current session. GET /permission returns
    // the server-global list, so filter to this session.
    fun loadPermissions() {
        val sessionId = _uiState.value.session?.id ?: return
        viewModelScope.launch {
            try {
                val all = api.getPermissions()
                if (_uiState.value.session?.id != sessionId) return@launch
                _uiState.update { it.copy(
                    pendingPermissions = all.filter {
                        it.sessionId == null || it.sessionId == sessionId
                    },
                ) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "loadPermissions failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_session, "${e.message}")
            }
        }
    }

    /** [reply] is "once" | "always" | "reject" (protocol values). */
    fun replyPermission(requestId: String, reply: String) {
        val sessionId = _uiState.value.session?.id ?: return
        // Optimistic: drop the prompt immediately so the buttons cannot be
        // double-tapped while the reply is in flight.
        _uiState.update { current -> current.copy(
            pendingPermissions = current.pendingPermissions
                .filterNot { it.id == requestId },
        ) }
        viewModelScope.launch {
            try {
                api.replyPermission(
                    requestId,
                    PermissionReplyRequest(reply = reply),
                ).close()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "replyPermission failed: ${e.message}")
                UserMessages.post(R.string.action_failed, "${e.message}")
            } finally {
                if (_uiState.value.session?.id == sessionId) loadPermissions()
            }
        }
    }

    fun loadPendingQuestions() {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                val questions = api.getSessionQuestions(session.id).data
                _uiState.update { it.copy(pendingQuestions = questions) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "loadPendingQuestions failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_session, "${e.message}")
            }
        }
    }

    fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        onDone: (Boolean) -> Unit,
    ) {
        val session = _uiState.value.session ?: return
        // Optimistic: drop the card immediately so the buttons cannot be
        // double-tapped and the UI never looks stuck while the reply is sent.
        _uiState.update { current -> current.copy(
            pendingQuestions = current.pendingQuestions
                .filterNot { it.id == requestId },
        ) }
        viewModelScope.launch {
            try {
                api.replySessionQuestion(
                    session.id,
                    requestId,
                    // answers is string[][] — one answer list per question.
                    QuestionReplyRequest(answers),
                ).close()
                loadPendingQuestions()
                refreshMessages(session.id)
                onDone(true)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "answerQuestion failed: ${e.message}")
                UserMessages.post(R.string.action_failed, "${e.message}")
                // A stale/expired request must not leave the user stuck: say so
                // and re-sync the real list from the server.
                _uiState.update { it.copy(
                    statusError = "Could not send the answer — the question may have expired",
                ) }
                loadPendingQuestions()
                onDone(false)
            }
        }
    }

    fun rejectQuestion(requestId: String, onDone: (Boolean) -> Unit) {
        val session = _uiState.value.session ?: return
        _uiState.update { current -> current.copy(
            pendingQuestions = current.pendingQuestions
                .filterNot { it.id == requestId },
        ) }
        viewModelScope.launch {
            try {
                api.rejectSessionQuestion(session.id, requestId).close()
                loadPendingQuestions()
                onDone(true)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "rejectQuestion failed: ${e.message}")
                UserMessages.post(R.string.action_failed, "${e.message}")
                _uiState.update { it.copy(
                    statusError = "Could not reject the question — it may have expired",
                ) }
                loadPendingQuestions()
                onDone(false)
            }
        }
    }

    // Lightweight fallback for missed/absent SSE: poll the (small) session
    // object and refresh messages only when its `time.updated` actually
    // changed. This is what makes remote (web/TUI) turns appear within a few
    // seconds even if the SSE stream dropped an event or never connected —
    // previously the only way to see another client's change was to leave and
    // re-enter the session.
    private var pollJob: Job? = null
    private var lastPollUpdated: Long? = null
    private var modelSelectionPending = false
    private var agentSelectionPending = false

    private suspend fun loadGuardStatus(sessionId: String) {
        // One health probe is enough; the authoritative selection already
        // comes from GET /session (sessionGuard) and is stored above.
        val health = runCatching { api.sessionGuardHealth() }.getOrNull()
        val body = health?.takeIf { it.isSuccessful }?.body()
        val enabled = body?.ok == true
        // Keep the banner visible when a guarded session goes unhealthy so
        // the user sees "offline" instead of it silently disappearing.
        val knownGuard = _uiState.value.session?.sessionGuard != null
        _uiState.update { current ->
            if (current.session?.id != sessionId) current
            else current.copy(
                guardEnabled = enabled || knownGuard,
                guardHealthy = enabled,
                guardAvgMs = body?.metrics?.avgMs,
            )
        }
        val guard = _uiState.value.session?.sessionGuard
        if (
            com.opencode.android.util.AutoAdoptPolicy.shouldAdopt(
                mismatch = guard?.mismatch == true,
                enabled = AppSettingsStore.state.value.autoAdoptGuard,
                revision = guard?.revision ?: -1L,
                alreadyAdoptedRevisions = autoAdoptedGuardRevisions,
            )
        ) {
            autoAdoptedGuardRevisions.add(guard?.revision ?: -1L)
            adoptServerSelection()
        }
    }

    private fun requestGuardStatus(sessionId: String) {
        viewModelScope.launch { loadGuardStatus(sessionId) }
    }

    private fun refreshSessionModel(sessionId: String) {
        viewModelScope.launch {
            try {
                val session = repo.session(sessionId)
                session.sessionGuard?.revision?.let { revision ->
                    selectionGuard.recordGuardRevision(sessionId, revision)
                }
                val selectedModel = resolveSessionModelRef(session.model, _uiState.value.models)
                _uiState.update { current ->
                    if (current.session?.id != sessionId) current
                    else current.copy(
                        session = current.session.copy(
                            model = session.model,
                            agent = session.agent ?: current.session.agent,
                        ),
                        selectedModel = selectedModel ?: current.selectedModel,
                        selectedAgent = session.agent ?: current.selectedAgent,
                        selectedVariant = session.model?.variant ?: current.selectedVariant,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Session metadata refresh is best-effort; next SSE/poll retries.
            }
        }
    }

    private fun startPolling(sessionId: String) {
        pollJob?.cancel()
        lastPollUpdated = null
        pollJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                if (_uiState.value.session?.id != sessionId) break
                try {
                    val updated = repo.session(sessionId).time?.updated
                    if (updated != null && updated != lastPollUpdated) {
                        lastPollUpdated = updated
                        refreshSessionModel(sessionId)
                        refreshMessages(sessionId)
                    }
                } catch (e: Exception) {
                    // Transient (offline): keep polling.
                }
            }
        }
    }

    private fun startSse(sessionId: String) {
        // Must follow the ACTIVE backend, not the compiled-in default: a user
        // pointed at another server previously got a live stream from the
        // hardcoded host, so streaming silently broke on custom backends.
        AppLog.d(APP_LOG_TAG) { "startSse: session=$sessionId url=${backendSession.currentBaseUrl()}/global/event" }
        conversation.dispatch(SessionCommand.Load(sessionId, backendSession.currentBaseUrl()))
    }

    /**
     * The agent finished a turn (or needs attention). Notifies/sounds according
     * to the Settings toggles that previously had no effect.
     */
    private fun notifyAgentDone() {
        val title = _uiState.value.session?.title ?: "OpenCode"
        com.opencode.android.data.Notifier.agent(title, "The agent finished", _uiState.value.session?.id)
    }

    // Both message schemas exist side by side (verified via API):
    // - GET /session/{id}/message → { info, parts[] } (classic sessions)
    // - GET /api/session/{id}/message → { type, agent, model, content[] } (newer runs)
    // Fetch both, merge by id and sort chronologically.
    // Recent-page size. The endpoint returns the newest N messages in FULL —
    // every part, every tool output. Measured on a heavy real session:
    //   limit=200 → 14.4 MB, limit=100 → 7.4 MB, limit=50 → 4.0 MB, limit=20 → 0.3 MB
    // Loading 200 (previously hard-coded) produced an OutOfMemoryError inside
    // okio.Buffer.readString once the heap was already ~110 MB used. Keep the
    // page small; history older than this simply is not loaded (the API has no
    // offset/before cursor, so pagination is not possible against this server).
    // Keep this SMALL: the endpoint returns full tool outputs/diffs and a large
    // page has caused OutOfMemoryError (see MESSAGE_LIMIT_MAX in Pagination.kt).
    private val messagePageSize = 30

    // The server exposes no cursor/offset pagination (verified: `offset` is
    // ignored, `before` errors), so older history can only be reached by asking
    // for a larger tail. Start small and grow on demand, so memory stays
    // bounded by default but the user can still walk back through a session.
    private var messageLimit = messagePageSize

    /**
     * Requests a larger tail of the conversation. Memory-safe: the fetch is
     * streamed and an OutOfMemoryError keeps the previously loaded messages
     * instead of killing the process.
     */
    fun loadOlderMessages() {
        val sessionId = _uiState.value.session?.id ?: return
        if (messageLimit >= MESSAGE_LIMIT_MAX) return
        messageLimit = (messageLimit + MESSAGE_PAGE_STEP).coerceAtMost(MESSAGE_LIMIT_MAX)
        refreshMessages(sessionId)
    }




    // --- Debounced refresh ----------------------------------------------------
    // The server replays bursts of events (tool storms). Refreshing the whole
    // message list + todos + VCS diff on every single event would fire hundreds
    // of requests and cause jank, so they are coalesced into one trailing pass.
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var refreshWantsMeta = false
    // Debounce-with-ceiling policy (pure + unit-tested; see RefreshCoalescer).
    private val refreshCoalescer =
        com.opencode.android.util.RefreshCoalescer(maxWaitMs = REFRESH_MAX_WAIT_MS)

    private fun scheduleRefresh(sessionId: String, includeMeta: Boolean = false) {
        refreshWantsMeta = refreshWantsMeta || includeMeta
        val jobActive = refreshJob?.isActive == true
        when (refreshCoalescer.onRequest(System.currentTimeMillis(), jobActive)) {
            com.opencode.android.util.RefreshCoalescer.Decision.Start -> Unit
            com.opencode.android.util.RefreshCoalescer.Decision.Restart ->
                refreshJob?.cancel()
            com.opencode.android.util.RefreshCoalescer.Decision.KeepRunning -> return
        }
        refreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(REFRESH_DEBOUNCE_MS)
            // The session may have changed while this was debounced.
            if (_uiState.value.session?.id != sessionId) return@launch
            val meta = refreshWantsMeta
            refreshWantsMeta = false
            refreshMessages(sessionId)
            if (meta) {
                // Context/todo figures come from the messages, so wait for the
                // refresh above to land before recomputing them.
                messagesJob?.join()
                loadTodos(sessionId)
                // Keep the question dock in sync with the server (a question can
                // be raised/answered between events).
                loadPendingQuestions()
                // Keep the Review tab's token/cost figures current even while
                // that tab is not composed, so opening it is never stale.
                loadContext()
                _uiState.value.session?.directory?.let { loadVcsDiff(it) }
            }
        }
    }

    /** First text part of a message, used to match a local echo to the server's. */
    private fun Message.firstText(): String =
        parts.firstOrNull { it.type == "text" }?.text?.trim().orEmpty()

    // Minimum gap between offline-cache writes while a turn is streaming.
    private val cacheWriteThrottle =
        com.opencode.android.util.WriteThrottle(minIntervalMs = CACHE_WRITE_MIN_INTERVAL_MS)

    private suspend fun cacheTail(
        sessionId: String,
        messages: List<com.opencode.android.domain.Message>,
        force: Boolean,
    ) {
        if (!cacheWriteThrottle.allow(System.currentTimeMillis(), force)) return
        repo.cacheMessages(sessionId, messages)
    }

    private companion object {
        // Short trailing debounce: the server replays event bursts, so a small
        // window still coalesces them, but the persisted message lands soon
        // after the step ends.
        const val REFRESH_DEBOUNCE_MS = 80L
        // Id prefix for the optimistic user echo; the refresh keeps such rows
        // until the server echoes the same text (see runRefresh).
        // Offline-cache write throttle (see cacheTail).
        const val CACHE_WRITE_MIN_INTERVAL_MS = 2_000L
        // Upper bound on coalescing so a non-stop event stream cannot postpone
        // the refresh indefinitely.
        const val REFRESH_MAX_WAIT_MS = 1_200L
        // Safety-net poll cadence for remote (web/TUI) changes when SSE misses.
        const val POLL_INTERVAL_MS = 5_000L
    }

    // Review/Changes tab: branch + git diff for the session directory.
    // Single-flight + failure cooldown: overlapping calls each hung until the
    // 15s socket timeout, saturating the connection and making streaming feel
    // sluggish. A non-git directory now costs one short probe per 30s.
    private var vcsJob: kotlinx.coroutines.Job? = null
    private var vcsFailedAt: Long = 0L

    fun loadVcsDiff(directory: String) {
        if (System.currentTimeMillis() - vcsFailedAt < 30_000L) return
        vcsJob?.cancel()
        vcsJob = viewModelScope.launch {
            try {
                val info = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    repo.vcs(directory)
                }
                // /vcs/diff returns every changed file WITH its full patch —
                // measured 1.1 MB / 50 files on a real session. The old 8 s cap
                // expired on that, and a null diff silently kept the previous
                // numbers on screen, so the Review tab never appeared to update.
                val diff = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    repo.vcsDiff(directory)
                }
                if (diff == null) {
                    // Background refresh only: keep previous stats, retry on
                    // the next refresh. No user toast — the timeout fires ~30s
                    // after opening a chat and read as a mystery "Action
                    // failed" with no context.
                    AppLog.e(APP_LOG_TAG, "getVcsDiff timed out after 30s — keeping previous stats")
                }
                if (info == null && diff == null) {
                    vcsFailedAt = System.currentTimeMillis()
                    return@launch
                }
                _uiState.update { current -> current.copy(
                    vcsBranch = info?.branch ?: current.vcsBranch,
                    vcsDiff = diff ?: current.vcsDiff,
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Single-flight cancels the previous call on purpose. Rethrow so
                // structured concurrency works, and do not poison the failure
                // cooldown with a normal cancellation.
                throw e
            } catch (e: Exception) {
                vcsFailedAt = System.currentTimeMillis()
                AppLog.e(APP_LOG_TAG, "getVcsDiff failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_diff, "${e.message}")
            }
        }
    }

    // Web shows a collapsible todo panel above the composer.
    fun loadTodos(sessionId: String) {
        viewModelScope.launch {
            try {
                val todos = api.getTodos(sessionId)
                _uiState.update { it.copy(todos = todos) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "getTodos failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_todos, "${e.message}")
            }
        }
    }

    // Single-flight: several call sites (send, scheduleRefresh, revert, fork,
    // answer) can request a refresh while one is already in flight. Each load
    // fetches and parses up to 200 messages with all their parts, so letting
    // them overlap stacked megabytes of transient allocations and network
    // connections — a memory-pressure source on a phone.
    private var messagesJob: Job? = null
    private var pendingRefresh: String? = null

    /**
     * Coalesces refreshes as "one in flight, one queued" instead of
     * cancel-and-restart. Cancelling mid-fetch aborted the HTTP call (the
     * "Socket closed" noise in logcat) and threw away a large response only to
     * start an identical one; under an event burst that could starve the load
     * entirely. The queued run always uses the newest session id.
     */
    private fun refreshMessages(sessionId: String) {
        if (messagesJob?.isActive == true) {
            pendingRefresh = sessionId
            return
        }
        messagesJob = viewModelScope.launch {
            var next: String? = sessionId
            while (next != null) {
                pendingRefresh = null
                runRefresh(next)
                next = pendingRefresh
            }
        }
    }

    private suspend fun runRefresh(sessionId: String) {
        try {
                // The user may have switched sessions while this fetch was in
                // flight; writing its result would show the old conversation
                // under the new session.
                if (_uiState.value.session?.id != sessionId) return
                // Fetch + merge + sort off the main thread: a 200-message page
                // with parts is real work, and doing it on Main stalled the
                // streaming frames while a refresh was in flight.
                val messages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repo.loadMessages(sessionId, messageLimit)
                }
                // Never wipe a populated conversation with a transiently-empty
                // response — on reconnect the server can briefly return [] while
                // replaying a session's events, which previously blanked the UI.
                if (messages.isEmpty() && _uiState.value.messages.isNotEmpty()) return
                // Keep an optimistic user echo alive until the server echoes it
                // back. The refresh right after a send often lands before the
                // message is persisted, which made the just-sent bubble vanish
                // and reappear a moment later — a visible flicker at exactly the
                // moment the user is watching.
                val currentMessages = _uiState.value.messages
                val toRow = { m: Message ->
                    com.opencode.android.util.MessageEcho.Row(
                        id = m.id,
                        role = m.role ?: m.info?.role,
                        text = m.firstText(),
                    )
                }
                val pendingTexts = com.opencode.android.util.MessageEcho
                    .pendingEchoes(currentMessages.map(toRow), messages.map(toRow))
                    .map { it.text }
                    .toSet()
                val pendingLocal = currentMessages.filter {
                    it.id?.startsWith(com.opencode.android.util.MessageEcho.LOCAL_PREFIX) == true &&
                        it.firstText() in pendingTexts
                }
                val effective = if (pendingLocal.isEmpty()) messages else messages + pendingLocal
                // Cheap short-circuit first: a different length is already a
                // change, and it is by far the common case (a turn was added).
                // Only an equal-length list needs the expensive deep equality,
                // which is kept off the main thread.
                val current = _uiState.value.messages
                val changed = if (effective.size != current.size) {
                    true
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        effective != current
                    }
                }
                // Skip the state write when nothing changed — avoids a full
                // message-list recomposition on every polling tick.
                val hasMore = canLoadOlder(messageLimit, effective.size)
                if (changed || _uiState.value.canLoadOlder != hasMore) {
                    _uiState.update { current -> current.copy(
                        messages = if (changed) effective else current.messages,
                        canLoadOlder = hasMore,
                    ) }
                }
                if (changed) {
                    // Keep the on-disk tail fresh for the next cold start.
                    // Throttled while streaming: a busy turn fires this refresh
                    // dozens of times, and each write re-encodes up to 4 MB to
                    // disk. Always writes once the turn is no longer running so
                    // the final state is never lost. Caches the server list, not
                    // the local echo (the echo must never outlive the process).
                    cacheTail(sessionId, messages, force = !_uiState.value.isGenerating)
                    // The persisted turn is now in the list: drop the live
                    // buffers that were kept around to avoid the finalize flash.
                    conversation.completePersist()
                }
                loadGuardStatus(sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // refresh race: no error state, no toast (see above)
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "refreshMessages ERROR: ${e.message}")
            UserMessages.post(R.string.could_not_load_session, "${e.message}")
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    // Web keeps agent/model/variant purely client-side (localStorage
    // "workspace:model-selection") and mirrors selection to the server so
    // web/TUI clients observe the same provider + model for this session.
    private fun persistSelection() {
        val s = _uiState.value
        val session = s.session ?: return
        val hasModel = s.selectedModel.isNotBlank()
        val (providerId, modelId) = if (hasModel) parseModelRef(s.selectedModel) else (null to null)
        ModelVisibilityStore.saveSelection(
            sessionId = session.id,
            agent = s.selectedAgent,
            providerId = if (hasModel) providerId else null,
            modelId = if (hasModel) modelId else null,
            variant = s.selectedVariant,
        )
    }

    private fun syncSessionModel(sessionId: String, modelRef: String, variant: String) {
        val generation = selectionGuard.beginModelSync()
        viewModelScope.launch {
            try {
                val (providerId, modelId) = resolveModelRef(modelRef, _uiState.value.models)
                if (providerId.isBlank() || modelId.isBlank()) return@launch
                val response = api.setModel(
                    sessionId,
                    ModelRefRequest(
                        model = ModelRef(
                            id = modelId,
                            providerId = providerId,
                            variant = variant.takeIf { it.isNotBlank() && it != "default" },
                        ),
                    ),
                )
                if (!response.isSuccessful) {
                    val detail = com.opencode.android.util.serverErrorMessage(
                        response.errorBody()?.string(),
                        "set model HTTP ${response.code()}",
                    )
                    throw java.io.IOException(detail)
                }
                response.headers()["X-Session-Guard-Revision"]?.toLongOrNull()?.let { revision ->
                    selectionGuard.recordGuardRevision(sessionId, revision)
                }
                response.body()?.close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "set session model failed: ${e.message}")
                UserMessages.post(
                    R.string.could_not_send,
                    "Model selection could not be saved: ${e.message}",
                )
            } finally {
                if (selectionGuard.isLatestModel(generation)) modelSelectionPending = false
            }
        }
    }

    private fun syncSessionAgent(sessionId: String, agent: String) {
        val generation = selectionGuard.beginAgentSync()
        viewModelScope.launch {
            try {
                if (agent.isNotBlank()) {
                    val response = api.setAgent(sessionId, mapOf("agent" to agent))
                    if (!response.isSuccessful) {
                        val detail = com.opencode.android.util.serverErrorMessage(
                            response.errorBody()?.string(),
                            "set agent HTTP ${response.code()}",
                        )
                        throw java.io.IOException(detail)
                    }
                    response.headers()["X-Session-Guard-Revision"]?.toLongOrNull()?.let { revision ->
                        selectionGuard.recordGuardRevision(sessionId, revision)
                    }
                    response.body()?.close()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "set session agent failed: ${e.message}")
                UserMessages.post(
                    R.string.could_not_send,
                    "Agent selection could not be saved: ${e.message}",
                )
            } finally {
                if (selectionGuard.isLatestAgent(generation)) agentSelectionPending = false
            }
        }
    }

    fun selectAgent(agent: String) {
        val session = _uiState.value.session
        _uiState.update { it.copy(selectedAgent = agent) }
        persistSelection()
        if (session != null && agent.isNotBlank()) {
            agentSelectionPending = true
            syncSessionAgent(session.id, agent)
        }
    }

    fun selectModel(model: String) {
        // Older picks stored the bare id; qualify so the provider survives.
        val qualified = qualifyModelRef(model)
        val session = _uiState.value.session
        val variant = _uiState.value.selectedVariant
        _uiState.update { it.copy(selectedModel = qualified) }
        persistSelection()
        if (session != null && qualified.isNotBlank()) {
            modelSelectionPending = true
            syncSessionModel(session.id, qualified, variant)
        }
    }

    // --- Manage models (client-side visibility, mirrors web localStorage) ---

    fun setModelVisibility(providerId: String, modelId: String, show: Boolean) {
        ModelVisibilityStore.setVisibility(providerId, modelId, show)
        _uiState.update { it.copy(
            modelVisibility = ModelVisibilityStore.visibilityMap(),
        ) }
    }

    fun setProviderVisibility(providerId: String, modelIds: List<String>, show: Boolean) {
        ModelVisibilityStore.setProviderVisibility(providerId, modelIds, show)
        _uiState.update { it.copy(
            modelVisibility = ModelVisibilityStore.visibilityMap(),
        ) }
    }

    fun selectVariant(variant: String) {
        val session = _uiState.value.session
        val model = _uiState.value.selectedModel
        _uiState.update { it.copy(selectedVariant = variant) }
        persistSelection()
        if (session != null && model.isNotBlank()) {
            modelSelectionPending = true
            syncSessionModel(session.id, model, variant)
        }
    }

    // Maps provider/usage errors to the same short labels the web shows.
    // e.g. "Provider request failed with HTTP 429: {…FreeUsageLimitError…}"
    //   -> "Free usage exceeded, subscribe to Go"
    private fun friendlyErrorText(raw: String): String {
        val nested = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(raw)
            .map { it.groupValues[1] }
            .lastOrNull()
        return when {
            raw.contains("FreeUsageLimitError") || raw.contains("Free usage exceeded") ->
                "Free usage exceeded, subscribe to Go"
            raw.contains("429") ->
                nested ?: "Rate limit exceeded. Please try again later."
            else -> nested ?: raw
        }
    }

    private fun friendlyError(error: kotlinx.serialization.json.JsonElement?): String? {
        if (error == null || error is kotlinx.serialization.json.JsonNull) return null
        // Plain-string payload (older endpoints).
        (error as? kotlinx.serialization.json.JsonPrimitive)?.let {
            val raw = it.content
            return if (raw.isBlank()) "The session reported an error" else friendlyErrorText(raw)
        }
        val obj = error as? kotlinx.serialization.json.JsonObject ?: return "The session reported an error"
        fun prim(key: String): String? =
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> !p.isString || p.content.isNotBlank() }?.content
        // session.error shape: { name, data: { message } }. step/tool.failed:
        // { type, message }. Some routes nest under error: { error: { message } }.
        val data = obj["data"] as? kotlinx.serialization.json.JsonObject
        val nestedErr = obj["error"] as? kotlinx.serialization.json.JsonObject
        val raw = (data?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: prim("message")
            ?: (nestedErr?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: prim("type")
            ?: prim("name")
            ?: "The session reported an error"
        return friendlyErrorText(raw)
    }

    // Parse "provider/model" or "model" into (providerId, modelId).
    // The catalog model id is authoritative: some providers (nvidia) expose
    // ids that already include the provider prefix, while others expose bare ids.
    private fun parseModelRef(model: String): Pair<String, String> =
        ModelSelection.parse(model, _uiState.value.models)

    /** Qualifies a picker value to "provider/model" using the catalog. */
    private fun qualifyModelRef(model: String): String =
        ModelSelection.qualify(model, _uiState.value.models)

    // Client IDs in web format: {prefix}_{12 hex of time*4096+counter}{14 random base62}.
    private var idCounter = 0
    private var lastIdMs = 0L

    private fun generateOpenCodeId(prefix: String): String {
        val now = System.currentTimeMillis()
        val count: Int
        synchronized(this) {
            if (now != lastIdMs) {
                lastIdMs = now
                idCounter = 0
            }
            idCounter += 1
            count = idCounter
        }
        var value = java.math.BigInteger.valueOf(now).shiftLeft(12)
            .add(java.math.BigInteger.valueOf(count.toLong()))
        val hex = StringBuilder()
        for (o in 0 until 6) {
            val byte = value.shiftRight(40 - 8 * o).and(java.math.BigInteger.valueOf(255)).toInt()
            hex.append(byte.toString(16).padStart(2, '0'))
        }
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = java.security.SecureRandom()
        val tail = StringBuilder()
        repeat(14) { tail.append(alphabet[random.nextInt(62)]) }
        return "${prefix}_${hex}${tail}"
    }

    fun addAttachments(uris: List<Pair<String, String>>) {
        if (uris.isEmpty()) return
        val current = _uiState.value.attachments.toMutableList()
        for ((uri, name) in uris) {
            if (current.none { it.uri == uri }) {
                val parsed = android.net.Uri.parse(uri)
                val mime = runCatching { appContext.contentResolver.getType(parsed) }.getOrNull()
                val size = runCatching {
                    appContext.contentResolver.query(
                        parsed,
                        arrayOf(android.provider.OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else null
                    }
                }.getOrNull()
                current.add(Attachment(uri = uri, name = name, mime = mime, size = size))
            }
        }
        _uiState.update { it.copy(attachments = current) }
    }

    fun removeAttachment(uri: String) {
        _uiState.update { current -> current.copy(
            attachments = current.attachments.filter { it.uri != uri },
        ) }
    }

    fun clearAttachments() {
        _uiState.update { it.copy(attachments = emptyList()) }
    }

    private fun attachmentMime(attachment: Attachment): String =
        attachment.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

    /** Streams from the content URI instead of buffering the whole file. */
    private fun attachmentRequestBody(attachment: Attachment): RequestBody {
        val resolver = appContext.contentResolver
        val uri = android.net.Uri.parse(attachment.uri)
        val mime = attachmentMime(attachment)
        val declaredSize = attachment.size ?: -1L
        return object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()

            override fun contentLength() = declaredSize

            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri)
                    ?: throw java.io.IOException("Attachment could not be read: ${attachment.name}")
                input.source().use { source -> sink.writeAll(source) }
            }
        }
    }

    private suspend fun uploadAttachment(attachment: Attachment): UploadResponse {
        val mime = attachmentMime(attachment)
        val size = attachment.size
        if (size != null && size > MAX_UPLOAD_BYTES) {
            throw java.io.IOException(
                "Attachment too large: ${attachment.name} (${size / (1024 * 1024)} MB, max 50 MB)",
            )
        }
        val response = api.uploadAttachment(
            attachment.name,
            mime,
            attachmentRequestBody(attachment),
        )
        if (!response.isSuccessful) {
            val detail = com.opencode.android.util.serverErrorMessage(
                response.errorBody()?.string(),
                "upload HTTP ${response.code()}",
            )
            throw java.io.IOException(detail)
        }
        val uploaded = response.body()?.takeIf { it.path.isNotBlank() }
            ?: throw java.io.IOException("Upload returned no path: ${attachment.name}")
        _uiState.update { it.copy(uploadDone = it.uploadDone + 1) }
        return uploaded
    }

    private suspend fun uploadAttachments(attachments: List<Attachment>): List<UploadResponse> =
        coroutineScope {
            // Parallel uploads; awaitAll preserves input order for the prompt.
            attachments.map { attachment ->
                async {
                    _uiState.update {
                        it.copy(uploadingUris = it.uploadingUris + attachment.uri)
                    }
                    try {
                        uploadAttachment(attachment)
                    } finally {
                        _uiState.update {
                            it.copy(uploadingUris = it.uploadingUris - attachment.uri)
                        }
                    }
                }
            }.awaitAll()
        }

    fun sendMessage() = conversation.dispatch(SessionCommand.Send)

    private fun performSend() {
        val session = _uiState.value.session ?: run {
            AppLog.e(APP_LOG_TAG, "sendMessage: no session")
            UserMessages.post(R.string.could_not_send, "no session")
            return
        }
        val rawText = _uiState.value.inputText.trim()
        val attachments = _uiState.value.attachments
        AppLog.d(APP_LOG_TAG) {
            // Length only: the prompt itself is user content.
            "sendMessage: chars=${rawText.length} attachments=${attachments.size} session=${session.id} generating=${_uiState.value.isGenerating}"
        }
        if (rawText.isEmpty() && attachments.isEmpty()) return

        // Web shape: text part + one "file" part per attachment, each with a
        // base64 data URL. The old text-only "[Attached: name]" marker never
        // reached the agent, so files were silently ignored.
        val text = rawText
        val displayText = buildDisplayText(rawText, attachments.map { it.name })
        if (text.isEmpty() && attachments.isEmpty()) return

        // Optimistic echo: show the user's message and clear the composer
        // immediately. prompt_async only returns 204 and the persisted message
        // is fetched afterwards, so without this the sent message appeared a
        // network round-trip later — the main source of the "not direct" feel.
        // The next refresh replaces this local row with the server one.
        val now = System.currentTimeMillis()
        val optimistic = buildOptimisticEcho(
            sessionId = session.id,
            messageId = com.opencode.android.util.MessageEcho.LOCAL_PREFIX + now,
            displayText = displayText,
            createdAt = now,
        )
        // Capture BEFORE the optimistic echo flips isGenerating to true.
        // Reading the flag after the update made it always true, so every send
        // fired POST /interrupt just before the prompt and could race with (and
        // abort) the request that was being started.
        val wasGenerating = _uiState.value.isGenerating
        _uiState.update { current -> current.copy(
            statusError = null,
            inputText = "",
            attachments = emptyList(),
            messages = current.messages + optimistic,
            isGenerating = true,
            pendingPersist = false,
        ) }

        viewModelScope.launch {
            // Only interrupt a turn that was actually still running.
            if (wasGenerating) {
                try {
                    api.interrupt(session.id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not fatal — the new prompt is sent either way — but a
                    // silently swallowed failure here made "the old turn kept
                    // running" impossible to diagnose.
                    AppLog.e(APP_LOG_TAG, "interrupt failed: ${e.message}")
                    UserMessages.post(R.string.could_not_interrupt, "${e.message}")
                }
            }
            // Reset all live-stream buffers so the previous turn's reasoning,
            // response text and tool rows cannot bleed into the new turn.
            conversation.resetLive()
            _uiState.update { it.copy(isGenerating = true, error = null) }
            try {
                // Primary flow mirrors web: POST /session/{id}/prompt_async
                // with client-generated msg_/prt_ IDs, agent + model per call.
                // Re-read server selection before sending so changes from
                // web/TUI cannot be overwritten by stale Android state.
                val serverSession = runCatching { repo.session(session.id) }.getOrNull()
                val serverModelRef = serverSession?.let {
                    resolveSessionModelRef(it.model, _uiState.value.models)
                }
                val effective = effectiveSelection(
                    server = ServerSelection(
                        model = serverModelRef,
                        agent = serverSession?.agent,
                        variant = serverSession?.model?.variant,
                    ),
                    ui = UiSelection(
                        model = _uiState.value.selectedModel,
                        agent = _uiState.value.selectedAgent,
                        variant = _uiState.value.selectedVariant,
                        modelPending = modelSelectionPending,
                        agentPending = agentSelectionPending,
                    ),
                )
                _uiState.update { current ->
                    current.copy(
                        selectedModel = effective.model,
                        selectedAgent = effective.agent,
                        selectedVariant = effective.variant,
                    )
                }
                val effectiveModelRef = effective.model
                val effectiveAgent = effective.agent
                val effectiveVariant = effective.variant
                val (providerId, modelId) = parseModelRef(effectiveModelRef)
                if (!providerDirectory.isAvailable(providerId, modelId)) {
                    val errorMsg = "Model $providerId/$modelId not available on server"
                    AppLog.e(APP_LOG_TAG, errorMsg)
                    UserMessages.post(R.string.could_not_send, errorMsg)
                    _uiState.update { it.copy(isGenerating = false, error = errorMsg) }
                    return@launch
                }
                if (attachments.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isUploading = true,
                            uploadDone = 0,
                            uploadTotal = attachments.size,
                        )
                    }
                }
                val uploaded = try {
                    uploadAttachments(attachments)
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "attachment upload failed: ${e.message}")
                    _uiState.update { current ->
                        current.copy(
                            messages = current.messages.filterNot { it.id == optimistic.id },
                            inputText = rawText,
                            attachments = attachments,
                            isGenerating = false,
                            isUploading = false,
                            uploadDone = 0,
                            uploadTotal = 0,
                            uploadingUris = emptySet(),
                            statusError = "Attachment upload failed: ${e.message}",
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(isUploading = false, uploadDone = 0, uploadTotal = 0, uploadingUris = emptySet()) }
                val finalText = buildPromptText(text, uploaded.map { it.path })
                if (finalText != displayText) {
                    _uiState.update { current ->
                        current.copy(
                            messages = current.messages.map { message ->
                                if (message.id == optimistic.id) {
                                    message.copy(parts = listOf(Part(type = "text", text = finalText)))
                                } else {
                                    message
                                }
                            },
                        )
                    }
                }

                val asyncBody = buildPromptAsyncRequest(
                    messageId = generateOpenCodeId("msg"),
                    partId = generateOpenCodeId("prt"),
                    agent = effectiveAgent,
                    providerId = providerId,
                    modelId = modelId,
                    variant = effectiveVariant,
                    finalText = finalText,
                )
                AppLog.d(APP_LOG_TAG) { "sendMessage: prompt_async" }
                when (val result = promptSender.send(session.id, asyncBody)) {
                    PromptSendResult.Ok -> Unit
                    PromptSendResult.Conflict -> {
                        _uiState.update { it.copy(
                            isGenerating = false,
                            guardConflict = true,
                            statusError = "Selection changed in another client. Review Guard status, then retry.",
                        ) }
                        return@launch
                    }
                    is PromptSendResult.Failed -> {
                        // The legacy endpoint has no file parts. Falling back
                        // with attachments present would silently drop the files.
                        if (attachments.isNotEmpty()) {
                            AppLog.e(APP_LOG_TAG, "sendMessage: attachment prompt failed: ${result.message}")
                            throw java.io.IOException(result.message)
                        }
                        // Fallback to the legacy prompt endpoint.
                        AppLog.e(APP_LOG_TAG, "sendMessage: prompt_async failed, fallback: ${result.message}")
                        UserMessages.post(R.string.could_not_send, "prompt_async failed, fallback: ${result.message}")
                        api.sendPrompt(
                            session.id,
                            PromptRequest(
                                prompt = PromptInput(text = finalText),
                                 agent = effectiveAgent,
                                model = modelId.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
                _uiState.update { it.copy(guardConflict = false) }
                AppLog.d(APP_LOG_TAG) { "sendMessage: API ok" }
                refreshMessages(session.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "sendMessage ERROR: ${e.message}", e)
                UserMessages.post(R.string.action_failed, "${e.message}")
                val sendError = e.message ?: "Message could not be sent"
                _uiState.update { current -> current.copy(
                    isGenerating = false,
                    isUploading = false,
                    uploadDone = 0,
                    uploadTotal = 0,
                    uploadingUris = emptySet(),
                    statusError = sendError,
                    // Drop the optimistic echo — it will never be persisted, so
                    // leaving it made the conversation show a message that the
                    // server never received — and put the text back in the
                    // composer so a failed send is retryable without retyping.
                    messages = current.messages.filterNot { it.id == optimistic.id },
                    inputText = if (current.inputText.isBlank()) {
                        rawText
                    } else {
                        current.inputText
                    },
                    error = sendError,
                ) }
            }
        }
    }

    fun adoptServerSelection() {
        val sessionId = _uiState.value.session?.id ?: return
        viewModelScope.launch {
            try {
                val response = api.adoptServerSelection(
                    sessionId,
                    selectionGuard.guardRevision(sessionId),
                )
                if (!response.isSuccessful) {
                    throw java.io.IOException("adopt HTTP ${response.code()}")
                }
                val guard = response.body()
                    ?: throw java.io.IOException("adopt returned no selection")
                guard.revision.let { selectionGuard.recordGuardRevision(sessionId, it) }
                _uiState.update { current ->
                    if (current.session?.id != sessionId) current
                    else current.copy(
                        session = current.session.copy(sessionGuard = guard),
                        guardConflict = false,
                        statusError = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "adopt server selection failed: ${e.message}")
                _uiState.update { it.copy(statusError = "Guard übernehmen fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun pushSelectionToServer() {
        val sessionId = _uiState.value.session?.id ?: return
        viewModelScope.launch {
            try {
                val response = api.pushGuardSelection(
                    sessionId,
                    selectionGuard.guardRevision(sessionId),
                )
                if (!response.isSuccessful) {
                    throw java.io.IOException("push HTTP ${response.code()}")
                }
                val guard = response.body()
                    ?: throw java.io.IOException("push returned no selection")
                guard.revision.let { selectionGuard.recordGuardRevision(sessionId, it) }
                _uiState.update { current ->
                    if (current.session?.id != sessionId) current
                    else current.copy(
                        session = current.session.copy(sessionGuard = guard),
                        guardConflict = false,
                        statusError = null,
                    )
                }
                refreshSelectionFromServer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "push guard selection failed: ${e.message}")
                _uiState.update { it.copy(statusError = "Guard pushen fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun removeGuardSelection() {
        val sessionId = _uiState.value.session?.id ?: return
        viewModelScope.launch {
            try {
                val response = api.removeGuardSelection(
                    sessionId,
                    selectionGuard.guardRevision(sessionId),
                )
                if (!response.isSuccessful) {
                    throw java.io.IOException("remove guard HTTP ${response.code()}")
                }
                selectionGuard.clearGuardRevision(sessionId)
                _uiState.update { current ->
                    if (current.session?.id != sessionId) current
                    else current.copy(
                        session = current.session.copy(sessionGuard = null),
                        guardConflict = false,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "remove guard selection failed: ${e.message}")
                _uiState.update { it.copy(statusError = "Guard entfernen fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun refreshSelectionFromServer() {
        val sessionId = _uiState.value.session?.id ?: return
        selectionGuard.clearGuardRevision(sessionId)
        _uiState.update { it.copy(guardConflict = false, statusError = null) }
        refreshSessionModel(sessionId)
        requestGuardStatus(sessionId)
    }

    /** Re-runs the session load after a visible failure (error banner Retry). */
    fun retry() = conversation.dispatch(SessionCommand.Retry)

    private fun performRetry() {
        val sessionId = _uiState.value.session?.id ?: return
        // A failed send restores the text + attachments and shows the error
        // banner. Its Retry must re-run that send, not just reload the session
        // (which would leave the user staring at the same unsent prompt).
        if (_uiState.value.attachments.isNotEmpty() && _uiState.value.inputText.isNotBlank()) {
            _uiState.update { it.copy(error = null, statusError = null) }
            performSend()
            return
        }
        _uiState.update { it.copy(error = null, statusError = null) }
        loadSession(sessionId)
    }

    /**
     * Re-runs the last user turn: reverts the session to that message (dropping
     * the answer that followed) and sends it again. Reuses [sendMessage] so the
     * send path stays single-sourced. If the revert is unsupported the send
     * still happens, which merely appends a second answer.
     */
    fun regenerate() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isGenerating) return
        val lastUser = _uiState.value.messages.lastOrNull { m ->
            (m.role ?: m.info?.role) == "user" && m.firstText().isNotBlank()
        } ?: return
        val text = lastUser.firstText()
        val messageId = lastUser.id
        viewModelScope.launch {
            if (messageId != null) {
                try {
                    api.revertMessage(
                        session.id,
                        RevertRequest(messageID = messageId),
                    ).close()
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "regenerate revert failed: ${e.message}")
                    UserMessages.post(R.string.could_not_revert, "${e.message}")
                }
            }
            _uiState.update { it.copy(inputText = text, attachments = emptyList()) }
            sendMessage()
        }
    }

    fun interrupt() = conversation.dispatch(SessionCommand.Interrupt)

    override fun onCleared() {
        conversation.stop()
        sessionJob?.cancel()
        super.onCleared()
    }

    // Models configured in opencode.json but not returned by /api/model
    // --- Session management (Rename/Archive/Delete/Share/Export) ---
    // Mirrors the web "More options" menu: Rename, Share..., Export...,
    // Archive (PATCH time.archived), separator, Delete... (DELETE).

    fun renameSession(newTitle: String, onDone: () -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                val updated = api.renameSession(session.id, mapOf("title" to newTitle))
                _uiState.update { it.copy(session = updated) }
                onDone()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "renameSession failed: ${e.message}")
                UserMessages.post(R.string.could_not_rename, "${e.message}")
            }
        }
    }

    fun archiveSession(onDone: () -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                api.updateSession(
                    session.id,
                    SessionUpdateRequest(time = SessionTimeUpdate(archived = System.currentTimeMillis())),
                )
                onDone()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "archiveSession failed: ${e.message}")
                UserMessages.post(R.string.could_not_archive, "${e.message}")
            }
        }
    }

    fun deleteSession(onDone: () -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                api.deleteSession(session.id)
                onDone()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "deleteSession failed: ${e.message}")
                UserMessages.post(R.string.could_not_delete, "${e.message}")
            }
        }
    }

    // --- Message actions (mirror web: Revert message / Restore / Fork) ---

    fun revertToMessage(messageId: String, onDone: (Boolean) -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                api.revertMessage(session.id, RevertRequest(messageID = messageId)).close()
                refreshMessages(session.id)
                onDone(true)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "revertToMessage failed: ${e.message}")
                UserMessages.post(R.string.could_not_revert, "${e.message}")
                onDone(false)
            }
        }
    }

    fun restoreMessages(onDone: (Boolean) -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                api.unrevertSession(session.id).close()
                refreshMessages(session.id)
                onDone(true)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "restoreMessages failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_session, "${e.message}")
                onDone(false)
            }
        }
    }

    fun forkFromMessage(messageId: String, onDone: (String?) -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                val result = api.forkSession(session.id, ForkRequest(messageID = messageId))
                // Response shape: { id } or { data: { id } } or { sessionID }.
                // Only accept real string primitives: `toString().trim('"')`
                // turned JsonNull into the literal id "null".
                fun kotlinx.serialization.json.JsonElement?.idString(): String? =
                    (this as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content
                        ?.takeIf { it.isNotBlank() }
                val data = result["data"] as? kotlinx.serialization.json.JsonObject
                val newId = result["id"].idString()
                    ?: data?.get("id").idString()
                    ?: result["sessionID"].idString()
                    ?: data?.get("sessionID").idString()
                onDone(newId)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "forkFromMessage failed: ${e.message}")
                UserMessages.post(R.string.could_not_fork, "${e.message}")
                onDone(null)
            }
        }
    }

    // --- File viewer (mirrors web "Open file" in Review and files panel) ---

    fun openFileViewer(path: String) {
        _uiState.update { it.copy(
            fileViewer = FileViewerState(path = path, isLoading = true)
        ) }
        viewModelScope.launch {
            try {
                val content = api.getFileContent(path)
                _uiState.update { it.copy(
                    fileViewer = FileViewerState(path = path, content = content.content, isLoading = false)
                ) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "openFileViewer failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_files, "${e.message}")
                _uiState.update { it.copy(
                    fileViewer = FileViewerState(path = path, isLoading = false, error = e.message)
                ) }
            }
        }
    }

    fun dismissFileViewer() {
        _uiState.update { it.copy(fileViewer = null) }
    }

    fun shareSession(onDone: (String?) -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                val shared = api.shareSession(session.id)
                onDone(shared.slug)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "shareSession failed: ${e.message}")
                UserMessages.post(R.string.action_failed, "${e.message}")
                onDone(null)
            }
        }
    }

    fun exportSession(onDone: (String?) -> Unit) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            try {
                val history = api.getHistory(session.id)
                // Build a readable export from the history events
                val sb = StringBuilder()
                sb.appendLine("# Session: ${session.title ?: session.id}")
                sb.appendLine()
                for (ev in history.data) {
                    val text = ev.data?.text
                    if (!text.isNullOrBlank()) {
                        sb.appendLine(text)
                        sb.appendLine()
                    }
                }
                onDone(sb.toString())
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "exportSession failed: ${e.message}")
                UserMessages.post(R.string.action_failed, "${e.message}")
                onDone(null)
            }
        }
    }
}