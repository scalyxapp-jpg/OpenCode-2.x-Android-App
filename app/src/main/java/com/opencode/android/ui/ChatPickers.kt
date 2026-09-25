package com.opencode.android.ui
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.data.ModelVisibilityStore
import com.opencode.android.data.RecentModelsStore
import com.opencode.android.domain.Model
import com.opencode.android.ui.theme.spacing
import com.opencode.android.ui.settings.SectionHeader
import kotlinx.coroutines.flow.filter
import com.opencode.android.domain.ProviderEntry
import androidx.compose.ui.res.stringResource
import com.opencode.android.R

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun ChipMenu(
    label: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            modifier = modifier,
            border = null,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.take(60).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, maxLines = 1) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * Search field shared by every picker dialog (model picker, manage models, …)
 * so they look and behave identically instead of each rolling its own field.
 */
@Composable
internal fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
    )
}

/**
 * One selectable row inside a picker: a title line, a muted secondary line, and
 * a trailing control (a check for a single-choice picker, a switch for a
 * toggle list). Shared by the model picker and "Manage models" so a model reads
 * the same in both places.
 */
@Composable
internal fun PickerRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
            )
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(
                horizontal = MaterialTheme.spacing.cardPadding,
                vertical = MaterialTheme.spacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> trailing()
            selected -> Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.selected_label),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun ModelPickerDialog(
    models: List<Model>,
    selectedModel: String,
    onSelect: (String) -> Unit,
    onManageModels: () -> Unit = {},
    onDismiss: () -> Unit,
    // Provider display names, so the group headers read "Groq" / "OpenRouter"
    // here exactly like in "Manage models" instead of the raw lowercase id.
    providerGroups: List<ProviderEntry> = emptyList(),
) {
    var searchQuery by remember { mutableStateOf("") }

    val providerNames = remember(providerGroups) {
        providerGroups.associate { it.id to (it.name ?: it.id) }
    }

    // Read visibility fresh from the store on every open: a model toggled off
    // in Settings → Models must disappear here immediately (the ViewModel
    // snapshot is only refreshed when a session loads).
    val modelVisibility = remember { ModelVisibilityStore.visibilityMap() }

    // Hide models the user disabled in "Manage models" (web behaviour).
    val visibleModels = remember(models, modelVisibility) {
        models.filter { m ->
            val key = ModelVisibilityStore.visibilityKey(m.providerId ?: "other", m.id)
            modelVisibility[key] != false
        }
    }

    // Group models by provider, de-duplicated per provider. The catalogue can
    // list the same model id more than once (a model reachable through two
    // entries). That alone was enough to crash the picker, because a LazyColumn
    // key has to be unique across the WHOLE list — the moment a search made two
    // copies visible at once it threw
    //   IllegalArgumentException: Key "…" was already used.
    val grouped = remember(visibleModels) {
        visibleModels
            .groupBy { it.providerId ?: "other" }
            .mapValues { (_, list) -> list.distinctBy { it.id } }
    }

    // Filter by search query
    val filtered = remember(grouped, searchQuery) {
        if (searchQuery.isBlank()) grouped
        else grouped.mapValues { (_, list) ->
            list.filter {
                it.id.contains(searchQuery, ignoreCase = true) ||
                    (it.name ?: "").contains(searchQuery, ignoreCase = true)
            }
        }.filterValues { it.isNotEmpty() }
    }

    // Recently used models, resolved against the currently visible set so a
    // model hidden in "Manage models" or absent from this backend disappears.
    val recentModels = remember(visibleModels) {
        val byRef = visibleModels.associateBy {
            com.opencode.android.util.sessionModelRef(it.id, it.providerId)
        }
        RecentModelsStore.recent().mapNotNull { byRef[it] }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_model)) },
        text = {
            Column {
                PickerSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.search_models),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.small))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 400dp + title/buttons exceeded the dialog height, so the
                        // last row was clipped; 320dp leaves room for everything.
                        .heightIn(max = 320.dp),
                    // Bottom clearance so the last model id is not clipped.
                    contentPadding = PaddingValues(bottom = MaterialTheme.spacing.medium),
                ) {
                    // Short recent-first section, only when not searching.
                    if (searchQuery.isBlank() && recentModels.isNotEmpty()) {
                        item(key = "recent-header") { SectionHeader("Recent") }
                        items(recentModels, key = { "recent-${it.providerId}/${it.id}" }) { model ->
                            val ref = com.opencode.android.util.sessionModelRef(
                                model.id,
                                model.providerId,
                            )
                            PickerRow(
                                title = model.name ?: model.id,
                                subtitle = model.id,
                                selected = ref == selectedModel,
                                onClick = {
                                    RecentModelsStore.record(ref)
                                    onSelect(ref)
                                },
                            )
                        }
                        item(key = "recent-divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                            )
                        }
                    }
                    filtered.forEach { (provider, providerModels) ->
                        item(key = "header-$provider") {
                            SectionHeader(providerNames[provider] ?: provider)
                        }
                        // Provider-qualified key: ids are only unique within a
                        // provider, and LazyColumn keys are global.
                        items(providerModels, key = { "$provider/${it.id}" }) { model ->
                            // selectedModel is the provider-qualified ref while
                            // model.id is bare, so compare through the helper.
                            val isSelected =
                                com.opencode.android.util.sessionModelRef(
                                    model.id,
                                    model.providerId,
                                ) == selectedModel
                            PickerRow(
                                modifier = Modifier.animateItem(
                                    placementSpec = com.opencode.android.ui.theme.Motion.spatial(),
                                    fadeInSpec = com.opencode.android.ui.theme.Motion.effects(),
                                    fadeOutSpec = com.opencode.android.ui.theme.Motion.effects(),
                                ),
                                title = model.name ?: model.id,
                                subtitle = model.id,
                                selected = isSelected,
                                onClick = {
                                    val ref = com.opencode.android.util.sessionModelRef(
                                        model.id,
                                        model.providerId ?: provider,
                                    )
                                    RecentModelsStore.record(ref)
                                    onSelect(ref)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onManageModels) {
                Text(stringResource(R.string.manage_models))
            }
        },
    )
}

// Same look as the model picker (search field, rows, check mark) for the
// composer agent switcher: the old dropdown menu became an endless list.
@Composable
internal fun AgentPickerDialog(
    agents: List<com.opencode.android.domain.Agent>,
    selectedAgent: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(agents, searchQuery) {
        if (searchQuery.isBlank()) agents
        else agents.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
                (it.name ?: "").contains(searchQuery, ignoreCase = true) ||
                (it.description ?: "").contains(searchQuery, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_agent)) },
        text = {
            Column {
                PickerSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.search_agents),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.small))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    contentPadding = PaddingValues(bottom = MaterialTheme.spacing.medium),
                ) {
                    items(filtered, key = { it.id }) { agent ->
                        PickerRow(
                            modifier = Modifier.animateItem(
                                placementSpec = com.opencode.android.ui.theme.Motion.spatial(),
                                fadeInSpec = com.opencode.android.ui.theme.Motion.effects(),
                                fadeOutSpec = com.opencode.android.ui.theme.Motion.effects(),
                            ),
                            title = agent.name ?: agent.id,
                            subtitle = agent.description ?: agent.mode ?: "",
                            selected = agent.id == selectedAgent,
                            onClick = { onSelect(agent.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

// Mirrors the web "Manage models" dialog: search, provider groups with a
// master switch and per-model visibility switches. Purely client-side.
@Composable
internal fun ManageModelsDialog(
    providerGroups: List<ProviderEntry>,
    onToggleModel: (providerId: String, modelId: String, show: Boolean) -> Unit,
    onToggleProvider: (providerId: String, modelIds: List<String>, show: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    // Live state: switches must reflect a toggle without reopening the dialog.
    var modelVisibility by remember { mutableStateOf(ModelVisibilityStore.visibilityMap()) }

    fun isVisible(providerId: String, modelId: String): Boolean =
        modelVisibility[ModelVisibilityStore.visibilityKey(providerId, modelId)] != false

    val filteredGroups = remember(providerGroups, searchQuery) {
        if (searchQuery.isBlank()) {
            providerGroups
        } else {
            providerGroups.mapNotNull { p ->
                val matches = p.models.values.filter {
                    it.id.contains(searchQuery, ignoreCase = true) ||
                        (it.name ?: "").contains(searchQuery, ignoreCase = true)
                }
                if (matches.isEmpty()) null
                else ProviderEntry(
                    id = p.id,
                    name = p.name,
                    models = matches.associateBy { it.id },
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.manage_models))
                Text(
                    text = stringResource(R.string.manage_models_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                PickerSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.search_models),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    // Bottom clearance so the last model row is not clipped by
                    // the dialog edge.
                    contentPadding = PaddingValues(bottom = MaterialTheme.spacing.medium),
                ) {
                    filteredGroups.forEach { provider ->
                        val ids = provider.models.keys.toList()
                        val allVisible = ids.isNotEmpty() && ids.all { isVisible(provider.id, it) }
                        item(key = "h-${provider.id}") {
                            SectionHeader(
                                title = provider.name ?: provider.id,
                                trailing = {
                                    Switch(
                                        checked = allVisible,
                                        onCheckedChange = {
                                            onToggleProvider(provider.id, ids, it)
                                            modelVisibility = ModelVisibilityStore.visibilityMap()
                                        },
                                    )
                                },
                            )
                        }
                        items(provider.models.values.toList(), key = { "${provider.id}/${it.id}" }) { model ->
                            val modelId = model.id.substringAfter('/')
                            PickerRow(
                                modifier = Modifier.animateItem(
                                    placementSpec = com.opencode.android.ui.theme.Motion.spatial(),
                                    fadeInSpec = com.opencode.android.ui.theme.Motion.effects(),
                                    fadeOutSpec = com.opencode.android.ui.theme.Motion.effects(),
                                ),
                                title = model.name ?: model.id,
                                subtitle = model.id,
                                trailing = {
                                    Switch(
                                        checked = isVisible(provider.id, modelId),
                                        onCheckedChange = { show ->
                                            onToggleModel(provider.id, modelId, show)
                                            modelVisibility = ModelVisibilityStore.visibilityMap()
                                        },
                                    )
                                },
                            )
                        }
                        item(key = "d-${provider.id}") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
