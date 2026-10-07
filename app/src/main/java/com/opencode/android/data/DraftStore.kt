package com.opencode.android.data

import com.opencode.android.ui.Attachment
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-session composer drafts.
 *
 * Switching tabs recreates the `ChatViewModel` on purpose (to bound the message
 * buffers), which used to silently discard an unsent draft. The draft is kept
 * here for the process lifetime and restored when the session is reopened.
 * Deliberately memory-only: a draft can reference cache URIs that do not
 * survive a process restart, so persisting it would restore broken attachments.
 */
object DraftStore {
    data class Draft(
        val text: String,
        val attachments: List<Attachment>,
    )

    private val drafts = ConcurrentHashMap<String, Draft>()

    fun save(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
    ) {
        if (text.isBlank() && attachments.isEmpty()) {
            drafts.remove(sessionId)
        } else {
            drafts[sessionId] = Draft(text, attachments)
        }
    }

    fun get(sessionId: String): Draft? = drafts[sessionId]

    fun clear(sessionId: String) {
        drafts.remove(sessionId)
    }
}
