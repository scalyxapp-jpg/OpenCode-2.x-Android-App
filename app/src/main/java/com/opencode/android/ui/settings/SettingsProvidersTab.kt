package com.opencode.android.ui.settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.android.R
import com.opencode.android.data.ProviderDirectory
import com.opencode.android.domain.AuthSetRequest
import com.opencode.android.domain.ProviderAuthMethod
import com.opencode.android.domain.ProviderEntry
import com.opencode.android.ui.InlineSpinner
import com.opencode.android.ui.theme.spacing
import kotlinx.coroutines.launch

// --- Providers -------------------------------------------------------------

// Web "Popular providers" (hardcoded client-side in the web bundle).
internal data class PopularProvider(
    val id: String,
    val name: String,
    val description: String,
    val badge: String? = null,
)

internal val POPULAR_PROVIDERS =
    listOf(
        PopularProvider(
            "opencode",
            "OpenCode Zen",
            "Curated models including Claude, GPT, Gemini and more",
            "Recommended",
        ),
        PopularProvider("anthropic", "Anthropic", "Direct access to Claude models, including Pro and Max"),
        PopularProvider("github-copilot", "GitHub Copilot", "AI models for coding assistance via GitHub Copilot"),
        PopularProvider("vercel", "Vercel AI Gateway", "Unified access to AI models with smart routing"),
        PopularProvider("custom", "Custom provider", "Add an OpenAI-compatible provider by base URL.", "Custom"),
    )

// Mirrors the web Providers tab (verified via Playwright):
//  - "Connected providers": name + source badge (Environment/Config/Custom/API key);
//    env-sourced rows show an explanation instead of Disconnect
//  - "Popular providers": curated list with Connect
//  - Disconnect = DELETE /auth/{id} then POST /global/dispose
//  - Connect    = GET /provider/auth, then PUT /auth/{id} {"type":"api","key":…}
@Composable
internal fun ProvidersTab() {
    val providerDirectory = com.opencode.android.ui.LocalProviderDirectory.current
    val backendSession = com.opencode.android.ui.LocalBackendSession.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var connectFor by remember { mutableStateOf<ProviderEntry?>(null) }
    var showAllPopular by remember { mutableStateOf(false) }

    // Shared, cached catalog: /provider is ~6 MB, so it is fetched once per
    // process instead of on every tab switch.
    val catalog by providerDirectory.state.collectAsStateWithLifecycle()

    LaunchedEffect(reload) {
        if (reload > 0) providerDirectory.invalidate()
        providerDirectory.load()
    }

    val connectedProviders =
        when (val c = catalog) {
            is ProviderDirectory.State.Ready -> c.providers.filter { c.connectedIds.contains(it.id) }
            else -> emptyList()
        }
    val allProviders = (catalog as? ProviderDirectory.State.Ready)?.providers ?: emptyList()
    val connected = (catalog as? ProviderDirectory.State.Ready)?.connectedIds ?: emptySet()
    val loadError = (catalog as? ProviderDirectory.State.Failed)?.message
    val loading = catalog is ProviderDirectory.State.Loading
    val popular = POPULAR_PROVIDERS.filterNot { connected.contains(it.id) }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        // Room for the bottom navigation bar: without it the last provider's
        // Disconnect button was cut off at the screen edge.
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.extraLarge * 3),
    ) {
        item { SectionHeader(stringResource(R.string.settings_connected_providers)) }
        if (loading) {
            item {
                Row(
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    InlineSpinner()
                    Text(
                        text = stringResource(R.string.loading_providers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (connectedProviders.isEmpty()) {
            item {
                Text(
                    text =
                        if (loadError != null) {
                            stringResource(R.string.could_not_load_providers, loadError)
                        } else {
                            stringResource(R.string.no_providers_connected)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (loadError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        items(connectedProviders, key = { it.id }) { p ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Text(p.name ?: p.id, style = MaterialTheme.typography.bodyMedium)
                        ProviderBadge(p.source)
                    }
                    Text(
                        text = p.models.size.toString() + " models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Env-sourced credentials can't be removed from here (server has
                // no auth entry for them) — the web shows an explanation instead.
                if (p.source == "env") {
                    Text(
                        text = stringResource(R.string.connected_from_env),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.4f),
                    )
                } else {
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                backendSession.api.disconnectProvider(p.id)
                                backendSession.api.globalDispose()
                            } catch (e: Exception) {
                                // Best-effort teardown, but never silent: a
                                // failed dispose was invisible before.
                                com.opencode.android.util.AppLog.e(
                                    com.opencode.android.util.APP_LOG_TAG,
                                    "disconnectProvider failed: ${e.message}",
                                )
                            }
                            providerDirectory.invalidate()
                            reload++
                        }
                    }) { Text(stringResource(R.string.disconnect)) }
                }
            }
            HorizontalDivider()
        }

        item { SectionHeader(stringResource(R.string.settings_popular_providers)) }
        items(popular, key = { "pop-" + it.id }) { pp ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Text(pp.name, style = MaterialTheme.typography.bodyMedium)
                        pp.badge?.let { ProviderBadge(it, "source") }
                    }
                    Text(
                        text = pp.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    // Custom provider has no catalog entry; open the generic dialog.
                    connectFor = allProviders.firstOrNull { it.id == pp.id }
                        ?: ProviderEntry(id = pp.id, name = pp.name)
                }) { Text(stringResource(R.string.connect)) }
            }
            HorizontalDivider()
        }
        if (!showAllPopular) {
            item {
                OutlinedButton(
                    onClick = { showAllPopular = true },
                    modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                ) { Text(stringResource(R.string.show_more_providers)) }
            }
        } else {
            // Remaining catalog providers that are not connected and not popular.
            val others =
                allProviders.filter {
                    !connected.contains(it.id) && POPULAR_PROVIDERS.none { pp -> pp.id == it.id }
                }
            items(others, key = { "other-" + it.id }) { p ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.name ?: p.id, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = p.models.size.toString() + " models",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { connectFor = p }) { Text(stringResource(R.string.connect)) }
                }
                HorizontalDivider()
            }
        }
        item { Spacer(Modifier.height(MaterialTheme.spacing.large)) }
    }

    connectFor?.let { provider ->
        ConnectProviderDialog(
            provider = provider,
            onDismiss = { connectFor = null },
            onConnected = {
                connectFor = null
                reload++
            },
        )
    }
}

@Composable
internal fun ProviderBadge(
    text: String?,
    kind: String = "source",
) {
    val label =
        when {
            text == null -> {
                return
            }

            kind == "source" -> {
                when (text) {
                    "env" -> "Environment"
                    "config" -> "Config"
                    "custom" -> "Custom"
                    "api" -> "API key"
                    else -> text
                }
            }

            else -> {
                text
            }
        }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall, vertical = 2.dp),
        )
    }
}

// Web Connect flow: GET /provider/auth lists the methods for this provider
// (oauth / api, with optional extra prompts), then PUT /auth/{id} stores the
// credential. Providers without an entry fall back to a plain API key field.
@Composable
internal fun ConnectProviderDialog(
    provider: ProviderEntry,
    onDismiss: () -> Unit,
    onConnected: () -> Unit,
) {
    val backendSession = com.opencode.android.ui.LocalBackendSession.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var methods by remember { mutableStateOf<List<ProviderAuthMethod>?>(null) }
    var key by remember { mutableStateOf("") }
    var prompts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // OAuth: the authorize call returns a URL (browser) plus instructions; the
    // headless variant prints a code the user pastes back to /oauth/callback.
    var oauthStarted by remember { mutableStateOf(false) }
    var oauthMethod by remember { mutableStateOf(0) }
    var oauthInstructions by remember { mutableStateOf<String?>(null) }
    var oauthCode by remember { mutableStateOf("") }

    LaunchedEffect(provider.id) {
        methods =
            try {
                backendSession.api.getProviderAuth()[provider.id] ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
    }

    val apiMethod = methods?.firstOrNull { it.type == "api" }
    val oauthMethods = methods?.filter { it.type == "oauth" } ?: emptyList()
    val visiblePrompts =
        apiMethod?.prompts?.filter { pr ->
            pr.whenCondition?.let { c -> prompts[c.key] == c.value } ?: true
        } ?: emptyList()
    // OAuth methods may declare required inputs (e.g. a GitHub Enterprise URL);
    // render them so the answer can be sent with the connect/oauth call.
    val oauthPromptFields = oauthMethods.flatMap { it.prompts }.distinctBy { it.key }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connect_provider, provider.name ?: provider.id)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                if (methods == null) {
                    InlineSpinner()
                }
                oauthMethods.forEachIndexed { index, m ->
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val resp =
                                        backendSession.api.providerOauthAuthorize(
                                            provider.id,
                                            kotlinx.serialization.json.JsonObject(
                                                mapOf(
                                                    "method" to kotlinx.serialization.json.JsonPrimitive(index),
                                                    "answer" to
                                                        kotlinx.serialization.json.JsonObject(
                                                            prompts
                                                                .filterValues { it.isNotBlank() }
                                                                .mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) },
                                                        ),
                                                ),
                                            ),
                                        ) as? kotlinx.serialization.json.JsonObject
                                    val url = (resp?.get("url") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    oauthInstructions =
                                        (resp?.get("instructions") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    oauthMethod = index
                                    oauthStarted = true
                                    if (!url.isNullOrBlank()) {
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(url),
                                                ),
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    error = "Failed: ${e.message}"
                                }
                                busy = false
                            }
                        },
                    ) { Text(m.label ?: "Sign in") }
                }
                oauthPromptFields.forEach { pr ->
                    OutlinedTextField(
                        value = prompts[pr.key] ?: "",
                        onValueChange = { prompts = prompts + (pr.key to it) },
                        label = { Text(pr.message ?: pr.key) },
                        placeholder = pr.placeholder?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (oauthStarted) {
                    oauthInstructions?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = oauthCode,
                        onValueChange = { oauthCode = it },
                        label = { Text("Authorization code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        enabled = !busy && oauthCode.isNotBlank(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    backendSession.api
                                        .providerOauthCallback(
                                            provider.id,
                                            kotlinx.serialization.json.JsonObject(
                                                mapOf(
                                                    "method" to kotlinx.serialization.json.JsonPrimitive(oauthMethod),
                                                    "code" to kotlinx.serialization.json.JsonPrimitive(oauthCode),
                                                ),
                                            ),
                                        ).close()
                                    runCatching { backendSession.api.globalDispose() }
                                    onConnected()
                                } catch (e: Exception) {
                                    error = "Failed: ${e.message}"
                                }
                                busy = false
                            }
                        },
                    ) { Text("Submit code") }
                }
                visiblePrompts.forEach { pr ->
                    OutlinedTextField(
                        value = prompts[pr.key] ?: "",
                        onValueChange = { prompts = prompts + (pr.key to it) },
                        label = { Text(pr.message ?: pr.key) },
                        placeholder = pr.placeholder?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("${provider.name ?: provider.id} API key") },
                    placeholder = { Text(stringResource(R.string.api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (key.isNotBlank() || visiblePrompts.any { !prompts[it.key].isNullOrBlank() }),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val resp =
                                backendSession.api.setProviderAuth(
                                    provider.id,
                                    AuthSetRequest(
                                        type = apiMethod?.type ?: "api",
                                        key = key.ifBlank { null },
                                        prompts = prompts.filterValues { it.isNotBlank() },
                                    ),
                                )
                            if (resp.isSuccessful) {
                                try {
                                    backendSession.api.globalDispose()
                                } catch (e: Exception) {
                                    com.opencode.android.util.AppLog.e(
                                        com.opencode.android.util.APP_LOG_TAG,
                                        "globalDispose after auth failed: ${e.message}",
                                    )
                                }
                                onConnected()
                            } else {
                                error = "Failed: HTTP ${resp.code()}"
                            }
                        } catch (e: Exception) {
                            error = "Failed: ${e.message}"
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.continue_label)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
