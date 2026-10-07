package com.opencode.android.data

import com.opencode.android.domain.PermissionReplyRequest

/**
 * Answers a pending tool-permission request. Reply values are protocol-fixed:
 * `"once"` | `"always"` | `"reject"`.
 *
 * The global `POST /permission/{id}/reply` is the canonical route, but some
 * server builds only expose the session-scoped form; the session fallback is
 * tried before giving up. Shared by the in-app dialog and the notification
 * action so both paths behave identically.
 *
 * @return true when either route accepted the reply.
 */
suspend fun replyPermission(
    api: OpenCodeApi,
    sessionId: String?,
    requestId: String,
    reply: String,
): Boolean {
    val body = PermissionReplyRequest(reply = reply)
    try {
        api.replyPermission(requestId, body).close()
        return true
    } catch (e: Exception) {
        if (sessionId.isNullOrBlank()) return false
        return try {
            api.replySessionPermission(sessionId, requestId, body).close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
