package com.opencode.android.ui.centre

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.android.R
import com.opencode.android.data.AuditEvent
import com.opencode.android.data.AuditPolicy
import com.opencode.android.ui.copyToClipboard
import com.opencode.android.ui.theme.spacing
import com.opencode.android.ui.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The cockpit: one screen that answers the three questions a phone user actually has.
 *
 * 1. **What is running right now** — with a per-session stop and the emergency stop.
 * 2. **What needs me** — blocked turns (permissions/questions) and sends still waiting.
 * 3. **What did today cost / what did I do** — the server's day aggregate and the local
 *    audit log.
 *
 * Deliberately not another session list: the home screen already lists sessions. This one
 * only shows what is *actionable* or *live*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CentreScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    viewModel: CentreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmStopAll by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (confirmStopAll) {
        AlertDialog(
            onDismissRequest = { confirmStopAll = false },
            title = { Text(stringResource(R.string.centre_stop_all)) },
            text = { Text(stringResource(R.string.centre_stop_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStopAll = false
                    viewModel.stopAll()
                }) {
                    Text(stringResource(R.string.centre_stop_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Running first (all of them), then a preview of the idle ones.
    val busyRows = state.rows.filter { it.running || it.retrying }
    val idleRows = state.rows.filterNot { it.running || it.retrying }.take(CentrePolicy.PREVIEW_ROWS)
    val idleHidden = state.rows.count { !it.running && !it.retrying } - idleRows.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.centre_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    if (state.audit.isNotEmpty()) {
                        IconButton(onClick = {
                            copyToClipboard(
                                context,
                                context.getString(R.string.centre_audit),
                                AuditPolicy.export(state.audit),
                            )
                            toast(context, context.getString(R.string.copied))
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            if (state.loading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(MaterialTheme.spacing.small),
                        )
                    }
                }
            }

            // --- the derived picture --------------------------------------------
            item {
                SectionRow(
                    title = stringResource(R.string.centre_insights),
                    subtitle =
                        stringResource(
                            R.string.centre_summary,
                            state.summary.running,
                            state.summary.needsYou,
                            CentrePolicy.costLabel(state.summary.costToday) ?: "—",
                        ),
                )
            }
            items(state.insights.take(INSIGHT_PREVIEW)) { insight ->
                InsightRow(
                    insight = insight,
                    onAct = {
                        when (insight.action) {
                            InsightAction.OpenSession -> insight.sessionId?.let(onOpenSession)
                            else -> viewModel.execute(insight)
                        }
                    },
                )
            }

            // --- what is running ------------------------------------------------
            item {
                SectionRow(
                    title = stringResource(R.string.centre_running),
                    trailing =
                        if (state.runningCount > 0) {
                            {
                                TextButton(onClick = { confirmStopAll = true }) {
                                    Text(stringResource(R.string.centre_stop_all))
                                }
                            }
                        } else {
                            null
                        },
                )
            }
            if (state.rows.isEmpty()) {
                item { EmptyLine(stringResource(R.string.centre_nothing_running)) }
            }
            // Running rows are all shown (they are the point of the screen); the idle ones
            // are capped: the cockpit is a glance, and 50 session rows used to push "Needs
            // you", "Today" and the audit log out of reach. The full list stays on Home.
            items(busyRows) { row ->
                SessionRow(
                    row = row,
                    onClick = { onOpenSession(row.sessionId) },
                    onStop = { viewModel.stop(row) },
                )
            }
            items(idleRows) { row ->
                SessionRow(
                    row = row,
                    onClick = { onOpenSession(row.sessionId) },
                    onStop = { viewModel.stop(row) },
                )
            }
            if (idleHidden > 0) {
                item {
                    Text(
                        text = stringResource(R.string.centre_more_sessions, idleHidden),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- what needs me -------------------------------------------------
            item {
                Spacer(Modifier.height(MaterialTheme.spacing.small))
                SectionRow(title = stringResource(R.string.centre_needs_you))
            }
            if (state.needs.isEmpty()) {
                item { EmptyLine(stringResource(R.string.centre_nothing_waiting)) }
            }
            items(state.needs) { need ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSession(need.sessionId) },
                ) {
                    Column(modifier = Modifier.padding(MaterialTheme.spacing.small)) {
                        Text(
                            text =
                                when (need.kind) {
                                    NeedKind.Permission -> "🔐 ${need.title}"
                                    NeedKind.Question -> "❓ ${need.title}"
                                    NeedKind.Outbox -> "📤 ${need.title}"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        need.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // --- today ---------------------------------------------------------
            item {
                Spacer(Modifier.height(MaterialTheme.spacing.small))
                SectionRow(
                    title = stringResource(R.string.centre_today),
                    subtitle = state.summary.trend,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    StatTile(
                        label = stringResource(R.string.centre_cost),
                        value = CentrePolicy.costLabel(state.costToday) ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.centre_sessions),
                        value = state.sessionsToday?.toString() ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.centre_prompts),
                        value = state.promptsToday?.toString() ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.centre_steps),
                        value = state.stepsToday?.toString() ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // --- the server behind it all --------------------------------------
            item {
                Spacer(Modifier.height(MaterialTheme.spacing.small))
                SectionRow(
                    title = stringResource(R.string.centre_server),
                    subtitle =
                        stringResource(
                            R.string.centre_mcp,
                            state.server.mcpConnected,
                            state.server.mcpTotal,
                        ) +
                            " · " +
                            stringResource(R.string.centre_skills_plugins, state.server.skills, state.server.plugins),
                )
            }
            items(state.server.branches) { (directory, branch) ->
                EmptyLine("${directory.substringAfterLast('/')} · $branch")
            }

            // --- audit ---------------------------------------------------------
            item {
                Spacer(Modifier.height(MaterialTheme.spacing.small))
                SectionRow(
                    title = stringResource(R.string.centre_audit),
                    subtitle = AuditPolicy.summary(state.audit),
                    trailing =
                        if (state.audit.isNotEmpty()) {
                            {
                                TextButton(onClick = { viewModel.clearAudit() }) {
                                    Text(stringResource(R.string.centre_clear))
                                }
                            }
                        } else {
                            null
                        },
                )
            }
            if (state.audit.isEmpty()) {
                item { EmptyLine(stringResource(R.string.centre_no_audit)) }
            }
            items(state.audit.take(AUDIT_PREVIEW)) { event ->
                AuditRow(event)
            }
            if (state.audit.size > AUDIT_PREVIEW) {
                item {
                    EmptyLine(stringResource(R.string.centre_audit_more, state.audit.size - AUDIT_PREVIEW))
                }
            }
        }
    }
}

/** How many audit rows the cockpit lists; the share action exports all of them. */
private const val AUDIT_PREVIEW = 12

/** How many derived statements are shown at once; the rest is noise on a phone. */
private const val INSIGHT_PREVIEW = 6

/**
 * One derived statement, with the action it suggests.
 *
 * The colour carries the severity: a warning about a wedged turn must be findable without
 * reading. The action text is the same everywhere because the button only calls
 * [CentreViewModel.execute] - the mapping lives in the ViewModel, not in the UI.
 */
@Composable
private fun InsightRow(
    insight: Insight,
    onAct: () -> Unit,
) {
    val container =
        when (insight.severity) {
            Severity.Critical -> MaterialTheme.colorScheme.errorContainer
            Severity.Warning -> MaterialTheme.colorScheme.tertiaryContainer
            Severity.Notice -> MaterialTheme.colorScheme.secondaryContainer
            Severity.Info -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val onContainer =
        when (insight.severity) {
            Severity.Critical -> MaterialTheme.colorScheme.onErrorContainer
            Severity.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
            Severity.Notice -> MaterialTheme.colorScheme.onSecondaryContainer
            Severity.Info -> MaterialTheme.colorScheme.onSurface
        }
    // TalkBack reads the title and detail as text; the severity is the part a screen reader
    // cannot see, so it is announced as the state of the row.
    val severityLabel =
        stringResource(
            when (insight.severity) {
                Severity.Critical -> R.string.centre_sev_critical
                Severity.Warning -> R.string.centre_sev_warning
                Severity.Notice -> R.string.centre_sev_notice
                Severity.Info -> R.string.centre_sev_info
            },
        )
    Surface(
        color = container,
        contentColor = onContainer,
        shape = MaterialTheme.shapes.small,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { stateDescription = severityLabel },
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                insight.detail.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (insight.action != InsightAction.None) {
                TextButton(onClick = onAct) {
                    Text(
                        text =
                            stringResource(
                                when (insight.action) {
                                    InsightAction.StopSession -> R.string.centre_stop
                                    InsightAction.SteerQueue -> R.string.centre_action_steer
                                    InsightAction.CompactSession -> R.string.centre_action_compact
                                    else -> R.string.centre_action_open
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionRow(
    row: CentreRow,
    onClick: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color =
            if (row.running || row.retrying) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        shape = MaterialTheme.shapes.small,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    if (row.running || row.retrying) {
                        Text(
                            text =
                                stringResource(
                                    if (row.retrying) R.string.centre_retrying else R.string.centre_running_now,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    CentrePolicy.costLabel(row.cost)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Context pressure per row: the session list already carries the tokens, the
                    // catalog the window - so this costs no request.
                    if (row.tokens > 0L) {
                        val limit = row.tokenLimit
                        Text(
                            text =
                                if (limit > 0L) {
                                    stringResource(
                                        R.string.centre_context,
                                        ((row.tokens.toDouble() * 100.0) / limit).toInt(),
                                        CentrePolicy.formatTokens(row.tokens),
                                    )
                                } else {
                                    CentrePolicy.formatTokens(row.tokens) + " tok"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (limit > 0L && row.tokens * 100.0 / limit >= 85.0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                    if (row.queueDepth > 0) {
                        Text(
                            text = stringResource(R.string.centre_queued_behind, row.queueDepth),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    row.directory?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (row.running || row.retrying) {
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.centre_stop))
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AuditRow(event: AuditEvent) {
    val stamp =
        remember(event.at) {
            SimpleDateFormat("MM-dd HH:mm", Locale.ENGLISH).format(Date(event.at))
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                text = stamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = AuditPolicy.label(event.kind),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = event.title,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        event.detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}
