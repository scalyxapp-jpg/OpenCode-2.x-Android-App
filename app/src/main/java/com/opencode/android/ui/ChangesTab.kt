package com.opencode.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.ui.theme.spacing
import com.opencode.android.ui.settings.SectionHeader
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.res.stringResource
import com.opencode.android.R

@Composable
internal fun ChangesTab(
    files: List<FileEntry>,
    fileViewer: FileViewerState?,
    vcsBranch: String? = null,
    vcsDiff: List<VcsDiffFile> = emptyList(),
    // Session directory (server path). Null until the session payload resolves
    // it; never a hardcoded developer path.
    initialPath: String? = null,
    // "File tree" setting: hide the tree panel entirely when off.
    showFileTree: Boolean = true,
    onLoadFiles: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onDismissViewer: () -> Unit,
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    // Mirrors the web "Toggle file tree" button in the Review and files panel.
    var treeExpanded by remember { mutableStateOf(true) }
    // Web parity for the changed-files panel: a path filter, a single open diff
    // with Previous/Next navigation, and the "Hide non-diff lines" switch.
    var diffFilter by remember { mutableStateOf("") }
    // Default matches the web: the whole diff is shown, and the toggle offers to
    // hide the unchanged context.
    var showAllLines by remember { mutableStateOf(true) }
    var expandedFile by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Follow the session directory. The ViewModel already loads it on session
    // open, so only re-fetch when the session actually changed — the old
    // LaunchedEffect(Unit) hardcoded a path and overrode the session listing.
    LaunchedEffect(initialPath) {
        if (initialPath != null && initialPath != currentPath) {
            currentPath = initialPath
            onLoadFiles(initialPath)
        }
    }
    LaunchedEffect(currentPath) {
        listState.scrollToItem(0)
    }

    fun parentOf(path: String?): String? {
        val trimmed = path?.trimEnd('/') ?: return null
        if (!trimmed.contains('/')) return null
        val parent = trimmed.substringBeforeLast('/')
        return parent.ifBlank { "/" }
    }

    val filtered = remember(vcsDiff, diffFilter) {
        if (diffFilter.isBlank()) {
            vcsDiff
        } else {
            vcsDiff.filter { it.file.contains(diffFilter, ignoreCase = true) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("changes-list")
            .padding(MaterialTheme.spacing.medium),
    ) {
        // Nothing to show (no VCS diff and no browsable directory): render an
        // explicit empty state instead of a blank panel.
        if (vcsDiff.isEmpty() && (!showFileTree || currentPath == null)) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = stringResource(R.string.no_changes),
                        icon = Icons.Default.CheckCircle,
                        compact = true,
                    )
                }
            }
        }
        // Session diff (web Review/Changes panel: GET /vcs + /vcs/diff?mode=git).
        // Stats come straight from the server payload (additions/deletions/
        // status) exactly like the web. The app used to re-count "+"/"-" lines
        // from the patch on every recomposition: slower, and the totals drifted
        // from what the web showed.
        if (vcsDiff.isNotEmpty()) {
            val totalAdd = vcsDiff.sumOf { it.additions }
            val totalRem = vcsDiff.sumOf { it.deletions }
            item {
                SectionHeader(
                    title = "${vcsDiff.size} Files Changed",
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        ) {
                            vcsBranch?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (totalAdd > 0) {
                                androidx.compose.animation.Crossfade(
                                    targetState = totalAdd,
                                    label = "totalAdd",
                                ) { n ->
                                    Text(
                                        text = "+$n",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (totalRem > 0) {
                                androidx.compose.animation.Crossfade(
                                    targetState = totalRem,
                                    label = "totalRem",
                                ) { n ->
                                    Text(
                                        text = "-$n",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    },
                )
            }
            // Web "Filter files": narrows the changed-file list by path.
            item {
                PickerSearchField(
                    query = diffFilter,
                    onQueryChange = { diffFilter = it },
                    placeholder = stringResource(R.string.filter_files),
                )
            }

            item {
                androidx.compose.animation.AnimatedVisibility(
                    visible = filtered.isEmpty(),
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    Text(
                        text = stringResource(R.string.no_matching_files),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(
                items = filtered,
                key = { changed -> "diff:${changed.file}" },
            ) { changed ->
                val isOpen = expandedFile == changed.file
                // Hoisted: the semantics lambda below is not a composable scope.
                val expandedLabel = stringResource(R.string.expanded)
                val collapsedLabel = stringResource(R.string.collapsed)
                Column(modifier = Modifier.animateContentSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable {
                                expandedFile = if (isOpen) null else changed.file
                                showAllLines = true
                            }
                            .semantics {
                                role = Role.Button
                                stateDescription = if (isOpen) expandedLabel else collapsedLabel
                            }
                            .padding(vertical = MaterialTheme.spacing.extraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        changed.status?.let { status ->
                            Text(
                                text = vcsStatusBadge(status),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Show the file NAME prominently and bound the directory:
                        // a single long path ellipsised away the file name, so
                        // many rows looked identical ("…/app/…").
                        val fileDir = changed.file.substringBeforeLast('/', "")
                        val fileName = changed.file.substringAfterLast('/')
                        if (fileDir.isNotEmpty()) {
                            Text(
                                text = "$fileDir/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        }
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (changed.additions > 0) {
                            Text(
                                text = "+${changed.additions}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (changed.deletions > 0) {
                            Text(
                                text = "-${changed.deletions}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (isOpen && !changed.patch.isNullOrBlank()) {
                        // Web parity: "Previous file" / "Next file" step through
                        // the changed files without collapsing back to the list,
                        // and "Hide non-diff lines" trims the context.
                        val index = filtered.indexOfFirst { it.file == changed.file }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = index > 0,
                                onClick = {
                                    expandedFile = filtered.getOrNull(index - 1)?.file
                                    showAllLines = true
                                },
                            ) { Text(stringResource(R.string.previous)) }
                            Text(
                                text = "${index + 1} / ${filtered.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = index >= 0 && index < filtered.size - 1,
                                onClick = {
                                    expandedFile = filtered.getOrNull(index + 1)?.file
                                    showAllLines = true
                                },
                            ) { Text(stringResource(R.string.next)) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showAllLines = !showAllLines }) {
                                Text(
                                    stringResource(
                                        if (showAllLines) {
                                            R.string.hide_non_diff_lines
                                        } else {
                                            R.string.show_all_lines
                                        },
                                    ),
                                )
                            }
                        }
                        DiffBlock(
                            patch = changed.patch.orEmpty(),
                            background = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contextOnly = !showAllLines,
                        )
                    }
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(MaterialTheme.spacing.small))
                    HorizontalDivider()
                    Spacer(Modifier.height(MaterialTheme.spacing.small))
                }
            }
        }

        if (showFileTree && currentPath != null) {
            item {
                SectionHeader(
                    title = stringResource(R.string.files),
                    trailing = {
                        OutlinedButton(onClick = { treeExpanded = !treeExpanded }) {
                            Text(
                                stringResource(
                                    if (treeExpanded) R.string.collapse_tree else R.string.expand_tree,
                                ),
                            )
                        }
                    },
                )
            }
            item {
                // Breadcrumb with parent navigation (mirrors file tree navigation)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                    modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
                ) {
                    parentOf(currentPath)?.let { parent ->
                        OutlinedButton(onClick = {
                            currentPath = parent
                            onLoadFiles(parent)
                        }) { Text("←") }
                    }
                    Text(
                        text = currentPath ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!treeExpanded) {
                item {
                    Box(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(MaterialTheme.spacing.large),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.file_tree_collapsed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (files.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(MaterialTheme.spacing.large),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.no_files),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(
                    items = files,
                    key = { file -> "file:${file.path ?: file.name ?: file.hashCode()}" },
                ) { file ->
                    val isDir = file.type == "directory"
                    Row(
                        modifier = Modifier
                            .animateItem(
                                placementSpec = com.opencode.android.ui.theme.Motion.spatial(),
                                fadeInSpec = com.opencode.android.ui.theme.Motion.effects(),
                                fadeOutSpec = com.opencode.android.ui.theme.Motion.effects(),
                            )
                            .fillMaxWidth()
                            .clickable {
                                if (isDir) {
                                    file.path?.let {
                                        currentPath = it
                                        onLoadFiles(it)
                                    }
                                } else {
                                    file.path?.let { onOpenFile(it) }
                                }
                            }
                            .padding(vertical = MaterialTheme.spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Icon(
                            if (isDir) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = file.name ?: file.path ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    // File viewer dialog (mirrors web "Open file")
    fileViewer?.let { viewer ->
        AlertDialog(
            onDismissRequest = onDismissViewer,
            title = {
                Text(
                    text = com.opencode.android.util.lastPathSegment(viewer.path),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                when {
                    viewer.isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        ) {
                            InlineSpinner()
                            Text(stringResource(R.string.loading_file), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    viewer.error != null -> {
                        Text(
                            text = stringResource(R.string.error_prefix, viewer.error ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            item {
                                Text(
                                    text = viewer.content ?: "(empty)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismissViewer) { Text(stringResource(R.string.close)) }
            },
        )
    }
}
