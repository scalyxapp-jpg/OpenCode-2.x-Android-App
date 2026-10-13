package com.opencode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import com.opencode.android.domain.Message
import com.opencode.android.domain.Tokens
import com.opencode.android.ui.theme.spacing

/**
 * `/compact` in the transcript, rendered like the web.
 *
 * The web shows a compaction message as a timeline notice plus the generated
 * summary:
 *
 * ```
 * ─────────── Session compaction started ───────────
 * ## Objective
 * …
 * <spinner> Compacting                              (while running)
 * ─────────── Session compacted · 12.3k in · 4.5k out ───────────
 * ```
 *
 * The server returns exactly that shape (`type = "compaction"` with `status`,
 * `summary` and `tokens`), so the app renders the same information instead of an
 * empty bubble — the command's output is the summary itself.
 */
@Composable
internal fun CompactionMessageRow(message: Message) {
    val status = message.status?.trim()?.lowercase()
    val running = status == "running"
    val summary = message.summary?.trim().orEmpty()

    // The POST answer is the session INBOX item: the web shows a lone
    // "Session compaction queued" line for it until the real compaction message
    // arrives with its summary.
    if (status == "queued") {
        TimelineNotice(stringResource(R.string.compaction_queued))
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        // The header notice is ALWAYS "started" on the web, even once the
        // compaction finished — the outcome goes into the footer instead.
        TimelineNotice(stringResource(R.string.compaction_started))

        if (summary.isNotBlank()) {
            MarkdownText(text = summary, modifier = Modifier.fillMaxWidth())
        }

        if (running) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                InlineSpinner()
                Text(
                    text = stringResource(R.string.compaction_running),
                    // Same type scale as the live status row, so the two
                    // "work is running" lines on screen look like one system.
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            TimelineNotice(compactionOutcomeLabel(message))
            message.error?.let { error ->
                Text(
                    text =
                        com.opencode.android.util.serverErrorMessage(
                            error.toString(),
                            stringResource(R.string.compaction_failed),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The web's outcome line: the status word plus the token usage of the compaction.
 *
 * `{{input}} in · {{output}} out` counts cache reads/writes as input and the
 * reasoning tokens as output, and is omitted while running or without usage.
 */
@Composable
private fun compactionOutcomeLabel(message: Message): String {
    val label = stringResource(compactionOutcomeRes(message.status, message.error?.toString()))
    val usage = compactionUsage(message.tokens)
    return if (usage.isNullOrBlank()) label else label + " · " + usage
}

/**
 * The outcome wording, mirroring the web's key selection: failed + aborted →
 * "cancelled", failed + interrupted → "interrupted", any other failure →
 * "failed", otherwise → "compacted" (and "by provider" when the provider did the
 * compaction itself). Pure, so it is unit-tested.
 */
internal fun compactionOutcomeRes(
    status: String?,
    errorText: String?,
): Int {
    val failed = status?.trim()?.lowercase() == "failed"
    val error = errorText?.lowercase().orEmpty()
    return when {
        failed && error.contains("abort") -> R.string.compaction_cancelled
        failed && error.contains("interrupt") -> R.string.compaction_interrupted
        failed -> R.string.compaction_failed
        else -> R.string.compaction_completed
    }
}

internal fun compactionUsage(tokens: Tokens?): String? {
    tokens ?: return null
    val input = (tokens.input ?: 0L) + (tokens.cache?.read ?: 0L) + (tokens.cache?.write ?: 0L)
    val output = (tokens.output ?: 0L) + (tokens.reasoning ?: 0L)
    if (input + output <= 0L) return null
    return "${com.opencode.android.util.formatCount(input)} in · " +
        "${com.opencode.android.util.formatCount(output)} out"
}

/**
 * A timeline notice: a label between two rules, the web's divider treatment for
 * events that are not messages of their own (compaction, model switch).
 */
@Composable
internal fun TimelineNotice(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
