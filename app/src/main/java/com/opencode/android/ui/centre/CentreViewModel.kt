package com.opencode.android.ui.centre

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.data.AuditEvent
import com.opencode.android.data.AuditKind
import com.opencode.android.data.AuditLogStore
import com.opencode.android.data.BackendSession
import com.opencode.android.data.OpenCodeApi
import com.opencode.android.data.OutboxStore
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.domain.SessionStatus
import com.opencode.android.ui.session.SessionStatusReducer
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@Immutable
data class CentreUiState(
    val loading: Boolean = false,
    val rows: List<CentreRow> = emptyList(),
    val needs: List<CentreNeed> = emptyList(),
    /** Today's server-side aggregate (the same figures the glance surfaces show). */
    val costToday: Double? = null,
    val sessionsToday: Int? = null,
    val promptsToday: Int? = null,
    val stepsToday: Int? = null,
    val audit: List<AuditEvent> = emptyList(),
    val stopping: Boolean = false,
    /** Set when the last action failed, so the screen can say so instead of doing nothing. */
    val message: String? = null,
) {
    val runningCount: Int get() = rows.count { it.running || it.retrying }
}

/**
 * The cockpit's data: what is running, what waits for the user, what today cost, and what
 * the user did to which session.
 *
 * Live-ness comes from the global event feed (the same `session.status` events the session
 * list uses), so a turn started by the web or the TUI shows up here at once and flips back
 * the moment it ends. The HTTP load is one snapshot call per visit/refresh — the feed is
 * the primary source, not a poll.
 */
@HiltViewModel
class CentreViewModel
    @Inject
    constructor(
        private val apiProvider: Provider<OpenCodeApi>,
        private val backendSession: BackendSession,
    ) : ViewModel() {
        private val api: OpenCodeApi get() = apiProvider.get()

        private val _state = MutableStateFlow(CentreUiState())
        val state: StateFlow<CentreUiState> = _state.asStateFlow()

        private val runningIds = MutableStateFlow<Set<String>>(emptySet())
        private val retryingIds = MutableStateFlow<Set<String>>(emptySet())

        private var feedJob: Job? = null

        init {
            // Audit events are written by other screens too; mirror the store.
            viewModelScope.launch {
                AuditLogStore.state.collect { events -> _state.update { it.copy(audit = events) } }
            }
            viewModelScope.launch {
                // A changed outbox always means the "needs you" section changed, and the
                // section is derived from several sources at once - so reload instead of
                // patching one of them (patching is what made the list disagree with the
                // server). Only fires on an actual change of the outbox.
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

        /** Reloads the snapshot: sessions + statuses, the day's stats, what is waiting. */
        fun refresh() {
            viewModelScope.launch {
                _state.update { it.copy(loading = true) }
                runCatching {
                    val sessions = api.getSessions(limit = 50, order = "desc").data
                    val statuses: Map<String, SessionStatus> =
                        runCatching { api.getSessionStatuses(CentrePolicy.directoriesToScan(sessions).firstOrNull().orEmpty()) }
                            .getOrDefault(emptyMap())
                    // Pending work is scoped PER DIRECTORY on this server (verified live: one
                    // project reported five open forms while another reported none). Asking
                    // only the first session's directory hid every other project, so the
                    // directories of the recent sessions are all scanned — plus the global
                    // location, which is the empty directory.
                    val directories = (CentrePolicy.directoriesToScan(sessions) + "").distinct()
                    val permissions =
                        directories
                            .flatMap { directory ->
                                runCatching { api.getPermissions(directory) }.getOrDefault(emptyList())
                            }.distinctBy { it.id ?: it.hashCode() }
                    val questions =
                        directories
                            .flatMap { directory ->
                                runCatching { api.getQuestionRequests(directory).data }.getOrDefault(emptyList())
                            }.distinctBy { it.id }
                    val stats = runCatching { api.getStats(fromMs = startOfToday()) }.getOrNull()
                    runningIds.value = statuses.filterValues { it.type == "busy" }.keys
                    retryingIds.value = statuses.filterValues { it.type == "retry" }.keys
                    val outbox =
                        OutboxStore.state.value
                            .groupingBy { it.sessionId }
                            .eachCount()
                    val titles = sessions.associate { it.id to it.title.orEmpty() }
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            rows = CentrePolicy.rows(sessions, statuses),
                            needs = CentrePolicy.needs(permissions, questions, outbox, titles),
                            costToday = stats?.cost,
                            sessionsToday = stats?.sessions,
                            promptsToday = stats?.prompts,
                            stepsToday = stats?.steps,
                            message = null,
                        )
                    }
                }.onFailure { error ->
                    AppLog.w(APP_LOG_TAG) { "centre: refresh failed (${error.message})" }
                    _state.update {
                        it.copy(
                            loading = false,
                            message = error.message.orEmpty(),
                        )
                    }
                }
            }
        }

        /**
         * Stops one turn: the agent keeps the work it already did, the turn ends.
         * Recorded in the audit log, since it is the user overriding the agent.
         */
        fun stop(row: CentreRow) {
            viewModelScope.launch {
                runCatching { api.interrupt(row.sessionId) }
                    .onSuccess {
                        AuditLogStore.record(
                            kind = AuditKind.Stop,
                            title = row.title,
                            sessionId = row.sessionId,
                        )
                        runningIds.value = runningIds.value - row.sessionId
                        retryingIds.value = retryingIds.value - row.sessionId
                        applyFold()
                    }.onFailure { error ->
                        _state.update {
                            it.copy(message = error.message.orEmpty())
                        }
                    }
            }
        }

        /**
         * The emergency stop: interrupts every running turn at once.
         *
         * Sequential on purpose (not `async`): ten parallel interrupts to one server add
         * load without finishing any earlier, and the audit entry must name how many
         * actually stopped.
         */
        fun stopAll() {
            val ids = CentrePolicy.runningIds(_state.value.rows)
            if (ids.isEmpty()) return
            viewModelScope.launch {
                _state.update { it.copy(stopping = true) }
                var stopped = 0
                ids.forEach { id ->
                    val ok = runCatching { api.interrupt(id) }.isSuccess
                    if (ok) {
                        stopped++
                        runningIds.value = runningIds.value - id
                        retryingIds.value = retryingIds.value - id
                    }
                }
                val titles =
                    _state.value.rows
                        .filter { it.sessionId in ids }
                        .map { it.title }
                AuditLogStore.record(
                    kind = AuditKind.StopAll,
                    title = "$stopped of ${ids.size} turns",
                    detail = titles.take(5).joinToString(", "),
                )
                _state.update {
                    it.copy(
                        stopping = false,
                        message =
                            if (stopped == ids.size) {
                                null
                            } else {
                                "$stopped of ${ids.size} could be stopped"
                            },
                    )
                }
                applyFold()
            }
        }

        fun clearAudit() = AuditLogStore.clear()

        fun dismissMessage() = _state.update { it.copy(message = null) }

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
    }

/** The audit switch gates recording; the screen hides the section when it is off. */
internal fun auditVisible(): Boolean = AppSettingsStore.state.value.auditLogEnabled
