package com.opencode.android.data
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Emits whenever Android reports a usable default network.
 *
 * The SSE reconnect loop otherwise sleeps out its exponential backoff even
 * though the network came back a second later — a cellular/wifi handoff could
 * leave the stream dead for up to 30 s. Waking on this signal reconnects
 * immediately (and resets the backoff), which is the behaviour the network
 * lifecycle expects; EventSource/okhttp-sse is unaware of it by design.
 */
object NetworkMonitor {
    // replay = 0 on purpose: this is an edge-triggered wake-up, not state. An
    // emission that arrives while nobody is waiting is dropped, which is fine —
    // the reconnect loop is already about to try, and the SSE watchdog recovers
    // a stream that stayed dead. extraBufferCapacity keeps tryEmit non-suspending
    // for the ConnectivityManager callback thread.
    private val _available =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
        )
    val available: SharedFlow<Unit> = _available

    @Volatile
    private var registered = false

    @Volatile
    private var manager: ConnectivityManager? = null

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun init(context: Context) {
        if (registered) return
        val cm =
            context.applicationContext
                .getSystemService(ConnectivityManager::class.java) ?: return
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _available.tryEmit(Unit)
                }
            }
        try {
            cm.registerDefaultNetworkCallback(cb)
            manager = cm
            callback = cb
            registered = true
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "NetworkMonitor init failed: ${e.message}")
        }
    }

    /** Unregisters the callback (best-effort; the singleton lives for the process). */
    fun shutdown() {
        val cm = manager
        val cb = callback
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "NetworkMonitor unregister failed: ${e.message}")
            }
        }
        callback = null
        manager = null
        registered = false
    }
}
