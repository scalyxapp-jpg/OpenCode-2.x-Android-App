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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.data.ModelVisibilityStore
import com.opencode.android.data.ProviderDirectory
import com.opencode.android.ui.theme.spacing
import com.opencode.android.ui.PickerSearchField
import com.opencode.android.ui.InlineSpinner
import com.opencode.android.domain.Model
import androidx.compose.ui.res.stringResource

// --- Models ----------------------------------------------------------------

@Composable
// Mirrors the web Models tab (verified via Playwright):
//  - only CONNECTED providers are listed, grouped by provider
//  - one Switch per model; the provider heading bulk-toggles its models
//  - visibility is client-side only (no endpoint) and upserted into
//    localStorage "opencode.global.dat:model" -> user[{providerID, modelID, visibility}]
internal fun ModelsTab() {
    val providerDirectory = com.opencode.android.ui.LocalProviderDirectory.current
    var query by remember { mutableStateOf("") }
    // Bumped after every toggle so the switches re-read the persisted state.
    var revision by remember { mutableIntStateOf(0) }

    // Shared, cached catalog (see ProviderDirectory).
    val catalog by providerDirectory.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { providerDirectory.load() }

    val visibleProviders = when (val c = catalog) {
        is ProviderDirectory.State.Ready -> c.providers.filter { c.connectedIds.contains(it.id) }
        else -> emptyList()
    }
    val loadError = (catalog as? ProviderDirectory.State.Failed)?.message
    val loading = catalog is ProviderDirectory.State.Loading
    val q = query.trim().lowercase()
    val visibility = remember(revision) { ModelVisibilityStore.visibilityMap() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            PickerSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.search_models),
                modifier = Modifier.padding(top = MaterialTheme.spacing.small),
            )
        }

        if (loading) {
            item {
                Row(
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    InlineSpinner()
                    Text(
                        text = stringResource(R.string.loading_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (visibleProviders.isEmpty()) {
            item {
                Text(
                    text = if (loadError != null) {
                        stringResource(R.string.could_not_load_models, loadError)
                    } else {
                        stringResource(R.string.no_providers_connected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loadError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                )
            }
        }

        visibleProviders.forEach { p ->
            val models = p.models.values
                .filter { m ->
                    q.isBlank() ||
                        (m.name ?: m.id).lowercase().contains(q) ||
                        m.id.lowercase().contains(q)
                }
                .sortedBy { (it.name ?: it.id).lowercase() }
            if (models.isEmpty()) return@forEach

            val allOn = models.all { m ->
                visibility[ModelVisibilityStore.visibilityKey(p.id, m.id)] ?: true
            }
            item(key = "h-${p.id}") {
                SectionSubheader(
                    title = p.name ?: p.id,
                    // Provider heading = bulk toggle for all its models (web parity).
                    trailing = {
                        OutlinedButton(onClick = {
                            ModelVisibilityStore.setProviderVisibility(
                                p.id,
                                models.map { it.id },
                                !allOn,
                            )
                            revision++
                        }) {
                            Text(
                                stringResource(
                                    if (allOn) R.string.hide_all else R.string.show_all,
                                ),
                            )
                        }
                    },
                )
            }
            items(models, key = { "${p.id}/${it.id}" }) { m: Model ->
                val shown = visibility[ModelVisibilityStore.visibilityKey(p.id, m.id)] ?: true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = m.name ?: m.id,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = shown,
                        onCheckedChange = { on ->
                            ModelVisibilityStore.setVisibility(p.id, m.id, on)
                            revision++
                        },
                    )
                }
                HorizontalDivider()
            }
        }
        item { Spacer(Modifier.height(MaterialTheme.spacing.large)) }
    }
}
