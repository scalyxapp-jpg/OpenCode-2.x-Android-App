package com.opencode.android.ui.settings
import com.opencode.android.util.UserMessages
import com.opencode.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.ui.theme.spacing
import com.opencode.android.ui.TopBarTitle
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onSwitchBackend: () -> Unit = {},
) {
    val backendSession = com.opencode.android.ui.LocalBackendSession.current
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("General", "Shortcuts", "Servers", "Providers", "Models")
    // Web parity: the settings header shows "OpenCode Desktop · v<version>".
    var version by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        version = try {
            backendSession.api.globalHealth().version
        } catch (_: Exception) {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        title = stringResource(R.string.settings),
                        subtitle = version?.let { stringResource(R.string.desktop_version, it) },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = MaterialTheme.spacing.small) {
                tabs.forEachIndexed { index, title ->
                    val selected = tab == index
                    Tab(
                        selected = selected,
                        onClick = { tab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (tab) {
                0 -> GeneralTab()
                1 -> ShortcutsTab()
                2 -> ServersTab(onSwitchBackend)
                3 -> ProvidersTab()
                4 -> ModelsTab()
            }
        }
    }
}

// --- General ---------------------------------------------------------------


@Composable
internal fun GeneralTab() {
    val settings by AppSettingsStore.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        item { SectionHeader(stringResource(R.string.settings_general)) }
        // NOTE: the Language, Terminal shell and Terminal font controls were
        // removed: the app ships a single locale and has no terminal UI, so
        // those dropdowns persisted a value that nothing ever read.
        item {
            // Web parity: this switch is rendered disabled in the current
            // server build (verified with Playwright), so it is not editable.
            SettingSwitch(
                title = stringResource(R.string.settings_auto_accept_permissions),
                subtitle = stringResource(R.string.settings_permission_requests_will_be_automatically_approv),
                checked = settings.autoApprove,
                enabled = false,
                onCheckedChange = { AppSettingsStore.setAutoApprove(it) },
            )
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_show_reasoning_summaries),
                subtitle = stringResource(R.string.settings_display_model_reasoning_summaries_in_the_timelin),
                checked = settings.showReasoningSummaries,
                onCheckedChange = { AppSettingsStore.setShowReasoningSummaries(it) },
            )
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_expand_shell_tool_parts),
                subtitle = stringResource(R.string.settings_show_shell_tool_parts_expanded_by_default_in_the),
                checked = settings.shellToolPartsExpanded,
                onCheckedChange = { AppSettingsStore.setShellToolPartsExpanded(it) },
            )
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_expand_edit_tool_parts),
                subtitle = stringResource(R.string.settings_show_edit_write_and_patch_tool_parts_expanded_by),
                checked = settings.editToolPartsExpanded,
                onCheckedChange = { AppSettingsStore.setEditToolPartsExpanded(it) },
            )
        }

        item { SectionHeader(stringResource(R.string.settings_appearance)) }
        item {
            SettingDropdown(
                title = stringResource(R.string.settings_color_scheme),
                subtitle = stringResource(R.string.settings_choose_whether_opencode_follows_the_system_light),
                value = settings.colorScheme,
                options = listOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                onSelect = { AppSettingsStore.setColorScheme(it) },
            )
        }
        item {
            SettingDropdown(
                title = stringResource(R.string.settings_theme),
                subtitle = stringResource(R.string.settings_customise_how_opencode_is_themed),
                value = com.opencode.android.ui.theme.normalizeThemeId(settings.theme),
                options = listOf("default" to "Default", "matrix" to "Matrix", "aqua" to "Aqua"),
                onSelect = { AppSettingsStore.setTheme(it) },
            )
        }
        // Web parity: three free-form font family fields (empty = system default).
        item {
            SettingTextInput(
                title = stringResource(R.string.settings_ui_font),
                subtitle = stringResource(R.string.settings_customise_the_font_used_throughout_the_interface),
                value = settings.sansFont,
                placeholder = stringResource(R.string.settings_system_sans),
                onValueChange = { AppSettingsStore.setSansFont(it) },
            )
        }
        // Web parity: two free-form font family fields (empty = system default).
        // The terminal font field is omitted (no terminal UI in this app).
        item {
            SettingTextInput(
                title = stringResource(R.string.settings_code_font),
                subtitle = stringResource(R.string.settings_customise_the_font_used_in_code_blocks),
                value = settings.monoFont,
                placeholder = stringResource(R.string.settings_system_mono),
                onValueChange = { AppSettingsStore.setMonoFont(it) },
            )
        }

        item { SectionHeader(stringResource(R.string.settings_system_notifications)) }
        item {
            SettingSwitch(stringResource(R.string.settings_agent), stringResource(R.string.settings_notify_agent_sub), settings.notifyAgent) { AppSettingsStore.setNotifyAgent(it) }
        }
        item {
            SettingSwitch(stringResource(R.string.settings_permissions), stringResource(R.string.settings_notify_permissions_sub), settings.notifyPermissions) { AppSettingsStore.setNotifyPermissions(it) }
        }
        item {
            SettingSwitch(stringResource(R.string.settings_errors), stringResource(R.string.settings_notify_errors_sub), settings.notifyErrors) { AppSettingsStore.setNotifyErrors(it) }
        }

        item { SectionHeader(stringResource(R.string.settings_sound_effects)) }
        // Web parity: each sound row is a single select (no separate enable
        // switch). Selecting a sound also marks it enabled.
        item {
            SettingSoundDropdown(
                title = stringResource(R.string.settings_agent),
                subtitle = stringResource(R.string.settings_play_sound_when_the_agent_is_complete_or_needs_a),
                value = settings.soundAgent,
            ) { AppSettingsStore.setSoundAgent(it); AppSettingsStore.setSoundAgentEnabled(true) }
        }
        item {
            SettingSoundDropdown(
                title = stringResource(R.string.settings_permissions),
                subtitle = stringResource(R.string.settings_play_sound_when_a_permission_is_required),
                value = settings.soundPermissions,
            ) { AppSettingsStore.setSoundPermissions(it); AppSettingsStore.setSoundPermissionsEnabled(true) }
        }
        item {
            SettingSoundDropdown(
                title = stringResource(R.string.settings_errors),
                subtitle = stringResource(R.string.settings_play_sound_when_an_error_occurs),
                value = settings.soundErrors,
            ) { AppSettingsStore.setSoundErrors(it); AppSettingsStore.setSoundErrorsEnabled(true) }
        }

        item { SectionHeader(stringResource(R.string.settings_session_guard)) }
        item { SessionGuardSection() }
        item {
            SettingSwitch(
                stringResource(R.string.settings_auto_adopt),
                stringResource(R.string.settings_auto_adopt_sub),
                settings.autoAdoptGuard,
            ) { AppSettingsStore.setAutoAdoptGuard(it) }
        }

        item { SectionHeader(stringResource(R.string.settings_advanced)) }
        item { SettingSwitch(stringResource(R.string.settings_file_tree), stringResource(R.string.settings_file_tree_sub), settings.showFileTree) { AppSettingsStore.setShowFileTree(it) } }
        item { SettingSwitch(stringResource(R.string.settings_command_palette), stringResource(R.string.settings_command_palette_sub), settings.showCommandPalette) { AppSettingsStore.setShowCommandPalette(it) } }
        item { SettingSwitch(stringResource(R.string.settings_server_status), stringResource(R.string.settings_server_status_sub), settings.showServerStatus) { AppSettingsStore.setShowServerStatus(it) } }
        item { SettingSwitch(stringResource(R.string.settings_show_agent), stringResource(R.string.settings_show_agent_sub), settings.showCustomAgents) { AppSettingsStore.setShowCustomAgents(it) } }

        item { SectionHeader(stringResource(R.string.settings_server)) }
        item { ServerSection() }

        item { SectionHeader(stringResource(R.string.settings_backup)) }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            // SAF document contracts: no storage permission and no FileProvider
            // entry needed, so the feature cannot destabilise the manifest.
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
                    "application/json",
                ),
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(AppSettingsStore.exportJson().toByteArray())
                        }
                    }.onSuccess {
                        UserMessages.post(R.string.settings_exported)
                    }.onFailure { e ->
                        UserMessages.post(R.string.could_not_export_settings, e.message ?: "")
                    }
                }
            }
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val text = runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()
                            ?.use { it.readText() }
                    }.getOrNull()
                    val failure = if (text == null) {
                        "could not read the file"
                    } else {
                        AppSettingsStore.importJson(text)
                    }
                    if (failure == null) {
                        UserMessages.post(R.string.settings_imported)
                    } else {
                        UserMessages.post(R.string.could_not_import_settings, failure)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("opencode-settings.json") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_export))
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_import))
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.settings_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Spacer(Modifier.height(MaterialTheme.spacing.large)) }
    }
}

@Composable
// Web-parity endpoints the app did not expose yet: POST /global/upgrade and
// GET /skill (Settings → Server).
private fun ServerSection() {
    val backendSession = com.opencode.android.ui.LocalBackendSession.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showSkills by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.settings_update_server))
            androidx.compose.material3.TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = runCatching {
                            backendSession.api.globalUpgrade(
                                kotlinx.serialization.json.JsonObject(emptyMap()),
                            ).close()
                        }.isSuccess
                        busy = false
                        UserMessages.post(
                            if (ok) R.string.settings_update_requested else R.string.settings_update_failed,
                        )
                    }
                },
            ) { Text(stringResource(R.string.settings_update_server)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.settings_skills))
            androidx.compose.material3.TextButton(onClick = {
                scope.launch {
                    skills = runCatching {
                        backendSession.api.getSkills().mapNotNull { el ->
                            (el as? kotlinx.serialization.json.JsonObject)
                                ?.get("name")
                                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        }
                    }.getOrDefault(emptyList())
                    showSkills = true
                }
            }) { Text(stringResource(R.string.settings_skills)) }
        }
    }

    if (showSkills) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSkills = false },
            title = { Text(stringResource(R.string.settings_skills)) },
            text = {
                if (skills.isEmpty()) {
                    Text(stringResource(R.string.settings_no_skills))
                } else {
                    Column { skills.forEach { Text(it) } }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showSkills = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun SessionGuardSection() {
    val backendSession = com.opencode.android.ui.LocalBackendSession.current
    val health by produceState<com.opencode.android.domain.SessionGuardHealth?>(initialValue = null) {
        value = runCatching { backendSession.api.sessionGuardHealth() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
    }
    val status = when {
        health == null -> stringResource(R.string.settings_session_guard_inactive)
        health?.ok == true -> stringResource(R.string.settings_session_guard_active)
        else -> stringResource(R.string.settings_session_guard_offline)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.extraSmall),
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        health?.metrics?.let { metrics ->
            Text(
                text = stringResource(
                    R.string.settings_session_guard_metrics,
                    metrics.requests,
                    metrics.errors,
                    metrics.avgMs,
                    metrics.maxMs,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        health?.let {
            Text(
                text = stringResource(R.string.settings_session_guard_sessions, it.guardedSessions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SettingSoundDropdown(
    title: String,
    subtitle: String,
    value: String,
    onSelect: (String) -> Unit,
) {
    SettingDropdown(
        title = title,
        subtitle = subtitle,
        value = value,
        options = listOf(
            "staplebops-01" to "Staplebops 01",
            "staplebops-02" to "Staplebops 02",
            "nope-03" to "Nope 03",
        ),
        onSelect = onSelect,
    )
}
