package com.opencode.android
import android.content.Context
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.data.BackendStore
import com.opencode.android.data.HomeCache
import com.opencode.android.data.HomePrefs
import com.opencode.android.data.LastSessionStore
import com.opencode.android.data.MessageCache
import com.opencode.android.data.ModelVisibilityStore
import com.opencode.android.data.RecentModelsStore
import com.opencode.android.data.WidgetStateStore
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Initialises the disk-backed stores off the main thread.
 *
 * Their `init` calls `getSharedPreferences`, which touches the filesystem; doing
 * that from `Application.onCreate` produced StrictMode `DiskReadViolation`s
 * (tens of ms) on every cold start. The UI waits for [ready] instead of racing
 * the load, so nothing reads a store before it is populated.
 */
object AppStartup {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + com.opencode.android.util.LogAndSwallow)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    fun initialize(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                AppSettingsStore.init(app)
                BackendStore.init(app)
                LastSessionStore.init(app)
                ModelVisibilityStore.init(app)
                HomePrefs.init(app)
                HomeCache.init(app)
                RecentModelsStore.init(app)
                WidgetStateStore.init(app)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "AppStartup init failed: ${e.message}")
            } finally {
                // Always release the UI, even if a store failed: the app falls
                // back to defaults rather than hanging on the splash.
                _ready.value = true
            }
            // AFTER releasing the UI, so the splash is never held by cache I/O:
            // warm the in-memory caches in the background so the first Home
            // paint and the first session tap are memory hits, not disk reads.
            warmCaches()
        }
    }

    /**
     * Pre-reads the last-known home snapshot and the last session's message tail
     * into their in-memory layers. Best-effort: a miss just means the normal
     * read happens later.
     */
    private suspend fun warmCaches() {
        try {
            HomeCache.read()
            LastSessionStore.sessionId()?.let { MessageCache.read(it) }
        } catch (e: Exception) {
            AppLog.w(APP_LOG_TAG) { "cache warm failed: ${e.message}" }
        }
    }
}
