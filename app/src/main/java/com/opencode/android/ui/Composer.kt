package com.opencode.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.domain.Agent
import com.opencode.android.domain.BUILTIN_SOURCE
import com.opencode.android.domain.CommandEntry
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.Model
import com.opencode.android.domain.ModelVariant
import com.opencode.android.domain.ProviderEntry
import com.opencode.android.domain.commandTemplate
import com.opencode.android.ui.theme.spacing
import kotlinx.coroutines.flow.filter

@Composable
private fun AttachmentLeadingIcon(attachment: Attachment) {
    val context = LocalContext.current
    val isImage = attachment.mime?.startsWith("image/") == true
    if (isImage) {
        // Decode off the main thread: a full-size photo (even at inSampleSize 4)
        // is milliseconds-to-hundreds of ms of work, and doing it inside
        // `remember` stalled the frame that composed the attachment chip.
        val bitmap by
            androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                initialValue = null,
                attachment.uri,
            ) {
                value =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val uri = android.net.Uri.parse(attachment.uri)
                            // Two-pass decode: read the bounds first, then pick an
                            // inSampleSize for a ~96px thumbnail. The old fixed
                            // inSampleSize=4 still decoded a 48 MP photo to ~3 MB
                            // per chip, and several chips could OOM.
                            val bounds =
                                android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                            }
                            var sample = 1
                            val target = 96
                            while (bounds.outWidth / sample > target * 2 ||
                                bounds.outHeight / sample > target * 2
                            ) {
                                sample *= 2
                            }
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                android.graphics.BitmapFactory.decodeStream(
                                    input,
                                    null,
                                    android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = sample
                                    },
                                )
                            }
                        }.getOrNull()
                    }
            }
        val decoded = bitmap
        if (decoded != null) {
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp)),
            )
            return
        }
    }
    Icon(
        if (isImage) Icons.Default.Image else Icons.Default.Description,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
    )
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

@Immutable
internal class ComposerActions(
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onInterrupt: () -> Unit,
    val onAddFiles: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onClearAttachments: () -> Unit = {},
    val onPickCommand: (String) -> Unit,
    val onBuiltinCommand: (String) -> Unit = {},
    val onAgentSelect: (String) -> Unit,
    val onModelSelect: (String) -> Unit,
    val onVariantSelect: (String) -> Unit,
    val onToggleModel: (providerId: String, modelId: String, show: Boolean) -> Unit = { _, _, _ -> },
    val onToggleProvider: (providerId: String, modelIds: List<String>, show: Boolean) -> Unit = { _, _, _ -> },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Composer(
    actions: ComposerActions,
    inputText: String,
    attachments: List<Attachment>,
    commands: List<CommandEntry>,
    files: List<FileEntry>,
    isGenerating: Boolean,
    isUploading: Boolean = false,
    uploadDone: Int = 0,
    uploadTotal: Int = 0,
    uploadingUris: Set<String> = emptySet(),
    statusError: String? = null,
    showAgent: Boolean = true,
    agents: List<Agent>,
    models: List<Model>,
    variants: List<ModelVariant>,
    selectedAgent: String,
    selectedModel: String,
    selectedVariant: String,
    providerGroups: List<ProviderEntry> = emptyList(),
) {
    val onInputChange = actions.onInputChange
    val onSend = actions.onSend
    val onInterrupt = actions.onInterrupt
    val onAddFiles = actions.onAddFiles
    val onRemoveAttachment = actions.onRemoveAttachment
    val onClearAttachments = actions.onClearAttachments
    val onPickCommand = actions.onPickCommand
    val onBuiltinCommand = actions.onBuiltinCommand
    val onAgentSelect = actions.onAgentSelect
    val onModelSelect = actions.onModelSelect
    val onVariantSelect = actions.onVariantSelect
    val onToggleModel = actions.onToggleModel
    val onToggleProvider = actions.onToggleProvider
    // Tactile confirmation for the two actions that change state globally
    // (send a turn / stop one); the button is small and the effect is otherwise
    // only visible after the network answers.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Show the display name like the web model button ("DeepSeek V4 Pro (New)").
    val modelDisplayName =
        remember(models, selectedModel) {
            com.opencode.android.util
                .friendlyModelName(models, selectedModel)
                .ifEmpty { "Choose model" }
        }
    var showModelPicker by remember { mutableStateOf(false) }
    var showManageModels by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val copiedMsg = stringResource(R.string.copied)

    fun toast(message: String) {
        toast(context, message)
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // Attachment chips and the command picker appear/disappear; animating
                // the height makes the whole composer grow and shrink smoothly
                // instead of snapping the message list up and down.
                .animateContentSize()
                .padding(horizontal = MaterialTheme.spacing.cardPadding, vertical = MaterialTheme.spacing.small)
                // Escape stops the running generation from anywhere in the composer.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape &&
                        isGenerating
                    ) {
                        onInterrupt()
                        true
                    } else {
                        false
                    }
                },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        // "/" command picker (mirrors web "/ for commands").
        // Visibility animates: without it the card snapped the whole
        // composer up/down on the first/last keystroke.
        androidx.compose.animation.AnimatedVisibility(
            visible =
                inputText.startsWith("/") && commands.isNotEmpty() &&
                    commands.any { it.name.lowercase().contains(inputText.removePrefix("/").substringBefore(" ").lowercase()) },
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            val query = inputText.removePrefix("/").substringBefore(" ").lowercase()
            val matches = commands.filter { it.name.lowercase().contains(query) }.take(6)
            if (matches.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        // Duplicate names would be a duplicate LazyColumn key crash.
                        items(matches.distinctBy { it.name }, key = { it.name }) { command ->
                            PickerRow(
                                modifier =
                                    Modifier.animateItem(
                                        placementSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .spatial(),
                                        fadeInSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                        fadeOutSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                    ),
                                title = "/${command.name}",
                                subtitle = command.description ?: "",
                                onClick = {
                                    // Client built-ins run immediately (e.g.
                                    // "/compact"); server commands insert their
                                    // template as before.
                                    if (command.source == BUILTIN_SOURCE) {
                                        onBuiltinCommand(command.name)
                                    } else {
                                        onPickCommand(
                                            commandTemplate(command)
                                                ?: "/${command.name} ",
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // "@" mention picker (mirrors web "@ for context", backed by the file list).
        val mentionQuery =
            remember(inputText) {
                Regex("@([A-Za-z0-9_./-]*)$").find(inputText)?.groupValues?.getOrNull(1)
            }
        val mentionMatches =
            remember(mentionQuery, files) {
                if (mentionQuery == null) {
                    emptyList()
                } else {
                    files
                        .filter {
                            (it.name ?: it.path ?: "").contains(mentionQuery, ignoreCase = true)
                        }.take(6)
                }
            }
        androidx.compose.animation.AnimatedVisibility(
            visible = mentionMatches.isNotEmpty(),
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            if (mentionMatches.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(
                            mentionMatches,
                            key = { it.path ?: it.name ?: it.hashCode().toString() },
                        ) { file ->
                            val ref = file.path ?: file.name ?: return@items
                            PickerRow(
                                modifier =
                                    Modifier.animateItem(
                                        placementSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .spatial(),
                                        fadeInSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                        fadeOutSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                    ),
                                title = file.name ?: ref,
                                subtitle = ref,
                                leading = {
                                    Icon(
                                        if (file.type == "directory") Icons.Default.Folder else Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    val q = mentionQuery ?: ""
                                    val replaced =
                                        inputText.replaceRange(
                                            inputText.length - q.length - 1,
                                            inputText.length,
                                            "@$ref ",
                                        )
                                    onInputChange(replaced)
                                },
                            )
                        }
                    }
                }
            }
        }

        // One calm container: attachments, input, controls. A faint focus ring
        // and a touch of elevation make it feel alive without shouting.
        val composerInteraction = remember { MutableInteractionSource() }
        val composerFocused by composerInteraction.collectIsFocusedAsState()
        val composerHasText = inputText.isNotBlank() || attachments.isNotEmpty()
        val composerBorder by animateColorAsState(
            targetValue =
                when {
                    composerFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    composerHasText -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                },
            animationSpec =
                com.opencode.android.ui.theme.Motion
                    .effects(),
            label = "composerBorder",
        )
        val composerContainer by animateColorAsState(
            targetValue =
                if (composerFocused) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            animationSpec =
                com.opencode.android.ui.theme.Motion
                    .effects(),
            label = "composerContainer",
        )
        val composerElevation by animateDpAsState(
            targetValue = if (composerFocused) 6.dp else 1.dp,
            animationSpec =
                com.opencode.android.ui.theme.Motion
                    .spatial(),
            label = "composerElevation",
        )
        Surface(
            color = composerContainer,
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, composerBorder),
            shadowElevation = composerElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Attachment chips (mirrors web attached files in the prompt bar)
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        items(
                            attachments,
                            key = { it.uri },
                        ) { attachment ->
                            AssistChip(
                                onClick = { onRemoveAttachment(attachment.uri) },
                                modifier =
                                    Modifier.animateItem(
                                        placementSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .spatial(),
                                        fadeInSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                        fadeOutSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                    ),
                                label = {
                                    val size = attachment.size?.takeIf { it > 0 }
                                    Text(
                                        text =
                                            if (size != null) {
                                                "${attachment.name} · ${formatBytes(size)}"
                                            } else {
                                                attachment.name
                                            },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    if (attachment.uri in uploadingUris) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        AttachmentLeadingIcon(attachment)
                                    }
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.remove),
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                        item {
                            TextButton(onClick = onClearAttachments) {
                                Text(
                                    stringResource(R.string.clear_all),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                // Single-row composer: plus, input, agent/model/effort chips,
                // send/stop. This removes the former full-width chip row while
                // keeping every control reachable.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                ) {
                    IconButton(
                        onClick = onAddFiles,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_images_and_files),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        interactionSource = composerInteraction,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    // Enter sends; Shift+Enter inserts a newline.
                                    if (event.key == Key.Enter && !event.isShiftPressed) {
                                        if (inputText.isNotBlank() || attachments.isNotEmpty()) onSend()
                                        true
                                    } else {
                                        false
                                    }
                                },
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions =
                            KeyboardActions(
                                onSend = {
                                    if (inputText.isNotBlank() || attachments.isNotEmpty()) onSend()
                                },
                            ),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.ask_anything_for_commands),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    // Send/Stop: one button that breathes. The container colour
                    // crossfades (wine → error container) and the whole button
                    // scales with an expressive spring so arming a send feels
                    // tactile rather than binary.
                    val actionActive = composerHasText || isGenerating
                    val actionScale by animateFloatAsState(
                        targetValue = if (actionActive) 1f else 0.86f,
                        animationSpec =
                            com.opencode.android.ui.theme.Motion
                                .expressive(),
                        label = "actionScale",
                    )
                    val actionContainer by animateColorAsState(
                        targetValue =
                            when {
                                isGenerating -> MaterialTheme.colorScheme.errorContainer
                                composerHasText -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        animationSpec =
                            com.opencode.android.ui.theme.Motion
                                .effects(),
                        label = "actionContainer",
                    )
                    val actionContent by animateColorAsState(
                        targetValue =
                            when {
                                isGenerating -> MaterialTheme.colorScheme.onErrorContainer
                                composerHasText -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        animationSpec =
                            com.opencode.android.ui.theme.Motion
                                .effects(),
                        label = "actionContent",
                    )
                    FilledTonalIconButton(
                        onClick = {
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            if (isGenerating) onInterrupt() else onSend()
                        },
                        enabled = (isGenerating || composerHasText) && !isUploading,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = actionContainer,
                                contentColor = actionContent,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        modifier =
                            Modifier
                                .size(40.dp)
                                .graphicsLayer {
                                    scaleX = actionScale
                                    scaleY = actionScale
                                },
                    ) {
                        androidx.compose.animation.Crossfade(
                            targetState = isGenerating,
                            animationSpec =
                                com.opencode.android.ui.theme.Motion
                                    .effects(),
                            label = "sendStopIcon",
                        ) { generating ->
                            if (isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = actionContent,
                                )
                            } else if (generating) {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop))
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.send),
                                )
                            }
                        }
                    }
                }
                // One centered selector row; widths are bounded so the chips
                // never wrap or overlap on a phone.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            MaterialTheme.spacing.extraSmall,
                            Alignment.CenterHorizontally,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showAgent) {
                        AssistChip(
                            onClick = { showAgentPicker = true },
                            border = null,
                            colors =
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            label = { Text(text = selectedAgent, maxLines = 1) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = { showModelPicker = true },
                        border = null,
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        modifier = Modifier.widthIn(max = 160.dp),
                        label = {
                            androidx.compose.animation.Crossfade(
                                targetState = modelDisplayName,
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                                label = "modelName",
                            ) { name ->
                                Text(
                                    text = name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    if (variants.isNotEmpty()) {
                        ChipMenu(
                            label = selectedVariant.ifEmpty { "default" },
                            // Remembered: the composer recomposes per keystroke
                            // and per streaming tick; the id list only changes
                            // when the variant set does.
                            options = remember(variants) { variants.map { it.id } },
                            onSelect = onVariantSelect,
                            modifier = Modifier.widthIn(min = 72.dp, max = 140.dp),
                        )
                    }
                }
                // Only show a status row when it carries information: spinner
                // while generating or a provider/usage error. The agent label
                // duplicated the top-bar subtitle and cost a full row.
                if (isGenerating || isUploading || !statusError.isNullOrBlank()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = MaterialTheme.spacing.small, end = MaterialTheme.spacing.small, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        if (isGenerating || isUploading) {
                            InlineSpinner()
                        }
                        if (isUploading) {
                            Text(
                                text =
                                    if (uploadTotal > 0) {
                                        "Uploading $uploadDone/$uploadTotal…"
                                    } else {
                                        "Uploading attachments…"
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            )
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !statusError.isNullOrBlank(),
                            enter =
                                androidx.compose.animation.fadeIn() +
                                    androidx.compose.animation.expandHorizontally(),
                            exit =
                                androidx.compose.animation.fadeOut() +
                                    androidx.compose.animation.shrinkHorizontally(),
                        ) {
                            // Long provider errors ("insufficient balance…"
                            // with account links) were hard-clipped at 2
                            // lines. Tap toggles the full text.
                            var errorExpanded by remember(statusError) { mutableStateOf(false) }
                            Text(
                                text = statusError ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = if (errorExpanded) Int.MAX_VALUE else 2,
                                overflow = if (errorExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .pointerInput(statusError) {
                                            detectTapGestures(
                                                onTap = { errorExpanded = !errorExpanded },
                                                onLongPress = {
                                                    val textToCopy = statusError.orEmpty()
                                                    if (textToCopy.isNotBlank()) {
                                                        val clipboard =
                                                            context.getSystemService(
                                                                Context.CLIPBOARD_SERVICE,
                                                            ) as ClipboardManager
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("error_text", textToCopy))
                                                        toast(copiedMsg)
                                                    }
                                                },
                                            )
                                        },
                            )
                        }
                    }
                }
            }
        }
        if (showAgentPicker) {
            AgentPickerDialog(
                agents = agents,
                selectedAgent = selectedAgent,
                onSelect = { agent ->
                    onAgentSelect(agent)
                    showAgentPicker = false
                },
                onDismiss = { showAgentPicker = false },
            )
        }
        if (showModelPicker) {
            ModelPickerDialog(
                models = models,
                selectedModel = selectedModel,
                onSelect = { model ->
                    onModelSelect(model)
                    showModelPicker = false
                },
                onManageModels = {
                    showModelPicker = false
                    showManageModels = true
                },
                onDismiss = { showModelPicker = false },
                providerGroups = providerGroups,
            )
        }
        if (showManageModels) {
            ManageModelsDialog(
                providerGroups = providerGroups,
                onToggleModel = onToggleModel,
                onToggleProvider = onToggleProvider,
                onDismiss = { showManageModels = false },
            )
        }
        // (A duplicate "Ask anything…" hint used to be rendered here, below the
        // composer. The real placeholder already lives inside the text field, so
        // this was a leftover line of dead height at the bottom of the screen.)
    }
}
