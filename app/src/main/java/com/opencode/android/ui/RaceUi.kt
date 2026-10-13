package com.opencode.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.domain.Model
import com.opencode.android.ui.race.RacePlan
import com.opencode.android.ui.race.RaceState
import com.opencode.android.ui.theme.spacing

/**
 * Picker for a model race: the composer's prompt is the task, and every picked model
 * runs it in its own session. The cap ([RacePlan.MAX_MODELS]) and the cost hint are
 * deliberate: a race is N full turns on the user's own key.
 */
@Composable
internal fun RaceDialog(
    models: List<Model>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible =
        remember(models, query) {
            val refs =
                models.map {
                    com.opencode.android.util
                        .sessionModelRef(it.id, it.providerId) to
                        (it.name?.takeIf { name -> name.isNotBlank() } ?: it.id)
                }
            if (query.isBlank()) refs else refs.filter { it.first.contains(query, true) || it.second.contains(query, true) }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.race_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
            ) {
                Text(
                    text = stringResource(R.string.race_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PickerSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.skills_pick_search),
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                )
                LazyColumn {
                    itemsIndexed(visible, key = { index, item -> "${item.first}#$index" }) { _, item ->
                        val (ref, label) = item
                        val checked = ref in picked
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(ref) }
                                    .padding(vertical = MaterialTheme.spacing.extraSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onToggle(ref) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = ref,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onStart,
                enabled = picked.size >= RacePlan.MIN_MODELS,
            ) {
                Text(stringResource(R.string.race_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * The race result in the transcript: one row per model with status, wall-clock time,
 * cost, the answer itself — and which member is the cheapest / fastest. Tapping a row
 * continues in that member's session, so the comparison is actionable instead of a
 * dead end.
 */
@Composable
internal fun RaceCard(
    race: RaceState,
    onOpenSession: (String) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cheapestRef = race.cheapest?.member?.modelRef
    val fastestRef = race.fastest?.member?.modelRef
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.race_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (!race.finished) {
                    Text(
                        text = stringResource(R.string.race_running),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = race.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            race.members.forEach { member ->
                val chips =
                    listOfNotNull(
                        if (member.member.modelRef == cheapestRef) {
                            stringResource(R.string.race_cheapest)
                        } else {
                            null
                        },
                        if (member.member.modelRef == fastestRef) {
                            stringResource(R.string.race_fastest)
                        } else {
                            null
                        },
                    ).joinToString(" · ")
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = member.sessionId != null) {
                                member.sessionId?.let(onOpenSession)
                            }.padding(vertical = MaterialTheme.spacing.extraSmall),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = member.member.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text =
                                listOfNotNull(
                                    if (member.running) {
                                        stringResource(R.string.race_running)
                                    } else {
                                        stringResource(R.string.race_seconds, "%.1f".format(member.elapsedMs / 1000.0))
                                    },
                                    member.cost?.let { stringResource(R.string.race_cost, "%.4f".format(it)) },
                                    member.tokens?.let { stringResource(R.string.race_tokens, it.toString()) },
                                ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (member.failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                    if (chips.isNotBlank()) {
                        Text(
                            text = chips,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        // The answer is the point of the race; three lines are enough
                        // to judge it, the session has the rest.
                        text =
                            member.answer.ifBlank {
                                stringResource(R.string.race_answer_empty)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                if (!race.finished) {
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.race_stop_all))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.race_dismiss))
                }
            }
        }
    }
}

/**
 * The explain card: a side question about one artifact, answered by the `ask`/`docs`
 * agent in its own session. Lives in the transcript like the race card (an ephemeral
 * block), because that is where the artifact the user pointed at is.
 */
@Composable
internal fun ExplainCard(
    state: com.opencode.android.ui.explain.ExplainState,
    onUseInPrompt: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.explain_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                state.label?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = state.snippet,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                state.running -> Text(
                    text = stringResource(R.string.explain_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.failed -> Text(
                    text = stringResource(R.string.explain_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> MarkdownText(text = state.answer, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
                if (!state.running && !state.failed && state.answer.isNotBlank()) {
                    TextButton(onClick = { onUseInPrompt(state.answer) }) {
                        Text(stringResource(R.string.explain_use), style = MaterialTheme.typography.labelSmall)
                    }
                }
                state.sessionId?.let { sessionId ->
                    TextButton(onClick = { onOpenSession(sessionId) }) {
                        Text(
                            stringResource(R.string.explain_open),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
