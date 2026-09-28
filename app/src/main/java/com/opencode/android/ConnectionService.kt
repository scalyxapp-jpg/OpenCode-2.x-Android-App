package com.opencode.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.opencode.android.data.Notifier

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
        Notifier.ensureConnectionChannel()
        startForeground(Notifier.ID_CONNECTION, Notifier.connectionNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
