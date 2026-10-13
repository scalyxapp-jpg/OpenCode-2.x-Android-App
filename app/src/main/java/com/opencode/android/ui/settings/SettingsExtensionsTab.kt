package com.opencode.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.opencode.android.R
import com.opencode.android.domain.McpEntry
import com.opencode.android.domain.McpResourceEntry
import com.opencode.android.domain.PluginEntry
import com.opencode.android.domain.SkillEntry
import com.opencode.android.ui.LocalBackendSession
import com.opencode.android.ui.theme.spacing
import kotlinx.coroutines.launch

/**
 * Settings → Extensions, mirroring the web's three sub-tabs (MCPs, Plugins,
 * Skills).
 *
 * Verified against the running server (`GET /api/mcp`, `GET /api/plugin`,
 * `GET /api/skill`, `GET /api/mcp/resource`) and against the web bundle, which is
 * where the toggle endpoints come from:
 *
 * ```
 * POST /api/experimental/mcp/{name}/connect      -> 204   (switch on)
 * POST /api/experimental/mcp/{name}/disconnect   -> 204   (switch off)
 * GET  /api/mcp                                  -> [{name, status:{status}}]
 * GET  /api/plugin                               -> [{id, source, features, state}]
 * GET  /api/skill                                -> [{id, name, description}]
 * ```
 *
 * The list is project-agnostic ("available to all projects", like the web) and the
 * toggles update the row optimistically, then re-read the server state so a
 * rejected change cannot look like success.
 */
@Composable
internal fun ExtensionsTab() {
    val backendSession = LocalBackendSession.current
    val scope = rememberCoroutineScope()
    val api = backendSession.api

    var section by remember { mutableStateOf(0) } // 0 MCPs · 1 Plugins · 2 Skills
    var mcps by remember { mutableStateOf<List<McpEntry>>(emptyList()) }
    var plugins by remember { mutableStateOf<List<PluginEntry>>(emptyList()) }
    var skills by remember { mutableStateOf<List<SkillEntry>>(emptyList()) }
    var resources by remember { mutableStateOf<List<McpResourceEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyMcp by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            mcps =
                api
                    .getMcpServers()
                    .map { (name, status) -> McpEntry(name = name, status = status.status ?: "unknown") }
                    .sortedBy { it.name }
            plugins = api.getPlugins().sortedBy { it.id }
            skills = api.getSkills().sortedWith(compareBy({ it.name ?: it.id }))
            // Resources are a per-server detail; a failure must not blank the list.
            resources = runCatching { api.getMcpResources() }.getOrDefault(emptyList())
        } catch (e: Exception) {
            error = e.message ?: "Could not load extensions"
        } finally {
            loading = false
        }
    }

    fun toggleMcp(
        entry: McpEntry,
        enable: Boolean,
    ) {
        busyMcp = entry.name
        // Optimistic: the switch must answer immediately; the reload below is the
        // truth (a 404/409 for an unknown server flips it back).
        mcps =
            mcps.map {
                if (it.name == entry.name) {
                    it.copy(status = if (enable) "pending" else "disabled")
                } else {
                    it
                }
            }
        scope.launch {
            try {
                if (enable) api.connectMcp(entry.name).close() else api.disconnectMcp(entry.name).close()
            } catch (e: Exception) {
                error = "${entry.name}: ${e.message}"
            } finally {
                busyMcp = null
                reload++
            }
        }
    }

    fun authenticate(entry: McpEntry) {
        busyMcp = entry.name
        scope.launch {
            try {
                api.authenticateMcp(entry.name).close()
            } catch (e: Exception) {
                error = "${entry.name}: ${e.message}"
            } finally {
                busyMcp = null
                reload++
            }
        }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                listOf(
                    stringResource(R.string.extensions_tab_mcps),
                    stringResource(R.string.extensions_tab_plugins),
                    stringResource(R.string.extensions_tab_skills),
                ).forEachIndexed { index, label ->
                    FilterChip(
                        selected = section == index,
                        onClick = { section = index },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }

        item {
            SectionHeader(
                stringResource(
                    when (section) {
                        0 -> R.string.extensions_tab_mcps
                        1 -> R.string.extensions_tab_plugins
                        else -> R.string.extensions_tab_skills
                    },
                ),
            )
        }

        item {
            // The web's note: these come from the server config, not from here.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.extensions_available_to_all_projects),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.extensions_manage_in_config),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (loading) {
            item {
                Text(
                    text = stringResource(R.string.extensions_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (section) {
            0 -> {
                if (!loading && mcps.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.mcp_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(mcps, key = { "mcp-${it.name}" }) { entry ->
                    val resourceCount = resources.count { it.server == entry.name }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(MaterialTheme.spacing.cardPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text =
                                        listOfNotNull(
                                            mcpStatusLabel(entry.status),
                                            if (resourceCount > 0) {
                                                stringResource(R.string.extensions_resource_count, resourceCount)
                                            } else {
                                                null
                                            },
                                        ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        if (entry.status == "connected") {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                                if (entry.needsAuth) {
                                    TextButton(
                                        onClick = { authenticate(entry) },
                                        enabled = busyMcp != entry.name,
                                    ) {
                                        Text(stringResource(R.string.mcp_click_to_authenticate))
                                    }
                                }
                            }
                            Switch(
                                checked = entry.enabled,
                                enabled = busyMcp != entry.name,
                                onCheckedChange = { toggleMcp(entry, it) },
                            )
                        }
                    }
                }
            }

            1 -> {
                items(
                    plugins.filter {
                        query.isBlank() ||
                            it.id.contains(query, ignoreCase = true) ||
                            (it.sourceType ?: "").contains(query, ignoreCase = true)
                    },
                    key = { "plugin-${it.id}" },
                ) { plugin ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = MaterialTheme.spacing.extraSmall),
                    ) {
                        Text(
                            text = plugin.id,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                listOfNotNull(
                                    plugin.sourceType,
                                    plugin.status,
                                    plugin.features.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                items(
                    skills.filter {
                        query.isBlank() ||
                            it.id.contains(query, ignoreCase = true) ||
                            (it.name ?: "").contains(query, ignoreCase = true) ||
                            (it.description ?: "").contains(query, ignoreCase = true)
                    },
                    key = { "skill-${it.id}" },
                ) { skill ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = MaterialTheme.spacing.extraSmall),
                    ) {
                        Text(
                            text = skill.name ?: skill.id,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        skill.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Counts at the bottom, so an empty list is distinguishable from a broken one.
        item {
            Text(
                text =
                    stringResource(
                        R.string.extensions_counts,
                        mcps.count { it.status == "connected" },
                        mcps.size,
                        plugins.size,
                        skills.size,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
            )
        }
    }
}

/** Web wording for an MCP status (`mcp.status.*`). */
@Composable
private fun mcpStatusLabel(status: String): String =
    when (status) {
        "connected" -> stringResource(R.string.mcp_status_connected)
        "disabled" -> stringResource(R.string.mcp_status_disabled)
        "failed" -> stringResource(R.string.mcp_status_failed)
        "needs_auth", "needs_client_registration" -> stringResource(R.string.mcp_status_needs_auth)
        "pending" -> stringResource(R.string.mcp_status_pending)
        else -> status
    }
