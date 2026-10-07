package com.opencode.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opencode.android.data.BackendSession
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
            Notifier.ACTION_PERMISSION_REPLY -> handlePermissionReply(intent)
        }
    }

    private fun handlePermissionReply(intent: Intent) {
        val requestId = intent.getStringExtra(Notifier.EXTRA_REQUEST_ID) ?: return
        val reply = intent.getStringExtra(Notifier.EXTRA_REPLY) ?: return
        val sessionId = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)

        // Optimistic: drop the notification immediately so the actions are gone
        // while the reply is in flight (the server also re-syncs the list).
        Notifier.clearPermission(requestId)

        val pending = goAsync()
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + com.opencode.android.util.LogAndSwallow,
        ).launch {
            try {
                // The BroadcastReceiver has a ~10 s budget; bound the call so a
                // hung request cannot outlive it and leak the PendingResult.
                val ok =
                    kotlinx.coroutines.withTimeoutOrNull(9_000L) {
                        replyPermission(backendSession.api, sessionId, requestId, reply)
                    }
                if (ok != true) {
                    AppLog.e(APP_LOG_TAG, "notification permission reply failed/timed out: $requestId")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
