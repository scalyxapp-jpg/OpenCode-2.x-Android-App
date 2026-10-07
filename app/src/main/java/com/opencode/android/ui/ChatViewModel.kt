package com.opencode.android.ui
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.R
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.data.BackendSession
import com.opencode.android.data.ChatRepository
import com.opencode.android.data.LastSessionStore
import com.opencode.android.data.MessagePage
import com.opencode.android.data.ModelVisibilityStore
import com.opencode.android.data.OpenCodeApi
import com.opencode.android.data.SseClient
import com.opencode.android.domain.Agent
import com.opencode.android.domain.BUILTIN_COMMANDS
import com.opencode.android.domain.CommandEntry
import com.opencode.android.domain.ContextUsage
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.ForkRequest
import com.opencode.android.domain.McpEntry
import com.opencode.android.domain.Message
import com.opencode.android.domain.Model
import com.opencode.android.domain.ModelRef
import com.opencode.android.domain.ModelRefRequest
import com.opencode.android.domain.ModelVariant
import com.opencode.android.domain.Part
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.PromptAsyncPart
import com.opencode.android.domain.PromptInput
import com.opencode.android.domain.PromptRequest
import com.opencode.android.domain.ProviderEntry
import com.opencode.android.domain.QuestionReplyRequest
import com.opencode.android.domain.RevertRequest
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.SessionTimeUpdate
import com.opencode.android.domain.SessionUpdateRequest
import com.opencode.android.domain.SummarizeRequest
import com.opencode.android.domain.TodoItem
import com.opencode.android.domain.UploadResponse
import com.opencode.android.domain.VcsDiffFile
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
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.ModelSelection
import com.opencode.android.util.UserMessages
import com.opencode.android.util.resolveModelRef
import com.opencode.android.util.resolveSessionModelRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** Matches the proxy's SESSION_GUARD_MAX_UPLOAD default. */
private const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

/**
 * Largest attachment embedded as a base64 data URL when no guard proxy is
 * present. Base64 inflates ~33% and the bytes + string are both in memory, so
 * this is deliberately far below the 50 MB guard-upload cap.
 */
private const val MAX_EMBED_BYTES = 20L * 1024 * 1024

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
    // True while the CURRENT step is actively producing content. The caret
    // blinks only then; it stops at each step boundary even though the turn
    // (and therefore the composer's stop button) keeps running.
    val streaming: Boolean = false,
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
    val variants: List<ModelVariant> =
        listOf(
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
    // Changes tab: the working-tree diff is computed by the server and can be
    // slow for a large repo, so the tab shows a spinner (or a clear timeout
    // message with Retry) instead of a silent blank panel.
    val vcsDiffLoading: Boolean = false,
    val vcsDiffError: String? = null,
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
class ChatViewModel
    @javax.inject.Inject
    constructor(
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
        private val selectionGuard =
            com.opencode.android.util
                .SelectionGuard()
        private val autoAdoptedGuardRevisions = mutableSetOf<Long>()

        // Transport seam for the workflows SessionConversation owns (interrupt).
        private val sessionTransport =
            com.opencode.android.data.BackendSessionTransport(
                session = backendSession,
                guard = selectionGuard,
            )

        // Guarded prompt_async state machine (409 retry, readable error, close).
        private val promptSender =
            PromptSender(
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
        private val conversationPort =
            object : ConversationPort {
                override fun send() = performSend()

                override fun retry() = performRetry()

                override fun notifyInterruptFailed(message: String?) {
                    AppLog.e(APP_LOG_TAG, "abort failed: $message")
                    UserMessages.post(R.string.could_not_interrupt, message ?: "")
                }

                override fun refreshMessages(sessionId: String) = this@ChatViewModel.refreshMessages(sessionId)

                override fun resyncSessionStatus() {
                    val session = _uiState.value.session ?: return
                    val sessionId = session.id
                    viewModelScope.launch {
                        try {
                            // `/api/session` (and `/session/{id}`) omit `status`,
                            // so `repo.session().status` is always null and can
                            // never tell us the turn is still running. Ask the
                            // directory-scoped status endpoint instead. `null`
                            // (unknown) keeps the current state.
                            val busy = sessionBusy(sessionId, session.directory) ?: return@launch
                            // The user may have switched while the status fetch
                            // was in flight; finalizing would then clear the NEW
                            // session's generating/persist state based on the old
                            // session's status.
                            if (_uiState.value.session?.id != sessionId) return@launch
                            // Converge in BOTH directions: the stream may have
                            // reconnected after the `busy` event was missed, so a
                            // server-busy session must re-arm the running state
                            // (otherwise the composer offers "send" for a turn
                            // that is already running), and a server-idle one must
                            // clear the stuck "generating" flag.
                            if (busy && !_uiState.value.isGenerating) {
                                AppLog.d(APP_LOG_TAG) { "resync: server busy, re-arming generating" }
                                conversation.seedGenerating(true)
                            } else if (!busy && _uiState.value.isGenerating) {
                                AppLog.d(APP_LOG_TAG) { "resync: server idle, clearing generating" }
                                conversation.forceIdle()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Best-effort; the next reconnect tries again.
                        }
                    }
                }

                override fun reconcile(includeMeta: Boolean) {
                    _uiState.value.session
                        ?.id
                        ?.let { scheduleRefresh(it, includeMeta) }
                }

                override fun loadPendingQuestions() = this@ChatViewModel.loadPendingQuestions()

                override fun onQuestionAsked(question: SessionQuestion) = this@ChatViewModel.onQuestionAsked(question)

                override fun onQuestionResolved(requestId: String) = this@ChatViewModel.onQuestionResolved(requestId)

                override fun loadPermissions() = this@ChatViewModel.loadPermissions()

                override fun notifyPermission() {
                    // The actionable notification is posted from loadPermissions(), where
                    // the request id (needed for the Allow/Deny actions) is known. Here
                    // only the sound is played, exactly once per `permission.asked`.
                    com.opencode.android.data.Notifier
                        .permissionSound()
                }

                override fun notifyDone() = notifyAgentDone()

                override fun notifyError(message: String?) {
                    com.opencode.android.data.Notifier.error(
                        "OpenCode error",
                        message ?: "The session reported an error",
                        _uiState.value.session?.id,
                    )
                }

                override fun notifyQuestion(question: SessionQuestion) {
                    com.opencode.android.data.Notifier
                        .question(question, _uiState.value.session?.id)
                }

                override fun refreshSessionModel() {
                    _uiState.value.session
                        ?.id
                        ?.let { refreshSessionModel(it) }
                }

                override fun loadVcsDiff() {
                    _uiState.value.session
                        ?.directory
                        ?.let { loadVcsDiff(it) }
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
                    // Brief reconnects must not flash the "reconnecting" banner:
                    // only surface a disconnect once it has persisted past a
                    // grace window, and clear immediately on reconnect.
                    reconnectGraceJob?.cancel()
                    if (value) {
                        _uiState.update { it.copy(sseConnected = true) }
                    } else {
                        reconnectGraceJob =
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(RECONNECT_BANNER_GRACE_MS)
                                _uiState.update { it.copy(sseConnected = false) }
                            }
                    }
                }

                override fun setSelectedModel(value: String) {
                    _uiState.update { it.copy(selectedModel = value) }
                }

                override fun setTodos(todos: List<TodoItem>) {
                    _uiState.update { it.copy(todos = todos) }
                }
            }

        // lazy: conversationPort.resyncSessionStatus() references conversation, and
        // a direct initializer would make the two declarations recursively typed.
        private val conversation: SessionConversation by lazy {
            SessionConversation(
                source = SseClient,
                scope = viewModelScope,
                models = { _uiState.value.models },
                friendlyError = ::friendlyError,
                transport = sessionTransport,
                port = conversationPort,
            )
        }

        // Streaming buffers live in their own flow (see LiveStreamState) so token
        // flushes do not recompose the whole screen. Owned by SessionStreamer.
        val liveState: StateFlow<LiveStreamState> = conversation.liveState

        // Last-known agent list per directory, used ONLY as a fallback when a
        // fetch fails. Never served in place of a successful fetch: agents can
        // be added or edited while the app runs, so a stale cache made the app
        // show fewer agents than the TUI (which reloads on start).
        private val cachedAgentsByDirectory = mutableMapOf<String, List<com.opencode.android.domain.Agent>>()

        // Tracked so a fast session switch cancels the previous load instead of
        // letting two loads race and write interleaved state (last writer won,
        // which could show session A's messages under session B's title).
        private var sessionJob: Job? = null

        // The send workflow writes selection/optimistic/upload state over
        // several suspending steps. It is cancelled on a session switch so a
        // mid-send switch cannot write the old session's state onto the new one.
        private var sendJob: Job? = null

        fun loadSession(sessionId: String) {
            // Opening a different session starts from the small default tail again;
            // re-loading the same one keeps whatever depth the user pulled in.
            if (_uiState.value.session?.id != sessionId) {
                messageLimit = messagePageSize
                olderCursor = null
                olderPagesLoaded = 0
            }
            sessionJob?.cancel()
            sessionJob =
                viewModelScope.launch {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    // Paint the cached tail first so a cold start shows the conversation
                    // immediately instead of a blank screen while the network answers.
                    // The authoritative load below replaces it a moment later. On a
                    // session switch this also clears the previous conversation instead
                    // of leaving it on screen under the new title.
                    val switching = _uiState.value.session?.id != sessionId
                    if (switching) {
                        // Cancel an in-flight send so its late writes (selection,
                        // optimistic echo, generating flag) cannot land in the
                        // newly opened session.
                        sendJob?.cancel()
                        // Never carry the previous session's live buffers (or the
                        // pending-persist flag) into the new one.
                        conversation.resetLive()
                        // Clear the previous conversation immediately (no
                        // suspension before this), so it cannot flash under the
                        // new title now that non-empty messages render ahead of
                        // the skeleton. The cached tail is loaded right after.
                        _uiState.update {
                            it.copy(
                                pendingPersist = false,
                                messages = emptyList(),
                                canLoadOlder = false,
                            )
                        }
                        // Keep the revision map bounded to the active session.
                        selectionGuard.retainOnly(sessionId)
                        autoAdoptedGuardRevisions.clear()
                        // Process-global pending flags: a selection made in the
                        // previous session that never confirmed would otherwise
                        // make the new session treat its UI selection as pending.
                        modelSelectionPending = false
                        agentSelectionPending = false
                    }
                    if (switching || _uiState.value.messages.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                messages =
                                    repo.cachedMessages(sessionId)
                                        ?: emptyList(),
                                canLoadOlder = false,
                            )
                        }
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
                        val messagePage: MessagePage
                        coroutineScope {
                            val sessionD = async { repo.session(sessionId) }
                            // /api/session omits `directory`; the web endpoint includes it.
                            val fullD =
                                async {
                                    try {
                                        repo.fullSession(sessionId)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                            val messagesD =
                                async {
                                    repo.loadMessages(sessionId, messageLimit, before = null)
                                }
                            sessionBase = sessionD.await()
                            fullSession = fullD.await()
                            messagePage = messagesD.await()
                        }
                        val messages = messagePage.messages
                        // The newest page carries the cursor for the next older
                        // page. Adopt it only when we have no older pages loaded
                        // (a switch resets them), so a reload cannot move the
                        // boundary back and re-serve pages we already have.
                        if (switching || olderPagesLoaded == 0) {
                            olderCursor = messagePage.nextCursor
                        }
                        AppLog.record(APP_LOG_TAG) {
                            "loadSession: messages=${messages.size} canLoadOlder=${olderCursor != null}"
                        }
                        val session =
                            if (fullSession != null && !fullSession.directory.isNullOrBlank()) {
                                sessionBase.copy(
                                    directory = fullSession.directory,
                                    path = fullSession.path ?: sessionBase.path,
                                )
                            } else {
                                // /api/session omits `directory`. Without it the Changes
                                // tab and VCS diff silently stop working, so fall back to
                                // the remembered project before giving up.
                                sessionBase.copy(
                                    directory =
                                        sessionBase.directory
                                            ?: LastSessionStore.directory(),
                                )
                            }
                        AppLog.d(
                            APP_LOG_TAG,
                        ) {
                            "loadSession: session=${session.id} dir=${session.directory} agent=${session.agent} model=${session.model?.id}"
                        }
                        // Directory-scoped: a project can define its own agents.
                        val agents = loadAgents(session.directory)
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
                        //
                        // Do NOT await the catalog: /provider is ~6 MB and fetched
                        // once per process. Awaiting it here made the first session
                        // open after a cold start block for seconds. Paint with
                        // whatever is already parsed and re-resolve when it arrives
                        // (the launch after the state write below).
                        providerDirectory.prefetch()
                        val providerGroups = providerDirectory.connectedProviders
                        val models = modelsFrom(providerDirectory)
                        session.sessionGuard?.revision?.let { revision ->
                            selectionGuard.recordGuardRevision(session.id, revision)
                        }

                        // Server session model is authoritative. A selection made in
                        // web/TUI must win over stale Android localStorage.
                        val remembered = ModelVisibilityStore.selection(session.id)
                        val serverModel = resolveSessionModelRef(session.model, models)
                        val rememberedRaw =
                            remembered?.let { r ->
                                if (!r.providerId.isNullOrBlank() && !r.modelId.isNullOrBlank()) {
                                    com.opencode.android.util
                                        .sessionModelRef(r.modelId, r.providerId)
                                } else {
                                    null
                                }
                            }
                        // Repair stale local picks against the exact provider catalog.
                        val rememberedModel =
                            rememberedRaw?.let { raw ->
                                val (provider, modelId) = resolveModelRef(raw, models)
                                com.opencode.android.util
                                    .sessionModelRef(modelId, provider)
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
                            // Never blank a populated conversation with a
                            // transiently empty/failed page (see SessionLoadMerge).
                            val safeMessages =
                                com.opencode.android.ui.session.SessionLoadMerge.resolve(
                                    current = current.messages,
                                    loaded = messages,
                                    sameSession = current.session?.id == session.id,
                                    merge = repo::mergeMessages,
                                )
                            current.copy(
                                session = session,
                                messages = safeMessages,
                                canLoadOlder = canLoadOlderNow(),
                                agents = agents,
                                models = models,
                                providerGroups = providerGroups,
                                modelVisibility = ModelVisibilityStore.visibilityMap(),
                                selectedAgent = session.agent ?: remembered?.agent ?: "build",
                                selectedModel = serverModel ?: rememberedModel ?: "",
                                selectedVariant = session.model?.variant ?: remembered?.variant ?: "default",
                                isLoading = false,
                            )
                        }
                        // Re-resolve the model list once the ~6 MB catalog has
                        // finished loading, so a cold start shows the picker a
                        // moment later instead of blocking the whole open. Runs
                        // AFTER the state write, so it can never be overwritten
                        // by the (possibly empty) list captured above.
                        viewModelScope.launch {
                            providerDirectory.load()
                            if (_uiState.value.session?.id != sessionId) return@launch
                            val groups = providerDirectory.connectedProviders
                            if (groups.isEmpty()) return@launch
                            val resolved =
                                groups.flatMap { provider ->
                                    provider.models.values.map { m ->
                                        Model(
                                            id = m.id,
                                            name = m.name,
                                            providerId = m.providerId ?: provider.id,
                                        )
                                    }
                                }
                            if (resolved.isEmpty()) return@launch
                            val resolvedServerModel = resolveSessionModelRef(session.model, resolved)
                            _uiState.update { current ->
                                if (current.session?.id != sessionId) {
                                    current
                                } else {
                                    current.copy(
                                        models = resolved,
                                        providerGroups = groups,
                                        selectedModel = resolvedServerModel ?: current.selectedModel,
                                    )
                                }
                            }
                        }
                        // Seed the generating flag through the conversation mirror
                        // so ConversationState and ChatUiState cannot disagree
                        // after a load that races a stream event. The session
                        // payloads omit `status` (always null), so ask the
                        // directory-scoped status endpoint — the only signal that
                        // also covers a turn started by another client (web/TUI).
                        // Off the critical path: the probe must not delay the
                        // first paint, and `null` (unknown) leaves the flag as-is.
                        viewModelScope.launch {
                            val busy = sessionBusy(session.id, session.directory) ?: return@launch
                            if (_uiState.value.session?.id == session.id) {
                                conversation.seedGenerating(busy)
                            }
                        }
                        // Restore the composer draft for this session (it
                        // survives the ViewModel recreation on a tab switch).
                        if (switching) {
                            val draft =
                                com.opencode.android.data.DraftStore
                                    .get(sessionId)
                            _uiState.update {
                                it.copy(
                                    inputText = draft?.text ?: "",
                                    attachments = draft?.attachments ?: emptyList(),
                                )
                            }
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
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = e.message ?: "Session could not be loaded",
                            )
                        }
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
            contextJob =
                viewModelScope.launch {
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
                // The newest assistant row can be a zero-token placeholder (a
                // summary/compaction row), which made the figures read 0 or
                // stay on the previous session's numbers. Prefer the newest
                // assistant message that actually has usage.
                fun usageOf(m: Message) = m.tokens ?: m.info?.tokens
                val assistantMessages = messages.filter { roleOf(it) == "assistant" }
                val lastAssistant =
                    assistantMessages
                        .filter { m ->
                            val t = usageOf(m)
                            t != null && (
                                (t.input ?: 0L) + (t.output ?: 0L) +
                                    (t.reasoning ?: 0L) + (t.cache?.read ?: 0L) +
                                    (t.cache?.write ?: 0L)
                            ) > 0L
                        }.maxByOrNull {
                            it.time?.created ?: it.info?.time?.created ?: 0L
                        }
                        ?: assistantMessages.maxByOrNull {
                            it.time?.created ?: it.info?.time?.created ?: 0L
                        }
                val tokens = lastAssistant?.let { usageOf(it) } ?: full.tokens
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
                val info =
                    ContextInfo(
                        // Provider can be absent on the session payload; derive it
                        // from the provider-qualified model id when possible so the
                        // dialog does not show "—" for a known model.
                        provider =
                            (full.model?.provider ?: session.model?.provider)
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
                val context =
                    try {
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
                _uiState.update {
                    it.copy(
                        contextUsage = context ?: it.contextUsage,
                        contextInfo = info,
                    )
                }
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
                ?.limit
                ?.context
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
        private suspend fun serverHome(): String? =
            try {
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
                    val servers =
                        api
                            .getMcpServers(directory)
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

        fun toggleMcp(
            name: String,
            enable: Boolean,
        ) {
            val target = if (enable) "connected" else "disabled"
            // Optimistic: the server flips an MCP server's status asynchronously
            // (the MCP client may take >10s to connect/disconnect), so show the
            // intended state immediately.
            _uiState.update { current ->
                current.copy(
                    mcpServers =
                        current.mcpServers.map {
                            if (it.name == name) it.copy(status = target) else it
                        },
                )
            }
            mcpToggleJob?.cancel()
            mcpToggleJob =
                viewModelScope.launch {
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
                            val map =
                                try {
                                    api.getMcpServers(directory)
                                } catch (_: Exception) {
                                    null
                                } ?: return@repeat
                            val confirmed = map[name]?.status == target
                            val servers =
                                map
                                    .map { (n, s) ->
                                        val status =
                                            if (!confirmed && n == name) {
                                                target
                                            } else {
                                                s.status ?: "disabled"
                                            }
                                        McpEntry(n, status)
                                    }.sortedBy { it.name }
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
            val modelRef =
                _uiState.value.selectedModel
                    .ifBlank {
                        com.opencode.android.util
                            .sessionModelRef(session.model?.id, session.model?.provider ?: session.model?.providerID)
                    }
            if (modelRef.isBlank()) {
                _uiState.update { it.copy(statusError = "No model selected") }
                return
            }
            // Catalog model ids are authoritative. NVIDIA exposes ids that already
            // include the provider prefix; other providers expose bare ids.
            val modelEntry =
                _uiState.value.models.firstOrNull {
                    com.opencode.android.util
                        .sessionModelRef(it.id, it.providerId) == modelRef
                } ?: _uiState.value.models.firstOrNull { it.id == modelRef }
            val provider =
                modelEntry?.providerId
                    ?: session.model?.provider
                    ?: session.model?.providerID
                    ?: modelRef.substringBefore('/', "")
            val modelId =
                modelEntry?.id
                    ?: session.model?.id?.takeIf { it.isNotBlank() }
                    ?: modelRef.substringAfter('/', modelRef)
            _uiState.update { it.copy(isCompacting = true, statusError = null) }
            viewModelScope.launch {
                try {
                    val response =
                        api.summarizeSession(
                            session.id,
                            // URL-encoded, exactly as the web sends it.
                            session.directory?.let {
                                java.net.URLEncoder.encode(it, Charsets.UTF_8.name())
                            },
                            SummarizeRequest(providerID = provider, modelID = modelId),
                        )
                    if (!response.isSuccessful) {
                        val message =
                            com.opencode.android.util.serverErrorMessage(
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
                    _uiState.update {
                        it.copy(
                            statusError =
                                com.opencode.android.util
                                    .serverErrorMessage(e.message, "Compact failed"),
                        )
                    }
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
                    val pending = all.filter { it.sessionId == null || it.sessionId == sessionId }
                    _uiState.update { it.copy(pendingPermissions = pending) }
                    // Actionable notifications for newly seen requests. Notifier
                    // dedupes by request id, so repeated loads do not re-alert.
                    com.opencode.android.data.Notifier
                        .permissionRequests(sessionId, pending)
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "loadPermissions failed: ${e.message}")
                    UserMessages.post(R.string.could_not_load_session, "${e.message}")
                }
            }
        }

        /** [reply] is "once" | "always" | "reject" (protocol values). */
        fun replyPermission(
            requestId: String,
            reply: String,
        ) {
            val sessionId = _uiState.value.session?.id ?: return
            // Optimistic: drop the prompt immediately so the buttons cannot be
            // double-tapped while the reply is in flight.
            _uiState.update { current ->
                current.copy(
                    pendingPermissions =
                        current.pendingPermissions
                            .filterNot { it.id == requestId },
                )
            }
            // The shade notification (if one was posted) is stale now.
            com.opencode.android.data.Notifier
                .clearPermission(requestId)
            viewModelScope.launch {
                // Shared with the notification action so both paths use the
                // same global -> session-scoped fallback.
                val ok =
                    com.opencode.android.data
                        .replyPermission(api, sessionId, requestId, reply)
                if (!ok) UserMessages.post(R.string.action_failed, "replyPermission")
                if (_uiState.value.session?.id == sessionId) loadPermissions()
            }
        }

        fun loadPendingQuestions(notifyOnError: Boolean = true) {
            val session = _uiState.value.session ?: return
            viewModelScope.launch {
                try {
                    var questions = api.getSessionQuestions(session.id).data
                    // A successful fetch ends any outage for logging purposes.
                    pendingQuestionsOutageLogged = false
                    if (questions.isEmpty()) {
                        // Fallback: the v2 pending list. The session-scoped route
                        // can be empty while the question tool is blocked.
                        questions =
                            api
                                .getQuestionRequests()
                                .data
                                .filter { it.sessionId == null || it.sessionId == session.id }
                    }
                    // A session switch while the fetch was in flight must not
                    // overwrite the new session's questions with the old list.
                    if (_uiState.value.session?.id != session.id) return@launch
                    // Both list endpoints can be empty while a question is pending
                    // (the request is delivered via the question.asked event), so
                    // only replace when the fetch actually returned something —
                    // otherwise the background poll would wipe the card.
                    if (questions.isNotEmpty()) {
                        _uiState.update { it.copy(pendingQuestions = questions) }
                    }
                } catch (e: Exception) {
                    // Log the first failure of a continuous outage only; a flapping
                    // link otherwise wrote ~120 identical lines per 10 minutes.
                    if (!pendingQuestionsOutageLogged) {
                        AppLog.e(APP_LOG_TAG, "loadPendingQuestions failed: ${e.message}")
                        pendingQuestionsOutageLogged = true
                    }
                    // The background poll must not toast on every transient miss.
                    if (notifyOnError) {
                        UserMessages.post(R.string.could_not_load_session, "${e.message}")
                    }
                }
            }
        }

        /**
         * A `question.asked` event delivered the full pending request. Upsert it so
         * the answerable card appears immediately, without waiting for a list fetch
         * (the server's list endpoints can stay empty while the tool is blocked).
         */
        fun onQuestionAsked(question: SessionQuestion) {
            _uiState.update { current ->
                current.copy(
                    pendingQuestions =
                        current.pendingQuestions.filterNot { it.id == question.id } + question,
                )
            }
        }

        /** The question was answered/rejected (locally or in another client). */
        fun onQuestionResolved(requestId: String) {
            _uiState.update { current ->
                current.copy(
                    pendingQuestions = current.pendingQuestions.filterNot { it.id == requestId },
                )
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
            _uiState.update { current ->
                current.copy(
                    pendingQuestions =
                        current.pendingQuestions
                            .filterNot { it.id == requestId },
                )
            }
            viewModelScope.launch {
                // The global reply route reaches the server's pending-question
                // store; the session-scoped route 404s on some builds. Try the
                // global form first and fall back.
                val body = QuestionReplyRequest(answers)
                val ok =
                    try {
                        api.replyQuestion(requestId, body).close()
                        true
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "replyQuestion (global) failed: ${e.message}")
                        try {
                            api.replySessionQuestion(session.id, requestId, body).close()
                            true
                        } catch (e2: Exception) {
                            AppLog.e(APP_LOG_TAG, "replySessionQuestion failed: ${e2.message}")
                            false
                        }
                    }
                if (ok) {
                    loadPendingQuestions()
                    refreshMessages(session.id)
                    onDone(true)
                } else {
                    // A stale/expired request must not leave the user stuck: say so
                    // and re-sync the real list from the server.
                    UserMessages.post(
                        R.string.action_failed,
                        "Could not send the answer — the question may have expired",
                    )
                    _uiState.update {
                        it.copy(
                            statusError = "Could not send the answer — the question may have expired",
                        )
                    }
                    loadPendingQuestions()
                    onDone(false)
                }
            }
        }

        fun rejectQuestion(
            requestId: String,
            onDone: (Boolean) -> Unit,
        ) {
            val session = _uiState.value.session ?: return
            _uiState.update { current ->
                current.copy(
                    pendingQuestions =
                        current.pendingQuestions
                            .filterNot { it.id == requestId },
                )
            }
            viewModelScope.launch {
                val ok =
                    try {
                        api.rejectQuestion(requestId).close()
                        true
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "rejectQuestion (global) failed: ${e.message}")
                        try {
                            api.rejectSessionQuestion(session.id, requestId).close()
                            true
                        } catch (e2: Exception) {
                            AppLog.e(APP_LOG_TAG, "rejectSessionQuestion failed: ${e2.message}")
                            false
                        }
                    }
                if (ok) {
                    loadPendingQuestions()
                    onDone(true)
                } else {
                    UserMessages.post(
                        R.string.action_failed,
                        "Could not reject the question — it may have expired",
                    )
                    _uiState.update {
                        it.copy(
                            statusError = "Could not reject the question — it may have expired",
                        )
                    }
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
        private var reconnectGraceJob: Job? = null

        // Message ids of optimistic user echoes that the server has not echoed
        // back yet. Matching is by id (not text) so an attachment-marker mismatch
        // cannot leave the row duplicated.
        private val optimisticEchoIds: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        // True after the first failed question-poll of a continuous outage, so
        // the log line is written once instead of once per 5 s retry.
        private var pendingQuestionsOutageLogged = false

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
                if (current.session?.id != sessionId) {
                    current
                } else {
                    current.copy(
                        guardEnabled = enabled || knownGuard,
                        guardHealthy = enabled,
                        guardAvgMs = body?.metrics?.avgMs,
                    )
                }
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
                        if (current.session?.id != sessionId) {
                            current
                        } else {
                            current.copy(
                                session =
                                    current.session.copy(
                                        model = session.model,
                                        agent = session.agent ?: current.session.agent,
                                    ),
                                selectedModel = selectedModel ?: current.selectedModel,
                                selectedAgent = session.agent ?: current.selectedAgent,
                                selectedVariant = session.model?.variant ?: current.selectedVariant,
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Session metadata refresh is best-effort; next SSE/poll retries.
                }
            }
        }

        /**
         * Project-scoped agent list. Uses `GET /agent?directory=` (the web endpoint)
         * because the legacy `GET /api/agent` ignores the directory and therefore
         * never surfaced a project's own agents. Falls back to the legacy endpoint
         * when the web one fails.
         *
         * Always refetches. Agents are defined in config/markdown files the user can
         * add or edit while the app runs; a process-lifetime cache hid newly added
         * agents until the app was restarted, so the app and the TUI (which reloads
         * on start) disagreed. The per-directory map is now only a fallback for a
         * failed fetch, never a substitute for a successful one.
         */

        /**
         * The model list for the pickers, WITHOUT awaiting the provider catalog.
         *
         * The catalog (`GET /provider`, ~6 MB) is prefetched at app start and
         * parsed in the background; awaiting it on the session-open path blocked
         * the first open for seconds. When it is still loading we return the
         * models parsed so far (usually empty) and the re-resolve after the
         * state write fills them. Only a FAILED catalog falls back to the small
         * `GET /api/model` endpoint, so an offline backend still offers models.
         */
        private suspend fun modelsFrom(directory: com.opencode.android.data.ProviderDirectory): List<Model> {
            val groups = directory.connectedProviders
            if (groups.isNotEmpty()) {
                return groups.flatMap { provider ->
                    provider.models.values.map { m ->
                        Model(id = m.id, name = m.name, providerId = m.providerId ?: provider.id)
                    }
                }
            }
            if (directory.state.value is com.opencode.android.data.ProviderDirectory.State.Failed) {
                return try {
                    api.getModels().data
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "getModels failed: ${e.message}")
                    UserMessages.post(R.string.could_not_load_models, "${e.message}")
                    emptyList()
                }
            }
            return emptyList()
        }

        private suspend fun loadAgents(directory: String?): List<Agent> {
            val key = directory ?: ""
            val scoped =
                try {
                    api.getProjectAgents(directory).map { p ->
                        Agent(id = p.name, name = p.name, description = p.description, mode = p.mode)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Never swallow cancellation: doing so broke structured concurrency
                    // and left the agent list empty when the user navigated mid-load.
                    throw e
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "getProjectAgents failed: ${e.message}")
                    emptyList()
                }
            val fetched =
                scoped.ifEmpty {
                    try {
                        api.getAgents().data
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "getAgents failed: ${e.message}")
                        UserMessages.post(R.string.could_not_load_agents, "${e.message}")
                        emptyList()
                    }
                }
            if (fetched.isNotEmpty()) cachedAgentsByDirectory[key] = fetched
            return fetched.ifEmpty { cachedAgentsByDirectory[key].orEmpty() }
        }

        private fun startPolling(sessionId: String) {
            pollJob?.cancel()
            lastPollUpdated = null
            pollJob =
                viewModelScope.launch {
                    // Grows the interval while the backend is unreachable so a long
                    // outage does not poll (and log) every 5 s for 90 minutes.
                    var failures = 0
                    while (isActive) {
                        kotlinx.coroutines.delay(POLL_INTERVAL_MS * (1 + minOf(failures, 6)))
                        if (_uiState.value.session?.id != sessionId) break
                        try {
                            // Agent questions can be missed by the SSE stream (the
                            // legacy question.asked bus event is not reliably forwarded
                            // on /global/event). Poll while the turn is running so the
                            // answerable card appears instead of only after the user
                            // aborts the session.
                            if (_uiState.value.isGenerating) {
                                loadPendingQuestions(notifyOnError = false)
                                // Watchdog: a `session.idle` lost during a reconnect
                                // gap must not leave the composer stuck on "stop".
                                // Only while the stream is DOWN — with SSE connected
                                // the idle event arrives normally and the connect
                                // resync already ran, so this stays a retry rather
                                // than a status request on every poll tick.
                                if (!_uiState.value.sseConnected) {
                                    conversationPort.resyncSessionStatus()
                                }
                            }
                            val updated = repo.session(sessionId).time?.updated
                            if (updated != null && updated != lastPollUpdated) {
                                lastPollUpdated = updated
                                refreshSessionModel(sessionId)
                                refreshMessages(sessionId)
                            }
                            failures = 0
                        } catch (e: Exception) {
                            // Transient (offline): back off and keep polling.
                            failures = (failures + 1).coerceAtMost(6)
                        }
                    }
                }
        }

        /**
         * Whether the server currently reports [sessionId] as busy or retrying,
         * or `null` when that cannot be determined (no directory / request
         * failed). Callers must treat `null` as "keep the current state", never
         * as "idle" — a failed probe must not clear a live running indicator.
         * `/session/status` is directory-scoped; without a directory the server
         * answers `{}`.
         */
        private suspend fun sessionBusy(
            sessionId: String,
            directory: String?,
        ): Boolean? {
            val dir = directory?.takeIf { it.isNotBlank() } ?: return null
            return try {
                val status = api.getSessionStatuses(dir)[sessionId]
                status?.type == "busy" || status?.type == "retry"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        private fun startSse(sessionId: String) {
            // Must follow the ACTIVE backend, not the compiled-in default: a user
            // pointed at another server previously got a live stream from the
            // hardcoded host, so streaming silently broke on custom backends.
            AppLog.d(APP_LOG_TAG) { "startSse: session=$sessionId url=${backendSession.currentBaseUrl()}/api/event" }
            conversation.dispatch(SessionCommand.Load(sessionId, backendSession.currentBaseUrl()))
        }

        /**
         * The agent finished a turn (or needs attention). Notifies/sounds according
         * to the Settings toggles that previously had no effect.
         */
        private fun notifyAgentDone() {
            val title = _uiState.value.session?.title ?: "OpenCode"
            com.opencode.android.data.Notifier
                .agent(title, "The agent finished", _uiState.value.session?.id)
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
        // page small; the endpoint returns full tool outputs/diffs and a large
        // page has caused OutOfMemoryError.
        private val messagePageSize = 40

        // Web parity: older pages use `limit=20&cursor=…`.
        private val olderPageSize = 20

        // Hard cap on loaded older pages so paging back cannot grow memory
        // without bound (~300 extra messages).
        private val maxOlderPages = 10

        // How many consecutive empty-but-cursored older pages to walk past
        // before concluding there is nothing older. Oversized pages (rejected
        // by the body cap) keep their cursor; skipping a few of those still
        // reaches a page that fits, but an unbounded walk would spin forever
        // on a session whose whole history is over the cap.
        private val maxOlderSkips = 3

        // Newest page size. No longer grows: older history is reached through
        // the server's `before` cursor instead of a bigger tail.
        private var messageLimit = messagePageSize

        // Cursor for the next (older) page, from the server's `x-next-cursor`.
        // null = no older page known (or none exists).
        private var olderCursor: String? = null
        private var olderPagesLoaded = 0
        private var loadingOlder = false

        private fun canLoadOlderNow(): Boolean = olderCursor != null && olderPagesLoaded < maxOlderPages

        /**
         * Fetches the next older page through the server's `before` cursor and
         * prepends it. Bounded and streamed, so it never re-fetches the newest
         * tail and never grows the retained history past [maxOlderPages].
         */
        fun loadOlderMessages() {
            val sessionId = _uiState.value.session?.id ?: return
            // Fall back to a locally built cursor from the oldest loaded message
            // (the server accepts it identically) so a tap always does something
            // even if the cursor was never captured.
            val startCursor =
                olderCursor
                    ?: _uiState.value.messages
                        .firstOrNull()
                        ?.let(com.opencode.android.util.MessageCursor::forMessage)
                    ?: return
            AppLog.record(APP_LOG_TAG) {
                "loadOlder: start (cursor=${startCursor.take(12)}, loaded=${_uiState.value.messages.size})"
            }
            if (loadingOlder || olderPagesLoaded >= maxOlderPages) return
            loadingOlder = true
            viewModelScope.launch {
                try {
                    var cursor = startCursor
                    var skipped = 0
                    while (true) {
                        val page =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repo.loadMessages(sessionId, olderPageSize, before = cursor)
                            }
                        // The user may have switched while the page was in flight.
                        if (_uiState.value.session?.id != sessionId) return@launch
                        if (page.messages.isNotEmpty()) {
                            olderCursor = page.nextCursor
                            olderPagesLoaded++
                            _uiState.update { current ->
                                current.copy(
                                    // Older page first, current second: for a
                                    // duplicate id the newer copy wins.
                                    messages = repo.mergeMessages(page.messages, current.messages),
                                    canLoadOlder = canLoadOlderNow(),
                                )
                            }
                            AppLog.record(APP_LOG_TAG) {
                                "loadOlder: +${page.messages.size} older messages " +
                                    "(next=${olderCursor != null})"
                            }
                            return@launch
                        }
                        // Empty page. A cursor that is still offered means the
                        // page was oversized (skipped) or transiently empty —
                        // walk past it a few times instead of stopping at the
                        // first one, which made "load older" look broken.
                        val next = page.nextCursor
                        if (next == null || skipped >= maxOlderSkips) {
                            olderCursor = next
                            _uiState.update { it.copy(canLoadOlder = canLoadOlderNow()) }
                            AppLog.record(APP_LOG_TAG) { "loadOlder: no older messages (next=${next != null})" }
                            return@launch
                        }
                        cursor = next
                        skipped++
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "loadOlderMessages failed: ${e.message}")
                } finally {
                    loadingOlder = false
                }
            }
        }

        // --- Debounced refresh ----------------------------------------------------
        // The server replays bursts of events (tool storms). Refreshing the whole
        // message list + todos + VCS diff on every single event would fire hundreds
        // of requests and cause jank, so they are coalesced into one trailing pass.
        private var refreshJob: kotlinx.coroutines.Job? = null
        private var refreshWantsMeta = false

        // Debounce-with-ceiling policy (pure + unit-tested; see RefreshCoalescer).
        private val refreshCoalescer =
            com.opencode.android.util
                .RefreshCoalescer(maxWaitMs = REFRESH_MAX_WAIT_MS)

        private fun scheduleRefresh(
            sessionId: String,
            includeMeta: Boolean = false,
        ) {
            refreshWantsMeta = refreshWantsMeta || includeMeta
            val jobActive = refreshJob?.isActive == true
            when (refreshCoalescer.onRequest(System.currentTimeMillis(), jobActive)) {
                com.opencode.android.util.RefreshCoalescer.Decision.Start -> {
                    Unit
                }

                com.opencode.android.util.RefreshCoalescer.Decision.Restart -> {
                    refreshJob?.cancel()
                }

                com.opencode.android.util.RefreshCoalescer.Decision.KeepRunning -> {
                    return
                }
            }
            refreshJob =
                viewModelScope.launch {
                    kotlinx.coroutines.delay(REFRESH_DEBOUNCE_MS)
                    // The session may have changed while this was debounced.
                    if (_uiState.value.session?.id != sessionId) return@launch
                    // Loop so a meta upgrade that arrives while this job is
                    // running (the KeepRunning path sets refreshWantsMeta) is not
                    // lost — otherwise todos/context/VCS stayed stale.
                    var passes = 0
                    do {
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
                            _uiState.value.session
                                ?.directory
                                ?.let { loadVcsDiff(it) }
                        }
                        passes++
                    } while (
                        refreshWantsMeta &&
                        passes < 3 &&
                        _uiState.value.session?.id == sessionId
                    )
                }
        }

        /** First text of a message, used to match a local echo to the server's. */
        private fun Message.firstText(): String =
            com.opencode.android.util.MessageEcho
                .firstText(this)

        // Minimum gap between offline-cache writes while a turn is streaming.
        private val cacheWriteThrottle =
            com.opencode.android.util
                .WriteThrottle(minIntervalMs = CACHE_WRITE_MIN_INTERVAL_MS)

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

            /** Delay before a dropped SSE stream shows the "reconnecting" banner. */
            const val RECONNECT_BANNER_GRACE_MS = 8_000L
        }

        // Review/Changes tab: branch + git diff for the session directory.
        // Single-flight + failure cooldown: overlapping calls each hung until the
        // 15s socket timeout, saturating the connection and making streaming feel
        // sluggish. A non-git directory now costs one short probe per 30s.
        private var vcsJob: kotlinx.coroutines.Job? = null

        fun loadVcsDiff(directory: String) {
            // Single-flight: a new request cancels the previous one. No failure
            // cooldown: it used to make the tab silently do nothing for 30 s after
            // one timeout, which read as "Changes always empty".
            vcsJob?.cancel()
            vcsJob =
                viewModelScope.launch {
                    _uiState.update { it.copy(vcsDiffLoading = true, vcsDiffError = null) }
                    try {
                        val info =
                            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                                repo.vcs(directory)
                            }
                        // /vcs/diff returns every changed file WITH its full patch. The
                        // web waits for this request indefinitely (the panel just shows
                        // "Loading ..." until it lands) and so does the app, but bounded:
                        // measured 22 s for /agent and minutes for a 347-file diff on a
                        // loaded host, so a 30 s cap timed out on a request that would
                        // have succeeded. 120 s mirrors the web's patience and still
                        // ends with a clear message + Retry if the server is stuck.
                        val diff =
                            kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                                repo.vcsDiff(directory)
                            }
                        // Ignore a stale result if the user switched session.
                        if (_uiState.value.session?.directory != directory) return@launch
                        when {
                            diff != null -> {
                                _uiState.update { current ->
                                    current.copy(
                                        vcsBranch = info?.branch ?: current.vcsBranch,
                                        vcsDiff = diff,
                                        vcsDiffLoading = false,
                                        vcsDiffError = null,
                                    )
                                }
                            }

                            info != null -> {
                                _uiState.update { current ->
                                    current.copy(
                                        vcsBranch = info.branch ?: current.vcsBranch,
                                        vcsDiffLoading = false,
                                        vcsDiffError = "Server timed out computing the diff for this repository",
                                    )
                                }
                            }

                            else -> {
                                _uiState.update { current ->
                                    current.copy(
                                        vcsDiffLoading = false,
                                        vcsDiffError = "Could not read the repository status",
                                    )
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Single-flight cancels the previous call on purpose.
                        throw e
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "getVcsDiff failed: ${e.message}")
                        _uiState.update { current ->
                            current.copy(
                                vcsDiffLoading = false,
                                vcsDiffError = e.message ?: "Could not load changes",
                            )
                        }
                    }
                }
        }

        // Web shows a collapsible todo panel above the composer.
        fun loadTodos(sessionId: String) {
            viewModelScope.launch {
                try {
                    val todos = api.getTodos(sessionId)
                    // The user may have switched sessions while this fetched;
                    // writing here would drop the old session's todos into the
                    // new one (and post a misleading toast).
                    if (_uiState.value.session?.id != sessionId) return@launch
                    // V2 has no GET todo endpoint — todos arrive via `todo.updated`
                    // events — so the adapter returns an empty list. Never let that
                    // empty result wipe the todos the event stream just delivered.
                    if (todos.isNotEmpty() || _uiState.value.todos.isEmpty()) {
                        _uiState.update { it.copy(todos = todos) }
                    }
                } catch (e: Exception) {
                    if (_uiState.value.session?.id != sessionId) return@launch
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
            messagesJob =
                viewModelScope.launch {
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
                val page =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repo.loadMessages(sessionId, messageLimit, before = null)
                    }
                val messages = page.messages
                // Re-check AFTER the fetch: the user may have switched sessions
                // while this was suspended. Without this second guard the old
                // session's messages were written under the new session (the
                // exact "A's conversation under B's title" bug). messagesJob is
                // not cancelled on switch, so this coroutine always resumes.
                if (_uiState.value.session?.id != sessionId) return
                // Adopt the newest page's cursor only while no older page is
                // loaded; otherwise keep the deeper boundary the user paged to.
                if (olderPagesLoaded == 0) olderCursor = page.nextCursor
                // Never wipe a populated conversation with a transiently-empty
                // response — on reconnect the server can briefly return [] while
                // replaying a session's events, which previously blanked the UI.
                if (messages.isEmpty() && _uiState.value.messages.isNotEmpty()) {
                    _uiState.update { it.copy(canLoadOlder = canLoadOlderNow()) }
                    return
                }
                // Keep an optimistic user echo alive until the server echoes it
                // back. The refresh right after a send often lands before the
                // message is persisted, which made the just-sent bubble vanish
                // and reappear a moment later — a visible flicker at exactly the
                // moment the user is watching.
                val currentMessages = _uiState.value.messages
                // Match optimistic echoes by their client-generated id: once the
                // server list contains that id the echo is confirmed and dropped;
                // unmatched echoes stay on screen.
                val serverIds = messages.mapNotNull { it.id }.toSet()
                optimisticEchoIds.removeAll(serverIds)
                val pendingLocal = currentMessages.filter { it.id != null && it.id in optimisticEchoIds }
                // Every optimistic echo is removed from the base BEFORE merging so
                // it cannot linger; unmatched echoes are re-added from pendingLocal.
                val localBase = currentMessages.filterNot { it.id != null && it.id in optimisticEchoIds }
                // Merge the newest page into whatever is loaded (older pages
                // included) off the main thread. `mergeMessages(base, newest)`
                // keeps older messages and lets the newest copy win.
                val (effective, changed) =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val merged =
                            if (localBase.isEmpty()) {
                                messages
                            } else {
                                repo.mergeMessages(localBase, messages)
                            }
                        val eff = if (pendingLocal.isEmpty()) merged else merged + pendingLocal
                        eff to (eff != currentMessages)
                    }
                // Skip the state write when nothing changed — avoids a full
                // message-list recomposition on every polling tick.
                val hasMore = canLoadOlderNow()
                if (changed || _uiState.value.canLoadOlder != hasMore) {
                    _uiState.update { current ->
                        val finalMessages =
                            if (current.messages === currentMessages) {
                                if (changed) effective else current.messages
                            } else {
                                // A concurrent older-page load replaced the list
                                // while we merged; re-merge against the latest so
                                // the older page is not dropped. Drop the local
                                // echoes from it too (see localBase above).
                                val latestBase =
                                    current.messages.filterNot { it.id != null && it.id in optimisticEchoIds }
                                val merged =
                                    if (latestBase.isEmpty()) {
                                        messages
                                    } else {
                                        repo.mergeMessages(latestBase, messages)
                                    }
                                if (pendingLocal.isEmpty()) merged else merged + pendingLocal
                            }
                        current.copy(messages = finalMessages, canLoadOlder = hasMore)
                    }
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

        private fun syncSessionModel(
            sessionId: String,
            modelRef: String,
            variant: String,
        ) {
            val generation = selectionGuard.beginModelSync()
            viewModelScope.launch {
                try {
                    val (providerId, modelId) = resolveModelRef(modelRef, _uiState.value.models)
                    if (providerId.isBlank() || modelId.isBlank()) return@launch
                    val response =
                        api.setModel(
                            sessionId,
                            ModelRefRequest(
                                model =
                                    ModelRef(
                                        id = modelId,
                                        providerId = providerId,
                                        variant = variant.takeIf { it.isNotBlank() && it != "default" },
                                    ),
                            ),
                        )
                    if (!response.isSuccessful) {
                        val detail =
                            com.opencode.android.util.serverErrorMessage(
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

        private fun syncSessionAgent(
            sessionId: String,
            agent: String,
        ) {
            val generation = selectionGuard.beginAgentSync()
            viewModelScope.launch {
                try {
                    if (agent.isNotBlank()) {
                        val response = api.setAgent(sessionId, mapOf("agent" to agent))
                        if (!response.isSuccessful) {
                            val detail =
                                com.opencode.android.util.serverErrorMessage(
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

        fun setModelVisibility(
            providerId: String,
            modelId: String,
            show: Boolean,
        ) {
            ModelVisibilityStore.setVisibility(providerId, modelId, show)
            _uiState.update {
                it.copy(
                    modelVisibility = ModelVisibilityStore.visibilityMap(),
                )
            }
        }

        fun setProviderVisibility(
            providerId: String,
            modelIds: List<String>,
            show: Boolean,
        ) {
            ModelVisibilityStore.setProviderVisibility(providerId, modelIds, show)
            _uiState.update {
                it.copy(
                    modelVisibility = ModelVisibilityStore.visibilityMap(),
                )
            }
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
            val nested =
                Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(raw)
                    .map { it.groupValues[1] }
                    .lastOrNull()
            return when {
                raw.contains("FreeUsageLimitError") || raw.contains("Free usage exceeded") -> {
                    "Free usage exceeded, subscribe to Go"
                }

                raw.contains("429") -> {
                    nested ?: "Rate limit exceeded. Please try again later."
                }

                else -> {
                    nested ?: raw
                }
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
            val raw =
                (data?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
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
        private fun parseModelRef(model: String): Pair<String, String> = ModelSelection.parse(model, _uiState.value.models)

        /** Qualifies a picker value to "provider/model" using the catalog. */
        private fun qualifyModelRef(model: String): String = ModelSelection.qualify(model, _uiState.value.models)

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
            var value =
                java.math.BigInteger
                    .valueOf(now)
                    .shiftLeft(12)
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
            return "${prefix}_${hex}$tail"
        }

        fun addAttachments(uris: List<Pair<String, String>>) {
            if (uris.isEmpty()) return
            val current = _uiState.value.attachments.toMutableList()
            for ((uri, name) in uris) {
                if (current.none { it.uri == uri }) {
                    val parsed = android.net.Uri.parse(uri)
                    val mime = runCatching { appContext.contentResolver.getType(parsed) }.getOrNull()
                    val size =
                        runCatching {
                            appContext.contentResolver
                                .query(
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
            _uiState.update { current ->
                current.copy(
                    attachments = current.attachments.filter { it.uri != uri },
                )
            }
        }

        fun clearAttachments() {
            _uiState.update { it.copy(attachments = emptyList()) }
        }

        private fun attachmentMime(attachment: Attachment): String =
            attachment.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        /**
         * Reads an attachment and encodes it as a `data:<mime>;base64,…` URL,
         * the portable way to hand a file to a plain OpenCode server (no guard
         * proxy). Capped because base64 inflates the payload ~33% and the whole
         * thing is held in memory for the request.
         */
        private fun attachmentDataUrl(attachment: Attachment): String {
            val size = attachment.size
            if (size != null && size > MAX_EMBED_BYTES) {
                throw java.io.IOException(
                    "Attachment too large to embed: ${attachment.name} " +
                        "(${size / (1024 * 1024)} MB, max ${MAX_EMBED_BYTES / (1024 * 1024)} MB without a guard proxy)",
                )
            }
            val resolver = appContext.contentResolver
            val uri = android.net.Uri.parse(attachment.uri)
            val bytes =
                resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("Attachment could not be read: ${attachment.name}")
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            return "data:${attachmentMime(attachment)};base64,$encoded"
        }

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
                    val input =
                        resolver.openInputStream(uri)
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
            val response =
                api.uploadAttachment(
                    attachment.name,
                    mime,
                    attachmentRequestBody(attachment),
                )
            if (!response.isSuccessful) {
                val detail =
                    com.opencode.android.util.serverErrorMessage(
                        response.errorBody()?.string(),
                        "upload HTTP ${response.code()}",
                    )
                throw java.io.IOException(detail)
            }
            val uploaded =
                response.body()?.takeIf { it.path.isNotBlank() }
                    ?: throw java.io.IOException("Upload returned no path: ${attachment.name}")
            _uiState.update { it.copy(uploadDone = it.uploadDone + 1) }
            return uploaded
        }

        private suspend fun uploadAttachments(attachments: List<Attachment>): List<UploadResponse> =
            coroutineScope {
                // Parallel uploads; awaitAll preserves input order for the prompt.
                attachments
                    .map { attachment ->
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
            val session =
                _uiState.value.session ?: run {
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
            // Client-generated message id (web parity): the SAME id goes into the
            // prompt body and the optimistic row, so the server echoes it and the
            // row dedupes by id instead of by text (text differs for attachments,
            // which made the prompt show twice).
            val now = System.currentTimeMillis()
            val messageId = generateOpenCodeId("msg")
            optimisticEchoIds.add(messageId)
            val optimistic =
                buildOptimisticEcho(
                    sessionId = session.id,
                    messageId = messageId,
                    displayText = displayText,
                    createdAt = now,
                )
            // Capture BEFORE the optimistic echo flips isGenerating to true.
            // Reading the flag after the update made it always true, so every send
            // fired POST /interrupt just before the prompt and could race with (and
            // abort) the request that was being started.
            val wasGenerating = _uiState.value.isGenerating
            _uiState.update { current ->
                current.copy(
                    statusError = null,
                    inputText = "",
                    attachments = emptyList(),
                    messages = current.messages + optimistic,
                    isGenerating = true,
                    pendingPersist = false,
                )
            }

            sendJob =
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
                        val serverModelRef =
                            serverSession?.let {
                                resolveSessionModelRef(it.model, _uiState.value.models)
                            }
                        val effective =
                            effectiveSelection(
                                server =
                                    ServerSelection(
                                        model = serverModelRef,
                                        agent = serverSession?.agent,
                                        variant = serverSession?.model?.variant,
                                    ),
                                ui =
                                    UiSelection(
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
                        // The guard proxy uploads attachments to the server HOST
                        // and the returned paths go into the prompt text. A plain
                        // OpenCode server has no such endpoint, so embed the
                        // files as base64 data-URL `file` parts instead (the web
                        // shape) — no guard required.
                        val useGuardUpload = attachments.isNotEmpty() && backendSession.hasGuard()
                        if (useGuardUpload) {
                            _uiState.update {
                                it.copy(
                                    isUploading = true,
                                    uploadDone = 0,
                                    uploadTotal = attachments.size,
                                )
                            }
                        }
                        val uploaded =
                            if (useGuardUpload) {
                                try {
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
                            } else {
                                emptyList()
                            }
                        _uiState.update { it.copy(isUploading = false, uploadDone = 0, uploadTotal = 0, uploadingUris = emptySet()) }
                        val fileParts =
                            if (attachments.isNotEmpty() && !useGuardUpload) {
                                try {
                                    attachments.map { attachment ->
                                        PromptAsyncPart(
                                            id = generateOpenCodeId("prt"),
                                            type = "file",
                                            mime = attachmentMime(attachment),
                                            filename = attachment.name,
                                            url = attachmentDataUrl(attachment),
                                        )
                                    }
                                } catch (e: Exception) {
                                    AppLog.e(APP_LOG_TAG, "attachment embed failed: ${e.message}")
                                    _uiState.update { current ->
                                        current.copy(
                                            messages = current.messages.filterNot { it.id == optimistic.id },
                                            inputText = rawText,
                                            attachments = attachments,
                                            isGenerating = false,
                                            isUploading = false,
                                            uploadingUris = emptySet(),
                                            statusError = "Attachment failed: ${e.message}",
                                        )
                                    }
                                    return@launch
                                }
                            } else {
                                emptyList()
                            }
                        val finalText = buildPromptText(text, uploaded.map { it.path })
                        if (finalText != displayText) {
                            _uiState.update { current ->
                                current.copy(
                                    messages =
                                        current.messages.map { message ->
                                            if (message.id == optimistic.id) {
                                                message.copy(parts = listOf(Part(type = "text", text = finalText)))
                                            } else {
                                                message
                                            }
                                        },
                                )
                            }
                        }

                        val asyncBody =
                            buildPromptAsyncRequest(
                                messageId = messageId,
                                partId = generateOpenCodeId("prt"),
                                agent = effectiveAgent,
                                providerId = providerId,
                                modelId = modelId,
                                variant = effectiveVariant,
                                finalText = finalText,
                                fileParts = fileParts,
                            )
                        AppLog.d(APP_LOG_TAG) { "sendMessage: prompt_async" }
                        when (val result = promptSender.send(session.id, asyncBody)) {
                            PromptSendResult.Ok -> {
                                Unit
                            }

                            PromptSendResult.Conflict -> {
                                _uiState.update {
                                    it.copy(
                                        isGenerating = false,
                                        guardConflict = true,
                                        statusError = "Selection changed in another client. Review Guard status, then retry.",
                                    )
                                }
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
                        _uiState.update { current ->
                            current.copy(
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
                                inputText =
                                    if (current.inputText.isBlank()) {
                                        rawText
                                    } else {
                                        current.inputText
                                    },
                                error = sendError,
                            )
                        }
                    }
                }
        }

        fun adoptServerSelection() {
            val sessionId = _uiState.value.session?.id ?: return
            viewModelScope.launch {
                try {
                    val response =
                        api.adoptServerSelection(
                            sessionId,
                            selectionGuard.guardRevision(sessionId),
                        )
                    if (!response.isSuccessful) {
                        throw java.io.IOException("adopt HTTP ${response.code()}")
                    }
                    val guard =
                        response.body()
                            ?: throw java.io.IOException("adopt returned no selection")
                    guard.revision.let { selectionGuard.recordGuardRevision(sessionId, it) }
                    _uiState.update { current ->
                        if (current.session?.id != sessionId) {
                            current
                        } else {
                            current.copy(
                                session = current.session.copy(sessionGuard = guard),
                                guardConflict = false,
                                statusError = null,
                            )
                        }
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
                    val response =
                        api.pushGuardSelection(
                            sessionId,
                            selectionGuard.guardRevision(sessionId),
                        )
                    if (!response.isSuccessful) {
                        throw java.io.IOException("push HTTP ${response.code()}")
                    }
                    val guard =
                        response.body()
                            ?: throw java.io.IOException("push returned no selection")
                    guard.revision.let { selectionGuard.recordGuardRevision(sessionId, it) }
                    _uiState.update { current ->
                        if (current.session?.id != sessionId) {
                            current
                        } else {
                            current.copy(
                                session = current.session.copy(sessionGuard = guard),
                                guardConflict = false,
                                statusError = null,
                            )
                        }
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
                    val response =
                        api.removeGuardSelection(
                            sessionId,
                            selectionGuard.guardRevision(sessionId),
                        )
                    if (!response.isSuccessful) {
                        throw java.io.IOException("remove guard HTTP ${response.code()}")
                    }
                    selectionGuard.clearGuardRevision(sessionId)
                    _uiState.update { current ->
                        if (current.session?.id != sessionId) {
                            current
                        } else {
                            current.copy(
                                session = current.session.copy(sessionGuard = null),
                                guardConflict = false,
                            )
                        }
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
            val lastUser =
                _uiState.value.messages.lastOrNull { m ->
                    (m.role ?: m.info?.role) == "user" && m.firstText().isNotBlank()
                } ?: return
            val text = lastUser.firstText()
            val messageId = lastUser.id
            viewModelScope.launch {
                if (messageId != null) {
                    try {
                        api
                            .revertMessage(
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
            // Preserve an unsent draft across the ViewModel recreation a tab
            // switch triggers.
            _uiState.value.session?.id?.let { id ->
                com.opencode.android.data.DraftStore.save(
                    id,
                    _uiState.value.inputText,
                    _uiState.value.attachments,
                )
            }
            conversation.stop()
            sessionJob?.cancel()
            super.onCleared()
        }

        // Models configured in opencode.json but not returned by /api/model
        // --- Session management (Rename/Archive/Delete/Share/Export) ---
        // Mirrors the web "More options" menu: Rename, Share..., Export...,
        // Archive (PATCH time.archived), separator, Delete... (DELETE).

        fun renameSession(
            newTitle: String,
            onDone: () -> Unit,
        ) {
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

        fun revertToMessage(
            messageId: String,
            onDone: (Boolean) -> Unit,
        ) {
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

        fun forkFromMessage(
            messageId: String,
            onDone: (String?) -> Unit,
        ) {
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
                    val newId =
                        result["id"].idString()
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
            _uiState.update {
                it.copy(
                    fileViewer = FileViewerState(path = path, isLoading = true),
                )
            }
            viewModelScope.launch {
                try {
                    val content = api.getFileContent(path)
                    _uiState.update {
                        it.copy(
                            fileViewer = FileViewerState(path = path, content = content.content, isLoading = false),
                        )
                    }
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "openFileViewer failed: ${e.message}")
                    UserMessages.post(R.string.could_not_load_files, "${e.message}")
                    _uiState.update {
                        it.copy(
                            fileViewer = FileViewerState(path = path, isLoading = false, error = e.message),
                        )
                    }
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
                    // V2 has no share endpoint: sharing is controlled by the
                    // server `share` config and an existing link is reported on
                    // the session (`share.url`).
                    val shared = api.shareSession(session.id)
                    onDone(shared.share?.url ?: shared.slug)
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "shareSession failed: ${e.message}")
                    UserMessages.post(R.string.action_failed, "${e.message}")
                    onDone(null)
                }
            }
        }

        /** Child sessions (subagents spawned by this session). */
        fun loadChildSessions(onLoaded: (List<Session>) -> Unit) {
            val sessionId = _uiState.value.session?.id ?: return
            viewModelScope.launch {
                val children =
                    try {
                        api.getSessionChildren(sessionId)
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "getSessionChildren failed: ${e.message}")
                        emptyList()
                    }
                onLoaded(children)
            }
        }

        /**
         * Web "Initialize": asks the server to create/refresh the project's
         * AGENTS.md from the current codebase. Runs a real turn, so it is only
         * triggered explicitly from the session menu.
         */
        fun initProject(onDone: (Boolean) -> Unit) {
            val sessionId = _uiState.value.session?.id ?: return
            viewModelScope.launch {
                val ok =
                    try {
                        api
                            .initSession(sessionId, kotlinx.serialization.json.JsonObject(emptyMap()))
                            .close()
                        true
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "initSession failed: ${e.message}")
                        UserMessages.post(R.string.action_failed, "${e.message}")
                        false
                    }
                if (ok) refreshMessages(sessionId)
                onDone(ok)
            }
        }

        fun exportSession(onDone: (String?) -> Unit) {
            val session = _uiState.value.session ?: return
            viewModelScope.launch {
                try {
                    // V2 export route: session + all messages.
                    val messages = api.exportSession(session.id)
                    // Build a readable transcript from the exported messages.
                    val sb = StringBuilder()
                    sb.appendLine("# Session: ${session.title ?: session.id}")
                    sb.appendLine()
                    for (message in messages) {
                        val text = exportedMessageText(message)
                        if (text.isNotBlank()) {
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

        /** Visible text of an exported message across the V2 part shapes. */
        private fun exportedMessageText(message: com.opencode.android.domain.Message): String {
            val bits = mutableListOf<String>()
            message.text?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
            message.content
                .filter { it.type == "text" }
                .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
                .let { bits.addAll(it) }
            message.parts
                .filter { it.type == "text" }
                .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
                .let { bits.addAll(it) }
            return bits.joinToString("\n")
        }
    }
