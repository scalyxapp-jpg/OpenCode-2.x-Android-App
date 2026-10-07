package com.opencode.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.data.BackendSession
import com.opencode.android.data.Notifier
import com.opencode.android.data.PendingShare
import com.opencode.android.data.ProviderDirectory
import com.opencode.android.ui.LocalBackendSession
import com.opencode.android.ui.LocalProviderDirectory
import com.opencode.android.ui.OpenCodeApp
import com.opencode.android.ui.theme.OpenCodeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject
    lateinit var backendSession: BackendSession

    @javax.inject.Inject
    lateinit var providerDirectory: ProviderDirectory

    // A notification tap carries the session to open. Kept as Compose state so
    // onNewIntent (app already running) updates the UI without a recreate.
    private val deepLinkSessionId = mutableStateOf<String?>(null)

    // Monotonic signals for the other intent-driven entry points. The widget/tile
    // "new session" action and the Android share sheet bump these; Compose reacts
    // to the value change (a plain boolean would be missed on repeat taps).
    private val newSessionSignal = mutableStateOf(0L)
    private val shareSignal = mutableStateOf(0L)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // Route client errors to the server log (POST /log). Best-effort.
        com.opencode.android.data.ClientLog
            .install { backendSession.api }
        // Keep the process (and the session SSE connection) alive while the app
        // is open, so backgrounding does not force a reconnect. Started here,
        // in the foreground, because Android 12+ forbids a background start.
        // Idempotent: do not re-start (and flicker the notification) if the
        // keep-alive service is already running.
        if (!ConnectionService.isRunning) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ConnectionService::class.java),
            )
        }
        enableEdgeToEdge()
        setContent {
            // Apply the persisted appearance settings (color scheme, theme,
            // font size and fonts). Without this the Settings screen was inert.
            val settings by AppSettingsStore.state.collectAsStateWithLifecycle()
            val darkTheme =
                when (settings.colorScheme) {
                    "light" -> false
                    "dark" -> true
                    else -> isSystemInDarkTheme()
                }
            // Android 13+ requires a runtime grant for notifications.
            val permissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted =
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            // Theme/mode switches apply via full activity recreate: a plain
            // recompose left stale colours on screen (stored value changed,
            // headers kept the old accent until restart). Recreate is the
            // platform-standard path for theme switches.
            val appliedLook = remember { mutableStateOf(settings.theme to settings.colorScheme) }
            LaunchedEffect(settings.theme, settings.colorScheme) {
                val current = settings.theme to settings.colorScheme
                if (appliedLook.value != current) {
                    appliedLook.value = current
                    recreate()
                }
            }
            OpenCodeTheme(
                darkTheme = darkTheme,
                themeId = settings.theme,
                fontScale = settings.fontSize / 14f,
                sansFont = settings.sansFont,
                monoFont = settings.monoFont,
            ) {
                CompositionLocalProvider(
                    LocalBackendSession provides backendSession,
                    LocalProviderDirectory provides providerDirectory,
                ) {
                    OpenCodeApp(
                        deepLinkSessionId = deepLinkSessionId.value,
                        newSessionSignal = newSessionSignal.value,
                        shareSignal = shareSignal.value,
                    )
                }
                // The first frame is on screen, so the content is usable.
                // Without this the platform only records time-to-INITIAL-display
                // and startup regressions hide behind the empty window.
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    reportFullyDrawn()
                }
            }
        }
    }

    /** Routes every entry-point intent: deep link, new session, or share. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        deepLinkSessionId.value = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)
        when (intent.action) {
            ACTION_NEW_SESSION -> {
                newSessionSignal.value = System.currentTimeMillis()
            }

            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                captureShare(intent)
            }
        }
    }

    /**
     * Copies shared content into a slot the first chat screen consumes. Shared
     * images are copied into the app cache: the URI read grant from the sending
     * app is not durable, so reading it later at upload time would fail.
     *
     * The copy runs OFF the main thread (a large shared image used to block
     * onCreate/onNewIntent → ANR) and is capped so a hostile/huge share cannot
     * fill the cache.
     */
    private fun captureShare(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val sourceUris: List<Uri> =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                }

                else -> {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            }
        lifecycleScope.launch {
            val cached =
                withContext(Dispatchers.IO) {
                    sourceUris.mapNotNull { copySharedToCache(it) }
                }
            if (text.isNullOrBlank() && cached.isEmpty()) return@launch
            PendingShare.set(text, cached)
            shareSignal.value = System.currentTimeMillis()
        }
    }

    private fun copySharedToCache(uri: Uri): String? {
        var file: java.io.File? = null
        return try {
            val mime = contentResolver.getType(uri)
            val ext =
                android.webkit.MimeTypeMap
                    .getSingleton()
                    .getExtensionFromMimeType(mime ?: "") ?: "bin"
            val target = java.io.File(cacheDir, "shared_${System.currentTimeMillis()}.$ext")
            file = target
            var total = 0L
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SHARED_BYTES) {
                            throw java.io.IOException("shared file exceeds $MAX_SHARED_BYTES bytes")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (target.length() > 0) Uri.fromFile(target).toString() else null
        } catch (e: Exception) {
            // Never leave a partial file behind.
            file?.delete()
            com.opencode.android.util.AppLog.e(
                com.opencode.android.util.APP_LOG_TAG,
                "share: could not cache $uri: ${e.message}",
            )
            null
        }
    }

    override fun onDestroy() {
        // Only stop the keep-alive service when the Activity is really going
        // away. It is also destroyed for a configuration change — and this
        // Activity calls recreate() itself on a theme switch — so an
        // unconditional stop tore down and restarted the foreground service (and
        // its SSE connection) on every rotation/theme toggle.
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, ConnectionService::class.java))
        }
        super.onDestroy()
    }

    companion object {
        /** Opens the app on the session list for a fresh session. */
        const val ACTION_NEW_SESSION = "com.opencode.android.action.NEW_SESSION"

        /** Upper bound on a single shared attachment copied into the cache. */
        private const val MAX_SHARED_BYTES = 25L * 1024 * 1024
    }
}
