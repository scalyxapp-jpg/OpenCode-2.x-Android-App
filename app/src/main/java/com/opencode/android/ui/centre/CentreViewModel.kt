package com.opencode.android.ui.centre

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.data.AuditEvent
import com.opencode.android.data.AuditLogStore
import com.opencode.android.data.BackendSession
import com.opencode.android.data.CentralActions
import com.opencode.android.data.OpenCodeApi
import com.opencode.android.data.OutboxStore
import com.opencode.android.data.PermissionAnswer
import com.opencode.android.data.ProviderDirectory
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.SummarizeRequest
import com.opencode.android.ui.session.SessionStatusReducer
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.contextLimitFor
import com.opencode.android.util.OpenCodeIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/** One line of the situational header. */
@Immutable
data class CentreSummary(
    val running: Int = 0,
    val needsYou: Int = 0,
    val costToday: Double? = null,
    /** `+45% vs. 7-day average`, when a baseline exists. */
    val trend: String? = null,
)

/** What the Centre knows about the server it is driving. */
@Immutable
data class CentreServer(
    val version: String? = null,
    val mcpConnected: Int = 0,
    val mcpTotal: Int = 0,
    val skills: Int = 0,
    val plugins: Int = 0,
    /** Branch per scanned project directory. */
    val branches: List<Pair<String, String>> = emptyList(),
)

@Immutable
data class CentreUiState(
    val loading: Boolean = false,
    val rows: List<CentreRow> = emptyList(),
    val needs: List<CentreNeed> = emptyList(),
    /** Derived statements about the current state, most urgent first. */
    val insights: List<Insight> = emptyList(),
    val summary: CentreSummary = CentreSummary(),
    val server: CentreServer = CentreServer(),
    val costToday: Double? = null,
    val costBaselinePerDay: Double? = null,
    val sessionsToday: Int? = null,
    val promptsToday: Int? = null,
    val stepsToday: Int? = null,
    val audit: List<AuditEvent> = emptyList(),
    /** One-tap prompts offered per session (from the server's command list). */
    val promptChips: List<String> = emptyList(),
    val stopping: Boolean = false,
    val message: String? = null,
) {
    val runningCount: Int get() = rows.count { it.running || it.retrying }
}

/**
 * The Central Intelligence: gathers the server's signals, derives what is worth acting on, and
 * performs the actions through the ONE central action layer ([CentralActions]).
 *
 * Live-ness comes from the global event feed (the same `session.status` events the session list
 * uses), so a turn started by the web or the TUI shows up here at once and flips back the moment
 * it ends. Everything else is one snapshot per visit/refresh — deliberately no polling: the
 * Centre is a picture, not a process.
 */
@HiltViewModel
class CentreViewModel
    @Inject
    constructor(
        private val apiProvider: Provider<OpenCodeApi>,
        private val backendSession: BackendSession,
        private val providerDirectory: ProviderDirectory,
    ) : ViewModel() {
        private val api: OpenCodeApi get() = apiProvider.get()

        private val _state = MutableStateFlow(CentreUiState())
        val state: StateFlow<CentreUiState> = _state.asStateFlow()

        private val runningIds = MutableStateFlow<Set<String>>(emptySet())
        private val retryingIds = MutableStateFlow<Set<String>>(emptySet())

        private var feedJob: Job? = null

        /** Sessions the server no longer knows; remembered so they are probed once. */
        private val knownGone = mutableSetOf<String>()

        /** `(fetchedAt, costOf7Days)` of the last baseline fetch. */
        private var baselineCache: Pair<Long, Double?>? = null

        /**
         * The central action layer. Every write the Centre performs goes through it, so a tap
         * here, in a notification or in the chat has identical guards, audit entries and error
         * wording.
         */
        private val actions =
            CentralActions(
                interrupt = { sessionId -> api.interrupt(sessionId) },
                steer = { sessionId, inboxId -> api.steerInboxItem(sessionId, inboxId, currentDirectoryFor(sessionId)) },
                compact = { sessionId, directory ->
                    api.summarizeSession(sessionId, directory, SummarizeRequest(id = OpenCodeIds.msg()))
                    Unit
                },
                handDecisionOver = { sessionId, text, description, directory ->
                    api.injectSynthetic(sessionId, text, description, directory)
                },
                replyPermission = { sessionId, requestId, answer ->
                    // Same helper the chat and the notification action use: it resolves the
                    // global vs. session-scoped route.
                    com.opencode.android.data.replyPermission(api, sessionId, requestId, answer)
                },
                deleteSession = { sessionId -> api.deleteSession(sessionId) },
                sendPrompt = { sessionId, text, delivery ->
                    // Minimal body: the server uses the session's own agent/model when they are
                    // absent, exactly like the chat's plain "send".
                    val body =
                        com.opencode.android.domain.PromptAsyncRequest(
                            messageID = OpenCodeIds.msg(),
                            delivery = delivery,
                            parts =
                                listOf(
                                    com.opencode.android.domain.PromptAsyncPart(
                                        id = OpenCodeIds.part(),
                                        type = "text",
                                        text = text,
                                    ),
                                ),
                        )
                    api.sendPromptAsync(sessionId, body).isSuccessful
                },
            )

        init {
            // Audit events are written by other screens too; mirror the store.
            viewModelScope.launch {
                AuditLogStore.state.collect { events -> _state.update { it.copy(audit = events) } }
            }
            viewModelScope.launch {
                // A changed outbox always means "needs you" changed, and that list is derived
                // from several sources at once - so reload instead of patching one of them.
                OutboxStore.state.collect { refresh() }
            }
            // Live running set: the feed's status events, folded exactly like the home list.
            feedJob =
                viewModelScope.launch {
                    runCatching {
                        com.opencode.android.data.SseClient
                            .events(backendSession.currentBaseUrl(), "")
                            .collect { event ->
                                val reduced =
                                    SessionStatusReducer.reduce(
                                        runningIds.value,
                                        retryingIds.value,
                                        event,
                                    ) ?: return@collect
                                runningIds.value = reduced.first
                                retryingIds.value = reduced.second
                                applyFold()
                            }
                    }.onFailure {
                        AppLog.w(APP_LOG_TAG) { "centre: status feed ended (${it.message})" }
                    }
                }
            refresh()
        }

        /** Reloads the whole picture. */
        fun refresh() {
            viewModelScope.launch {
                _state.update { it.copy(loading = true) }
                runCatching {
                    val sessions = api.getSessions(limit = 50, order = "desc").data
                    // Budget: three projects is what a phone can act on; every extra directory costs a
                    // permission request, a form request and a status request.
                    val directories = (CentrePolicy.directoriesToScan(sessions, limit = SCAN_DIRECTORY_LIMIT) + "").distinct()
                    val statuses: Map<String, SessionStatus> =
                        runCatching { api.getSessionStatuses(directories.firstOrNull().orEmpty()) }
                            .getOrDefault(emptyMap())

                    // Everything below is independent - fetch it in parallel. A failing part
                    // must not blank the rest, so each one defaults to empty.
                    // Everything below is independent - fetch it in parallel. A failing part
                    // must not blank the rest, so each one defaults to empty.
                    val extras =
                        coroutineScope {
                            val permissions =
                                async {
                                    directories
                                        .flatMap { directory ->
                                            runCatching { api.getPermissions(directory) }.getOrDefault(emptyList())
                                        }.distinctBy { it.id ?: it.hashCode() }
                                }
                            val questions =
                                async {
                                    directories
                                        .flatMap { directory ->
                                            runCatching { api.getQuestionRequests(directory).data }
                                                .getOrDefault(emptyList())
                                        }.distinctBy { it.id }
                                }
                            val mcp = async { runCatching { api.getMcpServers() }.getOrDefault(emptyMap()) }
                            val skills = async { runCatching { api.getSkills().size }.getOrDefault(0) }
                            val plugins = async { runCatching { api.getPlugins().size }.getOrDefault(0) }
                            val commands = async { runCatching { api.getCommands() }.getOrDefault(emptyList()) }
                            val today = async { runCatching { api.getStats(fromMs = startOfToday()) }.getOrNull() }
                            val week =
                                async {
                                    // The 7-day baseline moves slowly: caching it keeps a refresh
                                    // from spending a request on the same number every time.
                                    val cached = baselineCache
                                    if (cached != null && System.currentTimeMillis() - cached.first < BASELINE_TTL_MS) {
                                        com.opencode.android.domain.SessionStats(cost = cached.second)
                                    } else {
                                        val fetched =
                                            runCatching { api.getStats(fromMs = startOfToday() - SEVEN_DAYS_MS) }
                                                .getOrNull()
                                        baselineCache = System.currentTimeMillis() to fetched?.cost
                                        fetched
                                    }
                                }
                            // Projects: branch + uncommitted work in one pass per directory.
                            // `getVcsStatus` is the CHEAP route (per-file counts, no patches) -
                            // the diff route would ship megabytes for the same answer.
                            val projects =
                                async {
                                    CentrePolicy.directoriesToScan(sessions, limit = SCAN_DIRECTORY_LIMIT)
                                        .map { directory ->
                                            val branch =
                                                runCatching { api.getVcs(directory).branch }
                                                    .getOrNull()
                                                    ?.takeIf { it.isNotBlank() }
                                            val files =
                                                runCatching { api.getVcsStatus(directory) }
                                                    .getOrDefault(emptyList())
                                            Projects(
                                                branch = branch?.let { listOf(directory to it) }.orEmpty(),
                                                dirty =
                                                    CentrePolicy.dirtyProjectLabel(directory, branch, files)
                                                        ?.let { label ->
                                                            listOf(
                                                                DirtyProject(
                                                                    label = label,
                                                                    // The newest session of this project: the
                                                                    // Changes tab lives inside a session, and
                                                                    // the list is ordered newest first.
                                                                    targetSessionId =
                                                                        sessions
                                                                            .firstOrNull {
                                                                                (it.directory ?: it.path) == directory
                                                                            }?.id,
                                                                ),
                                                            )
                                                        }.orEmpty(),
                                            )
                                        }
                                }
                            // Server queues: only a running session can hold one, and a queue is
                            // exactly "prompts waiting behind the turn".
                            val queued =
                                async {
                                    sessions
                                        .filter { statuses[it.id]?.type == "busy" }
                                        .take(MAX_QUEUE_PROBES)
                                        .map { session ->
                                            session.id to
                                                runCatching { api.getInbox(session.id, session.directory) }
                                                    .getOrDefault(emptyList())
                                                    .count { it.type == "user" }
                                        }.toMap()
                                }
                            Extras(
                                permissions = permissions.await(),
                                questions = questions.await(),
                                mcp = mcp.await(),
                                skills = skills.await(),
                                plugins = plugins.await(),
                                commands = commands.await(),
                                today = today.await(),
                                week = week.await(),
                                projects =
                                    projects
                                        .await()
                                        .fold(Projects()) { acc, next ->
                                            Projects(
                                                branch = acc.branch + next.branch,
                                                dirty = acc.dirty + next.dirty,
                                            )
                                        },
                                queued = queued.await(),
                            )
                        }

                    runningIds.value = statuses.filterValues { it.type == "busy" }.keys
                    retryingIds.value = statuses.filterValues { it.type == "retry" }.keys

                    val outbox =
                        OutboxStore.state.value
                            .groupingBy { it.sessionId }
                            .eachCount()
                    val titles = sessions.associate { it.id to it.title.orEmpty() }
                    val allNeeds = CentrePolicy.needs(extras.permissions, extras.questions, outbox, titles)

                    // A need can only be acted on through its session; a deleted session leaves
                    // its pending forms behind on the server, so those rows opened a 404 - probe
                    // and drop them. A failed probe keeps the row: never hide what might be
                    // reachable.
                    // Known-gone sessions are remembered: re-probing them on every refresh was
                    // one request per orphan, every time.
                    val missing = mutableSetOf<String>()
                    allNeeds
                        .map { it.sessionId }
                        .distinct()
                        .take(MAX_SESSION_PROBES)
                        .forEach { sessionId ->
                            if (sessionId in knownGone) {
                                missing += sessionId
                                return@forEach
                            }
                            val gone =
                                runCatching { api.getSession(sessionId) }
                                    .exceptionOrNull()
                                    ?.let { com.opencode.android.util.isGoneError(it) } == true
                            if (gone) {
                                knownGone += sessionId
                                missing += sessionId
                            }
                        }
                    val needs = CentrePolicy.withoutMissing(allNeeds, missing)

                    // Context windows: the session list has the tokens, the provider catalog has
                    // the window size - no extra request per session.
                    val catalog =
                        when (val directoryState = providerDirectory.state.value) {
                            is ProviderDirectory.State.Ready -> directoryState.providers
                            else -> emptyList()
                        }
                    val rows =
                        CentrePolicy
                            .rows(sessions, statuses)
                            .map { row ->
                                val session = sessions.firstOrNull { it.id == row.sessionId }
                                row.copy(
                                    tokenLimit =
                                        contextLimitFor(
                                            catalog,
                                            session?.model?.provider ?: session?.model?.providerID,
                                            session?.model?.model ?: session?.model?.modelID ?: session?.model?.id,
                                        ) ?: 0L,
                                    queueDepth = extras.queued[row.sessionId] ?: 0,
                                )
                            }

                    val weekCost = extras.week?.cost
                    val baseline =
                        weekCost?.takeIf { it > 0.0 }?.let { total ->
                            // The window includes today; subtract it so "the previous days'
                            // average" is really the previous days.
                            ((total - (extras.today?.cost ?: 0.0)) / (SEVEN_DAYS_MS / DAY_MS).toDouble())
                                .takeIf { it > 0.0 }
                        }
                    val mcpPair = CentrePolicy.mcpSummary(extras.mcp)
                    val signals =
                        CentreSignals(
                            running =
                                rows.filter { it.running || it.retrying }.map { row ->
                                    RunningSignal(
                                        sessionId = row.sessionId,
                                        title = row.title,
                                        lastActivityMs = row.updatedAt,
                                        retrying = row.retrying,
                                        retryAttempt = row.retryAttempt,
                                        retryMessage = row.retryMessage,
                                        queueDepth = row.queueDepth,
                                        tokens = row.tokens,
                                        tokenLimit = row.tokenLimit,
                                        cost = row.cost,
                                    )
                                },
                            needsYou = needs.size,
                            orphaned = allNeeds.size - needs.size,
                            outboxWaiting = OutboxStore.state.value.size,
                            costToday = extras.today?.cost,
                            costBaselinePerDay = baseline,
                            mcpTotal = mcpPair.second,
                            mcpConnected = mcpPair.first,
                            dirtyProjects = extras.projects.dirty,
                        )

                    _state.update { current ->
                        current.copy(
                            loading = false,
                            rows = rows,
                            needs = needs,
                            insights = CentreInsights.evaluate(signals),
                            summary =
                                CentreSummary(
                                    running = rows.count { it.running || it.retrying },
                                    needsYou = needs.size,
                                    costToday = extras.today?.cost,
                                    trend = CentrePolicy.costTrend(extras.today?.cost, baseline),
                                ),
                            server =
                                CentreServer(
                                    // The server's version is shown by Home's status popup; the
                                    // Centre does not duplicate it (no cheap field on this API).
                                    version = null,
                                    mcpConnected = mcpPair.first,
                                    mcpTotal = mcpPair.second,
                                    skills = extras.skills,
                                    plugins = extras.plugins,
                                    branches = extras.projects.branch,
                                ),
                            costToday = extras.today?.cost,
                            costBaselinePerDay = baseline,
                            promptChips = CentrePolicy.promptSuggestions(extras.commands),
                            sessionsToday = extras.today?.sessions,
                            promptsToday = extras.today?.prompts,
                            stepsToday = extras.today?.steps,
                            message = null,
                        )
                    }
                }.onFailure { error ->
                    AppLog.w(APP_LOG_TAG) { "centre: refresh failed (${error.message})" }
                    _state.update {
                        it.copy(loading = false, message = error.message.orEmpty())
                    }
                }
            }
        }

        /**
         * Runs the action an insight suggests. The mapping lives here (not in the UI) so any
         * surface that renders insights can execute them the same way.
         */
        fun execute(insight: Insight) {
            val sessionId = insight.sessionId
            when (insight.action) {
                InsightAction.None -> {
                    Unit
                }

                InsightAction.OpenSession -> {
                    Unit
                }

                // navigation: the screen handles it
                InsightAction.StopSession -> {
                    sessionId?.let { id ->
                        stop(_state.value.rows.firstOrNull { it.sessionId == id } ?: return@let)
                    }
                }

                InsightAction.SteerQueue -> {
                    sessionId?.let { id ->
                        viewModelScope.launch {
                            val first =
                                runCatching { api.getInbox(id, currentDirectoryFor(id)) }
                                    .getOrDefault(emptyList())
                                    .firstOrNull { it.type == "user" && !it.id.isNullOrBlank() }
                                    ?: return@launch
                            report(
                                actions.steer(id, first.id!!, first.payload?.text.orEmpty()),
                                "Steered the queued prompt",
                            )
                            refresh()
                        }
                    }
                }

                InsightAction.CompactSession -> {
                    sessionId?.let { id ->
                        viewModelScope.launch {
                            val row = _state.value.rows.firstOrNull { it.sessionId == id }
                            report(actions.compact(id, row?.title.orEmpty(), row?.directory), "Compacting…")
                            refresh()
                        }
                    }
                }

                InsightAction.OpenChanges -> {
                    // Navigation: the screen opens the insight's session, where the Changes tab
                    // lives. Kept here so the mapping "statement -> action" stays in one place and
                    // a non-UI surface (notification, widget) knows where it leads.
                    Unit
                }
            }
        }

        /**
         * Answers a permission straight from the Centre's "needs you" list.
         *
         * The whole point of the list is not having to open the session: a blocked turn is
         * released with one tap, through the same central action layer (and audit entry) the
         * chat uses.
         */
        fun answerPermission(
            sessionId: String,
            requestId: String,
            answer: PermissionAnswer,
            title: String,
        ) {
            viewModelScope.launch {
                report(actions.replyPermission(sessionId, requestId, answer, title), "Answered")
                refresh()
            }
        }

        /**
         * Deletes a row's session (context menu). The screen asks first - this is permanent.
         *
         * The tab/route holding it is not closed here: the chat already explains a session that
         * no longer exists instead of showing a raw 404 (see `sessionGone`).
         */
        fun delete(row: CentreRow) {
            viewModelScope.launch {
                report(actions.delete(row.sessionId, row.title), "Deleted ${row.title}")
                refresh()
            }
        }

        /** Archives a row's session locally (reversible, no server write). */
        fun archive(row: CentreRow) {
            report(actions.archive(row.sessionId, row.title), "Archived ${row.title}")
            refresh()
        }

        /**
         * Sends a one-tap prompt to a session: the daily-use shortcut.
         *
         * Queued while the session runs (never kills work in flight), direct when idle.
         */
        fun quickPrompt(
            row: CentreRow,
            text: String,
        ) {
            viewModelScope.launch {
                val running = row.running || row.retrying
                report(actions.prompt(row.sessionId, text, row.title, running), "Prompt sent")
                refresh()
            }
        }

        /** Compacts a row's session (long-press / overflow menu, and the insight action). */
        fun compact(row: CentreRow) {
            viewModelScope.launch {
                report(actions.compact(row.sessionId, row.title, row.directory), "Compacting…")
                refresh()
            }
        }

        /** Releases the first prompt waiting behind a running turn. */
        fun steerFirstQueued(row: CentreRow) {
            viewModelScope.launch {
                val first =
                    runCatching { api.getInbox(row.sessionId, row.directory) }
                        .getOrDefault(emptyList())
                        .firstOrNull { it.type == "user" && !it.id.isNullOrBlank() }
                        ?: return@launch
                report(actions.steer(row.sessionId, first.id!!, first.payload?.text.orEmpty()), "Steered")
                refresh()
            }
        }

        /** Stops one turn through the central action layer. */
        fun stop(row: CentreRow) {
            viewModelScope.launch {
                report(actions.stop(row.sessionId, row.title), "Stopped ${row.title}")
                runningIds.value = runningIds.value - row.sessionId
                retryingIds.value = retryingIds.value - row.sessionId
                applyFold()
                refresh()
            }
        }

        /** The emergency stop: interrupts every running turn at once. */
        fun stopAll() {
            val turns =
                _state.value.rows
                    .filter { it.running || it.retrying }
                    .map { it.sessionId to it.title }
            if (turns.isEmpty()) return
            viewModelScope.launch {
                _state.update { it.copy(stopping = true) }
                val result = actions.stopAll(turns)
                turns.forEach { (id, _) ->
                    runningIds.value = runningIds.value - id
                    retryingIds.value = retryingIds.value - id
                }
                _state.update {
                    it.copy(
                        stopping = false,
                        message = (result as? com.opencode.android.data.ActionResult.Failed)?.message,
                    )
                }
                applyFold()
                refresh()
            }
        }

        fun clearAudit() = AuditLogStore.clear()

        fun dismissMessage() = _state.update { it.copy(message = null) }

        /** Shows the outcome of an action (or says nothing when it worked). */
        private fun report(
            result: com.opencode.android.data.ActionResult,
            successMessage: String,
        ) {
            _state.update {
                it.copy(
                    message =
                        when (result) {
                            is com.opencode.android.data.ActionResult.Ok -> null
                            is com.opencode.android.data.ActionResult.Skipped -> result.reason
                            is com.opencode.android.data.ActionResult.Failed -> result.message
                        },
                )
            }
        }

        private fun currentDirectoryFor(sessionId: String): String? =
            _state.value.rows
                .firstOrNull { it.sessionId == sessionId }
                ?.directory

        /** Re-applies the live running sets to the already loaded rows. */
        private fun applyFold() {
            _state.update { current ->
                current.copy(
                    rows =
                        CentrePolicy.sort(
                            current.rows.map { row ->
                                row.copy(
                                    running = row.sessionId in runningIds.value,
                                    retrying = row.sessionId in retryingIds.value,
                                )
                            },
                        ),
                )
            }
        }

        /** Midnight of the local day - the window `GET /experimental/session/stats` uses. */
        private fun startOfToday(): Long =
            java.util.Calendar
                .getInstance()
                .apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis

        /** Everything the snapshot fetched in parallel. */
        private data class Extras(
            val permissions: List<PermissionRequest>,
            val questions: List<SessionQuestion>,
            val mcp: Map<String, com.opencode.android.domain.McpStatus>,
            val skills: Int,
            val plugins: Int,
            val commands: List<com.opencode.android.domain.CommandEntry>,
            val today: com.opencode.android.domain.SessionStats?,
            val week: com.opencode.android.domain.SessionStats?,
            val projects: Projects,
            val queued: Map<String, Int>,
        )

        /** Per-project picture: the branch and any uncommitted work. */
        private data class Projects(
            val branch: List<Pair<String, String>> = emptyList(),
            val dirty: List<DirtyProject> = emptyList(),
        )

        private companion object {
            const val MAX_SESSION_PROBES = 10

            /** Projects scanned per snapshot (permissions, forms, branch, status). */
            const val SCAN_DIRECTORY_LIMIT = 3

            /** How long the 7-day baseline is reused before it is fetched again. */
            const val BASELINE_TTL_MS = 5L * 60L * 1000L
            const val MAX_QUEUE_PROBES = 5
            const val DAY_MS = 24L * 60L * 60L * 1000L
            const val SEVEN_DAYS_MS = 7L * DAY_MS
        }
    }
