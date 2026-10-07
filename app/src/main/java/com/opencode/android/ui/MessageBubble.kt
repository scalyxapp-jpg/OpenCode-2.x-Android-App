package com.opencode.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.opencode.android.domain.Message
import com.opencode.android.domain.Part
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.ui.theme.spacing
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import androidx.compose.material3.Icon


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: Message,
    pendingQuestions: List<SessionQuestion> = emptyList(),
    onCopyMessage: (label: String, text: String) -> Unit = { _, _ -> },
    onRevertMessage: (messageId: String) -> Unit = {},
    onForkMessage: (messageId: String) -> Unit = {},
    // Re-runs the last user turn (assistant messages only): after a stop or a
    // bad answer the user otherwise had no way forward except retyping.
    onRegenerate: () -> Unit = {},
    onAnswerQuestion: (requestId: String, answers: List<List<String>>) -> Unit = { _, _ -> },
    onRejectQuestion: (requestId: String) -> Unit = {},
    // Hoisted so each bubble doesn't subscribe to settings (perf).
    showReasoning: Boolean = false,
    // "Expand shell/edit tool parts" settings (web parity).
    shellToolPartsExpanded: Boolean = false,
    editToolPartsExpanded: Boolean = false,
    onOpenSession: (String) -> Unit = {},
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Support both structures:
    // - GET /session/{id}/message → info.role + parts[] (with .tool)
    // - GET /api/session/{id}/message → type/agent/model + text/content[] (with .name)
    val type = message.type?.trim()
    if (type == "model-switched") {
        // Subtle system line (mirrors the web model indicator change).
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.model_label, message.model?.id ?: "?"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val role = message.role ?: message.info?.role ?: type
    val isUser = role == "user"

    // Extract text from every source (skip blank results).
    // Dedupe consecutive identical parts: some servers repeat the same
    // reasoning/text chunk in parts[] snapshots, which rendered as
    // "Satz. Satz." inside one bubble.
    // Memoised on `message`: this body recomposes on every streaming tick for
    // every visible message, so re-parsing the part lists each time was waste
    // whenever the message itself had not changed.
    val text = remember(message) {
        val textBits = mutableListOf<String>()
        message.text?.takeIf { it.isNotBlank() }?.let { textBits.add(it) }
        message.parts
            .filter { it.type.trim() == "text" }
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            .let { textBits.addAll(it) }
        message.content
            .filter { it.type?.trim() == "text" }
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            .let { textBits.addAll(it) }
        textBits.fold(mutableListOf<String>()) { acc, s ->
            if (acc.lastOrNull()?.trim() != s.trim()) acc.add(s)
            acc
        }.joinToString("\n")
    }

    // Reasoning content renders as a collapsed "Thinking" section. The classic
    // web endpoint delivers parts[] (type=reasoning); the legacy endpoint uses
    // content[]. Reading only content[] meant reasoning NEVER showed for classic
    // sessions — verified live against the server.
    val reasoningText = remember(message) {
        (
            message.parts.filter { it.type.trim() == "reasoning" }.mapNotNull { it.text } +
                message.content.filter { it.type?.trim() == "reasoning" }.mapNotNull { it.text }
            )
            .filter { it.isNotBlank() }
            .fold(mutableListOf<String>()) { acc, s ->
                if (acc.lastOrNull()?.trim() != s.trim()) acc.add(s)
                acc
            }
            .joinToString("\n")
    }

    // NOTE: no logging here — MessageBubble recomposes on every streaming tick
    // and for every visible message; a Log.d in this body throttles the UI.

    // Normalize tool calls from both schemas (remembered: recomputed only when
    // the message itself changes, not on every recomposition).
    val toolParts = remember(message) {
        message.parts.filter { it.type.trim() == "tool" } +
            message.content
                .filter { it.type?.trim() == "tool" }
                .map {
                    Part(
                        id = it.id ?: "",
                        type = "tool",
                        tool = it.name,
                        name = it.name,
                        state = it.state,
                    )
                }
    }
    val questionParts = remember(toolParts) { toolParts.filter { it.toolName() == "question" } }
    val otherToolParts = remember(toolParts) { toolParts.filter { it.toolName() != "question" } }
    if (text.isBlank() && reasoningText.isBlank() && toolParts.isEmpty()) return

    // Meta line mirrors web: agent - model - duration - time. Memoised: the
    // bubble recomposes per streaming tick, but these only depend on `message`.
    val errorText = remember(message) { messageErrorText(message.error ?: message.info?.error) }
    val meta = remember(message, errorText) {
        val agent = message.agent ?: message.info?.agent
        val model = message.model?.id ?: message.info?.model?.id ?: message.info?.model?.model
        val created = message.time?.created ?: message.info?.time?.created
        val completed = message.time?.completed ?: message.info?.time?.completed
        val duration = formatDuration(
            if (created != null && completed != null && completed > created) completed - created else null
        )
        listOfNotNull(
            agent,
            model,
            duration,
            created?.let { formatTimestamp(it) },
            // Web appends "Interrupted" for aborted messages.
            errorText,
        ).joinToString(" · ")
    }
    val messageId = message.id ?: message.info?.id

    var reasoningExpanded by remember(messageId, reasoningText.length) { mutableStateOf(false) }
    // Long-press context menu (right-click equivalent) for per-message actions
    // like "Fork to new session" — no longer shown as buttons under every message.
    var showContextMenu by remember { mutableStateOf(false) }

    // Calm layout: user messages are bubbles, assistant messages are
    // full-width plain text (cards only for tools/questions/reasoning).
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        showContextMenu = true
                    },
                ),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
        if (isUser) {
            // A wine veil rather than a filled pink bubble: at full
            // primaryContainer a screen of user messages turned mostly pink.
            // A faint diagonal gradient plus a hairline border reads as "mine"
            // with depth, while keeping the black/white/wine identity and
            // adapting to light and dark without a second token.
            val bubbleShape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = 22.dp,
                bottomEnd = 8.dp,
            )
            Box(
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.17f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = bubbleShape,
                    ),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.medium,
                    ),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                if (text.isNotBlank()) {
                    // Markdown formatting (headings, lists, code, tables, inline styles).
                    MarkdownText(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // "Show reasoning summaries" setting (hoisted from the caller).
                if (showReasoning && reasoningText.isNotBlank()) {
                    // Resolved only when the reasoning block is drawn, so a
                    // streaming tick does not read resources for every bubble.
                    val expandedLabel = stringResource(R.string.expanded)
                    val collapsedLabel = stringResource(R.string.collapsed)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large,
                        onClick = { reasoningExpanded = !reasoningExpanded },
                        // A screen reader would otherwise announce only
                        // "Thinking ▼" and leave the state to be guessed from
                        // the arrow glyph.
                        modifier = Modifier.semantics {
                            // `this.role` on purpose: MessageBubble already has
                            // a local `val role` (the message author), which
                            // would otherwise shadow the semantics property.
                            this.role = Role.Button
                            stateDescription = if (reasoningExpanded) expandedLabel else collapsedLabel
                        },
                    ) {
Row(
                             modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = 2.dp),
                             verticalAlignment = Alignment.CenterVertically,
                             horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                         ) {

                             Text(
                                 text = stringResource(
                                     if (reasoningExpanded) {
                                         R.string.thinking_expanded
                                     } else {
                                         R.string.thinking_collapsed
                                     },
                                 ),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.primary,
                             )
                         }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = reasoningExpanded,
                        enter = androidx.compose.animation.expandVertically() +
                            androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() +
                            androidx.compose.animation.fadeOut(),
                    ) {
                        Text(
                            text = reasoningText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Web splits tools into two shapes:
                //  - read/search/list are grouped into an "Explored …" card
                //  - everything else (bash → "Shell", delegate_task, …) gets
                //    its own collapsible row.
                val exploreParts = remember(otherToolParts) {
                    otherToolParts.filter { isExploreTool(it.toolName()) }
                }
                val actionParts = remember(otherToolParts) {
                    otherToolParts.filterNot { isExploreTool(it.toolName()) }
                }
                if (exploreParts.isNotEmpty()) {
                    ToolSummaryCard(parts = exploreParts)
                }
                actionParts.forEach { part ->
                    ToolCallRow(
                        part = part,
                        onOpenSession = onOpenSession,
                        initiallyExpanded = defaultToolExpanded(
                            part,
                            shellExpanded = shellToolPartsExpanded,
                            editExpanded = editToolPartsExpanded,
                        ),
                    )
                }
                // Web "N Changed files" summary card (edit/write/patch).
                ChangedFilesCard(
                    files = remember(otherToolParts) { changedFiles(otherToolParts) },
                )
                // Inline question cards for question tool calls. The matching
                // pending request (by call id) wires up Submit/Ablehnen.
                questionParts.forEach { part ->
                    val match = pendingQuestions.firstOrNull { it.tool?.callID == part.id }
                    if (match != null) {
                        // Live request: answer all of its questions together.
                        QuestionRequestCard(
                            question = match,
                            onAnswer = onAnswerQuestion,
                            onReject = onRejectQuestion,
                        )
                    } else {
                        // No live request → render as a record, not a card with
                        // a permanently disabled Submit button.
                        parseToolQuestions(part).forEach { item ->
                            QuestionCard(
                                requestId = null,
                                item = item,
                                onAnswer = { _, _ -> },
                                onReject = {},
                                readOnly = true,
                            )
                        }
                    }
                }
                // Error state (mirrors web "Interrupted"/error display)
                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Meta line (web: "Commander · deepseek-v4-pro · 15s")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
        // All per-message actions (Copy/Fork/Revert) live in the long-press
        // context menu — no inline buttons under each message.
        } // end Column
        // Long-press context menu ("right-click"): per-message actions.
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
        ) {
            if (text.isNotBlank()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isUser) R.string.copy_message else R.string.copy_response,
                            ),
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onCopyMessage(if (isUser) "Message" else "Answer", text)
                    },
                )
            }
            if (!isUser) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.regenerate)) },
                    onClick = {
                        showContextMenu = false
                        onRegenerate()
                    },
                )
            }
            if (messageId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.fork_to_new_session)) },
                    onClick = {
                        showContextMenu = false
                        onForkMessage(messageId)
                    },
                )
                if (isUser) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.revert_message)) },
                        onClick = {
                            showContextMenu = false
                            onRevertMessage(messageId)
                        },
                    )
                }
            }
        }
    } // end Box
}
