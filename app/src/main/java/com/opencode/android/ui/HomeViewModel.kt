package com.opencode.android.ui
import com.opencode.android.util.UserMessages
import com.opencode.android.R
import com.opencode.android.util.AppLog
import androidx.compose.runtime.Immutable
import com.opencode.android.util.APP_LOG_TAG

import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.data.OpenCodeApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionCreateRequest
import com.opencode.android.domain.SessionLocation
import com.opencode.android.domain.SessionTimeUpdate
import com.opencode.android.domain.SessionUpdateRequest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.opencode.android.data.LastSessionStore

// Unified project row mirroring the web "Projects" panel:
// server projects (GET /project) plus locally added directories
// (web "Add project" opens a local project).
@Immutable
data class HomeProject(
    val id: String? = null,
    val name: String,
    val directory: String,
    val isLocal: Boolean = false,
)

@Immutable
data class HomeUiState(
    val projects: List<HomeProject> = emptyList(),
    val selectedProject: HomeProject? = null,
    val sessions: List<Session> = emptyList(),
    val searchQuery: String = "",
    // Session ids whose cached message tail matches `searchQuery` (local,
    // best-effort). Lets the search find conversations by content, not title.
    val messageMatchIds: Set<String> = emptySet(),
    val showOnlyGuarded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Session ids the user pinned; shown in a "Pinned" group at the top.
    val pinnedIds: Set<String> = emptySet(),
    // A refresh that already has content on screen: show the pull indicator,
    // not the full-row skeleton (which would blank the list for a moment).
    val isRefreshing: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    // Resolved per call: the active backend can change mid-process.
    private val apiProvider: Provider<OpenCodeApi>,
) : ViewModel() {

    private val api: OpenCodeApi get() = apiProvider.get()

    private companion object {
        const val PINNED_KEY = "pinned_sessions"
    }
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Single-flight jobs so overlapping loads cannot race (see loadSessions).
    private var projectsJob: kotlinx.coroutines.Job? = null
    private var sessionsJob: kotlinx.coroutines.Job? = null

    // Resolved on a background dispatcher (AppStartup); reads here are in-memory.
    init {
        loadPinned()
        _uiState.update {
            it.copy(showOnlyGuarded = com.opencode.android.data.HomePrefs.onlyGuarded())
        }
        loadProjects()
    }

    // --- Pinned sessions (local, like the web's favourites) ---

    private fun loadPinned() {
        val raw = com.opencode.android.data.HomePrefs.p()
            ?.getStringSet(PINNED_KEY, emptySet())
            .orEmpty()
        _uiState.update { it.copy(pinnedIds = raw) }
    }

    fun togglePinned(sessionId: String) {
        val next = _uiState.value.pinnedIds.toMutableSet().apply {
            if (!add(sessionId)) remove(sessionId)
        }
        com.opencode.android.data.HomePrefs.p()?.edit { putStringSet(PINNED_KEY, next) }
        _uiState.update { it.copy(pinnedIds = next) }
    }

    // --- Projects (web "Projects" panel + "Add project") ---

    fun loadProjects() {
        // Single-flight: the init load and the Refresh button (and a rename)
        // can overlap; cancelling the previous keeps the last one authoritative.
        projectsJob?.cancel()
        projectsJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val server = try {
                    api.getProjects()
                } catch (e: Exception) {
                    AppLog.e(APP_LOG_TAG, "getProjects failed: ${e.message}")
                    UserMessages.post(R.string.could_not_load_projects, "${e.message}")
                    emptyList()
                }
                val fromServer = server.mapNotNull { p ->
                    val dir = p.worktree ?: return@mapNotNull null
                    HomeProject(
                        id = p.id.takeIf { it != "global" },
                        name = com.opencode.android.util.lastPathSegment(dir),
                        directory = dir,
                    )
                }
                val local = loadLocalProjects()
                // Directories the user removed from the home list. Loaded once
                // per refresh so a deleted project never flashes back.
                val hiddenProjects = loadHiddenProjects()
                // Keep the last-used project selectable even when the server's
                // project list no longer contains it — otherwise the home screen
                // silently fell back to another (often empty) project and it
                // looked like the sessions "did not load".
                val rememberedDir = LastSessionStore.directory()
                val rememberedEntry = rememberedDir
                    ?.takeIf { dir -> (fromServer + local).none { it.directory == dir } }
                    ?.let { dir ->
                        HomeProject(
                            name = com.opencode.android.util.lastPathSegment(dir),
                            directory = dir,
                            isLocal = true,
                        )
                    }
                val merged = (fromServer + local + listOfNotNull(rememberedEntry))
                    // Drop projects the user deleted: a server-provided project
                    // would otherwise reappear on the next refresh.
                    .filterNot { it.directory in hiddenProjects }
                    .distinctBy { it.directory }
                val selected = _uiState.value.selectedProject?.let { sel ->
                    merged.firstOrNull { it.directory == sel.directory }
                } ?: merged.firstOrNull { it.directory == rememberedDir }
                    ?: merged.firstOrNull { it.directory != "/" }
                    ?: merged.firstOrNull()
                _uiState.update { it.copy(
                    projects = merged,
                    selectedProject = selected,
                    isLoading = false,
                ) }
                selected?.let { loadSessions(it) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "loadProjects failed: ${e.message}", e)
                UserMessages.post(R.string.could_not_load_projects, "${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    error = e.message ?: "Connection failed",
                ) }
            }
        }
    }

    fun selectProject(project: HomeProject) {
        LastSessionStore.saveDirectory(project.directory)
        _uiState.update { it.copy(selectedProject = project, searchQuery = "") }
        loadSessions(project)
    }

    fun addLocalProject(directory: String) {
        val dir = directory.trimEnd('/')
        if (dir.isBlank()) return
        // Re-adding a path the user previously deleted must clear the hidden
        // flag, otherwise the next refresh would silently remove it again.
        saveHiddenProjects(loadHiddenProjects() - dir)
        val current = loadLocalProjects().toMutableList()
        if (current.none { it.directory == dir }) {
            current.add(
                HomeProject(
                    name = com.opencode.android.util.lastPathSegment(dir),
                    directory = dir,
                    isLocal = true,
                )
            )
            saveLocalProjects(current)
        }
        val merged = (_uiState.value.projects + current).distinctBy { it.directory }
        val added = merged.first { it.directory == dir }
        _uiState.update { it.copy(projects = merged, selectedProject = added) }
        loadSessions(added)
    }

    /**
     * Removes a project from the home list (long-press → Delete, or the title
     * menu). Locally-added projects are dropped from local storage; every
     * removal is also remembered so a server-provided project does not come
     * back on the next refresh.
     */
    fun deleteProject(project: HomeProject) {
        saveHiddenProjects(loadHiddenProjects() + project.directory)
        if (project.isLocal) {
            saveLocalProjects(loadLocalProjects().filter { it.directory != project.directory })
        }
        val projects = _uiState.value.projects.filter { it.directory != project.directory }
        val selected = _uiState.value.selectedProject?.takeIf { it.directory != project.directory }
            // Same preference as loadProjects: never fall back to "/" while a
            // real project exists.
            ?: projects.firstOrNull { it.directory != "/" }
            ?: projects.firstOrNull()
        _uiState.update { it.copy(projects = projects, selectedProject = selected) }
        if (selected != null) {
            LastSessionStore.saveDirectory(selected.directory)
            loadSessions(selected)
        }
    }

    private fun loadHiddenProjects(): Set<String> = try {
        val raw = com.opencode.android.data.HomePrefs.p()?.getString("hidden_projects", "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { d -> d.isNotBlank() } }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun saveHiddenProjects(directories: Set<String>) {
        com.opencode.android.data.HomePrefs.p()?.edit {
            putString("hidden_projects", JSONArray(directories.toList()).toString())
        }
    }

    fun renameProject(project: HomeProject, newName: String, onDone: () -> Unit) {
        val id = project.id
        if (id == null) {
            // Local project: rename locally.
            val updated = loadLocalProjects().map {
                if (it.directory == project.directory) it.copy(name = newName) else it
            }
            saveLocalProjects(updated)
            _uiState.update { state -> state.copy(
                projects = state.projects.map {
                    if (it.directory == project.directory) it.copy(name = newName) else it
                },
                selectedProject = state.selectedProject?.takeIf { it.directory != project.directory }
                    ?: state.selectedProject?.copy(name = newName),
            ) }
            onDone()
            return
        }
        viewModelScope.launch {
            try {
                api.renameProject(id, mapOf("name" to newName))
                loadProjects()
                onDone()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "renameProject failed: ${e.message}")
                UserMessages.post(R.string.could_not_rename_project, "${e.message}")
            }
        }
    }

    private fun loadLocalProjects(): List<HomeProject> {
        return try {
            val raw = com.opencode.android.data.HomePrefs.p()
                ?.getString("recent_projects", "[]") ?: "[]"
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                HomeProject(
                    name = obj.optString("name"),
                    directory = obj.optString("directory"),
                    isLocal = true,
                )
            }.filter { it.directory.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveLocalProjects(projects: List<HomeProject>) {
        val arr = JSONArray()
        for (p in projects) {
            arr.put(JSONObject().put("name", p.name).put("directory", p.directory))
        }
        com.opencode.android.data.HomePrefs.p()?.edit { putString("recent_projects", arr.toString()) }
    }

    // --- Sessions (web "Recent sessions", scoped per project) ---

    fun loadSessions(project: HomeProject? = _uiState.value.selectedProject) {
        if (project == null) return
        // Single-flight + stale guard. Triggers overlap (init's loadProjects,
        // a project tap, pull-to-refresh, the Refresh button). Previously a
        // slower request for a *previously* selected project could land last
        // and blank the list or show the wrong project's sessions — the
        // "sessions don't load reliably" the user saw on the home screen.
        sessionsJob?.cancel()
        sessionsJob = viewModelScope.launch {
            // Skeleton only when there is nothing to keep on screen; otherwise
            // this is a pull-to-refresh and the list must stay put.
            val hasContent = _uiState.value.sessions.isNotEmpty() &&
                _uiState.value.selectedProject?.directory == project.directory
            _uiState.update { it.copy(
                isLoading = !hasContent,
                isRefreshing = hasContent,
                error = null,
            ) }
            try {
                // Mirror web home list: GET /session?directory=&roots=true&limit=55.
                // One retry: a dropped connection or a server restart otherwise
                // showed "Could not load sessions" for a list that loads fine a
                // moment later.
                var fetched: List<com.opencode.android.domain.Session>? = null
                var lastError: Exception? = null
                for (attempt in 1..2) {
                    try {
                        fetched = api.getProjectSessions(project.directory)
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        if (attempt < 2) kotlinx.coroutines.delay(400L)
                    }
                }
                val sessions = (fetched ?: throw (lastError ?: IllegalStateException("load failed")))
                    .filter { it.time?.archived == null }
                    .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                // Drop a stale result: another project was selected meanwhile.
                if (_uiState.value.selectedProject?.directory != project.directory) return@launch
                _uiState.update { it.copy(
                    sessions = sessions,
                    isLoading = false,
                    isRefreshing = false,
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.selectedProject?.directory != project.directory) return@launch
                AppLog.e(APP_LOG_TAG, "loadSessions failed: ${e.message}", e)
                UserMessages.post(R.string.could_not_load_sessions, "${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = e.message ?: "Connection failed",
                ) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val ids = com.opencode.android.data.MessageCache.search(query).toSet()
            // Ignore a stale result: the query may have changed while scanning.
            if (_uiState.value.searchQuery == query) {
                _uiState.update { it.copy(messageMatchIds = ids) }
            }
        }
    }

    fun setShowOnlyGuarded(enabled: Boolean) {
        _uiState.update { it.copy(showOnlyGuarded = enabled) }
        com.opencode.android.data.HomePrefs.setOnlyGuarded(enabled)
    }

    fun createSession(directory: String? = null, onCreated: (Session) -> Unit) {
        viewModelScope.launch {
            try {
                val dir = directory ?: _uiState.value.selectedProject?.directory
                val session = api.createSessionIn(
                    SessionCreateRequest(location = dir?.let { SessionLocation(it) })
                ).data
                onCreated(session)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Session creation failed") }
            }
        }
    }

    fun renameSession(session: Session, newTitle: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                api.renameSession(session.id, mapOf("title" to newTitle))
                _uiState.value.selectedProject?.let { loadSessions(it) }
                onDone()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "renameSession failed: ${e.message}")
                UserMessages.post(R.string.could_not_rename, "${e.message}")
            }
        }
    }

    fun archiveSession(session: Session) {
        viewModelScope.launch {
            try {
                api.updateSession(
                    session.id,
                    SessionUpdateRequest(time = SessionTimeUpdate(archived = System.currentTimeMillis())),
                )
                _uiState.value.selectedProject?.let { loadSessions(it) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "archiveSession failed: ${e.message}")
                UserMessages.post(R.string.could_not_archive, "${e.message}")
            }
        }
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            try {
                api.deleteSession(session.id)
                _uiState.value.selectedProject?.let { loadSessions(it) }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "deleteSession failed: ${e.message}")
                UserMessages.post(R.string.could_not_delete, "${e.message}")
            }
        }
    }

}
