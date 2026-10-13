package com.opencode.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.android.R
import com.opencode.android.ui.theme.spacing

@Composable
internal fun LiveStatusItem(
    liveState: kotlinx.coroutines.flow.StateFlow<LiveStreamState>,
    connected: Boolean,
    compacting: Boolean = false,
) {
    val live by liveState.collectAsStateWithLifecycle()
    LiveStatusIndicator(
        agent = live.agent,
        model = live.model,
        // Detailed activity: which tool is running, on what, and how much has been
        // written — instead of only "waiting"/"generating".
        status =
            liveStatus(
                connected = connected,
                compacting = compacting,
                thinking = live.thinking,
                waiting =
                    live.response.isBlank() &&
                        live.reasoning.isBlank() &&
                        live.parts.isEmpty(),
                parts = live.parts,
                streamingChars = live.response.length,
            ),
    )
}

@Composable
internal fun LiveReasoningItem(liveState: kotlinx.coroutines.flow.StateFlow<LiveStreamState>) {
    val live by liveState.collectAsStateWithLifecycle()
    // The setting controls reasoning summaries everywhere; the LIVE block ignored it,
    // so reasoning appeared even with the toggle off.
    val showReasoningLive = com.opencode.android.data.AppSettingsStore.state.value.showReasoningSummaries
    if (showReasoningLive && live.reasoning.isNotBlank()) {
        LiveReasoningBubble(
            text = live.reasoning,
            agent = live.agent,
            model = live.model,
            // The live section also stays mounted for a short `pendingPersist`
            // window after a step ends; the caret must not keep blinking then.
            streaming = live.streaming,
        )
    }
}

@Composable
internal fun LiveResponseItem(liveState: kotlinx.coroutines.flow.StateFlow<LiveStreamState>) {
    val live by liveState.collectAsStateWithLifecycle()
    if (live.response.isNotBlank()) {
        LiveResponseBubble(
            text = live.response,
            agent = live.agent,
            model = live.model,
            streaming = live.streaming,
        )
    }
}

@Composable
internal fun LiveToolsItem(
    liveState: kotlinx.coroutines.flow.StateFlow<LiveStreamState>,
    onOpenSession: (String) -> Unit,
    shellToolPartsExpanded: Boolean = false,
    editToolPartsExpanded: Boolean = false,
) {
    val live by liveState.collectAsStateWithLifecycle()
    if (live.parts.isNotEmpty()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Tool rows arrive one by one; animate the height so the
                    // list below glides instead of jumping per row.
                    .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            // Consecutive same-tool calls collapse into one row with a ×N
            // badge, so a "read ten files" storm does not produce ten rows and
            // ten recompositions of this column.
            androidx.compose.runtime
                .remember(live.parts) {
                    coalesceConsecutiveTools(live.parts)
                }.forEachIndexed { index, group ->
                    val part = group.part
                    val count = group.count
                    // Stable per-row identity so a new tool row does not recompose
                    // (and re-animate) every existing row. `group.id` is the FIRST
                    // call of the run (the representative part changes on every
                    // additional call).
                    androidx.compose.runtime.key(group.id.ifBlank { "tool-$index" }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                ToolCallRow(
                                    part = part,
                                    onOpenSession = onOpenSession,
                                    initiallyExpanded =
                                        defaultToolExpanded(
                                            part,
                                            shellExpanded = shellToolPartsExpanded,
                                            editExpanded = editToolPartsExpanded,
                                        ),
                                )
                            }
                            if (count > 1) {
                                // Count ticks up as calls coalesce; crossfade the
                                // digit instead of swapping it mid-stream.
                                androidx.compose.animation.Crossfade(
                                    targetState = count,
                                    animationSpec = tween(durationMillis = 180),
                                    label = "toolCount",
                                ) { n ->
                                    Text(
                                        text = "×$n",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
internal fun LiveStatusIndicator(
    agent: String?,
    model: String?,
    status: LiveStatus,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        InlineSpinner()
        Column(modifier = Modifier.weight(1f)) {
            // Line 1: WHAT is running right now.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                Text(
                    text = liveStatusText(status),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (status.kind == LiveStatusKind.DISCONNECTED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (status.animated) DotsPulse()
            }
            // Line 2: who is doing it (kept, so the row still identifies the model).
            val who = listOfNotNull(agent, model).filter { it.isNotBlank() }.joinToString(" · ")
            if (who.isNotBlank()) {
                Text(
                    text = who,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The concrete activity label, e.g. "Running read · ChatViewModel.kt (3 tools)" or
 * "Writing the answer · 1,240 chars". Falls back to the generic phases.
 */
@Composable
private fun liveStatusText(status: LiveStatus): String =
    when (status.kind) {
        LiveStatusKind.DISCONNECTED -> stringResource(R.string.reconnecting)
        LiveStatusKind.COMPACTING -> stringResource(R.string.compacting)
        LiveStatusKind.THINKING -> stringResource(R.string.thinking)
        LiveStatusKind.WAITING -> stringResource(R.string.waiting_for_model)
        LiveStatusKind.TOOL -> {
            val tool = status.tool?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tool)
            val base =
                status.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    // Long paths/commands: keep the row readable.
                    stringResource(R.string.status_running_tool_detail, tool, detail.take(60))
                } ?: stringResource(R.string.status_running_tool, tool)
            if (status.toolCount > 1) {
                base + " " + stringResource(R.string.status_tool_count, status.toolCount)
            } else {
                base
            }
        }
        LiveStatusKind.WRITING -> {
            val base = stringResource(R.string.status_writing_answer)
            if (status.streamingChars > 0) {
                base + " · " +
                    stringResource(
                        R.string.status_streamed_chars,
                        com.opencode.android.util.formatCount(status.streamingChars),
                    )
            } else {
                base
            }
        }
    }

/** Shared animated dots ("…") for the live status row. */
@Composable
private fun DotsPulse() {
    val transition = rememberInfiniteTransition(label = "statusDots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
            ),
        label = "statusDotsPhase",
    )
    Text(
        text = ".".repeat(phase.toInt().coerceIn(0, 2) + 1),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun LiveResponseBubble(
    text: String,
    agent: String?,
    model: String?,
    streaming: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            shape =
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(MaterialTheme.spacing.cardPadding)
                        // Each flush replaces the text; animating the size makes the
                        // bubble grow smoothly instead of jumping a line at a time.
                        .animateContentSize(),
            ) {
                (agent ?: model)?.let {
                    Text(
                        text = listOfNotNull(agent, model).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // Progressive markdown: the finished message renders markdown,
                // so rendering the live text as plain text made the whole bubble
                // reflow (headings/lists/code popping into place) the moment it
                // was persisted. `MarkdownText` parses incrementally now (the
                // finished prefix is memoised, only the growing tail is
                // re-parsed), so the live bubble can always use the SAME renderer
                // as the persisted one — no style jump when the text is persisted
                // and no full re-parse per flush.
                androidx.compose.foundation.text.selection.SelectionContainer {
                    MarkdownText(
                        text = text,
                        modifier = Modifier.animateContentSize(),
                    )
                }
                if (streaming) StreamingCaret()
            }
        }
    }
}

@Composable
internal fun LiveReasoningBubble(
    text: String,
    agent: String?,
    model: String?,
    streaming: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            shape =
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(MaterialTheme.spacing.cardPadding)
                        // Each flush replaces the text; animating the size makes the
                        // bubble grow smoothly instead of jumping a line at a time.
                        .animateContentSize(),
            ) {
                (agent ?: model)?.let {
                    Text(
                        text = listOfNotNull(agent, model).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    MarkdownText(
                        text = text,
                        modifier = Modifier.animateContentSize(),
                    )
                }
                if (streaming) StreamingCaret()
            }
        }
    }
}

// Extract a readable detail from a tool part's state (e.g. filePath from input)

/**
 * Tail indicator shown for as long as tokens are still arriving. The UI Craft
 * guidance is blunt about this: "no caret = 'is it broken?' panic". It is its
 * own composable so the per-frame alpha animation never recomposes the
 * (potentially long) response text next to it.
 */
@Composable
internal fun StreamingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 620, easing = LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
        label = "caretAlpha",
    )
    Row(
        modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
        repeat(3) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
