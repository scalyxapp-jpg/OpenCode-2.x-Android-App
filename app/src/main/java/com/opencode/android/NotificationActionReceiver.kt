package com.opencode.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opencode.android.data.BackendSession
import com.opencode.android.data.BackendStore
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.data.Notifier
import com.opencode.android.data.replyPermission
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles notification actions without opening the app. Today the only action
 * is answering a tool-permission prompt (Allow once / Always / Deny), so a
 * blocked agent can be unblocked from the notification shade.
 *
 * The reply is sent on a short-lived coroutine that keeps the process alive via
 * [goAsync] until the HTTP call finishes; the notification is cancelled
 * optimistically so the buttons cannot be double-tapped.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var backendSession: BackendSession

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Notifier.ACTION_PERMISSION_REPLY -> handlePermissionReply(context, intent)
        }
    }

    private fun handlePermissionReply(
        context: Context,
        intent: Intent,
    ) {
        val requestId = intent.getStringExtra(Notifier.EXTRA_REQUEST_ID) ?: return
        val reply = intent.getStringExtra(Notifier.EXTRA_REPLY) ?: return
        val sessionId = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + com.opencode.android.util.LogAndSwallow,
        ).launch {
            try {
                // A broadcast can START the process with the app gone: the stores are
                // then uninitialised (AppStartup runs asynchronously) and BackendSession
                // still points at its unconfigured 127.0.0.1 stand-in, so the reply went
                // nowhere. Done HERE (IO) instead of in onReceive: the store init reads
                // SharedPreferences and decrypts the guard token through the Keystore —
                // exactly the main-thread work AppStartup deliberately avoids.
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    BackendStore.init(app)
                    AppSettingsStore.init(app)
                    runCatching {
                        BackendStore.mostRecent()?.let { saved ->
                            if (saved.password != null) {
                                // Atomic triple update (url + credentials + api instance).
                                backendSession.setBackend(saved.url, saved.username, saved.password)
                            } else {
                                backendSession.setBaseUrl(saved.url)
                            }
                        }
                    }.onFailure { error ->
                        AppLog.e(
                            APP_LOG_TAG,
                            "notification reply: could not apply saved backend: ${error.message}",
                        )
                    }
                }
                // The BroadcastReceiver has a ~10 s budget; bound the call so a
                // hung request cannot outlive it and leak the PendingResult.
                val ok =
                    kotlinx.coroutines.withTimeoutOrNull(9_000L) {
                        replyPermission(backendSession.api, sessionId, requestId, reply)
                    }
                if (ok == true) {
                    // Only drop the notification once the server accepted the reply
                    // — a failed answer kept vanishing, leaving the user unable to
                    // retry it from the notification.
                    Notifier.clearPermission(requestId)
                } else {
                    AppLog.e(APP_LOG_TAG, "notification permission reply failed/timed out: $requestId")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
