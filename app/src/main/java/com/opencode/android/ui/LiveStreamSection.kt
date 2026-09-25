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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        thinking = live.thinking,
        connected = connected,
        compacting = compacting,
        // Nothing has arrived yet: the request is out but the model has not
        // started producing. Without this the row read "build · deepseek…" and
        // looked like it was already working.
        waiting = live.response.isBlank() &&
            live.reasoning.isBlank() &&
            live.parts.isEmpty(),
    )
}

@Composable
internal fun LiveReasoningItem(
    liveState: kotlinx.coroutines.flow.StateFlow<LiveStreamState>,
    // The live section also stays mounted for a short `pendingPersist` window
    // after the turn ends; the caret must not keep blinking then.
    streaming: Boolean,
) {
    val live by liveState.collectAsStateWithLifecycle()
    if (live.reasoning.isNotBlank()) {
        LiveReasoningBubble(
            text = live.reasoning,
            agent = live.agent,
            model = live.model,
            streaming = streaming,
        )
    }
}

@Composable
internal fun LiveResponseItem(
    liveState: kotlinx.coroutines.flow.StateFlow<LiveStreamState>,
    streaming: Boolean,
) {
    val live by liveState.collectAsStateWithLifecycle()
    if (live.response.isNotBlank()) {
        LiveResponseBubble(
            text = live.response,
            agent = live.agent,
            model = live.model,
            streaming = streaming,
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
            modifier = Modifier
                .fillMaxWidth()
                // Tool rows arrive one by one; animate the height so the
                // list below glides instead of jumping per row.
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            // Consecutive same-tool calls collapse into one row with a ×N
            // badge, so a "read ten files" storm does not produce ten rows and
            // ten recompositions of this column.
            androidx.compose.runtime.remember(live.parts) {
                coalesceConsecutiveTools(live.parts)
            }.forEach { (part, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
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

@Composable
internal fun LiveStatusIndicator(
    agent: String?,
    model: String?,
    thinking: Boolean = false,
    connected: Boolean = true,
    waiting: Boolean = false,
    compacting: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        InlineSpinner()
        // One Crossfade across the status phases: the label used to
        // hard-swap on every phase change (waiting → thinking → tools).
        val phase = when {
            compacting -> 0
            thinking -> 1
            !connected -> 2
            waiting -> 3
            else -> 4
        }
        androidx.compose.animation.Crossfade(
            targetState = phase,
            animationSpec = tween(durationMillis = 180),
            label = "livePhase",
        ) { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                when (p) {
                    0 -> {
                        // "/compact" runs a long server-side summarization; without this the
                        // thread looked idle for up to a minute. Same animated-dots treatment
                        // as "Thinking".
                        Text(
                            text = stringResource(R.string.compacting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DotsPulse()
                    }
                    1 -> {
                        // Web shows a "Thinking" label with animated dots while reasoning.
                        Text(
                            text = stringResource(R.string.thinking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DotsPulse()
                    }
                    2 -> {
                        // Stream dropped mid-generation — the client auto-reconnects with
                        // backoff, so surface it instead of looking frozen. Animated dots
                        // like "Thinking" so the row reads alive, not stuck.
                        Text(
                            text = stringResource(R.string.reconnecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        DotsPulse()
                    }
                    3 -> {
                        Text(
                            text = stringResource(R.string.waiting_for_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DotsPulse()
                    }
                    else -> {
                        val generatingLabel = stringResource(R.string.generating)
                        Text(
                            text = listOfNotNull(agent, model).joinToString(" · ").ifEmpty { generatingLabel },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
        animationSpec = infiniteRepeatable(
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp,
            ),
        ) {
            Column(
                modifier = Modifier
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
                // was persisted. Parsing is memoised per text and skipped past
                // LIVE_MARKDOWN_MAX_CHARS so a long stream cannot turn every
                // flush into a full re-parse.
                if (text.length <= LIVE_MARKDOWN_MAX_CHARS) {
                    MarkdownText(
                        text = text,
                        modifier = Modifier.animateContentSize(),
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp,
            ),
        ) {
            Column(
                modifier = Modifier
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
                if (text.length <= LIVE_MARKDOWN_MAX_CHARS) {
                    MarkdownText(
                        text = text,
                        modifier = Modifier.animateContentSize(),
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
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
private const val LIVE_MARKDOWN_MAX_CHARS = 4_000

@Composable
internal fun StreamingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
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
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
