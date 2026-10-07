package com.opencode.android.ui
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.domain.Part
import com.opencode.android.ui.theme.spacing
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

private val RE_HUNK = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** One patch line with its old-file and new-file numbers (TUI gutter). */
internal data class DiffRow(
    val line: String,
    val oldNum: Int?,
    val newNum: Int?,
)

/**
 * Old- and new-file line numbers per patch line (null for headers/hunks).
 * Removed lines carry their old-file number, added and context lines the
 * new-file number — the TUI gutter behaviour. Pure, so unit-testable.
 */
internal fun diffLineNumbers(patch: String): List<DiffRow> {
    var old = 0
    var new = 0
    return patch
        .lineSequence()
        .map { line ->
            when {
                line.startsWith("@@") -> {
                    RE_HUNK.find(line)?.let {
                        // toIntOrNull: a malformed/out-of-range hunk number must not
                        // throw NumberFormatException during composition (crash).
                        old = it.groupValues[1].toIntOrNull() ?: 0
                        new = it.groupValues[2].toIntOrNull() ?: 0
                    }
                    DiffRow(line, null, null)
                }

                line.startsWith("+++") || line.startsWith("---") -> {
                    DiffRow(line, null, null)
                }

                line.startsWith("+") -> {
                    DiffRow(line, null, new++)
                }

                line.startsWith("-") -> {
                    DiffRow(line, old++, null)
                }

                else -> {
                    DiffRow(line, old++, new++)
                }
            }
        }.toList()
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun DiffBlock(
    patch: String,
    background: Color,
    contextOnly: Boolean = false,
) {
    // Rows as (line, fileNumber?) pairs. Numbers are parsed from the @@ hunks
    // over ALL lines first, so filtering context lines never shifts them:
    // each row shows the number the line has in the file (removed lines
    // their old-file number, added/context the new-file number, like the TUI).
    // Web parity: "Hide non-diff lines" drops the unchanged context so only the
    // hunks remain — the fastest way to read a large diff on a phone. The
    // truncation count must be based on the SAME (filtered) list that is drawn,
    // not the raw line total.
    val rows =
        remember(patch, contextOnly) {
            val numbered = diffLineNumbers(patch)
            if (!contextOnly) {
                numbered
            } else {
                numbered.filter { (line, _, _) ->
                    line.startsWith("@@") || line.startsWith("+++") || line.startsWith("---") ||
                        line.startsWith("+") || line.startsWith("-")
                }
            }
        }
    // TUI gutter: old number | new number, both right-aligned, one width.
    val gutterWidth =
        remember(rows) {
            val maxNum = (rows.mapNotNull { it.oldNum } + rows.mapNotNull { it.newNum }).maxOrNull() ?: 1
            (maxNum.toString().length * 8 + 4).dp
        }
    val contentScroll = androidx.compose.foundation.rememberScrollState()
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val codeStyle = MaterialTheme.typography.labelSmall
    val codeFont =
        com.opencode.android.ui.theme
            .codeFont()
    // BoxWithConstraints hands the viewport width down: each tinted row is at
    // least viewport-wide (TUI full-width tint), longer rows grow with content.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background, MaterialTheme.shapes.small)
                .padding(vertical = MaterialTheme.spacing.small),
    ) {
        val viewWidth = maxWidth
        Column(
            modifier = Modifier.horizontalScroll(contentScroll),
        ) {
            rows.take(MAX_DIFF_LINES).forEach { (line, oldNum, newNum) ->
                // Full-width tint like the TUI: added rows green, removed red.
                // Tinted from primary/error (not the container): containers sit
                // too close to the surface in some themes and the tint drowned.
                val rowBg =
                    when {
                        line.startsWith("+") && !line.startsWith("+++") -> {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        }

                        line.startsWith("-") && !line.startsWith("---") -> {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                        }

                        else -> {
                            Color.Transparent
                        }
                    }
                // Marker column like the TUI ([space]marker[space]).
                val (marker, markerColor) =
                    when {
                        line.startsWith("+") && !line.startsWith("+++") -> {
                            "+" to MaterialTheme.colorScheme.primary
                        }

                        line.startsWith("-") && !line.startsWith("---") -> {
                            "-" to MaterialTheme.colorScheme.error
                        }

                        else -> {
                            " " to Color.Transparent
                        }
                    }
                val lineColor =
                    when {
                        line.startsWith("+++") || line.startsWith("---") -> {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        line.startsWith("@@") -> {
                            MaterialTheme.colorScheme.primary
                        }

                        else -> {
                            MaterialTheme.colorScheme.onSurface
                        }
                    }
                Row(
                    modifier =
                        Modifier
                            .widthIn(min = viewWidth)
                            .background(rowBg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = oldNum?.toString() ?: "",
                        style = codeStyle,
                        fontFamily = codeFont,
                        color = gutterColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        softWrap = false,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .width(gutterWidth)
                                .padding(start = MaterialTheme.spacing.small),
                    )
                    Text(
                        text = newNum?.toString() ?: "",
                        style = codeStyle,
                        fontFamily = codeFont,
                        color = gutterColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        softWrap = false,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .width(gutterWidth)
                                .padding(start = 4.dp),
                    )
                    Text(
                        text = marker,
                        style = codeStyle,
                        fontFamily = codeFont,
                        color = markerColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        softWrap = false,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .width(16.dp)
                                .padding(horizontal = 2.dp),
                    )
                    Text(
                        text = if (line.startsWith("+") || line.startsWith("-")) line.drop(1) else line,
                        style = codeStyle,
                        fontFamily = codeFont,
                        color = lineColor,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.padding(end = MaterialTheme.spacing.small),
                    )
                }
            }
        }
    }
    if (rows.size > MAX_DIFF_LINES) {
        Text(
            text =
                pluralStringResource(
                    R.plurals.diff_lines_truncated,
                    rows.size - MAX_DIFF_LINES,
                    rows.size - MAX_DIFF_LINES,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Web "N Changed files" card: aggregate +/- plus one row per file.
@Composable
internal fun ChangedFilesCard(files: List<ChangedFile>) {
    if (files.isEmpty()) return
    val totalAdd = files.sumOf { it.added }
    val totalRem = files.sumOf { it.removed }
    val expandedPaths = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.extraSmall),
    ) {
        Column(
            modifier =
                Modifier
                    .animateContentSize()
                    .padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(
                    text = pluralStringResource(R.plurals.changed_files, files.size, files.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (totalAdd > 0) {
                    Text(
                        text = "+$totalAdd",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (totalRem > 0) {
                    Text(
                        text = "-$totalRem",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            files.forEach { file ->
                val name = file.path.substringAfterLast('/')
                val isExpanded = expandedPaths[file.path] == true
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !file.diff.isNullOrBlank()) {
                                    expandedPaths[file.path] = !isExpanded
                                }.semantics { contentDescription = file.path }
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (file.added > 0) {
                            Text(
                                text = "+${file.added}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (file.removed > 0) {
                            Text(
                                text = "-${file.removed}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    // Expanded: unified diff, colourised per line (web parity).
                    // Horizontally scrollable so long lines are not truncated.
                    if (isExpanded && !file.diff.isNullOrBlank()) {
                        Box(modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall)) {
                            DiffBlock(
                                patch = file.diff.orEmpty(),
                                background = MaterialTheme.colorScheme.surface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToolCallRow(
    part: Part,
    onOpenSession: (String) -> Unit = {},
    // "Expand shell/edit tool parts" settings: whether this row starts open.
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(part.id) { mutableStateOf(initiallyExpanded) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Hoisted: the copy action below is a plain lambda, not composable.
    val copiedMsg = stringResource(R.string.copied)
    val expandedLabel = stringResource(R.string.expanded)
    val collapsedLabel = stringResource(R.string.collapsed)
    val toolId = part.toolName() ?: "tool"
    val label = toolDisplayName(toolId)
    val title = toolTitleText(part)
    val status = toolStatus(part)
    val command = toolCommandText(part)
    val output = toolOutputText(part)
    // edit/write/patch tools carry the unified diff in metadata.diff; show it
    // in the expanded view (the row only showed the tool result before).
    val diffText = toolDiffText(part)
    val params = toolInputParams(part)
    val subagent = toolSubagent(part)
    val diffStat = toolDiffStat(part)
    val isRunning = status == "running" || status == "pending"
    val isError = status == "error"
    // Chevron eases open/closed on the shared spatial spring instead of
    // snapping; the row already animates its height, so the glyph matches it.
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec =
            com.opencode.android.ui.theme.Motion
                .spatial(),
        label = "toolChevron",
    )
    // Hoisted out of the onClick lambda (not a composable scope).
    val toolCopyLabel = stringResource(R.string.tool)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Keeps the visual density but guarantees the 48 dp minimum
                        // touch target a row of this height would otherwise miss.
                        .minimumInteractiveComponentSize()
                        .clickable { expanded = !expanded }
                        .semantics {
                            role = Role.Button
                            stateDescription = if (expanded) expandedLabel else collapsedLabel
                        }.padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                // Tool badge ("Shell", "Read", …)
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall, vertical = 2.dp),
                    )
                }
                // Subagent badge (web: task/delegate_task show e.g. "explorer").
                // Clicking it opens the subagent's own session, like the web link.
                subagent?.let {
                    val subagentSessionId = toolSubagentSessionId(part)
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier =
                            if (subagentSessionId != null) {
                                Modifier.clickable(role = Role.Button) { onOpenSession(subagentSessionId) }
                            } else {
                                Modifier
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall, vertical = 2.dp),
                        ) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            if (subagentSessionId != null) {
                                Text(
                                    text = "›",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = if (params.isEmpty()) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Input params exactly like the web: "agent=Planner", "prompt=…".
                    params.forEach { (key, value) ->
                        Text(
                            text = "$key=$value",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (key == "prompt") 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Web: "+20 -1" behind edit/write tool calls.
                diffStat?.let { (added, removed) ->
                    Text(
                        text = "+$added",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "-$removed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isRunning) {
                    // A softly pulsing dot reads "live" at a glance; the plain
                    // "Running" label alone was easy to miss in a long thread.
                    val pulse = rememberInfiniteTransition(label = "toolPulse")
                    val dotAlpha by pulse.animateFloat(
                        initialValue = 0.30f,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(durationMillis = 850),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "toolDot",
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(7.dp)
                                    .graphicsLayer { alpha = dotAlpha }
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            text = stringResource(R.string.running),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier =
                            Modifier
                                .size(18.dp)
                                .rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter =
                    androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                exit =
                    androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.small)
                            .padding(bottom = MaterialTheme.spacing.small),
                ) {
                    Column(modifier = Modifier.padding(MaterialTheme.spacing.small)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            IconButton(
                                onClick = {
                                    val clipText =
                                        buildString {
                                            diffText?.let { append(it).append('\n') }
                                            command?.let { append("$ ").append(it).append('\n') }
                                            append(output ?: "")
                                        }
                                    copyToClipboard(context, toolCopyLabel, clipText)
                                    toast(context, copiedMsg)
                                },
                                // 48dp touch target (a11y minimum), not 28dp.
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.copy),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Unified diff first (edit/write/patch), then the result.
                        if (!diffText.isNullOrBlank()) {
                            DiffBlock(
                                patch = diffText,
                                background = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacing.small))
                        }
                        Text(
                            text =
                                cappedForDisplay(
                                    buildString {
                                        command?.let { append("$ ").append(it).append('\n') }
                                        append(output ?: "")
                                    },
                                ).ifBlank { if (diffText.isNullOrBlank()) "—" else "" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily =
                                com.opencode.android.ui.theme
                                    .codeFont(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToolSummaryCard(parts: List<Part>) {
    // Hoisted out of the onClick lambda (not a composable scope).
    val toolCopyLabel = stringResource(R.string.tool)
    val copiedMsg = stringResource(R.string.copied)
    var expanded by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Part?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.extraSmall),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.small)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = toolSummaryLabel(parts),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter =
                    androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                exit =
                    androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut(),
            ) {
                parts.forEach { part ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = part }
                                .padding(vertical = MaterialTheme.spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = part.toolName() ?: "Tool",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = toolBasename(part),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        toolStatus(part)?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { part ->
        val detail = extractToolDetail(part)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${part.toolName() ?: "Tool"} · ${toolBasename(part)}") },
            text = {
                Column {
                    detail?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    toolStatus(part)?.let { status ->
                        Text(
                            text = stringResource(R.string.status_label, status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                        )
                    }
                    part.state?.let { state ->
                        Text(
                            text = state.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { selected = null }) { Text(stringResource(R.string.close)) }
            },
            dismissButton = {
                if (detail != null) {
                    OutlinedButton(onClick = {
                        copyToClipboard(context, toolCopyLabel, detail)
                        toast(
                            context,
                            copiedMsg,
                        )
                    }) { Text(stringResource(R.string.copy)) }
                }
            },
        )
    }
}
