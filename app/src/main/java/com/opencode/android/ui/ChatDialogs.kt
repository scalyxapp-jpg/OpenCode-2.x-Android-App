package com.opencode.android.ui
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.domain.Model
import com.opencode.android.domain.Part
import com.opencode.android.ui.theme.spacing
import com.opencode.android.ui.settings.SectionHeader
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import com.opencode.android.domain.ContextItem
import com.opencode.android.domain.McpEntry
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.QuestionItem
import com.opencode.android.domain.QuestionOption
import com.opencode.android.domain.SessionQuestion
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.opencode.android.R

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun RenameDialog(
    title: String,
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newTitle by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(newTitle) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun ContextPanelDialog(
    info: ContextInfo?,
    messages: List<ContextItem>,
    // Resolved display name (header/Review/dialog must agree); falls back to the
    // raw id inside the caller.
    modelName: String? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.context_usage)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
            ) {
                item { SectionHeader(stringResource(R.string.session_info)) }
                info?.let {
                    item { ContextRow("Provider", it.provider?.takeIf { p -> p.isNotBlank() } ?: "—") }
                    item { ContextRow("Model", modelName?.takeIf { m -> m.isNotBlank() } ?: it.model?.takeIf { m -> m.isNotBlank() } ?: "—") }
                    it.contextLimit?.let { limit ->
                        item { ContextRow(stringResource(R.string.context_limit), "$limit") }
                    }
                    item { ContextRow("Messages", "${it.userMessages + it.assistantMessages}") }
                    item { ContextRow("User messages", "${it.userMessages}") }
                    item { ContextRow("Assistant messages", "${it.assistantMessages}") }
                    // Token/cost figures are meaningless (and noisy) before the
                    // first turn; show a hint instead of a wall of zeros.
                    val hasUsage = it.totalTokens > 0L || it.totalCost > 0.0
                    if (hasUsage) {
                        item { ContextRow("Total tokens", "${it.totalTokens}") }
                        it.contextLimit?.let { limit ->
                            val pct = if (limit > 0) (it.totalTokens * 100 / limit).coerceAtMost(100) else 0
                            item { ContextRow(stringResource(R.string.usage), "$pct%") }
                        }
                        item { ContextRow("Input-Tokens", "${it.inputTokens}") }
                        item { ContextRow("Output-Tokens", "${it.outputTokens}") }
                        item { ContextRow("Reasoning-Tokens", "${it.reasoningTokens}") }
                        item { ContextRow("Cache (read/write)", "${it.cacheRead} / ${it.cacheWrite}") }
                        item { ContextRow(stringResource(R.string.total_cost), com.opencode.android.util.formatCost(it.totalCost)) }
                    } else {
                        item {
                            Text(
                                text = stringResource(R.string.no_usage_yet),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
                            )
                        }
                    }
                    it.sessionCreated?.let { created ->
                        item { ContextRow("Created", formatDateTime(created)) }
                    }
                    it.lastActivity?.let { last ->
                        item { ContextRow("Last activity", formatDateTime(last)) }
                    }
                    if (hasUsage) {
                        item { SectionHeader(stringResource(R.string.context_breakdown)) }
                        item { ContextRow("User", "${it.userMessages}") }
                        item { ContextRow("Assistant", "${it.assistantMessages}") }
                    }
                }
                // Only show the "Messages" section when there is something in
                // it — otherwise it was a heading with no rows under it.
                if (messages.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.messages)) }
                }
                items(messages, key = { it.id ?: it.hashCode().toString() }) { msg ->
                    val text = msg.text ?: ""
                    if (text.isNotBlank()) {
                        Column(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall)) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/**
 * Web "/mcp" dialog, reproduced 1:1:
 *   title "MCPs" · subtitle "N of M enabled" · search box · one row per server
 *   (name + status text + switch). A server that needs auth shows
 *   "Click to authenticate" instead of a plain status.
 */
@Composable
internal fun McpDialog(
    servers: List<McpEntry>,
    onToggle: (name: String, enable: Boolean) -> Unit,
    onAuth: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(servers, query) {
        if (query.isBlank()) servers
        else servers.filter { it.name.contains(query, ignoreCase = true) }
    }
    val enabledCount = servers.count { it.enabled }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.mcp_enabled_of_total,
                        servers.size,
                        enabledCount,
                        servers.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (servers.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.mcp_empty),
                        compact = true,
                    )
                    return@Column
                }
                PickerSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.mcp_search),
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                )
                LazyColumn {
                    items(visible, key = { it.name }) { server ->
                        // The whole row authenticates when the server needs it;
                        // previously only the small status text was tappable.
                        val onAuthClick: (() -> Unit)? =
                            if (server.needsAuth) ({ onAuth(server.name) }) else null
                        PickerRow(
                            title = server.name,
                            subtitle = if (server.needsAuth) {
                                stringResource(R.string.mcp_click_to_authenticate)
                            } else {
                                server.status
                            },
                            subtitleColor = if (server.needsAuth) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            onClick = onAuthClick,
                            trailing = {
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { onToggle(server.name, it) },
                                )
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

/**
 * Tool-permission prompt, reproduced from the web dialog: a title, the tool
 * description, and three actions — Deny ("reject"), Allow always ("always"),
 * Allow once ("once"). Not dismissable by tapping outside: the agent is blocked
 * until an answer is given.
 */
@Composable
internal fun PermissionDialog(
    request: PermissionRequest,
    onReply: (String) -> Unit,
) {
    AlertDialog(
        // No onDismissRequest action: a permission must be explicitly answered.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.permission_required)) },
        text = {
            // Option rows appear/disappear with selection (custom input);
            // animate the height so the card breathes instead of snapping.
            Column(
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(
                    text = request.title ?: request.permission ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                request.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onReply("once") }) {
                Text(stringResource(R.string.permission_allow_once))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                OutlinedButton(onClick = { onReply("reject") }) {
                    Text(stringResource(R.string.permission_deny))
                }
                OutlinedButton(onClick = { onReply("always") }) {
                    Text(stringResource(R.string.permission_allow_always))
                }
            }
        },
    )
}

@Composable
internal fun ContextRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun formatDateTime(epochMillis: Long): String {
    return try {
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy, HH:mm", java.util.Locale.ENGLISH)
        sdf.format(java.util.Date(epochMillis))
    } catch (e: Exception) {
        ""
    }
}

internal fun jsonStr(element: kotlinx.serialization.json.JsonElement?): String? {
    return (element as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }
}

internal fun jsonBool(element: kotlinx.serialization.json.JsonElement?): Boolean {
    return try {
        (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    } catch (e: Exception) {
        false
    }
}

// Question items embedded in a question tool call's state.input.questions.
internal fun parseToolQuestions(part: Part): List<QuestionItem> {
    return try {
        val state = part.state as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val input = state["input"] as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val arr = input["questions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val options = (obj["options"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { optionElement ->
                    val optionObj = optionElement as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    val label = jsonStr(optionObj["label"]) ?: return@mapNotNull null
                    QuestionOption(
                        label = label,
                        description = jsonStr(optionObj["description"]),
                    )
                } ?: emptyList()
            QuestionItem(
                question = jsonStr(obj["question"]),
                header = jsonStr(obj["header"]),
                options = options,
                multiple = jsonBool(obj["multiple"] ?: obj["multiSelect"]),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// Normalized tool name across both schemas (parts[].tool vs content[].name).
internal fun Part.toolName(): String? = tool ?: name

// Mirrors the web agent-question card (single/multi select + custom answer).
@Composable
internal fun QuestionCard(
    requestId: String?,
    item: QuestionItem,
    onAnswer: (requestId: String, answers: List<String>) -> Unit,
    onReject: (requestId: String) -> Unit,
    // No live request for this card (already answered/expired): show it as a
    // record instead of a dead card with a permanently disabled Submit button.
    readOnly: Boolean = false,
) {
    val header = item.header
    val text = item.question ?: stringResource(R.string.question_fallback)
    val options = item.options
    val multi = item.multiple
    val interactive = !readOnly && requestId != null
    var selected by remember(requestId, text) { mutableStateOf(setOf<Int>()) }
    var customAnswer by remember(requestId, text) { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            header?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            if (interactive) {
                Text(
                    text = stringResource(if (multi) R.string.select_all_answers else R.string.select_one_answer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            options.forEachIndexed { index, option ->
                val checked = index in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (interactive) {
                                // toggleable gives TalkBack the radio/checkbox
                                // role + checked state on the whole row; the
                                // inner control is a non-interactive indicator.
                                Modifier.toggleable(
                                    value = checked,
                                    role = if (multi) Role.Checkbox else Role.RadioButton,
                                    onValueChange = { now ->
                                        selected = if (multi) {
                                            if (now) selected + index else selected - index
                                        } else {
                                            setOf(index)
                                        }
                                    },
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(vertical = MaterialTheme.spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    if (multi) {
                        Checkbox(checked = checked, onCheckedChange = null)
                    } else {
                        RadioButton(selected = checked, onClick = null)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                        option.description?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (interactive) {
                OutlinedTextField(
                    value = customAnswer,
                    onValueChange = { customAnswer = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.type_your_answer)) },
                    maxLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    // A custom answer alone is a valid reply, so either input
                    // enables Submit — the request id is guaranteed here.
                    val canSubmit = selected.isNotEmpty() || customAnswer.isNotBlank()
                    FilledTonalButton(
                        onClick = {
                            val answers = selected.sorted().map { options[it].label } +
                                (if (customAnswer.isNotBlank()) listOf(customAnswer) else emptyList())
                            requestId?.let { onAnswer(it, answers) }
                        },
                        enabled = canSubmit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.submit))
                    }
                    OutlinedButton(onClick = { requestId?.let { onReject(it) } }) {
                        Text(stringResource(R.string.reject))
                    }
                }
            }
        }
    }
}

/**
 * A whole agent-question request (one or more questions) as a single card with
 * one Submit. The reply protocol is `answers: string[][]` — one answer list per
 * question — so all questions must be answered together; the old per-question
 * cards sent only a single list and mismatched multi-question requests.
 */
@Composable
internal fun QuestionRequestCard(
    question: SessionQuestion,
    onAnswer: (requestId: String, answers: List<List<String>>) -> Unit,
    onReject: (requestId: String) -> Unit,
    readOnly: Boolean = false,
) {
    val items = question.questions
    if (items.isEmpty()) return
    val interactive = !readOnly
    // Selection state is keyed per question index.
    val selections = remember(question.id, items.size) { mutableStateMapOf<Int, Set<Int>>() }
    val customs = remember(question.id, items.size) { mutableStateMapOf<Int, String>() }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(MaterialTheme.spacing.cardPadding)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            items.forEachIndexed { qIndex, item ->
                val multi = item.multiple
                val selected = selections[qIndex] ?: emptySet()
                item.header?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = item.question ?: stringResource(R.string.question_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (interactive) {
                    Text(
                        text = stringResource(
                            if (multi) R.string.select_all_answers else R.string.select_one_answer,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.options.forEachIndexed { oIndex, option ->
                    val checked = oIndex in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (interactive) {
                                    Modifier.toggleable(
                                        value = checked,
                                        role = if (multi) Role.Checkbox else Role.RadioButton,
                                        onValueChange = { now ->
                                            selections[qIndex] = if (multi) {
                                                if (now) selected + oIndex else selected - oIndex
                                            } else {
                                                setOf(oIndex)
                                            }
                                        },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = MaterialTheme.spacing.extraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        if (multi) {
                            Checkbox(checked = checked, onCheckedChange = null)
                        } else {
                            RadioButton(selected = checked, onClick = null)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                            option.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (interactive) {
                    OutlinedTextField(
                        value = customs[qIndex] ?: "",
                        onValueChange = { customs[qIndex] = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.type_your_answer)) },
                        maxLines = 3,
                    )
                }
                if (qIndex != items.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
                }
            }
            if (interactive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    val canSubmit = items.indices.any { qi ->
                        (selections[qi]?.isNotEmpty() == true) || !customs[qi].isNullOrBlank()
                    }
                    FilledTonalButton(
                        onClick = {
                            val answers = items.indices.map { qi ->
                                val labels = (selections[qi] ?: emptySet())
                                    .sorted()
                                    .map { items[qi].options[it].label }
                                val custom = customs[qi]?.takeIf { it.isNotBlank() }
                                if (custom != null) labels + custom else labels
                            }
                            onAnswer(question.id, answers)
                        },
                        enabled = canSubmit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.submit))
                    }
                    OutlinedButton(onClick = { onReject(question.id) }) {
                        Text(stringResource(R.string.reject))
                    }
                }
            }
        }
    }
}
