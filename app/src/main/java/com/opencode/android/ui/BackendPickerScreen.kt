package com.opencode.android.ui
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.android.data.BackendStore
import com.opencode.android.ui.theme.spacing
import com.opencode.android.ui.settings.SectionHeader
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.opencode.android.R

/**
 * Startup screen: pick / add / remove opencode serve backends.
 *
 * Connecting probes GET /api/health. A password-protected server answers
 * 401 with "www-authenticate: Basic realm=Secure Area", which triggers the
 * password dialog; credentials are then stored per backend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackendPickerScreen(
    onConnected: () -> Unit,
) {
    val backendSession = LocalBackendSession.current
    val providerDirectory = LocalProviderDirectory.current
    val scope = rememberCoroutineScope()
    var backends by remember { mutableStateOf(BackendStore.backends()) }
    var showAdd by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var connectingUrl by remember { mutableStateOf<String?>(null) }
    var connectedUrl by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Free-form label editing (rename) + label for the add dialog.
    var renameFor by remember { mutableStateOf<BackendStore.Backend?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    // Set when the server demands auth.
    var passwordFor by remember { mutableStateOf<BackendStore.Backend?>(null) }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(BackendStore.DEFAULT_USERNAME) }

    // Hoisted: connect() runs inside scope.launch/try, which is not a
    // composable scope, so stringResource cannot be called from there.
    val serverUnhealthyMsg = stringResource(R.string.server_unhealthy)
    val connectionFailedPrefix = stringResource(R.string.connection_failed_prefix)

    fun connect(backend: BackendStore.Backend, pass: String?) {
        connectingUrl = backend.url
        errorText = null
        scope.launch {
            try {
                val needsAuth = backendSession.requiresAuth(backend.url)
                if (needsAuth == null) {
                    // Probe failed (unreachable). Do NOT clear credentials or
                    // treat the server as open — report it and stop.
                    errorText = connectionFailedPrefix + backend.url
                    connectingUrl = null
                    return@launch
                }
                if (needsAuth && pass == null) {
                    // Ask for the password first.
                    passwordFor = backend
                    username = backend.username.ifBlank { BackendStore.DEFAULT_USERNAME }
                    password = backend.password ?: ""
                    connectingUrl = null
                    return@launch
                }
                backendSession.setBaseUrl(backend.url)
                if (needsAuth) {
                    backendSession.setAuth(username.ifBlank { BackendStore.DEFAULT_USERNAME }, pass)
                } else {
                    backendSession.setAuth(null, null)
                }
                // Verify the connection (health must be reachable).
                val health = backendSession.api.health()
                if (!health.healthy) {
                    errorText = serverUnhealthyMsg
                    connectingUrl = null
                    return@launch
                }
                BackendStore.saveCredentials(
                    backend.url,
                    if (needsAuth) username.ifBlank { BackendStore.DEFAULT_USERNAME } else "",
                    if (needsAuth) pass else null,
                )
                BackendStore.markUsed(backend.url)
                backends = BackendStore.backends()
                // Warm the provider catalog (~6 MB, slow to parse) in the
                // background so Settings/chat never wait for it later.
                providerDirectory.invalidate()
                providerDirectory.prefetch()
                connectingUrl = null
                // Hero moment: celebrate the successful connection with an
                // expressive spring before navigating on.
                connectedUrl = backend.url
                kotlinx.coroutines.delay(650)
                onConnected()
            } catch (e: Exception) {
                errorText = connectionFailedPrefix + (e.message ?: "")
                connectingUrl = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { TopBarTitle(stringResource(R.string.choose_backend)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(MaterialTheme.spacing.screenPadding),
        ) {
            Text(
                text = stringResource(R.string.backend_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.backend_choose_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    top = MaterialTheme.spacing.extraSmall,
                    bottom = MaterialTheme.spacing.cardPadding,
                ),
            )

            SectionHeader(
                title = stringResource(R.string.backends),
                trailing = {
                    OutlinedButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(MaterialTheme.spacing.extraSmall))
                        Text(stringResource(R.string.add))
                    }
                },
            )

            errorText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
                )
            }

            if (backends.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_backend_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.cardPadding),
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                items(backends, key = { it.url }) { backend ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        shape = MaterialTheme.shapes.large,
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = connectingUrl == null) { connect(backend, null) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.cardPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Tonal server avatar (visual anchor).
                            Box(
                                modifier = Modifier
                                    .padding(end = MaterialTheme.spacing.cardPadding)
                                    .size(40.dp)
                                    .background(
                                        color = if (backend.password != null) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        },
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (backend.password != null) {
                                        Icons.Default.Lock
                                    } else {
                                        Icons.Default.Dns
                                    },
                                    contentDescription = null,
                                    tint = if (backend.password != null) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                // Free-form label first, URL as the technical subtitle.
                                if (!backend.name.isNullOrBlank()) {
                                    Text(
                                        text = backend.name.orEmpty(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = backend.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    Text(
                                        text = backend.url,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val hint = if (backend.password != null) {
                                    stringResource(R.string.password_saved)
                                } else {
                                    stringResource(R.string.no_password)
                                }
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                // Hero moment: checkmark pops in with an expressive spring.
                                connectedUrl == backend.url -> {
                                    val scale = remember { androidx.compose.animation.core.Animatable(0f) }
                                    LaunchedEffect(backend.url) {
                                        scale.animateTo(
                                            targetValue = 1f,
                                            animationSpec = com.opencode.android.ui.theme.Motion.expressive(),
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .graphicsLayer {
                                                scaleX = scale.value
                                                scaleY = scale.value
                                            }
                                            .background(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.connected),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                connectingUrl == backend.url -> {
                                    InlineSpinner()
                                }
                                else -> {
                                    // Rename the free-form label (no DNS semantics).
                                    IconButton(onClick = {
                                        renameFor = backend
                                        renameText = backend.name ?: ""
                                    }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.rename),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = {
                                        BackendStore.remove(backend.url)
                                        backends = BackendStore.backends()
                                    }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.remove),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Add backend dialog ------------------------------------------------
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_backend)) },
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
                        placeholder = { Text(stringResource(R.string.http_192_168_1_100)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newUrl.isNotBlank()) {
                        val backend = BackendStore.add(newUrl, newName)
                        backends = BackendStore.backends()
                        newUrl = ""
                        newName = ""
                        showAdd = false
                        connect(backend, null)
                    }
                }) { Text(stringResource(R.string.add_connect)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // --- Rename backend label ---------------------------------------------
    renameFor?.let { backend ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.rename_backend)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    Text(
                        text = backend.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.name)) },
                        placeholder = { Text(stringResource(R.string.e_g_home_server)) },
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

    // --- Password dialog (auto-detected via 401) ---------------------------
    passwordFor?.let { backend ->
        AlertDialog(
            onDismissRequest = { passwordFor = null },
            title = { Text(stringResource(R.string.password_required)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    Text(
                        text = stringResource(R.string.backend_password_protected, backend.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val b = backend
                    passwordFor = null
                    connect(b, password)
                }) { Text(stringResource(R.string.connect)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { passwordFor = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
