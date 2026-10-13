package com.opencode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.opencode.android.R
import com.opencode.android.domain.InboxItem
import com.opencode.android.ui.theme.spacing

/**
 * One prompt waiting in the session INBOX, rendered the way the web shows it:
 * the text with a "Queued" badge and the two actions the server supports
 * (`Steer` = deliver without waiting, `Remove` = drop it).
 *
 * The server delivers the oldest queued prompt when the running turn ends; the
 * list therefore empties by itself (the inbox is re-read on every inbox event).
 */
@Composable
internal fun QueuedMessageRow(
    item: InboxItem,
    onSteer: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            // The badge names the delivery mode: "Queued" waits for the turn to
            // end, "Steer" was already released and is about to run (the web shows
            // the same two words).
            val steered = item.delivery?.lowercase() == "steer"
            Text(
                text =
                    stringResource(
                        if (steered) R.string.queue_steer else R.string.queued_badge,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Long prompts stay readable but bounded: the whole text is in the
            // queue, not in the transcript.
            Text(
                text = item.payload?.text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!steered) {
                    TextButton(onClick = onSteer) {
                        Text(
                            text = stringResource(R.string.queue_steer),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.queue_remove),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
