package com.opencode.android.ui.settings
import com.opencode.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.ui.theme.spacing
import com.opencode.android.domain.Model
import androidx.compose.ui.res.stringResource

// --- Shortcuts -------------------------------------------------------------

internal data class Shortcut(val label: String, val keys: String)
internal data class ShortcutGroup(val name: String, val items: List<Shortcut>)

internal val SHORTCUT_GROUPS = listOf(
    ShortcutGroup(
        "General",
        listOf(
            Shortcut("Add selection to context", "Ctrl+Shift+L"),
            Shortcut("Close tab", "Ctrl+W"),
            Shortcut("Command palette", "Ctrl+K"),
            Shortcut("Focus input", "Ctrl+L"),
            Shortcut("Home", "Ctrl+B"),
            Shortcut("Navigate back", "Ctrl+["),
            Shortcut("Navigate forward", "Ctrl+]"),
            Shortcut("New session", "Ctrl+T"),
            Shortcut("Open settings", "Ctrl+,"),
            Shortcut("Reopen closed tab", "Ctrl+Shift+T"),
            Shortcut("Search sessions", "Ctrl+F"),
        ),
    ),
    ShortcutGroup(
        "Session",
        listOf(
            Shortcut("Archive session", "Ctrl+Shift+Backspace"),
            Shortcut("Auto-accept permissions", "Ctrl+Shift+A"),
            Shortcut("Compact session", "Unassigned"),
            Shortcut("Export session", "Unassigned"),
            Shortcut("Fork from message", "Unassigned"),
            Shortcut("New session", "Ctrl+Shift+S"),
            Shortcut("Next message", "Ctrl+Alt+]"),
            Shortcut("Previous message", "Ctrl+Alt+["),
            Shortcut("Redo", "Unassigned"),
            Shortcut("Share session", "Unassigned"),
            Shortcut("Toggle review", "Ctrl+Shift+R"),
            Shortcut("Undo", "Unassigned"),
            Shortcut("Unshare session", "Unassigned"),
        ),
    ),
    ShortcutGroup(
        "Navigation",
        listOf(
            Shortcut("Add files", "Ctrl+U"),
            Shortcut("Open file", "Ctrl+P"),
        ),
    ),
    ShortcutGroup(
        "Model and agent",
        listOf(
            Shortcut("Choose model", "Ctrl+'"),
            Shortcut("Cycle agent", "Ctrl+."),
            Shortcut("Cycle agent backwards", "Ctrl+Shift+."),
            Shortcut("Cycle thinking effort", "Ctrl+Shift+D"),
            Shortcut("Toggle MCPs", "Ctrl+;"),
        ),
    ),
    ShortcutGroup(
        "Terminal",
        listOf(
            Shortcut("Close terminal", "Ctrl+W"),
            Shortcut("New terminal", "Ctrl+Alt+T"),
            Shortcut("Toggle terminal", "Ctrl+`"),
        ),
    ),
    ShortcutGroup(
        "Prompt",
        listOf(
            Shortcut("Prompt", "Ctrl+Shift+E"),
            Shortcut("Shell", "Ctrl+Shift+X"),
        ),
    ),
)

@Composable
internal fun ShortcutsTab() {
    var showReset by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SectionHeader(
                title = stringResource(R.string.keyboard_shortcuts),
                trailing = {
                    OutlinedButton(onClick = { showReset = true }) {
                        Text(stringResource(R.string.reset_to_defaults))
                    }
                },
            )
        }
        // These are the web/desktop keybindings; the Android app has no
        // keyboard shortcuts, so say so instead of implying they work here.
        item {
            Text(
                text = stringResource(R.string.shortcuts_desktop_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
            )
        }
        SHORTCUT_GROUPS.forEach { group ->
            item { SectionHeader(group.name) }
            items(group.items) { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = s.keys,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = com.opencode.android.ui.theme.codeFont(),
                        color = if (s.keys == "Unassigned") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                HorizontalDivider()
            }
        }
        item { Spacer(Modifier.height(MaterialTheme.spacing.large)) }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.reset_to_defaults)) },
            text = { Text(stringResource(R.string.settings_backup_hint)) },
            confirmButton = {
                Button(onClick = {
                    AppSettingsStore.reset()
                    showReset = false
                }) { Text(stringResource(R.string.reset_to_defaults)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
