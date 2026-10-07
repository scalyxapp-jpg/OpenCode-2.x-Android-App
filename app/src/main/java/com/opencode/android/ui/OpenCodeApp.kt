package com.opencode.android.ui
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opencode.android.R
import com.opencode.android.data.BackendStore
import com.opencode.android.data.HomeCache
import com.opencode.android.data.LastSessionStore
import com.opencode.android.data.MessageCache
import com.opencode.android.ui.settings.SettingsScreen
import com.opencode.android.ui.theme.spacing
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.UserMessages
import kotlinx.coroutines.launch

object Routes {
    const val BACKENDS = "backends"
    const val HOME = "home"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"

    fun chat(sessionId: String) = "chat/$sessionId"
}

@Immutable
data class OpenTab(
    val sessionId: String,
    val title: String,
)

/** Flattens tabs into a saveable primitive list (sessionId, title, …). */
internal fun flattenTabs(tabs: List<OpenTab>): List<String> = tabs.flatMap { listOf(it.sessionId, it.title) }

/** Inverse of [flattenTabs]. */
internal fun unflattenTabs(flat: List<String>): List<OpenTab> =
    flat.chunked(2).mapNotNull { pair ->
        if (pair.size == 2) OpenTab(pair[0], pair[1]) else null
    }

/** Saver so open tabs survive rotation and process death. */
internal val OpenTabListSaver: androidx.compose.runtime.saveable.Saver<List<OpenTab>, Any> =
    listSaver(
        save = { flattenTabs(it) },
        restore = { unflattenTabs(it.filterIsInstance<String>()) },
    )

/**
 * Tabs are a compact switcher, not a second page title: the full title is
 * already shown in the session header, so strip the server-generated
 * "- 2026-09-17T14:13:35.981Z" suffix and cap the length.
 */
private fun compactTabTitle(title: String): String {
    val base =
        com.opencode.android.util
            .sessionDisplayTitle(title)
    return if (base.length > 20) base.take(20).trimEnd() + "…" else base
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun OpenCodeApp(
    deepLinkSessionId: String? = null,
    newSessionSignal: Long = 0L,
    shareSignal: Long = 0L,
) {
    val backendSession = LocalBackendSession.current
    val providerDirectory = LocalProviderDirectory.current
    // The disk-backed stores load on a background thread (see AppStartup).
    // Rendering before they are ready would race: the saved backend/session
    // would read as null and auto-connect would be skipped.
    val storesReady by com.opencode.android.AppStartup.ready
        .collectAsStateWithLifecycle()
    if (!storesReady) {
        // Branded splash instead of a bare spinner: the stores load lazily from
        // disk, and a wordmark reads as "starting" rather than "stuck".
        androidx.compose.foundation.layout.Column(
            modifier =
                androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            androidx.compose.material3.Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier
                    .height(MaterialTheme.spacing.large),
            )
            InlineSpinner()
        }
        return
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Open session tabs. Persisted with rememberSaveable so a configuration
    // change (rotation) or process death does not silently close every tab —
    // plain `remember` reset them on every activity recreation.
    var openTabs by rememberSaveable(
        stateSaver = OpenTabListSaver,
    ) { mutableStateOf<List<OpenTab>>(emptyList()) }
    var activeTabId by rememberSaveable { mutableStateOf<String?>(null) }

    fun openSession(
        sessionId: String,
        title: String,
    ) {
        if (openTabs.none { it.sessionId == sessionId }) {
            openTabs = openTabs + OpenTab(sessionId, title)
        }
        activeTabId = sessionId
        // Persist so a process death does not lose the user's place.
        LastSessionStore.save(
            sessionId = sessionId,
            title = title,
            directory = null,
        )
        navController.navigate(Routes.chat(sessionId)) {
            // Keep at most ONE chat destination on the back stack. Without
            // popUpTo, `launchSingleTop` only dedupes when the target is already
            // on TOP, so opening session after session stacked a NavBackStackEntry
            // (and its ChatViewModel with the full message list + live buffers)
            // per session. Memory grew until an OutOfMemoryError crash during
            // normal use. Switching tabs now recreates the ViewModel (reloaded
            // from the on-disk message cache), which is cheap and bounded.
            popUpTo(Routes.CHAT) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun closeTab(sessionId: String) {
        openTabs = openTabs.filter { it.sessionId != sessionId }
        if (activeTabId == sessionId) {
            activeTabId = openTabs.lastOrNull()?.sessionId
            val nextTabId = activeTabId
            if (nextTabId != null) {
                navController.navigate(Routes.chat(nextTabId)) {
                    popUpTo(Routes.CHAT) { inclusive = true }
                    launchSingleTop = true
                }
            } else {
                navController.navigate(Routes.HOME) {
                    popUpTo(navController.graph.findStartDestination().id)
                }
            }
        }
    }

    // Adaptive layout: navigation rail on wide screens, bottom bar on phones.
    androidx.compose.foundation.layout.BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        // Every user-facing error surfaces here, on top of whatever screen is open
        // (see UserMessages).
        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            UserMessages.events.collect { event ->
                val text =
                    when (event) {
                        is UserMessages.Event.Res -> {
                            context.getString(event.id, *event.args.toTypedArray())
                        }

                        is UserMessages.Event.Raw -> {
                            event.text
                        }
                    }
                snackbarHostState.showSnackbar(
                    message = text,
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                    // Long-lived error toasts need an explicit exit; otherwise
                    // they sit for the full duration with no recourse.
                    withDismissAction = true,
                )
            }
        }
        val showNav =
            currentDestination?.hierarchy?.any {
                it.route == Routes.HOME || it.route == Routes.SETTINGS
            } == true
        Scaffold(
            snackbarHost = {
                // Rounded + inset: matches the app's surfaces instead of the
                // default square M3 snackbar flush to the screen edge.
                // Themed container (not inverseSurface): the default light box
                // with black text punched through every dark theme.
                androidx.compose.material3.SnackbarHost(snackbarHostState) { data ->
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier.padding(
                                horizontal = MaterialTheme.spacing.medium,
                                vertical = MaterialTheme.spacing.small,
                            ),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary,
                        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // Edge-to-edge is enforced from Android 15 (targetSdk 35+), so
            // windowSoftInputMode="adjustResize" no longer shrinks the window. The
            // IME inset has to be consumed here (root of every screen) or the
            // composer ends up hidden behind the keyboard. Applied at the root so
            // the bottom navigation bar lifts with it too; the inner Scaffolds then
            // see the inset as already consumed.
            modifier = Modifier.imePadding(),
            topBar = {
                // This slot ALWAYS occupies at least the status bar. Two reasons:
                //  1. The content below then starts at a stable place whether or not
                //     tabs are open, so the inner screens must not re-apply the
                //     status-bar inset themselves (that double count is what left a
                //     ~150 px empty band above the chat title).
                //  2. The chip row is only worth a full row of height when there is
                //     an actual choice to make — with a single open session it spent
                //     a row on one label and looked like empty space.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                        if (openTabs.size > 1) {
                            val tabListState = rememberLazyListState()
                            // Keep the active tab in view as tabs open, close or switch.
                            LaunchedEffect(activeTabId, openTabs.size) {
                                val index = openTabs.indexOfFirst { it.sessionId == activeTabId }
                                if (index >= 0) tabListState.animateScrollToItem(index)
                            }
                            LazyRow(
                                // Scrolls horizontally when tabs overflow. Compose draws
                                // no scrollbar for lazy lists, so none is visible.
                                state = tabListState,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding =
                                    PaddingValues(
                                        horizontal = MaterialTheme.spacing.small,
                                        vertical = MaterialTheme.spacing.extraSmall,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                items(openTabs, key = { it.sessionId }) { tab ->
                                    Surface(
                                        onClick = { openSession(tab.sessionId, tab.title) },
                                        color =
                                            if (tab.sessionId == activeTabId) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(end = MaterialTheme.spacing.extraSmall),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = MaterialTheme.spacing.small, end = 2.dp),
                                        ) {
                                            Text(
                                                text = compactTabTitle(tab.title),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                // LazyRow items are measured with an unbounded
                                                // max width, where RowScope.weight collapses the
                                                // text to zero. Cap the width explicitly instead.
                                                modifier = Modifier.widthIn(max = 160.dp),
                                            )
                                            IconButton(
                                                onClick = { closeTab(tab.sessionId) },
                                                modifier = Modifier.padding(0.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = stringResource(R.string.close_tab),
                                                    modifier = Modifier.padding(0.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (!wide && showNav) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == Routes.HOME } == true,
                            onClick = {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.home)) },
                            label = { Text(stringResource(R.string.home)) },
                        )
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == Routes.SETTINGS } == true,
                            onClick = {
                                navController.navigate(Routes.SETTINGS) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) },
                            label = { Text(stringResource(R.string.settings)) },
                        )
                    }
                }
            },
        ) { padding ->
            androidx.compose.animation.SharedTransitionLayout {
                // Box (not Row): the splash overlay floats over the content Row.
                androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Wide screens: side navigation rail instead of the bottom bar.
                        if (wide && showNav) {
                            androidx.compose.material3.NavigationRail {
                                androidx.compose.material3.NavigationRailItem(
                                    selected = currentDestination?.hierarchy?.any { it.route == Routes.HOME } == true,
                                    onClick = {
                                        navController.navigate(Routes.HOME) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.home)) },
                                    label = { Text(stringResource(R.string.home)) },
                                )
                                androidx.compose.material3.NavigationRailItem(
                                    selected = currentDestination?.hierarchy?.any { it.route == Routes.SETTINGS } == true,
                                    onClick = {
                                        navController.navigate(Routes.SETTINGS) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) },
                                    label = { Text(stringResource(R.string.settings)) },
                                )
                            }
                        }
                        // The system can kill the process at any time (low memory on a phone),
                        // and users do not expect to re-connect the backend and re-open their
                        // session afterwards. Reconnect to the last-used backend automatically
                        // and jump straight back into the last conversation; the picker stays
                        // reachable via Settings / "Switch backend".
                        val savedBackend = remember { BackendStore.mostRecent() }
                        val savedSessionId = remember { LastSessionStore.sessionId() }
                        val savedSessionTitle = remember { LastSessionStore.title() }

                        // Point the session at the user's saved backend BEFORE the
                        // NavHost composes. Otherwise the first Home load raced
                        // this configuration and dialed the unconfigured
                        // stand-in ("Failed to connect to /127.0.0.1:80"). The
                        // backend address is always the user's saved one; there
                        // is no built-in server.
                        //
                        // Returns a value rather than Unit: `remember` must not be
                        // used purely for side effects (Compose may skip it), so the
                        // applied flag is what is remembered.
                        @Suppress("UNUSED_VARIABLE")
                        val backendConfigured =
                            remember(savedBackend) {
                                savedBackend?.let { backend ->
                                    backendSession.setBaseUrl(backend.url)
                                    if (!backend.password.isNullOrBlank()) {
                                        backendSession.setAuth(
                                            backend.username.ifBlank {
                                                BackendStore.DEFAULT_USERNAME
                                            },
                                            backend.password,
                                        )
                                    }
                                    true
                                } ?: false
                            }
                        LaunchedEffect(deepLinkSessionId) {
                            val backend = savedBackend ?: return@LaunchedEffect
                            try {
                                providerDirectory.invalidate()
                                providerDirectory.prefetch()
                                // A notification tap wins over the last conversation: open the
                                // session the event belongs to.
                                val sessionId = deepLinkSessionId ?: savedSessionId
                                if (sessionId != null) {
                                    // The stored session can be gone (deleted elsewhere, or it
                                    // belongs to a different backend). Opening it showed
                                    // "Could not load sessions" and an empty chat; verify first
                                    // and fall back to Home instead.
                                    val exists =
                                        try {
                                            backendSession.api.getSessionFull(sessionId)
                                            true
                                        } catch (e: retrofit2.HttpException) {
                                            e.code() != 404
                                        } catch (_: Exception) {
                                            // Network error: do not erase a session that may exist.
                                            true
                                        }
                                    if (exists) {
                                        openSession(sessionId, savedSessionTitle ?: "Session")
                                    } else {
                                        AppLog.e(APP_LOG_TAG, "restore: session $sessionId not found, staying on Home")
                                        LastSessionStore.clear()
                                    }
                                }
                            } catch (e: Exception) {
                                AppLog.e(APP_LOG_TAG, "auto-connect failed: ${e.message}")
                                UserMessages.post(R.string.could_not_load_projects, "${e.message}")
                            }
                        }
                        // Quick Settings tile / widget "new session": always land
                        // on Home, where a project/session can be chosen.
                        LaunchedEffect(newSessionSignal) {
                            if (newSessionSignal > 0L) {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(navController.graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            }
                        }
                        // Share sheet: reopen the last conversation so the composer
                        // can take the shared text/image. With no session yet, Home
                        // is the landing spot; the pending share is consumed by the
                        // first chat that opens.
                        LaunchedEffect(shareSignal) {
                            if (shareSignal <= 0L) return@LaunchedEffect
                            val target = savedSessionId
                            if (target != null) {
                                openSession(target, savedSessionTitle ?: "Session")
                            } else {
                                navController.navigate(Routes.HOME) { launchSingleTop = true }
                            }
                        }
                        NavHost(
                            navController = navController,
                            // Startup: skip the picker when a backend has already been saved.
                            startDestination = if (savedBackend != null) Routes.HOME else Routes.BACKENDS,
                            modifier = Modifier.weight(1f),
                            // Fluid screen transitions (slide + fade).
                            enterTransition = {
                                androidx.compose.animation.slideInHorizontally(
                                    initialOffsetX = { it / 6 },
                                    animationSpec =
                                        com.opencode.android.ui.theme.Motion
                                            .spatial(),
                                ) +
                                    androidx.compose.animation.fadeIn(
                                        animationSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                    )
                            },
                            exitTransition = {
                                androidx.compose.animation.fadeOut(
                                    animationSpec =
                                        com.opencode.android.ui.theme.Motion
                                            .effects(),
                                )
                            },
                            popEnterTransition = {
                                androidx.compose.animation.fadeIn(
                                    animationSpec =
                                        com.opencode.android.ui.theme.Motion
                                            .effects(),
                                )
                            },
                            popExitTransition = {
                                androidx.compose.animation.slideOutHorizontally(
                                    targetOffsetX = { it / 6 },
                                    animationSpec =
                                        com.opencode.android.ui.theme.Motion
                                            .spatial(),
                                ) +
                                    androidx.compose.animation.fadeOut(
                                        animationSpec =
                                            com.opencode.android.ui.theme.Motion
                                                .effects(),
                                    )
                            },
                        ) {
                            composable(Routes.BACKENDS) {
                                BackendPickerScreen(
                                    onConnected = {
                                        navController.navigate(Routes.HOME) {
                                            popUpTo(Routes.BACKENDS) { inclusive = true }
                                        }
                                    },
                                )
                            }
                            composable(Routes.HOME) {
                                val switchScope = rememberCoroutineScope()
                                HomeScreen(
                                    sharedScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this,
                                    onSessionClick = { sessionId ->
                                        openSession(sessionId, "Session")
                                    },
                                    onOpenSettings = {
                                        navController.navigate(Routes.SETTINGS) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onSwitchBackend = {
                                        openTabs = emptyList()
                                        activeTabId = null
                                        // The remembered session/project and the cached
                                        // conversations belong to the OLD backend. Keeping them
                                        // would try to reopen a session that does not exist on
                                        // the new server and would leave another server's
                                        // conversation text on disk.
                                        LastSessionStore.clear()
                                        switchScope.launch {
                                            MessageCache.clearAll()
                                            HomeCache.clearAll()
                                        }
                                        navController.navigate(Routes.BACKENDS) {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    },
                                )
                            }
                            composable(Routes.CHAT) { backStackEntry ->
                                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                                ChatScreen(
                                    sessionId = sessionId,
                                    shareSignal = shareSignal,
                                    sharedScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this,
                                    onBack = { navController.popBackStack() },
                                    onTitleChange = { title ->
                                        openTabs =
                                            openTabs.map {
                                                if (it.sessionId == sessionId) it.copy(title = title) else it
                                            }
                                    },
                                    // Clicking a subagent badge opens its own session as a tab
                                    // (web parity: subagent opens in a new session tab).
                                    onOpenSession = { subagentSessionId ->
                                        if (subagentSessionId.isNotBlank() && subagentSessionId != sessionId) {
                                            openSession(subagentSessionId, "Subagent")
                                        }
                                    },
                                )
                            }
                            composable(Routes.SETTINGS) {
                                SettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    onSwitchBackend = { navController.navigate(Routes.BACKENDS) },
                                )
                            }
                        } // NavHost
                    } // Row
                    // Launch brand moment over everything; gone after ~1.6s.
                    // var showSplash by remember { mutableStateOf(true) }
                    // if (showSplash) {
                    //     SplashOverlay { showSplash = false }
                    // }
                } // Box
            } // SharedTransitionLayout
        } // Scaffold
    }
}
