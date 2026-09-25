package com.opencode.android.ui.settings
import com.opencode.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.data.BackendStore
import com.opencode.android.ui.theme.spacing
import androidx.compose.ui.res.stringResource

// --- Servers ---------------------------------------------------------------

@Composable
internal fun ServersTab(onSwitchBackend: () -> Unit) {
    // Shows the real persisted backends instead of a hardcoded placeholder.
    // Connecting/sign-in still happens on the start screen, which owns the
    // password prompt, so this tab only manages the saved list.
    var backends by remember { mutableStateOf(BackendStore.backends()) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var renameFor by remember { mutableStateOf<BackendStore.Backend?>(null) }
    var renameText by remember { mutableStateOf("") }
    val activeUrl = com.opencode.android.ui.LocalBackendSession.current.currentBaseUrl()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SectionSubheader(
                title = stringResource(R.string.saved_servers),
                trailing = {
                    OutlinedButton(onClick = { showAdd = true }) { Text(stringResource(R.string.add)) }
                },
            )
        }
        if (backends.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_servers_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                )
            }
        } else {
            items(backends, key = { it.url }) { backend ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    // Space between the "Active" badge and the Rename/Remove
                    // buttons (they were touching).
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = backend.name ?: backend.url,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (backend.name != null) {
                                backend.url
                            } else if (backend.password != null) {
                                stringResource(R.string.password_saved)
                            } else {
                                stringResource(R.string.no_password)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (backend.url == activeUrl) {
                        Text(
                            text = stringResource(R.string.active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedButton(onClick = {
                        renameFor = backend
                        renameText = backend.name ?: ""
                    }) { Text(stringResource(R.string.rename)) }
                    OutlinedButton(
                        onClick = {
                            BackendStore.remove(backend.url)
                            backends = BackendStore.backends()
                        },
                        // Destructive: tinted like every other "remove".
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.remove)) }
                }
                HorizontalDivider()
            }
        }
        item {
            var guardToken by remember { mutableStateOf(AppSettingsStore.state.value.guardToken) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.settings_guard_token),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = guardToken,
                    onValueChange = { guardToken = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { AppSettingsStore.setGuardToken(guardToken) },
                    modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
                ) { Text(stringResource(R.string.save)) }
            }
        }
        item {
            Text(
                text = stringResource(R.string.sign_in_on_start),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.spacing.medium),
            )
            OutlinedButton(
                onClick = onSwitchBackend,
                modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
            ) { Text(stringResource(R.string.switch_server)) }
        }
        item { Spacer(Modifier.height(MaterialTheme.spacing.large)) }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_server)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.name_optional)) },
                        placeholder = { Text(stringResource(R.string.e_g_home_server)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text(stringResource(R.string.server_url)) },
                        placeholder = { Text(stringResource(R.string.http_host_4096)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newUrl.isNotBlank()) {
                        BackendStore.add(newUrl, newName)
                        backends = BackendStore.backends()
                    }
                    newName = ""
                    newUrl = ""
                    showAdd = false
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    renameFor?.let { backend ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.rename_server)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    Text(
                        text = backend.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    BackendStore.rename(backend.url, renameText)
                    backends = BackendStore.backends()
                    renameFor = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { renameFor = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
