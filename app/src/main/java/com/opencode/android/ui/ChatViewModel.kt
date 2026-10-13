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
import com.opencode.android.domain.IMPLEMENTED_BUILTIN_COMMANDS
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
import com.opencode.android.domain.InboxItem
import com.opencode.android.domain.InboxPayload
import com.opencode.android.domain.MessageTime
import com.opencode.android.domain.Session
import com.opencode.android.domain.SkillEntry
import com.opencode.android.data.OutboxItem
import com.opencode.android.data.PromptHistoryEntry
import com.opencode.android.data.PromptHistoryImport
import com.opencode.android.data.PromptHistoryStore
import com.opencode.android.data.OutboxPolicy
import com.opencode.android.data.OutboxStore
import com.opencode.android.ui.race.RaceController
import com.opencode.android.ui.race.RaceMember
import com.opencode.android.ui.race.RacePlan
import com.opencode.android.ui.race.RaceState
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
    // 1-based step of the running turn. A turn can consist of several model
    // calls ("steps"); the status row names the one that is running so a long
    // turn does not read like a single generic wait.
    val stepIndex: Int = 0,
)

@Immutable
data class ChatUiState(
    val session: Session? = null,
    /**
     * Prompts waiting in the session INBOX (web parity: `/prompt` with
     * `delivery:"queue"`). Rendered in the transcript with a "Queued" badge and
     * delivered by the server when the running turn ends.
     */
    val queuedMessages: List<InboxItem> = emptyList(),
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
    // Directory listing state (see loadFiles): the previous list stays
    // visible while loading, and a failure keeps it instead of showing the
    // old contents under a new breadcrumb.
    val filesLoading: Boolean = false,
    val filesError: String? = null,
    val commands: List<CommandEntry> = emptyList(),
    // "/mcp": configured MCP servers (name + status), alphabetical.
    val mcpServers: List<McpEntry> = emptyList(),
    val mcpDialogVisible: Boolean = false,
    /** Prompt history (TUI parity): the composer can recall what was sent before. */
    val historyDialogVisible: Boolean = false,
    val history: List<PromptHistoryEntry> = emptyList(),
    /** True while prompts are imported from the server (backfill). */
    val historyImporting: Boolean = false,
    /**
     * Prompts that could not be sent because the server was unreachable; they are
     * delivered automatically once it is back (offline outbox).
     */
    val outbox: List<OutboxItem> = emptyList(),
    val outboxFlushing: Boolean = false,
    /** Model race: picker visibility, the picked refs and the running comparison. */
    val raceDialogVisible: Boolean = false,
    val racePick: Set<String> = emptySet(),
    val race: RaceState? = null,
    /** "/skills": the picker (TUI: skill menu) and the loaded skill catalog. */
    val skillsDialogVisible: Boolean = false,
    val skills: List<SkillEntry> = emptyList(),
    val skillsLoading: Boolean = false,
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
    // A compaction is running ON THE SERVER for this session, whatever client
    // started it (local /compact, web UI or TUI). Cleared by the compaction's own
    // end event or when the turn ends.
    val isCompacting: Boolean = false,
    // A STEP finished and the live buffers are still on screen waiting for the
    // persisted message to land in `messages` (or for the persist timeout). It is
    // set at every step boundary, not only at turn end; the answer never blanks
    // out and reappears a network round-trip later.
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
    // SSE connection health for the UI. NOTE: this is the GRACE-DEBOUNCED banner
    // value (a blip shorter than the grace window never flips it), while
    // StreamProjection.sseConnected is the raw transport state. Same name, two
    // latencies — do not use this one for logic that must be exact.
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

        init {
            // Offline outbox: retry as soon as Android reports a usable network. The
            // flow is edge-triggered (no replay), which is exactly right here — the
            // manual "Send now" and the flush on session open cover the rest.
            viewModelScope.launch {
                com.opencode.android.data.NetworkMonitor.available.collect { flushOutbox() }
            }
        }

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

                override fun refreshInbox(sessionId: String) = this@ChatViewModel.loadInbox(sessionId)

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
                                isUploading = false,
                                uploadDone = 0,
                                uploadTotal = 0,
                                uploadingUris = emptySet(),
                                // Session-scoped collections: the loaders below only
                                // write NON-empty results, so the previous session's
                                // question cards / todos / diff stayed on screen under
                                // the new title (and "Answer" targeted the old session).
                                pendingQuestions = emptyList(),
                                pendingPermissions = emptyList(),
                                todos = emptyList(),
                                vcsDiff = emptyList(),
                                vcsDiffError = null,
                                contextUsage = null,
                                contextInfo = null,
                                queuedMessages = emptyList(),
                            )
                        }
                        // The outbox is per session; show only this session's items.
                        _uiState.update { it.copy(outbox = OutboxStore.forSession(sessionId)) }
                        // Fence the old refresh: while this load runs, `_uiState.session`
                        // is still the PREVIOUS session, so a late write from it would
                        // pass its own id check and paint A's messages under B.
                        messagesJob?.cancel()
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
                    loadInbox(sessionId)
                    // Offline outbox: show this session's waiting prompts and try to
                    // deliver them (opening a session is a user signal that the server
                    // is expected to be reachable).
                    refreshOutbox()
                    flushOutbox()
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
                            "loadSession: session=${session.id} title=${session.title} dir=${session.directory} agent=${session.agent} model=${session.model?.id}"
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
                        providerDirectory.refreshIfStale(com.opencode.android.data.ProviderDirectory.DEFAULT_TTL_MS)
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
                // Provider can be absent on the session payload; derive it from the
                // CATALOG so the context dialog does not show "—" for a known model
                // and the limit is looked up for the right provider. Splitting a ref
                // at the first slash was wrong for model ids that carry their own
                // namespace (Nvidia: "z-ai/glm-5.3" is not provider "z-ai").
                val sessionProvider =
                    (full.model?.provider ?: session.model?.provider)
                        ?.takeIf { it.isNotBlank() }
                        ?: _uiState.value.models
                            .firstOrNull { m ->
                                m.id == modelId || "${m.providerId}/${m.id}" == modelId
                            }?.providerId
                            ?.takeIf { it.isNotBlank() }
                // The provider catalog loads asynchronously; when it is not ready
                // yet (or was just invalidated on a reconnect) the limit lookup
                // returns null and the ring flashed to 0%. Reuse the last known
                // limit instead of dropping to unknown.
                val previous = _uiState.value.contextInfo
                val info =
                    ContextInfo(
                        provider = sessionProvider,
                        model = modelId,
                        contextLimit =
                            contextLimitFor(sessionProvider, modelId) ?: previous?.contextLimit,
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Structured concurrency: a cancelled load must stay cancelled
                // instead of reporting "could not load".
                throw e
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "loadContext failed: ${e.message}")
                UserMessages.post(R.string.could_not_load_context, "${e.message}")
            }
        }

        // Context window for the SELECTED provider + model. The provider catalog is
        // authoritative (GET /provider → models[..].limit.context), which is what
        // the web UI renders as "Context Limit". Provider-aware: the same model id
        // can be offered by several providers with different windows, so matching
        // on the id alone showed the limit of an arbitrary provider — see
        // `contextLimitFor` in ContextPercent.kt.
        private fun contextLimitFor(
            provider: String?,
            modelId: String,
        ): Long? =
            com.opencode.android.util
                .contextLimitFor(_uiState.value.providerGroups, provider, modelId)

        fun loadFiles(path: String) {
            // Fenced by the requested path: tapping two folders quickly let the LAST
            // response win, so the list showed one directory under the other's
            // breadcrumb. The previous list is kept visible while loading and restored
            // on failure (no silent "old list under the new path").
            filesRequestPath = path
            viewModelScope.launch {
                val previous = _uiState.value.files
                _uiState.update { it.copy(filesLoading = true, filesError = null) }
                try {
                    // /file takes a RELATIVE path + an absolute `directory` base.
                    val files = api.getFiles(path = ".", directory = path)
                    if (filesRequestPath != path) return@launch
                    _uiState.update { it.copy(files = files, filesLoading = false) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (filesRequestPath != path) return@launch
                    AppLog.e(APP_LOG_TAG, "loadFiles failed: ${e.message}")
                    UserMessages.post(R.string.could_not_load_files, "${e.message}")
                    _uiState.update {
                        it.copy(files = previous, filesLoading = false, filesError = e.message)
                    }
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
                    val server =
                        api.getCommands(directory)
                            // A server command that shares a name with a CLIENT
                            // built-in is dropped: otherwise the same typed
                            // "/compact" ran locally or was forwarded to the server
                            // depending on the server's configuration, so one user
                            // action had two behaviours.
                            .filterNot { it.name in IMPLEMENTED_BUILTIN_COMMANDS }
                    // Built-ins first, then the server commands (no name is defined
                    // by both any more).
                    val names = server.map { it.name }.toSet()
                    // Only built-ins this app implements are listed; the rest of
                    // the picker is exactly what the server offers.
                    val commands =
                        BUILTIN_COMMANDS
                            .filterNot { it.name in names }
                            .filter { canRunBuiltin(it.name) } + server
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

        /**
         * Built-ins this app actually IMPLEMENTS. Anything not in here must come
         * from the server, so a client command can never be listed that would do
         * nothing when tapped — listing an unimplemented "/name" (the TUI's own
         * commands like /sessions or /themes have no server endpoint at all) is
         * exactly the "command does nothing" trap.
         */
        private fun canRunBuiltin(name: String): Boolean = name in IMPLEMENTED_BUILTIN_COMMANDS

        /** Runs an app-local built-in. Single dispatch for the picker and typing. */
        fun runBuiltinCommand(name: String) {
            when (name) {
                "compact" -> compactSession()
                "mcp" -> openMcpDialog()
                "skills" -> openSkillsDialog()
                else -> AppLog.w(APP_LOG_TAG) { "builtin /$name is not implemented; ignoring" }
            }
        }

        /**
         * "/skills" — mirrors the TUI: clear the composer, show the skill list, and
         * on pick inject the skill into the session
         * (`POST /api/experimental/session/{id}/skill`). The server then emits
         * `session.skill.activated`, stores a `skill` message and starts a turn, so
         * the injection is visible in the transcript exactly like in the TUI.
         */
        fun openSkillsDialog() {
            _uiState.update { it.copy(skillsDialogVisible = true, inputText = "") }
            loadSkills()
        }

        fun closeSkillsDialog() {
            _uiState.update { it.copy(skillsDialogVisible = false) }
        }

        fun loadSkills() {
            val directory = _uiState.value.session?.directory
            _uiState.update { it.copy(skillsLoading = true) }
            viewModelScope.launch {
                try {
                    val skills =
                        api
                            .getSkills(directory)
                            .sortedWith(compareBy({ it.name ?: it.id }))
                    _uiState.update { it.copy(skills = skills, skillsLoading = false) }
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "loadSkills failed: ${e.message}")
                    _uiState.update { it.copy(skillsLoading = false) }
                    UserMessages.post(R.string.could_not_load_skills, "${e.message}")
                }
            }
        }

        /** Picks a skill: inject it into the session and close the picker. */
        fun activateSkill(skillId: String) {
            val session = _uiState.value.session ?: return
            val name = _uiState.value.skills.firstOrNull { it.id == skillId }?.name ?: skillId
            _uiState.update { it.copy(skillsDialogVisible = false) }
            viewModelScope.launch {
                try {
                    if (api.activateSkill(session.id, skillId, session.directory)) {
                        UserMessages.post(R.string.skill_injected, name)
                        // The skill message + the started turn arrive over SSE; the
                        // reload makes them visible even if the event is in flight.
                        refreshMessages(session.id)
                    } else {
                        UserMessages.post(R.string.skill_injection_failed, name)
                    }
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "activateSkill failed: ${e.message}")
                    UserMessages.post(R.string.skill_injection_failed, "${e.message}")
                }
            }
        }

        // --- model race -----------------------------------------------------

        /**
         * One prompt, several models, side by side — the app can show price and
         * context size, but only a race shows how a model handles *your* repository.
         * Every member runs in its own session inside the same project.
         */
        private val raceController =
            RaceController(
                api = api,
                scope = viewModelScope,
                onState = { race -> _uiState.update { it.copy(race = race) } },
                onError = { message -> UserMessages.post(R.string.race_failed, message) },
            )

        fun openRaceDialog() {
            _uiState.update {
                it.copy(raceDialogVisible = true, racePick = it.racePick.take(0).toSet())
            }
        }

        fun closeRaceDialog() {
            _uiState.update { it.copy(raceDialogVisible = false) }
        }

        fun toggleRacePick(ref: String) {
            _uiState.update { current ->
                val picked = current.racePick
                val next =
                    when {
                        ref in picked -> picked - ref
                        picked.size >= RacePlan.MAX_MODELS -> picked
                        else -> picked + ref
                    }
                current.copy(racePick = next)
            }
        }

        /** Starts the race with the composer's text as the prompt for every member. */
        fun startRace() {
            val prompt = _uiState.value.inputText.trim()
            when (val plan = RacePlan.build(prompt, _uiState.value.racePick.toList(), ::resolveRaceModel)) {
                is RacePlan.Result.Rejected -> {
                    val message =
                        when (plan.reason) {
                            RacePlan.Result.Reason.EMPTY_PROMPT -> R.string.race_need_prompt
                            RacePlan.Result.Reason.TOO_FEW_MODELS -> R.string.race_need_models
                            RacePlan.Result.Reason.UNKNOWN_MODEL -> R.string.race_unknown_model
                        }
                    UserMessages.post(message)
                    return
                }

                is RacePlan.Result.Ok -> {
                    val session = _uiState.value.session ?: return
                    _uiState.update {
                        it.copy(
                            raceDialogVisible = false,
                            inputText = "",
                            attachments = emptyList(),
                        )
                    }
                    raceController.start(
                        prompt = prompt,
                        directory = session.directory,
                        agent = _uiState.value.selectedAgent,
                        members = plan.members,
                    )
                }
            }
        }

        /** Catalog → (label, providerId, modelId); null keeps unknown refs out. */
        private fun resolveRaceModel(ref: String): Triple<String, String, String>? {
            val entry =
                _uiState.value.models.firstOrNull {
                    com.opencode.android.util.sessionModelRef(it.id, it.providerId) == ref
                } ?: return null
            val providerId = entry.providerId ?: return null
            return Triple(entry.name?.takeIf { it.isNotBlank() } ?: entry.id, providerId, entry.id)
        }

        fun stopRace() = raceController.stop()

        fun dismissRace() = raceController.dismiss()

        fun refreshRace() = raceController.refresh()

        // --- prompt history -------------------------------------------------

        /**
         * Opens the history list (newest first, filterable by session).
         *
         * The history only contains what THIS app sent, so an empty list is backfilled
         * from the current session's user messages — the app can then recall prompts that
         * were sent before the feature existed or from the web UI / TUI.
         */
        fun openHistory() {
            _uiState.update {
                it.copy(historyDialogVisible = true, history = PromptHistoryStore.state.value)
            }
            if (PromptHistoryStore.state.value.isEmpty()) importHistoryFromCurrentSession()
        }

        /** Imports the user prompts of the OPEN session (already-loaded messages). */
        fun importHistoryFromCurrentSession() {
            val sessionId = _uiState.value.session?.id ?: return
            val messages = _uiState.value.messages
            if (messages.isEmpty()) return
            scopeImport {
                PromptHistoryStore.mergeImported(
                    PromptHistoryImport.merge(
                        existing = PromptHistoryStore.state.value,
                        messages = messages,
                        sessionId = sessionId,
                    ),
                )
            }
        }

        /**
         * Imports prompts from the recent sessions of this project.
         *
         * Bounded on purpose ([PromptHistoryImport.MAX_SESSIONS] sessions ×
         * [PromptHistoryImport.MESSAGES_PER_SESSION] messages): a full-history import of
         * every session would be a burst of requests on a phone link.
         */
        fun importHistoryFromAllSessions() {
            val directory = _uiState.value.session?.directory ?: return
            if (_uiState.value.historyImporting) return
            val current = _uiState.value.session?.id
            _uiState.update { it.copy(historyImporting = true) }
            viewModelScope.launch {
                var sessions: List<String> = emptyList()
                try {
                    sessions =
                        api
                            .getProjectSessions(directory, roots = true, limit = PromptHistoryImport.MAX_SESSIONS)
                            .mapNotNull { it.id }
                            .take(PromptHistoryImport.MAX_SESSIONS)
                } catch (e: Exception) {
                    AppLog.w(APP_LOG_TAG) { "history import: session list failed: ${e.message}" }
                }
                val targets = (listOfNotNull(current) + sessions).distinct()
                var merged = PromptHistoryStore.state.value
                targets.forEach { sessionId ->
                    try {
                        val messages = api.getMessages(sessionId, PromptHistoryImport.MESSAGES_PER_SESSION)
                        merged = PromptHistoryImport.merge(merged, messages, sessionId)
                    } catch (e: Exception) {
                        AppLog.w(APP_LOG_TAG) { "history import: $sessionId failed: ${e.message}" }
                    }
                }
                val before = PromptHistoryStore.state.value.size
                PromptHistoryStore.mergeImported(merged)
                _uiState.update {
                    it.copy(
                        historyImporting = false,
                        history = PromptHistoryStore.state.value,
                    )
                }
                UserMessages.post(
                    R.string.history_imported,
                    (PromptHistoryStore.state.value.size - before).coerceAtLeast(0),
                )
            }
        }

        /** Runs a tiny store merge + refreshes the dialog. */
        private fun scopeImport(merge: () -> Unit) {
            merge()
            _uiState.update { it.copy(history = PromptHistoryStore.state.value) }
        }

        fun closeHistory() {
            _uiState.update { it.copy(historyDialogVisible = false) }
        }

        /** Inserts a recalled prompt into the composer (the TUI recalls, it does not send). */
        fun useHistoryPrompt(text: String) {
            _uiState.update {
                it.copy(
                    historyDialogVisible = false,
                    inputText = text,
                    statusError = null,
                )
            }
        }

        /** Records a prompt that the server accepted. */
        private fun recordHistory(
            sessionId: String,
            text: String,
        ) {
            if (text.isBlank()) return
            PromptHistoryStore.record(text = text, sessionId = sessionId)
            if (_uiState.value.historyDialogVisible) {
                _uiState.update { it.copy(history = PromptHistoryStore.state.value) }
            }
        }

        // --- offline outbox -------------------------------------------------

        private fun enqueueOutbox(
            sessionId: String,
            text: String,
            agent: String?,
            providerId: String?,
            modelId: String?,
            variant: String?,
        ) {
            OutboxStore.add(
                OutboxItem(
                    id = generateOpenCodeId("msg"),
                    sessionId = sessionId,
                    text = text,
                    agent = agent,
                    providerId = providerId,
                    modelId = modelId,
                    variant = variant,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            refreshOutbox()
        }

        /** Mirrors the store into the UI state (per active session). */
        private fun refreshOutbox() {
            val sessionId = _uiState.value.session?.id ?: return
            _uiState.update { it.copy(outbox = OutboxStore.forSession(sessionId)) }
        }

        /**
         * Delivers the session's outbox in order. Stopping at the first failure keeps
         * the original order — a prompt that depends on the previous one must not jump
         * the queue.
         */
        fun flushOutbox() {
            val session = _uiState.value.session ?: return
            if (_uiState.value.outboxFlushing) return
            val items = OutboxStore.forSession(session.id)
            if (items.isEmpty()) return
            val (providerId, modelId) = parseModelRef(_uiState.value.selectedModel)
            _uiState.update { it.copy(outboxFlushing = true) }
            sendJob =
                viewModelScope.launch {
                    var sent = 0
                    try {
                        for (item in items) {
                            val now = System.currentTimeMillis()
                            if (!OutboxPolicy.shouldAttempt(item, now)) continue
                            val body =
                                buildPromptAsyncRequest(
                                    messageId = generateOpenCodeId("msg"),
                                    partId = generateOpenCodeId("prt"),
                                    agent = item.agent ?: _uiState.value.selectedAgent,
                                    providerId = item.providerId ?: providerId,
                                    modelId = item.modelId ?: modelId,
                                    variant = item.variant ?: _uiState.value.selectedVariant,
                                    finalText = item.text,
                                )
                            val result =
                                try {
                                    promptSender.send(session.id, body)
                                } catch (e: Exception) {
                                    PromptSendResult.Failed(e.message ?: "offline")
                                }
                            when (result) {
                                PromptSendResult.Ok -> {
                                    OutboxStore.remove(item.id)
                                    recordHistory(session.id, item.text)
                                    sent++
                                }

                                PromptSendResult.Conflict -> {
                                    OutboxStore.markAttempt(item.id, now, "Selection changed elsewhere")
                                    break
                                }

                                is PromptSendResult.Failed -> {
                                    OutboxStore.markAttempt(item.id, now, result.message)
                                    // Retryable or not, stop here: the next flush (network
                                    // event, manual tap, session open) tries again.
                                    break
                                }
                            }
                        }
                    } finally {
                        refreshOutbox()
                        _uiState.update { it.copy(outboxFlushing = false) }
                    }
                    if (sent > 0) {
                        UserMessages.post(R.string.outbox_sent, sent)
                        refreshMessages(session.id)
                    }
                }
        }

        /** Drops one waiting prompt. */
        fun discardOutbox(itemId: String) {
            OutboxStore.remove(itemId)
            refreshOutbox()
        }

        /** Drops everything waiting for this session. */
        fun clearOutbox() {
            val sessionId = _uiState.value.session?.id ?: return
            OutboxStore.clearSession(sessionId)
            refreshOutbox()
        }

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

        /** Per-server toggle jobs (see setMcpEnabled). */
        private val mcpToggleJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

        /** Path of the last requested directory listing (see loadFiles). */
        @Volatile
        private var filesRequestPath: String? = null

        /** True when [performSend] failed and its draft was restored (see performRetry). */
        @Volatile
        private var lastFailureWasSend: Boolean = false

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
            // One job PER SERVER: a single shared job meant toggling server B
            // cancelled an unconfirmed connect/disconnect still in flight for A, so
            // A's optimistic status silently flipped back with no request ever landing.
            mcpToggleJobs.remove(name)?.cancel()
            // `var` (not `val`): a local val cannot be referenced inside its own
            // initializer, and the finally block needs to identify its own job.
            var started: Job? = null
            started =
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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // A newer toggle cancelled this one: stay cancellable.
                        throw e
                    } finally {
                        // Remove only THIS job: the unconditional `remove(name)` deleted a
                        // newer toggle's entry (the finally of the cancelled job runs
                        // after the new one registered), so the next toggle found nothing
                        // to cancel and two poll loops wrote `mcpServers` at once.
                        started?.let { mine ->
                            mcpToggleJobs.remove(name, mine)
                            if (mcpToggleJob === mine) mcpToggleJob = null
                        }
                    }
                }
            mcpToggleJob = started
            mcpToggleJobs[name] = started
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

        /**
         * Reads the session INBOX: the prompts the user queued (Ctrl+Enter in the
         * web, the queue button in the composer here).
         */
        fun loadInbox(sessionId: String? = null) {
            val session = _uiState.value.session ?: return
            val id = sessionId ?: session.id
            if (_uiState.value.session?.id != id) return
            viewModelScope.launch {
                try {
                    val items = api.getInbox(id, session.directory)
                    // Fence: the session may have changed while the request ran.
                    if (_uiState.value.session?.id != id) return@launch
                    _uiState.update {
                        // Only user prompts are rendered; synthetic/compaction/move
                        // inbox items are not queued messages.
                        it.copy(queuedMessages = items.filter { item -> item.type == "user" })
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The queue is an extra: a failure must not disturb the chat.
                    AppLog.w(APP_LOG_TAG) { "loadInbox failed: ${e.message}" }
                }
            }
        }

        /** The web's "Remove" on a queued prompt. */
        fun cancelQueued(inboxId: String) {
            val session = _uiState.value.session ?: return
            viewModelScope.launch {
                try {
                    if (!api.cancelInboxItem(session.id, inboxId, session.directory)) {
                        UserMessages.post(R.string.queue_action_failed)
                    }
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "cancelQueued failed: ${e.message}")
                    UserMessages.post(R.string.queue_action_failed)
                } finally {
                    loadInbox(session.id)
                }
            }
        }

        /** The web's "Steer" on a queued prompt: deliver it without waiting. */
        fun steerQueued(inboxId: String) {
            val session = _uiState.value.session ?: return
            viewModelScope.launch {
                try {
                    if (!api.steerInboxItem(session.id, inboxId, session.directory)) {
                        UserMessages.post(R.string.queue_action_failed)
                    }
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "steerQueued failed: ${e.message}")
                    UserMessages.post(R.string.queue_action_failed)
                } finally {
                    loadInbox(session.id)
                }
            }
        }

        /** Queues the composer's text instead of interrupting the running turn. */
        fun queueMessage() = performSend(queued = true)

        /**
         * Hands the composer's text to the RUNNING turn without interrupting it
         * (web: "Steer", tooltip "Send without interrupting"). Verified live: the
         * server accepts the prompt while a turn runs, finishes that turn and then
         * handles the follow-up — no interrupt call is involved.
         */
        fun steerMessage() = performSend(steer = true)

        // "/compact": summarize the session to shrink the context window.
        //
        // Web parity (verified against the running server): the request is
        // `POST /api/session/{id}/compact` with `{"id":"msg_…"}` — a
        // CLIENT-generated id and nothing else (no model). The server echoes that
        // id on the compaction message, which the transcript renders as the web's
        // compaction block ("Session compaction started" + summary +
        // "Session compacted · X in · Y out").
        fun compactSession() {
            val session = _uiState.value.session ?: return
            if (_uiState.value.isCompacting) return
            val compactSessionId = session.id
            val compactionId = generateOpenCodeId("msg")
            _uiState.update { it.copy(isCompacting = true, statusError = null) }
            viewModelScope.launch {
                try {
                    val response =
                        api.summarizeSession(
                            session.id,
                            // Raw path — the same directory value every other
                            // call sends. URL-encoding it made the server look
                            // for a location literally named "%2Fhome%2F…".
                            session.directory,
                            SummarizeRequest(id = compactionId),
                        )
                    if (!response.isSuccessful) {
                        val message =
                            com.opencode.android.util.serverErrorMessage(
                                response.errorBody()?.string(),
                                "Compact failed (HTTP ${response.code()})",
                            )
                        AppLog.e(APP_LOG_TAG, "compactSession failed: HTTP ${response.code()} $message")
                        UserMessages.post(R.string.compact_failed, "HTTP ${response.code()} $message")
                        _uiState.update { current ->
                            if (current.session?.id != compactSessionId) current else current.copy(statusError = message)
                        }
                        return@launch
                    }
                    // The server answers with the pending compaction message.
                    // Showing it right away matches the web, which puts
                    // "Session compaction queued" into the transcript before the
                    // summary exists; the reload below replaces the optimistic
                    // row with the server's copy (summary + usage).
                    val created = response.body()?.data
                    if (created != null) {
                        val optimistic =
                            created.copy(
                                type = "compaction",
                                // The endpoint answers with the inbox item; the
                                // web labels that state "queued".
                                status = created.status ?: "queued",
                                sessionId = created.sessionId ?: compactSessionId,
                            )
                        _uiState.update { current ->
                            if (current.session?.id != compactSessionId) {
                                current
                            } else {
                                current.copy(
                                    messages =
                                        current.messages.filterNot {
                                            (it.id ?: it.info?.id) == (optimistic.id ?: optimistic.info?.id)
                                        } + optimistic,
                                )
                            }
                        }
                    }
                    // Compaction appends a summary + resets the window. The web
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
                    _uiState.update { current ->
                        if (current.session?.id != compactSessionId) current else current.copy(isCompacting = false)
                    }
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
                    UserMessages.post(R.string.could_not_load_permissions, "${e.message}")
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
                if (!ok) {
                    // Was an internal method name ("replyPermission"), which tells
                    // the user nothing.
                    UserMessages.post(R.string.could_not_answer_permission, "the server rejected the reply")
                }
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
                        UserMessages.post(R.string.could_not_load_questions, "${e.message}")
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
            // Stop deduping this request: once it is resolved, a re-ask with the
            // same id (server retry) must be able to notify again, and the entry
            // otherwise lingered until the cap trimmed it.
            com.opencode.android.data.Notifier
                .clearQuestion(requestId)
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
            // Remembered so a FAILED reply can put the card back: dropping it
            // optimistically and never restoring it lost the user's answers.
            val asked = _uiState.value.pendingQuestions.firstOrNull { it.id == requestId }
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
                val failure = sendQuestionReply(session.id, requestId, answers)
                if (failure == null) {
                    // Same symmetry as the permission path: without this the actionable
                    // notification stayed in the shade until a `question.resolved` event
                    // (which the server does not always emit).
                    com.opencode.android.data.Notifier.clearQuestion(requestId)
                    loadPendingQuestions()
                    refreshMessages(session.id)
                    onDone(true)
                    return@launch
                }
                val message = questionReplyMessage(failure)
                AppLog.e(APP_LOG_TAG, "answerQuestion failed: ${failure.tag} ${failure.message}")
                UserMessages.post(R.string.action_failed, message)
                if (failure.tag == "FormAlreadySettledError") {
                    // The card is genuinely obsolete: keep it removed and re-sync.
                    com.opencode.android.data.Notifier.clearQuestion(requestId)
                } else if (asked != null) {
                    // Restore the card so the answer can be retried instead of being
                    // retyped from scratch.
                    _uiState.update { current ->
                        if (current.session?.id != session.id) {
                            current
                        } else if (current.pendingQuestions.any { it.id == asked.id }) {
                            current
                        } else {
                            current.copy(pendingQuestions = current.pendingQuestions + asked)
                        }
                    }
                }
                _uiState.update { current ->
                    if (current.session?.id != session.id) {
                        current
                    } else {
                        current.copy(statusError = message)
                    }
                }
                loadPendingQuestions()
                onDone(false)
            }
        }

        /**
         * Sends a form reply, retrying once with the server's REAL pending form id.
         *
         * The question card may be built from an SSE event whose id is not the form id
         * (`frm_…`); posting that id yielded `FormNotFoundError`, which the UI reported as
         * "the question may have expired" although nothing had expired. The session-scoped
         * route is tried first because the session is always known here.
         */
        private suspend fun sendQuestionReply(
            sessionId: String,
            requestId: String,
            answers: List<List<String>>,
        ): com.opencode.android.data.FormReplyException? {
            val body = QuestionReplyRequest(answers)
            var first: com.opencode.android.data.FormReplyException? =
                try {
                    api.replySessionQuestion(sessionId, requestId, body).close()
                    return null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: com.opencode.android.data.FormReplyException) {
                    e
                } catch (e: Exception) {
                    com.opencode.android.data.FormReplyException(null, e.message ?: "reply failed")
                }
            if (first?.tag == "FormAlreadySettledError") return first
            // Resolve the real pending form and retry ONCE.
            val realId =
                runCatching { api.getSessionQuestions(sessionId).data }
                    .getOrNull()
                    ?.takeIf { it.size == 1 }
                    ?.first()
                    ?.id
                    ?.takeIf { it.isNotBlank() && it != requestId }
            if (realId != null) {
                first =
                    try {
                        api.replySessionQuestion(sessionId, realId, body).close()
                        return null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: com.opencode.android.data.FormReplyException) {
                        e
                    } catch (e: Exception) {
                        com.opencode.android.data.FormReplyException(null, e.message ?: "reply failed")
                    }
            }
            return first
        }

        /** Turns a rejected form reply into something the user can act on. */
        private fun questionReplyMessage(failure: com.opencode.android.data.FormReplyException): String =
            when (failure.tag) {
                "FormAlreadySettledError" -> "This question was already answered or cancelled"
                "FormNotFoundError" -> "This question is no longer pending on the server"
                "FormInvalidAnswerError" -> "The answer was rejected: ${failure.message}"
                else -> "Could not send the answer: ${failure.message}"
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
                    _uiState.update { current ->
                        if (current.session?.id != session.id) {
                            current
                        } else {
                            current.copy(statusError = "Could not reject the question — it may have expired")
                        }
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
                                        // The server names a session from its first
                                        // turn; without copying the title the header
                                        // stayed on the "Session" fallback forever.
                                        // `directory` too: /api/session omits it, and
                                        // the Changes tab/VCS need it.
                                        title = session.title ?: current.session.title,
                                        directory = session.directory ?: current.session.directory,
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
                        Agent(
                            id = p.id,
                            name = p.name?.takeIf { it.isNotBlank() } ?: p.id,
                            description = p.description,
                            mode = p.mode,
                        )
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

        /**
         * Set by [ChatScreen] while the chat is actually visible. The 5 s status poll
         * runs in `viewModelScope` (not lifecycle-bound), so without this it kept
         * issuing requests with the screen off.
         */
        @Volatile
        private var screenVisible: Boolean = true

        fun setScreenVisible(visible: Boolean) {
            screenVisible = visible
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
                        // Backgrounded: stay quiet (the SSE feed already keeps the
                        // running state fresh) instead of polling every 5 s.
                        if (!screenVisible) continue
                        try {
                            // Agent questions can be missed by the SSE stream (the
                            // legacy question.asked bus event is not reliably forwarded
                            // on /global/event). Poll while the turn is running so the
                            // answerable card appears instead of only after the user
                            // aborts the session.
                            if (_uiState.value.isGenerating) {
                                loadPendingQuestions(notifyOnError = false)
                                // Watchdog: a terminal `session.idle` can also be
                                // LOST while the stream looks connected — it is a data
                                // event and the bounded SSE buffer drops the oldest
                                // under a tool storm. The old guard only re-synced while
                                // the stream was down, so the composer stayed on "stop".
                                // One small status request per poll tick while a turn is
                                // running is cheap insurance against that.
                                conversationPort.resyncSessionStatus()
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
            val state = _uiState.value
            val sessionId = state.session?.id
            val title = state.session?.title ?: "OpenCode"
            // The "end summary" deserves the answer itself, not just "finished":
            // the notification then already tells the user what happened, and a
            // tap lands on that answer.
            val last =
                state.messages.lastOrNull {
                    (it.role ?: it.info?.role) == "assistant"
                }
            val snippet =
                (last?.let { com.opencode.android.util.MessageEcho.firstText(it) } ?: "")
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                    .take(180)
            com.opencode.android.data.Notifier.agentFinished(
                title = title,
                text = snippet.ifBlank { "The agent finished this turn" },
                sessionId = sessionId,
                messageId = last?.id ?: last?.info?.id,
                // The background watcher may report the same turn end.
                turnKey = "$sessionId:${last?.id ?: last?.info?.id ?: "turn"}",
            )
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
                UserMessages.post(R.string.could_not_load_messages, "${e.message}")
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
                // "pending" means "the user's choice wins until the server has
                // ACKNOWLEDGED it" (see EffectiveSelection). Clearing it in the
                // finally block regardless of the outcome silently reverted the
                // user's pick to the server value after a failed save.
                var confirmed = false
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
                    confirmed = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "set session model failed: ${e.message}")
                    UserMessages.post(
                        R.string.could_not_save_selection,
                        "${e.message}",
                    )
                } finally {
                    if (confirmed && selectionGuard.isLatestModel(generation)) modelSelectionPending = false
                }
            }
        }

        private fun syncSessionAgent(
            sessionId: String,
            agent: String,
        ) {
            val generation = selectionGuard.beginAgentSync()
            viewModelScope.launch {
                // See syncSessionModel: pending is cleared only once the server
                // confirmed the write.
                var confirmed = false
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
                    confirmed = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "set session agent failed: ${e.message}")
                    UserMessages.post(
                        R.string.could_not_save_selection,
                        "${e.message}",
                    )
                } finally {
                    if (confirmed && selectionGuard.isLatestAgent(generation)) agentSelectionPending = false
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
        /**
         * `delivery` value that asks the server to QUEUE the prompt instead of
         * running it immediately (verified against the live server: Ctrl+Enter in
         * the web sends exactly this).
         */
        private val QUEUE_DELIVERY = "queue"

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
            viewModelScope.launch {
                // ContentResolver lookups block on the (possibly slow) provider, so
                // they run off Main; the result is then applied as ONE `update`
                // transformation. The old code read the attachment list, filled it
                // in and wrote the whole snapshot back — a concurrent
                // removeAttachment/clearAttachments (or a second add) was silently
                // overwritten by the stale snapshot.
                // Session at the time the pick was made (see the guard below).
                val targetSessionId = _uiState.value.session?.id
                val resolved =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        uris.mapNotNull { (uri, name) ->
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
                            Attachment(uri = uri, name = name, mime = mime, size = size)
                        }
                    }
                if (resolved.isEmpty()) return@launch
                _uiState.update { current ->
                    // The ContentResolver lookup runs on IO; if the user switched
                    // sessions meanwhile (the draft re-populates `attachments`), the
                    // files chosen in the OLD session must not appear in the new one.
                    if (current.session?.id != targetSessionId) return@update current
                    val merged = current.attachments.toMutableList()
                    for (attachment in resolved) {
                        if (merged.none { it.uri == attachment.uri }) merged.add(attachment)
                    }
                    current.copy(attachments = merged)
                }
            }
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
        private suspend fun attachmentDataUrl(attachment: Attachment): String {
            val size = attachment.size
            if (size != null && size > MAX_EMBED_BYTES) {
                throw java.io.IOException(
                    "Attachment too large to embed: ${attachment.name} " +
                        "(${size / (1024 * 1024)} MB, max ${MAX_EMBED_BYTES / (1024 * 1024)} MB without a guard proxy)",
                )
            }
            // Reading + base64-encoding up to 20 MB used to run on the main
            // thread (the send path is Main.immediate) and froze the UI with no
            // spinner at all.
            val bytes =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val resolver = appContext.contentResolver
                    val uri = android.net.Uri.parse(attachment.uri)
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw java.io.IOException("Attachment could not be read: ${attachment.name}")
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

        /**
         * Undoes an optimistic echo after the send failed *before* the prompt
         * reached the server (model unavailable, conflict, upload/embed error).
         *
         * Every early exit of [performSend] must use this: dropping only the row
         * (or nothing at all) left the user's message on screen although the
         * server never received it — and, because the composer had already been
         * cleared, the prompt text was lost.
         *
         * Also clears the `optimisticEchoIds` entry (otherwise the set grew for
         * the process lifetime) and re-syncs the conversation mirror's
         * `isGenerating`, which the `_uiState`-only writes used to leave stuck at
         * `true`.
         */
        private fun rollbackOptimisticEcho(
            optimisticId: String,
            rawText: String,
            attachments: List<Attachment>,
            statusError: String,
            /** false when the prompt was saved to the outbox instead. */
            restoreText: Boolean = true,
        ) {
            optimisticEchoIds.remove(optimisticId)
            _uiState.update { current ->
                current.copy(
                    messages = current.messages.filterNot { it.id == optimisticId },
                    // Restore the composer so a failed send is retryable without
                    // retyping; never clobber text the user typed meanwhile.
                    inputText =
                        if (!restoreText) {
                            current.inputText
                        } else if (current.inputText.isBlank()) {
                            rawText
                        } else {
                            current.inputText
                        },
                    attachments =
                        if (!restoreText || current.attachments.isNotEmpty()) {
                            current.attachments
                        } else {
                            attachments
                        },
                    isGenerating = false,
                    isUploading = false,
                    uploadDone = 0,
                    uploadTotal = 0,
                    uploadingUris = emptySet(),
                    statusError = statusError,
                    error = statusError,
                )
            }
            // Mirror + `_uiState` in one step (see the doc above).
            conversation.seedGenerating(false)
        }

        /**
         * Posts the composer's text with `delivery:"queue"` — exactly what Ctrl+Enter
         * does in the web composer: the server keeps it in the session inbox and
         * runs it once the current turn is done.
         */
        private fun queuePrompt(
            session: Session,
            rawText: String,
        ) {
            val (providerId, modelId) = parseModelRef(_uiState.value.selectedModel)
            val messageId = generateOpenCodeId("msg")
            // Optimistic queue card: the round trip for the inbox reload would
            // otherwise leave the composer empty with nothing visible. The reload
            // afterwards reconciles by id (same id: the server echoes it).
            val optimistic =
                InboxItem(
                    id = messageId,
                    sessionId = session.id,
                    type = "user",
                    time = MessageTime(created = System.currentTimeMillis()),
                    payload = InboxPayload(text = rawText),
                    delivery = QUEUE_DELIVERY,
                )
            _uiState.update {
                it.copy(
                    inputText = "",
                    attachments = emptyList(),
                    statusError = null,
                    queuedMessages = it.queuedMessages.filterNot { item -> item.id == messageId } + optimistic,
                )
            }
            sendJob =
                viewModelScope.launch {
                    try {
                        val body =
                            buildPromptAsyncRequest(
                                messageId = messageId,
                                partId = generateOpenCodeId("prt"),
                                agent = _uiState.value.selectedAgent,
                                providerId = providerId,
                                modelId = modelId,
                                variant = _uiState.value.selectedVariant,
                                finalText = rawText,
                                delivery = QUEUE_DELIVERY,
                            )
                        when (val result = promptSender.send(session.id, body)) {
                            PromptSendResult.Ok -> {
                                recordHistory(session.id, rawText)
                                UserMessages.post(R.string.queued_message_added)
                            }

                            PromptSendResult.Conflict -> {
                                UserMessages.post(R.string.queue_action_failed)
                                dropOptimisticQueued(messageId)
                                restoreQueuedInput(rawText)
                            }

                            is PromptSendResult.Failed -> {
                                AppLog.e(APP_LOG_TAG, "queueMessage failed: ${result.message}")
                                UserMessages.post(R.string.could_not_send, result.message)
                                dropOptimisticQueued(messageId)
                                restoreQueuedInput(rawText)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "queueMessage failed: ${e.message}")
                        UserMessages.post(R.string.could_not_send, "${e.message}")
                        dropOptimisticQueued(messageId)
                        restoreQueuedInput(rawText)
                    } finally {
                        // The server owns the queue: read it back (delivery may keep
                        // the item queued while the turn runs).
                        loadInbox(session.id)
                    }
                }
        }

        /** Removes the optimistic queue card of a send that did not reach the server. */
        private fun dropOptimisticQueued(messageId: String) {
            _uiState.update { current ->
                current.copy(queuedMessages = current.queuedMessages.filterNot { it.id == messageId })
            }
        }

        /** Gives the text back to the composer when queueing failed. */
        private fun restoreQueuedInput(rawText: String) {
            _uiState.update {
                if (it.inputText.isBlank()) it.copy(inputText = rawText) else it
            }
        }

        private fun performSend(
            queued: Boolean = false,
            steer: Boolean = false,
        ) {
            val session =
                _uiState.value.session ?: run {
                    AppLog.e(APP_LOG_TAG, "sendMessage: no session")
                    UserMessages.post(R.string.could_not_send, appContext.getString(R.string.no_session_open))
                    return
                }
            val rawText = _uiState.value.inputText.trim()
            val attachments = _uiState.value.attachments
            AppLog.d(APP_LOG_TAG) {
                // Length only: the prompt itself is user content.
                "sendMessage: chars=${rawText.length} attachments=${attachments.size} session=${session.id} generating=${_uiState.value.isGenerating}"
            }
            if (rawText.isEmpty() && attachments.isEmpty()) return

            // A "/name args" line for a SERVER command runs through the command
            // endpoint, where the server expands the command's template — exactly
            // what the TUI does. Posting it as a prompt would just send the literal
            // text, which is why commands appeared to do nothing.
            // Client built-ins typed by hand run locally, exactly like picking
            // them from the "/" list; posting "/compact" as a prompt would do
            // nothing.
            com.opencode.android.ui.session
                .parseBuiltinCommand(rawText, _uiState.value.commands)
                ?.takeIf { canRunBuiltin(it) }
                ?.let { name ->
                    _uiState.update { it.copy(inputText = "", attachments = emptyList()) }
                    runBuiltinCommand(name)
                    return
                }
            com.opencode.android.ui.session
                .parseSlashCommand(rawText, _uiState.value.commands)
                ?.let { command ->
                    _uiState.update { it.copy(inputText = "", attachments = emptyList(), statusError = null) }
                    sendJob =
                        viewModelScope.launch {
                            try {
                                AppLog.d(APP_LOG_TAG) {
                                    "runCommand: /${command.name} args=${command.arguments.length} session=${session.id}"
                                }
                                if (!api.runCommand(session.id, command.name, command.arguments, session.directory)) {
                                    AppLog.e(APP_LOG_TAG, "runCommand rejected: /${command.name}")
                                    UserMessages.post(R.string.action_failed, "/${command.name} was rejected")
                                    // Put the line back so the user does not have to
                                    // retype it (same rule as a failed send).
                                    _uiState.update {
                                        it.copy(
                                            statusError = "Command /${command.name} was rejected",
                                            inputText = if (it.inputText.isBlank()) rawText else it.inputText,
                                        )
                                    }
                                } else {
                                    refreshMessages(session.id)
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLog.e(APP_LOG_TAG, "runCommand failed: ${e.message}")
                                UserMessages.post(R.string.action_failed, "${e.message}")
                                _uiState.update {
                                    it.copy(
                                        statusError = e.message,
                                        inputText = if (it.inputText.isBlank()) rawText else it.inputText,
                                    )
                                }
                            }
                        }
                    return
                }

            // A QUEUED prompt never interrupts and never echoes locally: the web
            // keeps it out of the transcript as a normal message and instead shows
            // it from the session INBOX (badge "Queued") until the server delivers
            // it. Attachments are not supported on the queue path — the queued item
            // would have to carry the base64 parts through the inbox — so the user
            // is asked to send directly instead of losing the files silently.
            if (queued) {
                if (attachments.isNotEmpty()) {
                    UserMessages.post(R.string.queue_attachments_unsupported)
                    return
                }
                queuePrompt(session, rawText)
                return
            }

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
                    // A STEER goes into the running turn: interrupting or clearing the
                    // live buffers would kill exactly what the user wants to keep
                    // watching.
                    if (!steer) {
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
                    } else {
                        AppLog.d(APP_LOG_TAG) { "sendMessage: steer into the running turn" }
                    }
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
                        // `isKnownMissing` (not `isAvailable`): an unloaded catalog
                        // must not cancel a valid send.
                        if (providerDirectory.isKnownMissing(providerId, modelId)) {
                            val errorMsg = "Model $providerId/$modelId not available on server"
                            AppLog.e(APP_LOG_TAG, errorMsg)
                            UserMessages.post(R.string.could_not_send, errorMsg)
                            rollbackOptimisticEcho(messageId, rawText, attachments, errorMsg)
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
                                    rollbackOptimisticEcho(
                                        messageId,
                                        rawText,
                                        attachments,
                                        "Attachment upload failed: ${e.message}",
                                    )
                                    return@launch
                                }
                            } else {
                                emptyList()
                            }
                        _uiState.update { it.copy(isUploading = false, uploadDone = 0, uploadTotal = 0, uploadingUris = emptySet()) }
                        val fileParts =
                            if (attachments.isNotEmpty() && !useGuardUpload) {
                                try {
                                    // Same visible progress as the upload path.
                                    _uiState.update {
                                        it.copy(
                                            isUploading = true,
                                            uploadDone = 0,
                                            uploadTotal = attachments.size,
                                        )
                                    }
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
                                    rollbackOptimisticEcho(
                                        messageId,
                                        rawText,
                                        attachments,
                                        "Attachment failed: ${e.message}",
                                    )
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
                                rollbackOptimisticEcho(
                                    messageId,
                                    rawText,
                                    attachments,
                                    "Selection changed in another client. Review Guard status, then retry.",
                                )
                                _uiState.update { it.copy(guardConflict = true) }
                                return@launch
                            }

                            is PromptSendResult.Failed -> {
                                // The legacy endpoint has no file parts. Falling back
                                // with attachments present would silently drop the files.
                                if (attachments.isNotEmpty()) {
                                    AppLog.e(APP_LOG_TAG, "sendMessage: attachment prompt failed: ${result.message}")
                                    throw java.io.IOException(result.message)
                                }
                                // Fallback to the legacy prompt endpoint. The failure is
                                // reported only if THIS also fails: announcing "could not
                                // send" while the retry succeeds told the user the opposite
                                // of what happened.
                                AppLog.e(APP_LOG_TAG, "sendMessage: prompt_async failed, fallback: ${result.message}")
                                try {
                                    api.sendPrompt(
                                        session.id,
                                        PromptRequest(
                                            prompt = PromptInput(text = finalText),
                                            agent = effectiveAgent,
                                            // QUALIFIED ref: the adapter parses "provider/model"
                                            // to call setModel; a bare catalog id parsed to
                                            // null, so the legacy fallback silently kept the
                                            // PREVIOUS model.
                                            model =
                                                modelId
                                                    .takeIf { it.isNotBlank() }
                                                    ?.let { id ->
                                                        if (providerId.isBlank()) {
                                                            id
                                                        } else {
                                                            "$providerId/$id"
                                                        }
                                                    },
                                        ),
                                    )
                                } catch (fallbackError: Exception) {
                                    UserMessages.post(R.string.could_not_send, fallbackError.message ?: result.message)
                                    throw fallbackError
                                }
                            }
                        }
                        lastFailureWasSend = false
                        _uiState.update { it.copy(guardConflict = false) }
                        AppLog.d(APP_LOG_TAG) { "sendMessage: API ok" }
                        recordHistory(session.id, rawText)
                        refreshMessages(session.id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // A session switch cancels this job. The rollback must still
                        // run: otherwise the optimistic id stayed in the (unbounded)
                        // set and the prompt text the composer already cleared was
                        // lost. The echo row itself is dropped with the conversation.
                        optimisticEchoIds.remove(messageId)
                        // The upload flags too: a switch during an attachment upload
                        // left `isUploading` true, which keeps the send button disabled
                        // (and the spinner spinning) for good.
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                uploadDone = 0,
                                uploadTotal = 0,
                                uploadingUris = emptySet(),
                            )
                        }
                        throw e
                    } catch (e: Exception) {
                        AppLog.e(APP_LOG_TAG, "sendMessage ERROR: ${e.message}", e)
                        // A CONNECTIVITY failure is queued in the offline outbox: on a
                        // phone the link is often gone for a moment, and retyping the
                        // prompt later was the old (bad) answer. A real rejection
                        // (HTTP 4xx, conflict, missing model) is still reported.
                        val retryable =
                            attachments.isEmpty() &&
                                OutboxPolicy.isRetryable(e::class.java.name, e.message)
                        lastFailureWasSend = true
                        rollbackOptimisticEcho(
                            messageId,
                            rawText,
                            attachments,
                            e.message ?: "Message could not be sent",
                            restoreText = !retryable,
                        )
                        if (retryable) {
                            // The selection is read from the UI state: the try-block's
                            // locals are out of scope here.
                            val selection = _uiState.value
                            val (savedProvider, savedModel) = parseModelRef(selection.selectedModel)
                            enqueueOutbox(
                                sessionId = session.id,
                                text = rawText,
                                agent = selection.selectedAgent,
                                providerId = savedProvider,
                                modelId = savedModel,
                                variant = selection.selectedVariant,
                            )
                            UserMessages.post(
                                R.string.outbox_saved,
                                OutboxStore.forSession(session.id).size,
                            )
                        } else {
                            UserMessages.post(R.string.action_failed, "${e.message}")
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
                    _uiState.update {
                        it.copy(
                            statusError =
                                appContext.getString(R.string.could_not_adopt_guard, e.message ?: ""),
                        )
                    }
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
                    _uiState.update {
                        it.copy(
                            statusError =
                                appContext.getString(R.string.could_not_push_guard, e.message ?: ""),
                        )
                    }
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
                    _uiState.update {
                        it.copy(
                            statusError =
                                appContext.getString(R.string.could_not_remove_guard, e.message ?: ""),
                        )
                    }
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
            // OR, not AND: rollbackOptimisticEcho restores the text OR the
            // attachments (each only when it is currently empty), so a text-only or
            // attachment-only failure never met the "&&" condition and Retry only
            // reloaded the session.
            // Only re-send when the LAST failure actually was a send. The old check
            // used "the composer is not empty" as a proxy, so a banner from a failed
            // session LOAD plus text the user had typed meanwhile was sent instead of
            // reloading the session.
            if (lastFailureWasSend) {
                lastFailureWasSend = false
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
                    // Session-guard: the user can switch sessions while the rename
                    // is in flight. Writing the (old session's) payload unchecked
                    // replaced the NEW session's id/directory/model/title, so the
                    // header, Changes tab and VCS pointed at the previous session.
                    _uiState.update { current ->
                        if (current.session?.id != session.id) current else current.copy(session = updated)
                    }
                    onDone()
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "renameSession failed: ${e.message}")
                    UserMessages.post(R.string.could_not_rename, "${e.message}")
                }
            }
        }

        /**
         * Hides the session locally and leaves the chat.
         *
         * V2 has no server-side archive (`PATCH /api/session/{id}` only takes a
         * title), so the previous call changed NOTHING while the UI closed the chat —
         * the session reappeared on the next reload. The id now goes into the local
         * archived set, with an Undo action in the Snackbar.
         */
        fun archiveSession(onDone: () -> Unit) {
            val session = _uiState.value.session ?: return
            com.opencode.android.data.ArchivedSessionsStore.archive(session.id)
            UserMessages.postWithAction(
                R.string.session_archived,
                actionLabel = "Undo",
                onAction = { com.opencode.android.data.ArchivedSessionsStore.unarchive(session.id) },
            )
            onDone()
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
                    UserMessages.post(R.string.restore_failed, "${e.message}")
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
                    if (newId == null) {
                        // The server DID fork (HTTP success) but the id is in a shape we
                        // do not parse: reporting "Fork failed" claimed the opposite of
                        // what happened and left the new session unfindable.
                        AppLog.w(APP_LOG_TAG) { "forkFromMessage: no id in the response" }
                        UserMessages.post(R.string.fork_created_unknown_id)
                        // Refresh the lists so the new session shows up somewhere.
                        refreshMessages(session.id)
                    }
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
                    // Fenced: a slower earlier fetch must not overwrite the file the
                    // user opened last, and must not resurrect a closed viewer.
                    if (_uiState.value.fileViewer?.path != path) return@launch
                    _uiState.update {
                        it.copy(
                            fileViewer = FileViewerState(path = path, content = content.content, isLoading = false),
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (_uiState.value.fileViewer?.path != path) return@launch
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
            val sessionId = _uiState.value.session?.id
            if (sessionId == null) {
                // Without a session the callback used to never fire, so the menu item
                // did nothing at all.
                onLoaded(emptyList())
                return
            }
            viewModelScope.launch {
                val children =
                    try {
                        api.getSessionChildren(sessionId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A failed load looked identical to "this session has no
                        // subagents".
                        AppLog.e(APP_LOG_TAG, "getSessionChildren failed: ${e.message}")
                        UserMessages.post(R.string.could_not_load_subagents, "${e.message}")
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
