package com.opencode.android.ui
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencode.android.R
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.domain.Agent
import com.opencode.android.domain.Message
import com.opencode.android.domain.Model
import com.opencode.android.domain.Part
import com.opencode.android.domain.TodoItem
import com.opencode.android.ui.settings.SectionHeader
import com.opencode.android.ui.theme.spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Cap for the conversation width on wide (tablet/desktop) windows. */
private val CONTENT_MAX_WIDTH = 840.dp

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun ChatScreen(
    sessionId: String,
    // Bumped by MainActivity when the app is opened from the Android share
    // sheet; the pending text/image is folded into this chat's composer.
    shareSignal: Long = 0L,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit = {},
    // Opens a subagent's session (web: clicking the subagent link in a task row).
    onOpenSession: (String) -> Unit = {},
    // Shared-element scopes (hero transition session card → chat title).
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appSettings by AppSettingsStore.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Android share sheet -> composer: shared text becomes the draft, shared
    // images become attachments. Consumed once per share signal.
    LaunchedEffect(shareSignal) {
        val shared =
            com.opencode.android.data.PendingShare
                .consume() ?: return@LaunchedEffect
        shared.text?.let { viewModel.updateInput(it) }
        if (shared.imageUris.isNotEmpty()) {
            viewModel.addAttachments(shared.imageUris.map { it to it.substringAfterLast('/') })
        }
        com.opencode.android.util.UserMessages
            .post(R.string.shared_content_ready)
    }
    // Keep the home-screen widget in sync with the live conversation.
    LaunchedEffect(uiState.session?.title, uiState.isGenerating, uiState.selectedModel) {
        com.opencode.android.data.WidgetStateStore.save(
            status = if (uiState.isGenerating) "Working…" else "Idle",
            model = uiState.selectedModel,
            generating = uiState.isGenerating,
        )
        com.opencode.android.ui.widget
            .refreshSessionWidget(context)
    }

    // Hoisted: the callbacks below are plain lambdas, not composables, so the
    // toast strings must be resolved here. Keeps every user-facing string in
    // strings.xml and the UI in one language (it used to mix German/English).
    val copiedMsg = stringResource(R.string.copied)
    val revertedMsg = stringResource(R.string.message_reverted)
    val revertFailedMsg = stringResource(R.string.revert_failed)
    val forkedFmt = stringResource(R.string.forked)
    val forkFailedMsg = stringResource(R.string.fork_failed)
    val answerSentMsg = stringResource(R.string.answer_sent)
    val answerFailedMsg = stringResource(R.string.answer_failed)
    val questionRejectedMsg = stringResource(R.string.question_rejected)
    val rejectFailedMsg = stringResource(R.string.reject_failed)

    fun toast(message: String) {
        toast(context, message)
    }

    // Notify parent of title changes for the tab bar
    LaunchedEffect(uiState.session?.title) {
        uiState.session?.title?.let { onTitleChange(it) }
    }

    // File picker for web-equivalent "Add images and files" (multi-select).
    // Picks are shown as removable chips in the composer and travel with
    // the prompt on send (see ChatViewModel.attachments).
    val filePicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .GetMultipleContents(),
        ) { uris ->
            if (!uris.isNullOrEmpty()) {
                val pairs =
                    uris.map { uri ->
                        val name =
                            runCatching {
                                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                                }
                            }.getOrNull() ?: (uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString())
                        uri.toString() to name
                    }
                viewModel.addAttachments(pairs)
            }
        }

    LaunchedEffect(sessionId) {
        viewModel.loadSession(sessionId)
    }

    Scaffold(
        // Edge-to-edge is enforced from Android 15 (targetSdk 35+), so
        // windowSoftInputMode="adjustResize" no longer shrinks the window and
        // the composer ended up hidden behind the keyboard. Consuming the IME
        // inset here lifts the whole content area above the keyboard.
        modifier = Modifier.imePadding(),
        // Zero insets: the root Scaffold already pads its content by the system
        // bars (and its top slot always occupies at least the status bar). This
        // screen re-applying them added a second status bar above the title and
        // a second navigation bar under the composer — a visible empty band at
        // both ends.
        contentWindowInsets =
            androidx.compose.foundation.layout.WindowInsets(
                0,
                0,
                0,
                0,
            ),
        topBar = {
            TopAppBar(
                // Same reason as contentWindowInsets above.
                windowInsets =
                    androidx.compose.foundation.layout.WindowInsets(
                        0,
                        0,
                        0,
                        0,
                    ),
                title = {
                    // Shared element target: matches the Home session card so the
                    // title morphs in as a hero transition.
                    val titleSharedModifier =
                        if (sharedScope != null && animatedVisibilityScope != null) {
                            with(sharedScope) {
                                Modifier.sharedElement(
                                    state = rememberSharedContentState(key = "session-$sessionId"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                            }
                        } else {
                            Modifier
                        }
                    Box(modifier = titleSharedModifier) {
                        val selectedModelName =
                            com.opencode.android.util
                                .friendlyModelName(uiState.models, uiState.selectedModel)
                        val subtitle =
                            "${uiState.selectedAgent} · " +
                                selectedModelName.ifEmpty { "Choose model" }
                        // Crossfade on agent/model switch: no hard text swap.
                        androidx.compose.animation.Crossfade(
                            targetState = subtitle,
                            animationSpec =
                                com.opencode.android.ui.theme.Motion
                                    .effects(),
                            label = "topSubtitle",
                        ) { animatedSubtitle ->
                            TopBarTitle(
                                title =
                                    uiState.session
                                        ?.title
                                        ?.let {
                                            com.opencode.android.util
                                                .sessionDisplayTitle(it)
                                        }
                                        ?: "Session",
                                subtitle = animatedSubtitle,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    var showContext by remember { mutableStateOf(false) }
                    // Hoisted: Modifier.semantics' lambda is not composable.
                    val contextUsageLabel = stringResource(R.string.context_usage)
                    // Web parity: the header button shows the share of the
                    // context window in use ("11%"), not a count of context
                    // events. Derived from the server token aggregates.
                    //
                    // The limit is resolved at RENDER time from the live provider
                    // catalog, so the ring stays correct even if the catalog was
                    // not loaded yet when loadContext last ran (that used to
                    // flash "0%" until the next refresh).
                    val contextPct =
                        uiState.contextInfo?.let { info ->
                            val limit =
                                info.contextLimit?.takeIf { it > 0 }
                                    ?: uiState.providerGroups
                                        .asSequence()
                                        .flatMap { it.models.values.asSequence() }
                                        .firstOrNull { it.id == info.model || it.model == info.model }
                                        ?.limit
                                        ?.context
                                        ?.takeIf { it > 0 }
                            com.opencode.android.util
                                .contextPercent(info.totalTokens, limit)
                        }
                    val contextTooltip =
                        contextPct?.let { pct ->
                            "$pct% · ${uiState.contextInfo?.totalTokens ?: 0} tokens"
                        } ?: contextUsageLabel
                    IconButton(
                        onClick = {
                            viewModel.loadContext()
                            showContext = true
                        },
                        // The visible label is a bare percentage; a screen
                        // reader gets the usage and token count spelled out.
                        modifier =
                            Modifier.semantics {
                                contentDescription = contextTooltip
                            },
                    ) {
                        // Always the same ring; an unknown context shows "0%"
                        // rather than a bare "…", which read as a second
                        // three-dot menu next to "More options". The sweep
                        // glides to its value instead of jumping per refresh.
                        val pct = contextPct ?: 0
                        val animatedSweep by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = pct / 100f,
                            animationSpec =
                                androidx.compose.animation.core
                                    .tween(durationMillis = 500),
                            label = "contextSweep",
                        )
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { animatedSweep },
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (showContext) {
                        ContextPanelDialog(
                            info = uiState.contextInfo,
                            messages = uiState.contextUsage?.data ?: emptyList(),
                            modelName =
                                com.opencode.android.util.friendlyModelName(
                                    uiState.models,
                                    uiState.contextInfo?.model ?: uiState.selectedModel,
                                ),
                            onDismiss = { showContext = false },
                        )
                    }
                    // "/mcp" dialog (web parity: title "MCPs", "N of M enabled",
                    // search, per-server switch, auth for needs-auth servers).
                    if (uiState.mcpDialogVisible) {
                        McpDialog(
                            servers = uiState.mcpServers,
                            onToggle = viewModel::toggleMcp,
                            onAuth = viewModel::authenticateMcp,
                            onDismiss = viewModel::closeMcpDialog,
                        )
                    }
                    // Tool-permission prompt (web: Deny / Allow always / Allow once).
                    // Only one prompt is shown at a time; the list refreshes as
                    // each is answered.
                    uiState.pendingPermissions.firstOrNull()?.let { request ->
                        PermissionDialog(
                            request = request,
                            onReply = { reply ->
                                request.id?.let { viewModel.replyPermission(it, reply) }
                            },
                        )
                    }
                    // More options menu (mirrors web: Rename/Share.../Export.../
                    // Archive/separator/Delete...)
                    var showMore by remember { mutableStateOf(false) }
                    var showRename by remember { mutableStateOf(false) }
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    var showChildren by remember { mutableStateOf(false) }
                    var children by remember { mutableStateOf<List<com.opencode.android.domain.Session>>(emptyList()) }
                    Box {
                        IconButton(onClick = { showMore = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    showMore = false
                                    showRename = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share)) },
                                onClick = {
                                    showMore = false
                                    viewModel.shareSession { slug ->
                                        toast(
                                            context,
                                            slug?.let { context.getString(R.string.shared_fmt, it) }
                                                ?: context.getString(R.string.share_failed),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export)) },
                                onClick = {
                                    showMore = false
                                    viewModel.exportSession { export ->
                                        toast(
                                            context,
                                            export?.let {
                                                context.resources.getQuantityString(
                                                    R.plurals.exported_chars,
                                                    it.length,
                                                    it.length,
                                                )
                                            } ?: context.getString(R.string.export_failed),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive)) },
                                onClick = {
                                    showMore = false
                                    viewModel.archiveSession { onBack() }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.restore_messages)) },
                                onClick = {
                                    showMore = false
                                    viewModel.restoreMessages { ok ->
                                        toast(
                                            context,
                                            context.getString(
                                                if (ok) R.string.messages_restored else R.string.restore_failed,
                                            ),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subagent_sessions)) },
                                onClick = {
                                    showMore = false
                                    viewModel.loadChildSessions { list ->
                                        children = list
                                        showChildren = true
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.initialize_project)) },
                                onClick = {
                                    showMore = false
                                    viewModel.initProject { ok ->
                                        toast(
                                            context,
                                            context.getString(
                                                if (ok) R.string.initialized_ok else R.string.init_failed,
                                            ),
                                        )
                                    }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMore = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
                    }
                    if (showRename) {
                        RenameDialog(
                            title = stringResource(R.string.rename_session),
                            // Prefill the same clean title the header shows.
                            currentTitle =
                                uiState.session
                                    ?.title
                                    ?.let {
                                        com.opencode.android.util
                                            .sessionDisplayTitle(it)
                                    }
                                    ?: "",
                            onConfirm = { newTitle ->
                                viewModel.renameSession(newTitle) { showRename = false }
                            },
                            onDismiss = { showRename = false },
                        )
                    }
                    if (showChildren) {
                        AlertDialog(
                            onDismissRequest = { showChildren = false },
                            title = { Text(stringResource(R.string.subagent_sessions)) },
                            text = {
                                if (children.isEmpty()) {
                                    Text(stringResource(R.string.no_subagents))
                                } else {
                                    Column {
                                        children.forEach { child ->
                                            TextButton(
                                                onClick = {
                                                    showChildren = false
                                                    onOpenSession(child.id)
                                                },
                                            ) {
                                                Text(
                                                    text = child.title ?: child.id,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showChildren = false }) {
                                    Text(stringResource(R.string.close))
                                }
                            },
                        )
                    }
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text(stringResource(R.string.delete_session)) },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.delete_session_confirm,
                                        // Same clean title as the list row (no ISO suffix).
                                        uiState.session
                                            ?.title
                                            ?.let {
                                                com.opencode.android.util
                                                    .sessionDisplayTitle(it)
                                            }
                                            ?: uiState.session?.id
                                            ?: "",
                                    ),
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showDeleteConfirm = false
                                        viewModel.deleteSession { onBack() }
                                    },
                                    // Destructive: unmistakably an error action,
                                    // not the brand accent.
                                    colors =
                                        androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError,
                                        ),
                                ) { Text(stringResource(R.string.delete_2)) }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                // A short centered pill instead of a full-width bar: quieter,
                // and it reads as one crisp accent under the active label.
                indicator = { tabPositions ->
                    val position = tabPositions.getOrNull(selectedTab)
                    if (position != null) {
                        Box(
                            modifier =
                                Modifier
                                    .offset(x = position.left)
                                    .width(position.width),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 4.dp)
                                        .size(width = 32.dp, height = 3.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                },
            ) {
                // Active tab is white + bold; inactive is dimmed grey. Default
                // colours made the active tab hard to tell apart on dark.
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            stringResource(R.string.session),
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.loadContext()
                    },
                    text = {
                        Text(
                            stringResource(R.string.review),
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        // On-demand VCS: first open (or stale tab) triggers
                        // the heavy diff fetch; chat open stays light.
                        uiState.session?.directory?.let { viewModel.loadVcsDiff(it) }
                    },
                    text = {
                        Text(
                            stringResource(R.string.changes),
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }

            // Animated tab switch. The list state is hoisted in ChatScreen, so
            // the conversation keeps its scroll position across the fade.
            androidx.compose.animation.AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState > initialState
                    (
                        androidx.compose.animation.slideInHorizontally(
                            animationSpec =
                                com.opencode.android.ui.theme.Motion
                                    .spatial(),
                        ) { if (forward) it / 10 else -it / 10 } +
                            androidx.compose.animation.fadeIn(
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                            )
                    ) togetherWith (
                        androidx.compose.animation.slideOutHorizontally(
                            animationSpec =
                                com.opencode.android.ui.theme.Motion
                                    .spatial(),
                        ) { if (forward) -it / 10 else it / 10 } +
                            androidx.compose.animation.fadeOut(
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                            )
                    )
                },
                label = "chatTab",
            ) { tab ->
                when (tab) {
                    // Adaptive: on a tablet/desktop window the conversation is
                    // capped and centred instead of stretching to unreadable line
                    // lengths; on a phone the cap is never reached.
                    0 -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            SessionTab(
                                modifier =
                                    Modifier
                                        .fillMaxHeight()
                                        .widthIn(max = CONTENT_MAX_WIDTH),
                                uiState = uiState,
                                liveState = viewModel.liveState,
                                listState = listState,
                                appSettings = appSettings,
                                actions =
                                    // remember: ChatActions has identity equals,
                                    // so a fresh instance every recomposition made
                                    // SessionTab/composer never skippable.
                                    remember(onOpenSession) {
                                        ChatActions(
                                            onOpenSession = onOpenSession,
                                            onLoadOlder = { viewModel.loadOlderMessages() },
                                            onRetry = viewModel::retry,
                                            onInputChange = viewModel::updateInput,
                                            onSend = viewModel::sendMessage,
                                            onInterrupt = viewModel::interrupt,
                                            onAddFiles = {
                                                filePicker.launch("*/*")
                                            },
                                            onRemoveAttachment = viewModel::removeAttachment,
                                            onClearAttachments = viewModel::clearAttachments,
                                            onCopyMessage = { label, text ->
                                                copyToClipboard(context, label, text)
                                                toast(copiedMsg)
                                            },
                                            onRevertMessage = { messageId ->
                                                viewModel.revertToMessage(messageId) { ok ->
                                                    toast(if (ok) revertedMsg else revertFailedMsg)
                                                }
                                            },
                                            onForkMessage = { messageId ->
                                                viewModel.forkFromMessage(messageId) { newId ->
                                                    toast(newId?.let { forkedFmt.format(it) } ?: forkFailedMsg)
                                                }
                                            },
                                            onAnswerQuestion = { requestId, answers ->
                                                viewModel.answerQuestion(requestId, answers) { ok ->
                                                    toast(if (ok) answerSentMsg else answerFailedMsg)
                                                }
                                            },
                                            onRejectQuestion = { requestId ->
                                                viewModel.rejectQuestion(requestId) { ok ->
                                                    toast(if (ok) questionRejectedMsg else rejectFailedMsg)
                                                }
                                            },
                                            onRegenerate = viewModel::regenerate,
                                            onPickCommand = { template ->
                                                // Insert the command template; $ARGUMENTS becomes the cursor area.
                                                val clean = template.replace("\$ARGUMENTS", "").trimEnd()
                                                viewModel.updateInput(if (clean.isBlank()) "/" else "$clean ")
                                            },
                                            onBuiltinCommand = { name ->
                                                // Built-ins execute immediately; drop the "/name" text
                                                // so the picker closes and the field is ready again.
                                                viewModel.updateInput("")
                                                when (name) {
                                                    "compact" -> viewModel.compactSession()
                                                    "mcp" -> viewModel.openMcpDialog()
                                                }
                                            },
                                            onAgentSelect = viewModel::selectAgent,
                                            onModelSelect = viewModel::selectModel,
                                            onVariantSelect = viewModel::selectVariant,
                                            onToggleModel = viewModel::setModelVisibility,
                                            onToggleProvider = viewModel::setProviderVisibility,
                                            onRefreshSelection = { viewModel.refreshSelectionFromServer() },
                                            onAdoptServerSelection = { viewModel.adoptServerSelection() },
                                            onPushSelection = { viewModel.pushSelectionToServer() },
                                            onRemoveGuard = { viewModel.removeGuardSelection() },
                                        )
                                    },
                            )
                        }
                    }

                    1 -> {
                        ReviewTab(
                            uiState = uiState,
                            onLoad = viewModel::loadContext,
                        )
                    }

                    2 -> {
                        ChangesTab(
                            files = uiState.files,
                            fileViewer = uiState.fileViewer,
                            vcsBranch = uiState.vcsBranch,
                            vcsDiff = uiState.vcsDiff,
                            vcsDiffLoading = uiState.vcsDiffLoading,
                            vcsDiffError = uiState.vcsDiffError,
                            initialPath = uiState.session?.directory,
                            showFileTree = appSettings.showFileTree,
                            onLoadFiles = viewModel::loadFiles,
                            onOpenFile = viewModel::openFileViewer,
                            onDismissViewer = viewModel::dismissFileViewer,
                            onRetryDiff = {
                                uiState.session?.directory?.let { viewModel.loadVcsDiff(it) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Placeholder rows shown while the first message page loads. A gently pulsing
 * surfaceVariant block reads as "content is coming" rather than "the app hung".
 */
@Composable
internal fun MessageSkeleton() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardPadding),
    ) {
        listOf(0.85f to 72.dp, 0.6f to 44.dp, 0.9f to 96.dp, 0.55f to 44.dp).forEach { (w, h) ->
            SkeletonBox(
                modifier =
                    Modifier
                        .fillMaxWidth(w)
                        .height(h),
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}

/**
 * Stable bundle of session-level UI callbacks for [SessionTab]. Hoisting these
 * into one value keeps the composable's parameter list small and lets the
 * caller move a whole group of callbacks without touching the signature.
 */
@Immutable
internal class ChatActions(
    val onOpenSession: (String) -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onInterrupt: () -> Unit,
    val onAddFiles: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onClearAttachments: () -> Unit = {},
    val onCopyMessage: (label: String, text: String) -> Unit,
    val onRevertMessage: (messageId: String) -> Unit,
    val onForkMessage: (messageId: String) -> Unit,
    val onAnswerQuestion: (requestId: String, answers: List<List<String>>) -> Unit,
    val onRejectQuestion: (requestId: String) -> Unit,
    val onRegenerate: () -> Unit = {},
    val onPickCommand: (template: String) -> Unit,
    val onBuiltinCommand: (name: String) -> Unit,
    val onAgentSelect: (String) -> Unit,
    val onModelSelect: (String) -> Unit,
    val onVariantSelect: (String) -> Unit,
    val onToggleModel: (String, String, Boolean) -> Unit,
    val onToggleProvider: (String, List<String>, Boolean) -> Unit,
    val onRefreshSelection: () -> Unit = {},
    val onAdoptServerSelection: () -> Unit = {},
    val onPushSelection: () -> Unit = {},
    val onRemoveGuard: () -> Unit = {},
)

@Composable
internal fun SessionTab(
    modifier: Modifier = Modifier,
    uiState: ChatUiState,
    liveState: kotlinx.coroutines.flow.StateFlow<LiveStreamState>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    actions: ChatActions,
    appSettings: AppSettingsStore.AppSettings,
) {
    val onOpenSession = actions.onOpenSession
    val onLoadOlder = actions.onLoadOlder
    val onRetry = actions.onRetry
    val onInputChange = actions.onInputChange
    val onSend = actions.onSend
    val onInterrupt = actions.onInterrupt
    val onAddFiles = actions.onAddFiles
    val onRemoveAttachment = actions.onRemoveAttachment
    val onClearAttachments = actions.onClearAttachments
    val onCopyMessage = actions.onCopyMessage
    val onRevertMessage = actions.onRevertMessage
    val onForkMessage = actions.onForkMessage
    val onAnswerQuestion = actions.onAnswerQuestion
    val onRejectQuestion = actions.onRejectQuestion
    val onRegenerate = actions.onRegenerate
    val onPickCommand = actions.onPickCommand
    val onBuiltinCommand = actions.onBuiltinCommand
    val onAgentSelect = actions.onAgentSelect
    val onModelSelect = actions.onModelSelect
    val onVariantSelect = actions.onVariantSelect
    val onToggleModel = actions.onToggleModel
    val onToggleProvider = actions.onToggleProvider
    val onRefreshSelection = actions.onRefreshSelection
    val onAdoptServerSelection = actions.onAdoptServerSelection
    val onPushSelection = actions.onPushSelection
    val onRemoveGuard = actions.onRemoveGuard
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.copied)
    var showGuardDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Connection banner. The SSE stream is the only channel for live
        // updates, so a dropped stream previously froze the screen silently —
        // the user could not tell a stalled turn from a dead connection.
        // Visible also mid-generation: a drop exactly then looked like the
        // model thinking forever.
        androidx.compose.animation.AnimatedVisibility(
            visible = !uiState.sseConnected && !uiState.isLoading,
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(
                            horizontal = MaterialTheme.spacing.medium,
                            vertical = MaterialTheme.spacing.extraSmall,
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                InlineSpinner()
                Text(
                    text = stringResource(R.string.reconnecting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (uiState.guardEnabled) {
            val guard = uiState.session?.sessionGuard
            // Remembered: the screen recomposes on every message/stream update;
            // this label only changes when the guard selection does.
            val guardDetails =
                remember(guard, uiState.guardAvgMs) {
                    listOfNotNull(
                        guard?.revision?.let { "rev $it" },
                        guard?.providerID,
                        guard?.modelID,
                        guard?.agent,
                        guard?.variant,
                        uiState.guardAvgMs?.let { "avg %.0fms".format(it) },
                    ).filter { it.isNotBlank() }.joinToString(" • ").ifEmpty { "keine Session-Auswahl" }
                }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border =
                    BorderStroke(
                        1.dp,
                        if (uiState.guardHealthy && guard?.mismatch != true) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        },
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text =
                            when {
                                uiState.guardConflict -> "Guard-Konflikt · $guardDetails"
                                guard?.mismatch == true -> "Guard-Drift · $guardDetails"
                                !uiState.guardHealthy -> "Guard offline · $guardDetails"
                                else -> "Guard aktiv · $guardDetails"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clickable { showGuardDialog = true },
                    )
                    TextButton(
                        onClick = {
                            if (guard?.mismatch == true) {
                                onAdoptServerSelection()
                            } else {
                                onRefreshSelection()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            if (guard?.mismatch == true || uiState.guardConflict) "Fix" else "Sync",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        if (showGuardDialog) {
            val guard = uiState.session?.sessionGuard
            val detailLines =
                listOfNotNull(
                    "Status: " + if (uiState.guardHealthy) "Active" else "Offline",
                    guard?.revision?.let { "Revision: $it" },
                    guard?.providerID?.let { "Provider: $it" },
                    guard?.modelID?.let { "Model: $it" },
                    guard?.agent?.let { "Agent: $it" },
                    guard?.variant?.let { "Variant: $it" },
                    if (guard?.mismatch == true) {
                        "Server: ${guard.serverProviderID}/${guard.serverModelID}"
                    } else {
                        null
                    },
                    if (guard?.mismatch == true && guard.serverAgent.isNotBlank()) {
                        "Server-Agent: ${guard.serverAgent}"
                    } else {
                        null
                    },
                    uiState.guardAvgMs?.let { "Avg latency: %.0f ms".format(it) },
                )
            AlertDialog(
                onDismissRequest = { showGuardDialog = false },
                title = { Text("Session Guard") },
                text = {
                    Column {
                        Text(detailLines.joinToString("\n"))
                        TextButton(
                            onClick = {
                                onRemoveGuard()
                                showGuardDialog = false
                            },
                        ) { Text("Guard entfernen") }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAdoptServerSelection()
                            showGuardDialog = false
                        },
                    ) { Text("Server → Guard") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onPushSelection()
                            showGuardDialog = false
                        },
                    ) { Text("Guard → Server") }
                },
            )
        }
        // Pending agent questions (mirrors web question cards / dock).
        if (uiState.pendingQuestions.isNotEmpty()) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                items(
                    // Duplicate ids would be a duplicate LazyColumn key crash.
                    uiState.pendingQuestions.distinctBy { it.id },
                    key = { q -> q.id },
                ) { question ->
                    // Cards pop in/out (answer/reject drops them); animate
                    // so the dock breathes instead of snapping.
                    androidx.compose.foundation.layout.Box(
                        modifier =
                            if (uiState.isGenerating) {
                                Modifier
                            } else {
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
                                )
                            },
                    ) {
                        QuestionRequestCard(
                            question = question,
                            onAnswer = onAnswerQuestion,
                            onReject = onRejectQuestion,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        // Messages
        Box(modifier = Modifier.weight(1f)) {
            // Theme backdrops: Matrix rain / Aqua fish. Rendered behind the
            // list so messages stay fully readable on top.
            val themeDark =
                when (appSettings.colorScheme) {
                    "light" -> false
                    "dark" -> true
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                }
            when (
                com.opencode.android.ui.theme
                    .normalizeThemeId(appSettings.theme)
            ) {
                "matrix" -> {
                    MatrixRainBackground(
                        modifier = Modifier.matchParentSize(),
                        dark = themeDark,
                    )
                }

                "aqua" -> {
                    AquaFishBackground(
                        modifier = Modifier.matchParentSize(),
                        dark = themeDark,
                    )
                }

                "metal" -> {
                    MetalSheenBackground(
                        modifier = Modifier.matchParentSize(),
                        dark = themeDark,
                    )
                }

                "hello-kitty" -> {
                    HelloKittyBackground(
                        modifier = Modifier.matchParentSize(),
                        dark = themeDark,
                    )
                }
            }
            // Crossfade between the three area states (skeleton / empty /
            // conversation). Opening a session used to hard-cut from the
            // skeleton to the message list.
            val areaState =
                when {
                    // Cached messages win over the skeleton: the offline cache
                    // exists precisely so a reopen paints instantly, and the
                    // old order (isLoading first) hid it behind the skeleton
                    // until the network answered.
                    uiState.messages.isNotEmpty() -> 2

                    uiState.isLoading -> 0

                    else -> 1
                }
            androidx.compose.animation.Crossfade(
                targetState = areaState,
                animationSpec =
                    com.opencode.android.ui.theme.Motion
                        .effects(),
                label = "chatArea",
            ) { area ->
                when {
                    area == 0 -> {
                        // Skeleton instead of a lone spinner: it shows the shape of
                        // what is arriving and removes the "is it stuck?" pause on a
                        // cold start (the cached tail paints almost instantly).
                        MessageSkeleton()
                    }

                    area == 1 -> {
                        // A bare "Start a conversation" line told a new user nothing
                        // about what the composer accepts (/ commands, @ context).
                        Column(
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(MaterialTheme.spacing.extraLarge),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        ) {
                            EmptyState(
                                title = stringResource(R.string.empty_chat_title),
                                subtitle = stringResource(R.string.empty_chat_hint),
                                icon = Icons.Outlined.ChatBubbleOutline,
                            )
                            // Starter suggestions. An empty chat is intimidating —
                            // the guidance from AI-chat UX work is to offer a few
                            // concrete prompts instead of a blank box.
                            androidx.compose.foundation.layout.Spacer(
                                Modifier.height(MaterialTheme.spacing.small),
                            )
                            // Resolved up front: the onClick lambda is not a
                            // composable scope, so stringResource cannot be called
                            // from inside it.
                            val suggestions =
                                listOf(
                                    R.string.suggestion_explain,
                                    R.string.suggestion_bug,
                                    R.string.suggestion_tests,
                                    R.string.suggestion_review,
                                ).map { stringResource(it) }
                            // Staggered entrance: chips fade/slide in one by one
                            // instead of flashing as a block.
                            suggestions.forEachIndexed { index, suggestion ->
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = true,
                                    enter =
                                        androidx.compose.animation.fadeIn(
                                            animationSpec =
                                                androidx.compose.animation.core.tween(
                                                    durationMillis = 220,
                                                    delayMillis = 60 * index,
                                                ),
                                        ) +
                                            androidx.compose.animation.slideInVertically(
                                                animationSpec =
                                                    androidx.compose.animation.core.tween(
                                                        durationMillis = 220,
                                                        delayMillis = 60 * index,
                                                    ),
                                            ) { it / 2 },
                                ) {
                                    AssistChip(
                                        onClick = { onInputChange(suggestion) },
                                        label = { Text(suggestion) },
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Hoisted: asReversed() would allocate a new list on every
                        // recomposition otherwise.
                        val reversedMessages =
                            remember(uiState.messages) {
                                uiState.messages.asReversed()
                            }
                        // Unique, stable LazyColumn keys: a duplicate key is a
                        // hard crash, and two messages can share a (non-null)
                        // server id after an optimistic-echo merge. Suffix only
                        // the duplicates so normal ids keep their identity.
                        val messageKeys =
                            remember(reversedMessages) {
                                val seen = HashMap<String, Int>()
                                reversedMessages.map { m ->
                                    val base = m.id ?: m.info?.id ?: "anon"
                                    val n = (seen[base] ?: 0) + 1
                                    seen[base] = n
                                    if (n == 1) base else "$base#$n"
                                }
                            }
                        Box(modifier = Modifier.fillMaxSize()) {
                            // reverseLayout=true: newest messages at the bottom (index 0)
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(MaterialTheme.spacing.medium),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardPadding),
                                reverseLayout = true,
                            ) {
                                // Live streaming response (shown at the bottom).
                                // Each item collects liveState itself, so a token
                                // flush recomposes only the small live subtree —
                                // not this screen and its markdown message rows.
                                // pendingPersist: generation ended but the persisted
                                // assistant message has not landed yet — keep the
                                // live content on screen so it does not flash away.
                                if (uiState.isGenerating || uiState.pendingPersist || uiState.isCompacting) {
                                    if (uiState.isGenerating || uiState.isCompacting) {
                                        item(key = "live-status") {
                                            LiveStatusItem(
                                                liveState = liveState,
                                                connected = uiState.sseConnected,
                                                compacting = uiState.isCompacting,
                                            )
                                        }
                                    }
                                    item(key = "live-reasoning") {
                                        LiveReasoningItem(liveState = liveState)
                                    }
                                    item(key = "live-response") {
                                        LiveResponseItem(liveState = liveState)
                                    }
                                    // Live tool rows: rendered directly from SSE part
                                    // snapshots so tools appear without a full message
                                    // reload (the web does the same).
                                    item(key = "live-tools") {
                                        LiveToolsItem(
                                            liveState = liveState,
                                            onOpenSession = onOpenSession,
                                            shellToolPartsExpanded = appSettings.shellToolPartsExpanded,
                                            editToolPartsExpanded = appSettings.editToolPartsExpanded,
                                        )
                                    }
                                }
                                // Messages in reverse order (newest at the bottom)
                                // API returns messages chronologically (oldest first),
                                // so asReversed() puts the newest last = at the bottom
                                // with reverseLayout=true.
                                itemsIndexed(
                                    items = reversedMessages,
                                    // Unique keys computed above (duplicate ids
                                    // are suffixed), so a duplicate key can never
                                    // crash the list.
                                    key = { index, _ -> messageKeys[index] },
                                    contentType = { _, _ -> "message" },
                                ) { _, message ->
                                    // Smooth insert/remove/placement via M3 motion
                                    // springs — but NOT while streaming: the live
                                    // section's height changes on every token flush,
                                    // so the placement spec kept re-animating every
                                    // visible row for the whole generation. Keep the
                                    // animation for discrete inserts only.
                                    val itemModifier =
                                        if (uiState.isGenerating) {
                                            Modifier
                                        } else {
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
                                            )
                                        }
                                    Box(modifier = itemModifier) {
                                        MessageBubble(
                                            message = message,
                                            pendingQuestions = uiState.pendingQuestions,
                                            onCopyMessage = onCopyMessage,
                                            onRevertMessage = onRevertMessage,
                                            onForkMessage = onForkMessage,
                                            onAnswerQuestion = onAnswerQuestion,
                                            onRejectQuestion = onRejectQuestion,
                                            onRegenerate = onRegenerate,
                                            showReasoning = appSettings.showReasoningSummaries,
                                            shellToolPartsExpanded = appSettings.shellToolPartsExpanded,
                                            editToolPartsExpanded = appSettings.editToolPartsExpanded,
                                            onOpenSession = onOpenSession,
                                        )
                                    }
                                }
                                // reverseLayout puts the LAST declared item at the
                                // TOP, which is exactly where "older" belongs. The
                                // server has no pagination, so this asks for a larger
                                // tail (streamed + OOM-guarded).
                                if (uiState.canLoadOlder) {
                                    item(key = "load-older") {
                                        TextButton(
                                            onClick = onLoadOlder,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(stringResource(R.string.load_older_messages))
                                        }
                                    }
                                }
                            }
                            // Auto-scroll to bottom (index 0 in reversed layout).
                            // Keyed on discrete events only — a new message or a new
                            // generation — NOT on the growing stream text. Keying on
                            // the text restarted this effect on every token flush
                            // (~6x/s), relaunching a coroutine and fighting the user
                            // while they were reading. New messages and new turns
                            // always snap to the newest entry; while text merely
                            // streams into an existing item the list is left alone
                            // (offset 0 in the reversed layout already keeps the
                            // newest text pinned to the bottom).
                            // Whether the user is parked at the newest message,
                            // tracked continuously so the follow below can consult
                            // the value as it was BEFORE the list changed.
                            var atBottom by remember { mutableStateOf(true) }
                            var unreadBaseline by remember { mutableIntStateOf(0) }
                            // Key on size too: otherwise messages that arrive
                            // while parked at the bottom were still counted as
                            // "unread" once the user later scrolled up.
                            LaunchedEffect(atBottom, uiState.messages.size) {
                                if (atBottom) unreadBaseline = uiState.messages.size
                            }
                            LaunchedEffect(listState) {
                                androidx.compose.runtime
                                    .snapshotFlow {
                                        listState.firstVisibleItemIndex to
                                            listState.firstVisibleItemScrollOffset
                                    }.collect { (index, offset) ->
                                        atBottom = index == 0 && offset < 8
                                    }
                            }
                            // Identity of the NEWEST message. Keying the
                            // auto-scroll on `messages.size` made prepending
                            // older messages (Load older) count as a change and
                            // yank the list back to the bottom — the user never
                            // saw the history they just loaded. The newest
                            // message's id only changes when a genuinely new
                            // message arrives.
                            val newestMessageKey =
                                uiState.messages
                                    .lastOrNull()
                                    ?.let { it.id ?: it.info?.id ?: it.time?.created }
                            LaunchedEffect(
                                newestMessageKey,
                                uiState.isGenerating,
                                uiState.isCompacting,
                            ) {
                                // Wait until the list actually has items before
                                // scrolling (the effect can run before first layout).
                                androidx.compose.runtime
                                    .snapshotFlow {
                                        listState.layoutInfo.totalItemsCount
                                    }.filter { it > 0 }
                                    .first()
                                // Only follow the newest message when the user was
                                // already at the bottom. The old code scrolled on
                                // every message/turn change, yanking a user who was
                                // reading history. `atBottom` is updated by the
                                // listState effect above, so at effect start it
                                // still reflects the position before this change.
                                if (atBottom) {
                                    listState.scrollToItem(0)
                                    atBottom = true
                                }
                            }
                            // Auto-load older history when the user scrolls near
                            // the top, so the feature works without hunting for
                            // the button. In the reversed layout the top is the
                            // HIGHEST index. Bounded by the canLoadOlder guard
                            // and loadOlderMessages' own single-flight guard.
                            val canLoadOlderNow by
                                androidx.compose.runtime.rememberUpdatedState(uiState.canLoadOlder)
                            LaunchedEffect(listState) {
                                androidx.compose.runtime
                                    .snapshotFlow {
                                        val total = listState.layoutInfo.totalItemsCount
                                        val first = listState.firstVisibleItemIndex
                                        total > 0 && first >= total - 4
                                    }.distinctUntilChanged()
                                    .collect { nearTop ->
                                        if (nearTop && canLoadOlderNow) onLoadOlder()
                                    }
                            }
                            // Jump to latest — only while the user has actually
                            // scrolled away from the newest message. It used to be
                            // rendered permanently, so it hovered over the list even
                            // when already at the bottom. In the reversed layout the
                            // newest entry is index 0, so index > 0 means "scrolled".
                            val showJumpToLatest by remember {
                                androidx.compose.runtime.derivedStateOf {
                                    listState.firstVisibleItemIndex > 0 ||
                                        listState.firstVisibleItemScrollOffset > 0
                                }
                            }
                            val scope = rememberCoroutineScope()
                            // Scale+fade in/out instead of popping: the chip
                            // appears exactly when the user scrolls up mid-stream.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showJumpToLatest,
                                enter =
                                    androidx.compose.animation.scaleIn() +
                                        androidx.compose.animation.fadeIn(),
                                exit =
                                    androidx.compose.animation.scaleOut() +
                                        androidx.compose.animation.fadeOut(),
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(MaterialTheme.spacing.medium),
                            ) {
                                val unread = (uiState.messages.size - unreadBaseline).coerceAtLeast(0)
                                BadgedBox(
                                    badge = {
                                        if (unread > 0) {
                                            Badge { Text("$unread") }
                                        }
                                    },
                                ) {
                                    SmallFloatingActionButton(
                                        // Animated, not a jump: an instant snap is the
                                        // single most jarring transition in a long thread.
                                        onClick = {
                                            scope.launch { listState.animateScrollToItem(0) }
                                        },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = stringResource(R.string.jump_to_latest),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Error, with a Retry that re-runs the session load. Without it the
        // only way out of a failed load was to leave and re-enter the screen.
        androidx.compose.animation.AnimatedVisibility(
            visible = uiState.error != null,
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            uiState.error?.let { error ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.medium,
                                vertical = MaterialTheme.spacing.extraSmall,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Text(
                        text = error,
                        modifier =
                            Modifier
                                .weight(1f)
                                .pointerInput(error) {
                                    detectTapGestures(
                                        onLongPress = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("error_text", error))
                                            toast(context, copiedMsg)
                                        },
                                    )
                                },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }

        // Provider/usage error banner (web shows "Free usage exceeded…" in the
        // stream). Stays visible while the provider keeps retrying.
        androidx.compose.animation.AnimatedVisibility(
            visible = !uiState.statusError.isNullOrBlank(),
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.cardPadding, vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.cardPadding, vertical = MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    // Long provider errors collapse to 2 lines; tap reveals
                    // the full text (same pattern as the composer status).
                    var bannerExpanded by remember(uiState.statusError) { mutableStateOf(false) }
                    Text(
                        text = uiState.statusError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (bannerExpanded) Int.MAX_VALUE else 2,
                        overflow = if (bannerExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .weight(1f)
                                .pointerInput(uiState.statusError) {
                                    detectTapGestures(
                                        onTap = { bannerExpanded = !bannerExpanded },
                                        onLongPress = {
                                            val textToCopy = uiState.statusError.orEmpty()
                                            if (textToCopy.isNotBlank()) {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("error_text", textToCopy))
                                                toast(context, copiedMsg)
                                            }
                                        },
                                    )
                                },
                    )
                }
            }
        }

        // "/compact" progress (the server summarizes the session).
        androidx.compose.animation.AnimatedVisibility(
            visible = uiState.isCompacting,
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            Row(
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.cardPadding,
                        vertical = 2.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                InlineSpinner()
                Text(
                    text = stringResource(R.string.compacting_session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Todo panel above the composer (web: "N of M todos completed").
        androidx.compose.animation.AnimatedVisibility(
            visible = uiState.todos.isNotEmpty(),
            enter =
                androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
            exit =
                androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
        ) {
            TodoPanel(todos = uiState.todos)
        }

        // Composer (mirrors web prompt bar)
        Composer(
            actions =
                // remember: identity equals meant the composer subtree (text
                // field, pickers) recomposed on every uiState emission.
                remember {
                    ComposerActions(
                        onInputChange = onInputChange,
                        onSend = onSend,
                        onInterrupt = onInterrupt,
                        onAddFiles = onAddFiles,
                        onRemoveAttachment = onRemoveAttachment,
                        onClearAttachments = onClearAttachments,
                        onPickCommand = onPickCommand,
                        onBuiltinCommand = onBuiltinCommand,
                        onAgentSelect = onAgentSelect,
                        onModelSelect = onModelSelect,
                        onVariantSelect = onVariantSelect,
                        onToggleModel = onToggleModel,
                        onToggleProvider = onToggleProvider,
                    )
                },
            inputText = uiState.inputText,
            attachments = uiState.attachments,
            commands = uiState.commands,
            files = uiState.files,
            isGenerating = uiState.isGenerating,
            isUploading = uiState.isUploading,
            uploadDone = uiState.uploadDone,
            uploadTotal = uiState.uploadTotal,
            uploadingUris = uiState.uploadingUris,
            statusError = uiState.statusError,
            showAgent = appSettings.showCustomAgents,
            agents = uiState.agents,
            models = uiState.models,
            variants = uiState.variants,
            selectedAgent = uiState.selectedAgent,
            selectedModel = uiState.selectedModel,
            selectedVariant = uiState.selectedVariant,
            providerGroups = uiState.providerGroups,
        )
    }
}

@Composable
internal fun ReviewTab(
    uiState: ChatUiState,
    onLoad: () -> Unit,
) {
    // The context figures (tokens, cache, cost) are server-computed. Loading
    // only once left every number frozen while the session kept running — the
    // Review tab has to re-read whenever the conversation grows or a turn ends.
    // Only when the turn has ENDED: while generating, messages.size changes on
    // every refresh and the load would be re-triggered constantly.
    LaunchedEffect(uiState.messages.size, uiState.isGenerating) {
        if (!uiState.isGenerating) onLoad()
    }
    val info = uiState.contextInfo
    // O(messages x parts) work: remember it instead of rebuilding + rescanning
    // the whole conversation on every recomposition (every Crossfade tick).
    val toolParts =
        remember(uiState.messages) {
            uiState.messages.flatMap { m ->
                m.parts.filter { it.type.trim() == "tool" } +
                    m.content.filter { it.type?.trim() == "tool" }.map {
                        Part(id = it.id ?: "", type = "tool", tool = it.name, name = it.name, state = it.state)
                    }
            }
        }
    val reads = remember(toolParts) { toolParts.count { it.toolName()?.lowercase() == "read" } }
    val roleOf = { m: Message -> m.role ?: m.info?.role ?: m.type }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        item { SectionHeader(stringResource(R.string.session)) }
        item { ContextRow("Title", uiState.session?.title ?: "—") }
        item { ContextRow("Agent", uiState.selectedAgent) }
        item {
            ContextRow(
                "Model",
                com.opencode.android.util
                    .friendlyModelName(uiState.models, info?.model ?: uiState.selectedModel)
                    .ifBlank { "—" },
            )
        }
        item {
            // Counts tick on every turn; crossfade the digits.
            androidx.compose.animation.Crossfade(
                targetState = "${uiState.messages.size}",
                animationSpec =
                    com.opencode.android.ui.theme.Motion
                        .effects(),
                label = "reviewMsgCount",
            ) { n ->
                ContextRow("Messages", n)
            }
        }
        item {
            ContextRow(
                "User / Assistant",
                "${info?.userMessages ?: uiState.messages.count {
                    roleOf(
                        it,
                    ) == "user"
                }} / ${info?.assistantMessages ?: uiState.messages.count { roleOf(it) == "assistant" }}",
            )
        }
        item { ContextRow("Tool-Calls", "${toolParts.size} ($reads read)") }
        info?.let {
            item { SectionHeader(stringResource(R.string.usage)) }
            item { ContextRow("Total tokens", "${it.totalTokens}") }
            item { ContextRow("Input / Output", "${it.inputTokens} / ${it.outputTokens}") }
            item { ContextRow("Reasoning", "${it.reasoningTokens}") }
            it.contextLimit?.let { limit ->
                val pct = if (limit > 0) (it.totalTokens * 100 / limit).coerceAtMost(100) else 0
                item { ContextRow(stringResource(R.string.context_limit_usage), "$limit / $pct%") }
            }
            item { SectionHeader(stringResource(R.string.cache)) }
            item { ContextRow("Cache (read/write)", "${it.cacheRead} / ${it.cacheWrite}") }
            item { SectionHeader(stringResource(R.string.cost)) }
            // Server sends full double precision (1.8125874359999998); four
            // decimals is what a cost figure needs.
            item {
                ContextRow(
                    "Cost",
                    com.opencode.android.util
                        .formatCost(it.totalCost),
                )
            }
            it.lastActivity?.let { last ->
                item { ContextRow("Last activity", formatDateTime(last)) }
            }
        }
    }
}

internal fun formatDuration(millis: Long?): String? {
    if (millis == null || millis < 0) return null
    val seconds = millis / 1000
    return if (seconds < 60) {
        "${seconds}s"
    } else {
        "${seconds / 60}m ${seconds % 60}s"
    }
}

internal fun formatTimestamp(epochMillis: Long): String =
    try {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ENGLISH)
        sdf.format(java.util.Date(epochMillis))
    } catch (e: Exception) {
        ""
    }

// Web todo panel above the composer: "N of M todos completed" + Collapse/Expand.
@Composable
internal fun TodoPanel(todos: List<TodoItem>) {
    var expanded by remember { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.cardPadding, vertical = 2.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = MaterialTheme.spacing.cardPadding, vertical = MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Count glides on progress instead of hard-swapping digits.
                androidx.compose.animation.Crossfade(
                    targetState = pluralStringResource(R.plurals.todos_completed, done, done, todos.size),
                    animationSpec =
                        com.opencode.android.ui.theme.Motion
                            .effects(),
                    label = "todoCount",
                    modifier = Modifier.weight(1f),
                ) { countText ->
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = stringResource(if (expanded) R.string.collapse else R.string.expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
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
                Column {
                    todos.forEach { todo ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MaterialTheme.spacing.cardPadding, vertical = 3.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        ) {
                            // Glyph morphs as the todo moves (○ → ◐ → ✓).
                            androidx.compose.animation.Crossfade(
                                targetState = todo.status,
                                animationSpec =
                                    com.opencode.android.ui.theme.Motion
                                        .effects(),
                                label = "todoGlyph",
                            ) { status ->
                                Text(
                                    text =
                                        when (status) {
                                            "completed" -> "✓"
                                            "in_progress" -> "◐"
                                            else -> "○"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (status == "completed") {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                            Text(
                                text = todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (todo.status == "completed") {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.small))
            }
        }
    }
}
