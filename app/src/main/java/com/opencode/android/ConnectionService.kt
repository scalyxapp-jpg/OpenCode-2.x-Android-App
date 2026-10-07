package com.opencode.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.opencode.android.data.Notifier
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog

/**
 * Keeps the process out of Android's cached-app freezer while the app is open.
 *
 * Why: the session SSE connection lives in the process. When the app is
 * backgrounded, Android freezes the process after a short time, so the socket
 * goes stale; reopening the app then had to reconnect and surfaced connection
 * errors. A foreground service with an ongoing (silent, minimum-importance)
 * notification exempts the process from the freezer, so the connection stays
 * up regardless of whether the app has focus.
 *
 * Lifecycle: started by [MainActivity] while the app is in the foreground
 * (Android 12+ forbids starting a foreground service from the background) and
 * stopped in `onDestroy`, so it runs exactly as long as the app is open.
 *
 * `dataSync` type: it carries a network session. Note Android 15+ caps a
 * `dataSync` foreground service at ~6 hours per day; after that the OS stops it
 * and the app falls back to the normal (reconnecting) behaviour.
 */
class ConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        try {
            Notifier.ensureConnectionChannel()
            startForeground(Notifier.ID_CONNECTION, Notifier.connectionNotification())
            isRunning = true
        } catch (e: Exception) {
            // Android 12+ refuses a background FGS start and Android 14+ throws
            // when the dataSync type permission is missing; a restart after the
            // OS killed the process can hit either. The keep-alive service is
            // optional — never crash the app over it.
            AppLog.e(APP_LOG_TAG, "ConnectionService: startForeground failed: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    /**
     * Android 15+ enforces a time budget on a `dataSync` foreground service and
     * throws `ForegroundServiceDidNotStopInTimeException` if it is still running
     * when the budget expires (observed in the client crash reports). Stop it
     * cleanly instead of letting the OS kill the process.
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** True while the foreground keep-alive service is alive. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
