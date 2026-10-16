package com.opencode.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
                        .clip(MaterialTheme.shapes.extraSmall),
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
    /** Dictation appends to the CURRENT text; the composable's captured value would lose chunks. */
    val onAppendInput: (String) -> Unit = onInputChange,
    /** Raw dictated text plus the punctuation switch - finalised in the view model. */
    val onAppendDictated: (String, Boolean) -> Unit = { text, _ -> onAppendInput(text) },
    val onSend: () -> Unit,
    /**
     * Queues the composer's text instead of sending it now — the web's Ctrl+Enter
     * (POST /prompt with `delivery:"queue"`).
     */
    val onQueue: () -> Unit = {},
    /** Opens the prompt history (TUI parity). */
    val onHistory: () -> Unit = {},
    /** Offline outbox: try to deliver the waiting prompts now. */
    val onFlushOutbox: () -> Unit = {},
    /** Offline outbox: drop them. */
    val onClearOutbox: () -> Unit = {},
    /** Hands the text to the running turn (web: Steer). */
    val onSteer: () -> Unit = {},
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
    /** Prompts waiting in the session queue (web shows "{{count}} queued"). */
    queuedCount: Int = 0,
    /** "queue" or "steer" — what the follow-up button does (Preferences). */
    followUpBehavior: String = "queue",
    /** Settings → "Speech input in the composer": show and allow the microphone. */
    speechInputEnabled: Boolean = false,
    /** Settings → dictation quality/behaviour (passed through to the request policy). */
    speechQuality: Boolean = true,
    speechOfflinePreferred: Boolean = false,
    /** Settings → spoken punctuation and dictation language. */
    speechPunctuation: Boolean = true,
    speechLanguage: String = "",
    /** Title of the open session: supplies the vocabulary hint for dictation. */
    speechSessionTitle: String? = null,
    /** Prompts waiting in the offline outbox for this session. */
    outboxCount: Int = 0,
    outboxFlushing: Boolean = false,
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
    val onAppendInput = actions.onAppendInput
    val onAppendDictated = actions.onAppendDictated
    val onSend = actions.onSend
    val onQueue = actions.onQueue
    val onSteer = actions.onSteer
    val onHistory = actions.onHistory
    val onFlushOutbox = actions.onFlushOutbox
    val onClearOutbox = actions.onClearOutbox
    val onInterrupt = actions.onInterrupt
    val onAddFiles = actions.onAddFiles

    // --- speech-to-text (push to talk) ------------------------------------
    // Hold the button to talk, release to get the text. Nothing records on its own: the only
    // place that starts listening is the press handler below, and the release ends it - so a
    // forgotten microphone cannot happen. Recognition runs on the device's own service.
    val speechContext = androidx.compose.ui.platform.LocalContext.current
    val speech = remember { SpeechInput(speechContext) }
    var listening by remember { mutableStateOf(false) }

    /** Last partial of the running attempt: the fallback when the engine returns nothing. */
    var lastPartial by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { speech.destroy() } }
    val speechToast = com.opencode.android.util.UserMessages
    val micPermission =
        rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) { granted ->
            // Granting must NOT start a recording by itself: the finger is already off the button,
            // and nothing may record without it. Say what to do instead.
            speechToast.post(
                if (granted) R.string.speech_permission_granted else R.string.speech_permission_denied,
            )
        }

    /** Records until [stopDictation]; the final result lands in the composer. */

    /**
     * Starts recording. Called ONLY from the press handler of the microphone button, and always
     * followed by [stopDictation] on release - nothing starts a recording by itself.
     */
    fun startDictation() {
        val options =
            com.opencode.android.util.SpeechOptions(
                preferOffline = speechOfflinePreferred,
                optimizeQuality = speechQuality,
                // The device's language, made explicit: an engine that guesses wrong costs whole
                // words.
                // An explicit choice wins; otherwise the device's own language, sent
                // explicitly because an engine that guesses costs whole words.
                languageTag =
                    speechLanguage.takeIf { it.isNotBlank() }
                        ?: if (android.os.Build.VERSION.SDK_INT >= 24) {
                            java.util.Locale
                                .getDefault()
                                .toLanguageTag()
                        } else {
                            null
                        },
                biasing =
                    com.opencode.android.util.SpeechRequestPolicy
                        .biasingFor(speechSessionTitle) +
                        (if (speechQuality) com.opencode.android.util.SpeechRequestPolicy.DOMAIN_WORDS else emptyList()),
            )
        lastPartial = ""
        speech.start(
            options = options,
            onPartial = { lastPartial = it },
            // A chunk the engine finalised while the finger STAYS on the button: insert it now and
            // keep recording, so the rest of the sentence is not cut off (reported).
            // Raw text + the punctuation switch go to the view model: it knows the CURRENT field
            // text, so it can decide whether the phrase continues a sentence (and therefore must
            // NOT be capitalised). Doing that here captured a stale value.
            onChunk = { chunk -> onAppendDictated(chunk, speechPunctuation) },
            onFinal = { text ->
                listening = false
                // Spoke, released, engine returned nothing: use the last partial it already heard
                // instead of dropping the words (on-device engines do this regularly).
                val source =
                    text.ifBlank {
                        com.opencode.android.util.DictationPolicy
                            .fallbackFromPartial(lastPartial)
                    }
                lastPartial = ""
                // Straight into the composer: finalised text, no intermediate window, no message.
                onAppendDictated(source, speechPunctuation)
            },
            onError = { error, message ->
                listening = false
                // The policy already filtered the expected noise (release, stale attempt, nothing
                // heard); what arrives here is worth telling.
                when (error) {
                    com.opencode.android.util.SpeechError.Unavailable -> {
                        speechToast.post(R.string.speech_unavailable)
                    }

                    com.opencode.android.util.SpeechError.Busy -> {
                        speechToast.post(R.string.speech_busy)
                    }

                    com.opencode.android.util.SpeechError.Permission -> {
                        speechToast.post(R.string.speech_permission_denied)
                    }

                    else -> {
                        speechToast.post(R.string.speech_failed, message.orEmpty())
                    }
                }
            },
        )
        listening = true
    }

    /** Ends the recording because the button was released. */
    fun stopDictation() {
        speech.stop()
        listening = false
    }

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
    // When several providers serve the same name the provider is appended, so the
    // active model stays unambiguous.
    val modelDisplayName =
        remember(models, selectedModel, providerGroups) {
            com.opencode.android.util
                .activeModelLabel(models, selectedModel, providerGroups)
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
                    com.opencode.android.ui.session
                        .filterCommands(commands, inputText.removePrefix("/").substringBefore(" "))
                        .isNotEmpty(),
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            val query = inputText.removePrefix("/").substringBefore(" ").lowercase()
            // EVERY matching command (the TUI lists them all); the list scrolls.
            // The old `.take(6)` silently hid most commands, and which six
            // survived depended on catalog order — "sometimes this, sometimes that".
            val matches =
                com.opencode.android.ui.session
                    .filterCommands(commands, query)
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
                                    // "/compact"). For a server command the
                                    // server's `/command` list carries no template
                                    // string, so "/name " is inserted and the user
                                    // types the arguments.
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
                    // No `.take(6)`: the list scrolls (heightIn(max = 220.dp)), and the
                    // cap silently hid every match past the sixth — the same defect the
                    // "/" picker already had fixed.
                    files.filter {
                        (it.name ?: it.path ?: "").contains(mentionQuery, ignoreCase = true)
                    }
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
                    // Microphone and attachment sit as ONE group with no gap between them.
                    // The right-hand pair (history 40 dp + send 48 dp) shows a 24 dp glyph gap;
                    // with 48 + 4 + 48 on the left it was 28 dp, which read as too much space
                    // (reported with a screenshot). Same 24 dp now.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        if (speechInputEnabled) {
                            // Same box as the other controls: a 48 dp touch target with a GLYPH at the
                            // icon size. Drawing the icon itself at 48 dp made it roughly 1.7x the
                            // neighbouring "+" (reported with a screenshot).
                            Box(
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .pointerInput(speechInputEnabled) {
                                            detectTapGestures(
                                                onPress = {
                                                    // Confirmation that the recording started: the
                                                    // microphone is held with the thumb, so the screen is
                                                    // often not readable while dictating.
                                                    // Direct vibrator pulse: performHapticFeedback is
                                                    // dropped when the system touch-feedback setting is
                                                    // off, which made the button feel dead.
                                                    com.opencode.android.util.Haptics.press(speechContext)
                                                    if (speech.hasPermission()) {
                                                        startDictation()
                                                    } else {
                                                        micPermission.launch(
                                                            android.Manifest.permission.RECORD_AUDIO,
                                                        )
                                                    }
                                                    // Suspends until release or cancel.
                                                    tryAwaitRelease()
                                                    // A lighter tick marks the end, so "recording" and
                                                    // "done" are distinguishable by feel.
                                                    com.opencode.android.util.Haptics.release(speechContext)
                                                    stopDictation()
                                                },
                                            )
                                        }.semantics { this.role = Role.Button },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = stringResource(R.string.speech_hold_to_talk),
                                    tint =
                                        if (listening) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                        IconButton(
                            onClick = onAddFiles,
                            // 48 dp = platform minimum touch target; 40 dp missed it on
                            // the two most-tapped controls of the composer.
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_images_and_files),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                                    when {
                                        // Esc stops a running turn (nothing else does
                                        // this from the keyboard). The picker closes
                                        // first because it handles Esc while open.
                                        event.key == Key.Escape && isGenerating -> {
                                            onInterrupt()
                                            true
                                        }

                                        // Enter sends; Shift+Enter inserts a newline.
                                        // Ctrl/Cmd+Enter also sends, the habit from
                                        // desktop chat clients.
                                        event.key == Key.Enter &&
                                            (!event.isShiftPressed) -> {
                                            // While a turn runs the button becomes "Stop", so
                                            // the keyboard must do the same: sending would
                                            // interrupt and throw away the running answer.
                                            if (isGenerating) {
                                                onInterrupt()
                                            } else if (!isUploading &&
                                                (inputText.isNotBlank() || attachments.isNotEmpty())
                                            ) {
                                                onSend()
                                            }
                                            true
                                        }

                                        else -> {
                                            false
                                        }
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
                                    // Same gating as the send button: the IME action used
                                    // to fire during an upload.
                                    if (!isUploading && (inputText.isNotBlank() || attachments.isNotEmpty())) {
                                        onSend()
                                    }
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
                    // Queue: the web's Ctrl+Enter. Sits LEFT of send/stop and only
                    // appears while there is something to queue, so the composer
                    // stays a single calm row when idle.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = composerHasText && !isUploading,
                        enter =
                            androidx.compose.animation.fadeIn(
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                            ) +
                                androidx.compose.animation.scaleIn(
                                    animationSpec =
                                        com.opencode.android.ui.theme.Motion
                                            .expressive(),
                                ),
                        exit =
                            androidx.compose.animation.fadeOut(
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                            ),
                    ) {
                        val steers = followUpBehavior == "steer"
                        androidx.compose.material3.BadgedBox(
                            badge = {
                                if (queuedCount > 0) {
                                    androidx.compose.material3.Badge {
                                        Text("$queuedCount")
                                    }
                                }
                            },
                        ) {
                            IconButton(
                                onClick = {
                                    com.opencode.android.util.Haptics.press(speechContext)
                                    // Follow-up behavior (web: Preferences):
                                    // queue the message or steer the running turn.
                                    // The queued cards' "Steer" stays available as
                                    // the explicit override either way.
                                    if (steers) onSteer() else onQueue()
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    if (steers) {
                                        Icons.Default.PlaylistPlay
                                    } else {
                                        Icons.Default.PlaylistAdd
                                    },
                                    contentDescription =
                                        stringResource(
                                            if (steers) R.string.queue_steer else R.string.queue_send,
                                        ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    // Prompt history: offered while the field is EMPTY, which is when
                    // recalling something makes sense (and it never competes with the
                    // queue button, which appears while typing).
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !composerHasText && !isUploading,
                        enter =
                            androidx.compose.animation.fadeIn(
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                            ),
                        exit =
                            androidx.compose.animation.fadeOut(
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                            ),
                    ) {
                        IconButton(
                            onClick = onHistory,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = stringResource(R.string.history_open),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                            com.opencode.android.util.Haptics.press(speechContext)
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
                                .size(48.dp)
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
                // Offline outbox: prompts that could not be delivered because the
                // server was unreachable. Shown above the queue line, with the two
                // actions that matter (send now / discard) — the alternative was
                // silently losing the text the user typed in a tunnel.
                if (outboxCount > 0) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                                    MaterialTheme.shapes.small,
                                ).padding(horizontal = MaterialTheme.spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.outbox_hint, outboxCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (outboxFlushing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            TextButton(onClick = onFlushOutbox) {
                                Text(
                                    text = stringResource(R.string.outbox_send_now),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        TextButton(onClick = onClearOutbox) {
                            Text(
                                text = stringResource(R.string.outbox_discard),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                // Queue status line: the queued prompts live in the transcript, but
                // the count belongs next to the composer (the web shows
                // "{{count}} queued" the same way).
                if (queuedCount > 0) {
                    Text(
                        text = stringResource(R.string.queue_count, queuedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                    )
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
                        // Attachment summary: count + total size, so a heavy draft is
                        // noticed BEFORE sending (the chips show names only).
                        if (attachments.isNotEmpty()) {
                            val totalBytes = attachments.mapNotNull { it.size }.sum()
                            val summary =
                                if (totalBytes > 0) {
                                    // Locale-independent formatting is fine here: this is
                                    // a size, not user prose.
                                    java.lang.String.format(
                                        java.util.Locale.ENGLISH,
                                        "%d file(s) · %.1f MB",
                                        attachments.size,
                                        totalBytes / (1024.0 * 1024.0),
                                    )
                                } else {
                                    "%d file(s)".format(attachments.size)
                                }
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
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
                            // Copy affordance: a provider error is exactly what ends up
                            // in a bug report, and it was not selectable before.
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    val clipboard =
                                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText("error", statusError ?: ""),
                                    )
                                },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_error),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
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
