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
import androidx.compose.runtime.saveable.rememberSaveable
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var backends by remember { mutableStateOf(BackendStore.backends()) }
    // Hoisted out of the LazyColumn item: a `remember` inside a lazy item is
    // disposed when it scrolls out of view, which silently discarded a half-typed
    // guard token.
    var guardTokenDraft by rememberSaveable { mutableStateOf<String?>(null) }
    // Confirmation for the destructive remove (deletes the saved credentials too).
    var deleteFor by remember { mutableStateOf<BackendStore.Backend?>(null) }
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
                    OutlinedButton(onClick = {
                        // The URL is what you paste into a browser or a bug report; the
                        // server list showed it but offered no way to copy it.
                        val clipboard =
                            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("backend", backend.url),
                        )
                        android.widget.Toast
                            .makeText(context, R.string.copied, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }) { Text(stringResource(R.string.copy_server_url)) }
                    OutlinedButton(
                        // Confirmation, like the start screen: this also deletes the
                        // saved credentials.
                        onClick = { deleteFor = backend },
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
            val guardToken = guardTokenDraft ?: AppSettingsStore.state.value.guardToken
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
                    onValueChange = { guardTokenDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        AppSettingsStore.setGuardToken(guardToken)
                        // Clear the draft so the field reflects the stored value again.
                        guardTokenDraft = null
                    },
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

    deleteFor?.let { backend ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.remove_backend)) },
            text = { Text(stringResource(R.string.remove_backend_confirm, backend.url)) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        BackendStore.remove(backend.url)
                        backends = BackendStore.backends()
                        deleteFor = null
                    },
                    colors =
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                androidx.compose.material3.OutlinedButton(onClick = { deleteFor = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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
