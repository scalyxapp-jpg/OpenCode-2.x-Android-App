package com.opencode.android.ui
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.ui.theme.spacing
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.HealthResponse
import com.opencode.android.domain.Session
import kotlinx.coroutines.launch
import java.util.Calendar
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import com.opencode.android.util.RelativeTime
import com.opencode.android.util.formatCost
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun HomeScreen(
    onSessionClick: (String) -> Unit,
    onSwitchBackend: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // Shared-element scopes (hero transition session row → chat).
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val backendSession = LocalBackendSession.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appSettings by AppSettingsStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val statusScope = rememberCoroutineScope()
    var showAddProject by remember { mutableStateOf(false) }
    var showRenameProject by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // "Server status" setting: health + version popup in the title bar.
    var showServerStatus by remember { mutableStateOf(false) }
    var serverHealth by remember { mutableStateOf<HealthResponse?>(null) }
    var serverStatusChecked by remember { mutableStateOf(false) }
    // "Command palette" setting: quick action launcher in the title bar.
    var showPalette by remember { mutableStateOf(false) }

    val scopeName = uiState.selectedProject?.name ?: "…"
    val filteredSessions = remember(
        uiState.sessions,
        uiState.searchQuery,
        uiState.showOnlyGuarded,
    ) {
        filterSessions(uiState.sessions, uiState.searchQuery, uiState.showOnlyGuarded)
    }
    val grouped = remember(filteredSessions, uiState.pinnedIds) {
        groupSessions(filteredSessions, uiState.pinnedIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        title = stringResource(R.string.opencode),
                        subtitle = uiState.selectedProject?.directory,
                    )
                },
                actions = {
                    if (appSettings.showCommandPalette) {
                        IconButton(onClick = { showPalette = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.settings_command_palette))
                        }
                    }
                    if (appSettings.showServerStatus) {
                        IconButton(onClick = {
                            showServerStatus = true
                            statusScope.launch {
                                serverHealth = try {
                                    backendSession.api.globalHealth()
                                } catch (_: Exception) {
                                    null
                                }
                                serverStatusChecked = true
                            }
                        }) {
                            // Status is conveyed by colour AND the accessible
                            // label, so it is not colour-only information.
                            val statusLabel = when {
                                !serverStatusChecked -> stringResource(R.string.settings_server_status)
                                serverHealth?.healthy == true -> stringResource(R.string.healthy)
                                else -> stringResource(R.string.unreachable)
                            }
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = statusLabel,
                                tint = when {
                                    !serverStatusChecked -> MaterialTheme.colorScheme.onSurfaceVariant
                                    serverHealth?.healthy == true -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                    // Switch to another opencode serve backend (back to start screen).
                    IconButton(onClick = onSwitchBackend) {
                        Icon(Icons.Default.Dns, contentDescription = stringResource(R.string.switch_backend))
                    }
                    IconButton(onClick = {
                        viewModel.loadProjects()
                        uiState.selectedProject?.let { viewModel.loadSessions(it) }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    // Project actions live here so the list stays uncluttered.
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_project)) },
                                onClick = { showMenu = false; showAddProject = true },
                            )
                            uiState.selectedProject?.let { _ ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename_project)) },
                                    onClick = { showMenu = false; showRenameProject = true },
                                )
                            }
                            // Deleting a project lives on the chip's long-press
                            // menu (works for every project, not just local ones).
                        }
                    }
                },
            )
        },
    ) { padding ->
        // Pull-to-refresh: keeps the existing list on screen and shows the
        // indicator instead of blanking it (the skeleton is for first load).
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.loadSessions() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Non-blocking loading hint while refreshing existing content
            // (instead of replacing the list with a spinner).
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.isLoading &&
                    (uiState.sessions.isNotEmpty() || uiState.projects.isNotEmpty()),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                uiState.isLoading && uiState.sessions.isEmpty() && uiState.projects.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null && uiState.sessions.isEmpty() && uiState.projects.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(
                            message = uiState.error ?: "",
                            hint = stringResource(R.string.server_hint),
                            onRetry = {
                                viewModel.loadProjects()
                                uiState.selectedProject?.let { viewModel.loadSessions(it) }
                            },
                        )
                    }
                }
                else -> {
                    // Pull-to-refresh: the list stays put and the indicator shows while
                    // the sessions reload (loadSessions sets isRefreshing only when there
                    // is content to keep on screen).
                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = {
                            viewModel.loadProjects()
                            uiState.selectedProject?.let { viewModel.loadSessions(it) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = MaterialTheme.spacing.extraSmall, bottom = MaterialTheme.spacing.large),
                        ) {
                        // --- Project switcher: compact scrollable chips ---
                        item(key = "projects") {
                            ProjectChipRow(
                                projects = uiState.projects,
                                selected = uiState.selectedProject,
                                onSelect = { viewModel.selectProject(it) },
                                onAdd = { showAddProject = true },
                                onDelete = { viewModel.deleteProject(it) },
                            )
                        }

                        // --- Search + new session ---
                        item(key = "search") {
                            SearchRow(
                                query = uiState.searchQuery,
                                onQueryChange = viewModel::setSearchQuery,
                                scopeName = scopeName,
                                onNewSession = {
                                    viewModel.createSession { onSessionClick(it.id) }
                                },
                                onlyGuarded = uiState.showOnlyGuarded,
                                onToggleGuarded = viewModel::setShowOnlyGuarded,
                            )
                        }

                        if (uiState.isLoading) {
                            // Row skeletons instead of a lone spinner: the list
                            // already shows the shape of what is arriving, so the
                            // swap when it lands is not a layout jump.
                            items(4, key = { "skeleton-$it" }) { SessionRowSkeleton() }
                        }

                        if (filteredSessions.isEmpty() && !uiState.isLoading) {
                            item(key = "empty") {
                                EmptyState(
                                    title = if (uiState.searchQuery.isBlank()) {
                                        stringResource(R.string.empty_home_title)
                                    } else {
                                        stringResource(
                                            R.string.no_sessions_found,
                                            uiState.searchQuery,
                                        )
                                    },
                                    subtitle = if (uiState.searchQuery.isBlank()) {
                                        stringResource(R.string.empty_home_hint)
                                    } else {
                                        null
                                    },
                                    icon = Icons.AutoMirrored.Filled.Chat,
                                )
                            }
                        } else {
                            grouped.forEach { (label, sessions) ->
                                item(key = "header-$label") {
                                    Text(
                                        text = label.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        letterSpacing = 0.8.sp,
                                        modifier = Modifier.padding(
                                            start = MaterialTheme.spacing.medium,
                                            end = MaterialTheme.spacing.medium,
                                            top = MaterialTheme.spacing.medium,
                                            bottom = 2.dp,
                                        ),
                                    )
                                }
                                items(sessions, key = { it.id }) { session ->
                                    SessionRow(
                                        // Smooth insert/remove/reorder when the
                                        // list changes (pin, archive, delete).
                                        modifier = Modifier.animateItem(
                                            placementSpec = com.opencode.android.ui.theme.Motion.spatial(),
                                            fadeInSpec = com.opencode.android.ui.theme.Motion.effects(),
                                            fadeOutSpec = com.opencode.android.ui.theme.Motion.effects(),
                                        ),
                                        session = session,
                                        isPinned = session.id in uiState.pinnedIds,
                                        onTogglePin = { viewModel.togglePinned(session.id) },
                                        onClick = { onSessionClick(session.id) },
                                        onRename = { title -> viewModel.renameSession(session, title) },
                                        onArchive = { viewModel.archiveSession(session) },
                                        onDelete = { viewModel.deleteSession(session) },
                                        sharedScope = sharedScope,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    )
                                }
                            }
                        }
                    } // end LazyColumn
                    }
                }
            }
        }
        }
    }

    // Hoisted: the dialog callbacks below are plain lambdas, not composables.
    val projectAddedMsg = stringResource(R.string.project_added)

    if (showAddProject) {
        AddProjectDialog(
            onConfirm = { directory ->
                viewModel.addLocalProject(directory)
                showAddProject = false
                toast(
                    context,
                    projectAddedMsg,
                )
            },
            onDismiss = { showAddProject = false },
        )
    }
    if (showRenameProject) {
        val project = uiState.selectedProject
        RenameDialog(
            title = stringResource(R.string.rename_project),
            currentTitle = project?.name ?: "",
            onConfirm = { name ->
                showRenameProject = false
                project?.let { viewModel.renameProject(it, name) {} }
            },
            onDismiss = { showRenameProject = false },
        )
    }

    if (showServerStatus) {
        AlertDialog(
            onDismissRequest = { showServerStatus = false },
            title = { Text(stringResource(R.string.settings_server_status)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
                    Text(
                        text = backendSession.currentBaseUrl(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val healthy = serverHealth?.healthy
                    Text(
                        text = when {
                            !serverStatusChecked -> stringResource(R.string.loading_providers)
                            healthy == true -> stringResource(R.string.healthy)
                            else -> stringResource(R.string.unreachable)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (healthy == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    serverHealth?.version?.let {
                        Text(
                            text = stringResource(R.string.desktop_version, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showServerStatus = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    if (showPalette) {
        CommandPaletteDialog(
            onDismiss = { showPalette = false },
            actions = listOf(
                stringResource(R.string.new_session) to {
                    viewModel.createSession { onSessionClick(it.id) }
                },
                stringResource(R.string.refresh) to {
                    viewModel.loadProjects()
                    uiState.selectedProject?.let { viewModel.loadSessions(it) }
                },
                stringResource(R.string.add_project) to { showAddProject = true },
                stringResource(R.string.settings) to onOpenSettings,
                stringResource(R.string.switch_backend) to onSwitchBackend,
            ),
        )
    }
}

/** Simple, searchable action launcher (the mobile "command palette"). */
@Composable
private fun CommandPaletteDialog(
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(actions, query) {
        if (query.isBlank()) actions
        else actions.filter { it.first.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_command_palette)) },
        text = {
            Column {
                PickerSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.search_commands),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.first }) { (label, action) ->
                        PickerRow(
                            modifier = Modifier.animateItem(
                                placementSpec = com.opencode.android.ui.theme.Motion.spatial(),
                                fadeInSpec = com.opencode.android.ui.theme.Motion.effects(),
                                fadeOutSpec = com.opencode.android.ui.theme.Motion.effects(),
                            ),
                            title = label,
                            subtitle = "",
                            onClick = {
                                onDismiss()
                                action()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

// Compact, scrollable project switcher. Keeps the header small instead of
// spending a full card on every project.
@Composable
private fun ProjectChipRow(
    projects: List<HomeProject>,
    selected: HomeProject?,
    onSelect: (HomeProject) -> Unit,
    onAdd: () -> Unit,
    onDelete: (HomeProject) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        projects.forEach { project ->
            ProjectChip(
                project = project,
                selected = project.directory == selected?.directory,
                onSelect = { onSelect(project) },
                onDelete = { onDelete(project) },
            )
        }
        AssistChip(
            onClick = onAdd,
            label = { Text(stringResource(R.string.add)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

/**
 * A project chip that also answers a long press: a small context menu with a
 * destructive "Delete" appears so a project can be removed from the home list
 * (the top-bar menu only ever offered it for the selected project).
 *
 * Built from a tonal Surface + combinedClickable rather than FilterChip so the
 * long press and the tap share one gesture detector.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectChip(
    project: HomeProject,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val (pressModifier, pressInteraction) = rememberPressScale()
    Box {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = pressModifier
                .height(32.dp)
                .combinedClickable(
                    interactionSource = pressInteraction,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onSelect,
                    onLongClick = {
                        // A tactile tick makes the hidden long-press discoverable.
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        showMenu = true
                    },
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        // Immediate action: no ellipsis (unlike the session
                        // menu, whose "Delete…" opens a confirmation).
                        text = stringResource(R.string.delete_project),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    scopeName: String,
    onNewSession: () -> Unit,
    onlyGuarded: Boolean,
    onToggleGuarded: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.search_in, scopeName)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        FilterChip(
            selected = onlyGuarded,
            onClick = { onToggleGuarded(!onlyGuarded) },
            label = {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            },
        )
        FilledTonalIconButton(onClick = onNewSession) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_session))
        }
    }
}

internal fun filterSessions(
    sessions: List<Session>,
    query: String,
    onlyGuarded: Boolean,
): List<Session> {
    val q = query.trim()
    return sessions
        .filter { !onlyGuarded || it.sessionGuard != null }
        .filter {
            q.isBlank() ||
                (it.title ?: "").contains(q, ignoreCase = true) ||
                (it.path ?: "").contains(q, ignoreCase = true)
        }
}

internal fun groupSessions(
    sessions: List<Session>,
    pinnedIds: Set<String>,
): List<Pair<String, List<Session>>> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayStart = cal.timeInMillis
    val today = mutableListOf<Session>()
    val yesterday = mutableListOf<Session>()
    val older = mutableListOf<Session>()
    for (s in sessions) {
        if (s.id in pinnedIds) continue
        val ts = s.time?.updated ?: s.time?.created ?: 0L
        when {
            ts >= todayStart -> today.add(s)
            ts >= yesterdayStart -> yesterday.add(s)
            else -> older.add(s)
        }
    }
    return buildList {
        // Pinned first and removed from the date buckets so a session never
        // appears twice.
        val pinned = sessions.filter { it.id in pinnedIds }
        if (pinned.isNotEmpty()) add("Pinned" to pinned)
        if (today.isNotEmpty()) add("Today" to today)
        if (yesterday.isNotEmpty()) add("Yesterday" to yesterday)
        if (older.isNotEmpty()) add("Older" to older)
    }
}

// Error unwrapping lives in FileErrors (pure and unit-tested); see
// FileErrors.friendly(e) at the call site below.

// Add project browses SERVER directories (web "opening a local project"
// targets the serve host's filesystem).
@Composable
private fun AddProjectDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val backendSession = LocalBackendSession.current
    // Mirrors the web "Open project" dialog (verified via Playwright):
    //  - starts at the serve host's home dir (GET /path → home)
    //  - lists directories via GET /file?path=.&directory=<abs>
    //  - "Search folders" via GET /find/file?query=&dirs=true&limit=50&directory=<abs>
    //  - breadcrumb navigation + "Open" confirms the current folder as a project
    var home by remember { mutableStateOf<String?>(null) }
    var currentDir by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The server can take tens of seconds for a large tree (measured up to
    // 27 s), so a fast second navigation could be overwritten by the slower
    // first response. Cancel the in-flight listing before starting a new one.
    var listJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Listings are cached per (directory, query). Without this, stepping back up
    // through a deep tree re-queried every level — each costing seconds — and
    // the browser felt unusable. Retry bypasses the cache on purpose.
    val listingCache = remember { mutableStateOf<Map<String, List<FileEntry>>>(emptyMap()) }

    // Search fires per keystroke otherwise, and each server call can take
    // seconds; wait for a short pause instead.
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun list(dir: String, force: Boolean = false) {
        val key = dir + "\u0000" + query
        if (!force) {
            listingCache.value[key]?.let { cached ->
                listJob?.cancel()
                entries = cached
                error = null
                loading = false
                return
            }
        }
        listJob?.cancel()
        loading = true
        error = null
        listJob = scope.launch {
            val result = try {
                val loaded = if (query.isBlank()) {
                    backendSession.api.getFiles(path = ".", directory = dir)
                        .filter { it.type == "directory" }
                } else {
                    // /find/file needs a RELATIVE directory and returns plain
                    // string paths relative to it. An absolute path returned []
                    // (the "search does nothing" bug).
                    val base = home?.trimEnd('/')?.ifEmpty { "/" } ?: "/"
                    val underHome = com.opencode.android.util.isUnderHome(dir, base)
                    val searchBase = if (underHome) dir.trimEnd('/').ifEmpty { "/" } else base
                    val rel = if (underHome) {
                        com.opencode.android.util.relativeSearchDir(dir, base)
                    } else {
                        "."
                    }
                    backendSession.api.findFiles(query = query, directory = rel).map { p ->
                        val clean = p.trimEnd('/')
                        FileEntry(
                            // Search hits span directories, so show the relative
                            // path — several folders can share a basename
                            // (Documents/.opencode vs open-design/.opencode).
                            name = clean,
                            path = clean,
                            // The click handler navigates via `absolute`; search
                            // results must set it or tapping a hit did nothing.
                            absolute = "$searchBase/$clean",
                            type = "directory",
                        )
                    }
                }
                loaded.sortedBy { (it.name ?: it.path ?: "").lowercase() }
            } catch (e: Exception) {
                error = FileErrors.friendly(e)
                null
            }
            if (result != null) {
                listingCache.value = listingCache.value + (key to result)
                entries = result
            } else {
                entries = emptyList()
            }
            loading = false
        }
    }

    // Seed the browser from the serve host's home directory.
    LaunchedEffect(Unit) {
        val info = try { backendSession.api.getPathInfo() } catch (_: Exception) { null }
        val h = info?.home?.trimEnd('/')?.ifBlank { "/" } ?: "/"
        home = h
        currentDir = h
        list(h)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                // Search folders (web parity).
                PickerSearchField(
                    query = query,
                    onQueryChange = { value ->
                        query = value
                        searchJob?.cancel()
                        searchJob = scope.launch {
                            kotlinx.coroutines.delay(300)
                            currentDir?.let { list(it) }
                        }
                    },
                    placeholder = stringResource(R.string.search_folders),
                )
                // Breadcrumb: current location relative to home.
                Text(
                    text = currentDir?.let { d ->
                        val base = home?.trimEnd('/') ?: "/"
                        val rel = d.removePrefix(base).trim('/')
                        if (rel.isEmpty()) "~/" else "~/$rel/"
                    } ?: "~/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Parent row lives OUTSIDE the load/error switch on purpose: it
                // used to sit inside the success branch, so a folder that failed
                // to load (server 500 on e.g. /root, or a slow large tree) left
                // the user with an error and NO way back up — the browser felt
                // like it refused to navigate. Up-navigation must always work.
                // substringBeforeLast('/') yields "" for one-level paths such as
                // "/home", which silently removed the "up" row exactly there.
                // Collapse to "/" so every non-root directory can be left.
                val parentDir = currentDir?.let { d ->
                    val trimmed = d.trimEnd('/')
                    when {
                        trimmed.isEmpty() || trimmed == "/" -> null
                        else -> trimmed.substringBeforeLast('/').ifEmpty { "/" }
                    }
                }
                if (parentDir != null && parentDir != currentDir) {
                    PickerRow(
                        title = stringResource(R.string.up_one_level),
                        subtitle = "",
                        leading = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                        onClick = {
                            currentDir = parentDir
                            query = ""
                            list(parentDir)
                        },
                    )
                }
                when {
                    loading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            MaterialTheme.spacing.small,
                            Alignment.CenterHorizontally,
                        ),
                    ) {
                        InlineSpinner()
                        Text(
                            text = stringResource(R.string.reading_folder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    error != null -> Column {
                        Text(
                            text = stringResource(R.string.folder_error, error ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { currentDir?.let { list(it, force = true) } }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                    entries.isEmpty() -> EmptyState(
                        title = stringResource(R.string.no_subfolders),
                        icon = Icons.Default.Folder,
                        compact = true,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        // Bottom clearance so the last folder is not flush
                        // against the dialog edge.
                        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.cardPadding),
                    ) {
                        items(entries, key = { it.absolute ?: it.path ?: it.name ?: it.hashCode().toString() }) { entry ->
                            PickerRow(
                                modifier = Modifier.animateItem(
                                    placementSpec = com.opencode.android.ui.theme.Motion.spatial(),
                                    fadeInSpec = com.opencode.android.ui.theme.Motion.effects(),
                                    fadeOutSpec = com.opencode.android.ui.theme.Motion.effects(),
                                ),
                                title = entry.name ?: entry.path ?: "",
                                subtitle = "",
                                leading = {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                },
                                onClick = {
                                    // `directory` must be absolute; `path` is
                                    // relative, so it can never be used here.
                                    val next = entry.absolute ?: return@PickerRow
                                    currentDir = next
                                    query = ""
                                    list(next)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = currentDir != null,
                onClick = { currentDir?.let(onConfirm) },
            ) { Text(stringResource(R.string.open)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// Compact session row: small tonal avatar, one title line, one quiet meta line.
// Flat (no card) so a long list stays calm and dense.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SessionRow(
    session: Session,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
) {
    var showMore by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val sharedModifier = if (sharedScope != null && animatedVisibilityScope != null) {
        with(sharedScope) {
            Modifier.sharedElement(
                state = rememberSharedContentState(key = "session-${session.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }

    val (pressModifier, pressInteraction) = rememberPressScale(0.985f)
    Row(
        modifier = modifier
            .then(sharedModifier)
            .then(pressModifier)
            .fillMaxWidth()
            .clickable(
                interactionSource = pressInteraction,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(start = MaterialTheme.spacing.medium, end = MaterialTheme.spacing.extraSmall, top = MaterialTheme.spacing.small, bottom = MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardPadding),
    ) {
        // Tonal avatar with the session's initial (visual anchor, web-like).
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (session.title ?: "N").trim().take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                // Strip the server's " - <ISO timestamp>" suffix so the list
                // shows a clean title instead of "New session - 2026-09-13T…".
                text = session.title?.let { com.opencode.android.util.sessionDisplayTitle(it) }
                    ?: "New session",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val now = System.currentTimeMillis()
            val meta = listOfNotNull(
                session.agent,
                // Drop the provider prefix ("deepseek/deepseek-v4-flash" → "deepseek-v4-flash").
                session.model?.id?.substringAfterLast('/'),
                // Relative while recent ("3h ago"), absolute date beyond a week.
                session.time?.updated?.let {
                    RelativeTime.label(it, now) ?: formatHomeTime(it)
                },
                session.cost?.takeIf { it > 0.0 }?.let { formatCost(it) },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Wrap instead of truncating: agent · model · date · cost
                        // used to cut off the cost on the right.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    session.sessionGuard?.let { guard ->
                        Icon(
                            Icons.Default.Security,
                            contentDescription = if (guard.mismatch) {
                                "Session Guard drift"
                            } else {
                                "Session Guard"
                            },
                            tint = if (guard.mismatch) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            // Web home shows the turn's change summary on the row ("3 files
            // +12 -4"); without it the list gives no hint of what happened.
            val summary = session.summary
            if (summary != null && summary.files > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.session_files_changed,
                            summary.files,
                            summary.files,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (summary.additions > 0) {
                        Text(
                            text = "+${summary.additions}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (summary.deletions > 0) {
                        Text(
                            text = "−${summary.deletions}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Box {
            IconButton(
                onClick = { showMore = true },
                // 48dp touch target (a11y minimum), not 32dp.
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_options),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isPinned) R.string.unpin_session else R.string.pin_session,
                            ),
                        )
                    },
                    onClick = { showMore = false; onTogglePin() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    onClick = {
                        showMore = false
                        showRename = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.archive)) },
                    onClick = { showMore = false; onArchive() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { showMore = false; showDelete = true },
                )
            }
        }
    }
    if (showRename) {
        RenameDialog(
            title = stringResource(R.string.rename_session),
            // Prefill the same clean title the list shows (no ISO suffix).
            currentTitle = session.title
                ?.let { com.opencode.android.util.sessionDisplayTitle(it) }
                ?: "",
            onConfirm = { name ->
                showRename = false
                onRename(name)
            },
            onDismiss = { showRename = false },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete_session)) },
            text = {
                Text(stringResource(
                    R.string.delete_session_confirm,
                    session.title?.let { com.opencode.android.util.sessionDisplayTitle(it) }
                        ?: session.id,
                ))
            },
            confirmButton = {
                Button(
                    onClick = { showDelete = false; onDelete() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.delete_2)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Cost badge text; sub-cent amounts would all read "$0.00". */


private fun formatHomeTime(epochMillis: Long): String {
    return try {
        // English pattern to match the app's English UI (the Language setting is
        // stored but not applied to the process locale).
        val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.ENGLISH)
        sdf.format(java.util.Date(epochMillis))
    } catch (e: Exception) {
        ""
    }
}


/**
 * Placeholder that mirrors a session row (avatar circle + title line + meta
 * line) so the first paint after a project switch does not jump.
 */
@Composable
private fun SessionRowSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                top = MaterialTheme.spacing.small,
                bottom = MaterialTheme.spacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardPadding),
    ) {
        SkeletonBox(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp),
            )
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(10.dp),
            )
        }
    }
}
