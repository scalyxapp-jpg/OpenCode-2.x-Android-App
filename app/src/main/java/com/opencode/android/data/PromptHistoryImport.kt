package com.opencode.android.data

import com.opencode.android.domain.Message
import com.opencode.android.util.MessageEcho

/**
 * Backfills the prompt history from what the SERVER already knows.
 *
 * The history is only recorded when *this app* sends a prompt, so two kinds of prompts
 * are missing by construction: everything sent before the feature existed, and everything
 * sent from the web UI or the TUI. Both are stored as user messages in the session, so
 * they can be imported — that is what this does.
 *
 * Pure (given the message list) so the selection rules are unit-tested.
 */
object PromptHistoryImport {
    /** User messages read per session when importing (newest N). */
    const val MESSAGES_PER_SESSION = 60

    /** Sessions visited by an "all sessions" import (newest first). */
    const val MAX_SESSIONS = 20

    /**
     * The user prompts of one session, **oldest first**.
     *
     * Both message schemas are supported (`role`/`info.role` on the classic endpoint,
     * `type` on the V2 one), attachments-only turns are skipped (no text to recall), and
     * the list is de-duplicated per consecutive repetition so a prompt that was re-sent
     * does not appear twice in a row.
     */
    fun prompts(messages: List<Message>): List<String> {
        val out = mutableListOf<String>()
        messages.forEach { message ->
            val role = message.role ?: message.info?.role ?: message.type
            if (role != "user") return@forEach
            val text = MessageEcho.firstText(message).trim()
            if (text.isEmpty()) return@forEach
            if (out.lastOrNull() == text) return@forEach
            out += text
        }
        return out
    }

    /**
     * Merges imported prompts into an existing history **by timestamp**.
     *
     * Not `PromptHistoryPolicy.record` in a loop: that always puts the new entry on top
     * (correct for a fresh send, wrong for an import — an OLD prompt from the web UI
     * would jump above a newer one from this app; the unit test caught it). The result is
     * sorted newest-first, and a prompt that both sides know is taken from the import
     * (the server's timestamp) instead of appearing twice.
     */
    fun merge(
        existing: List<PromptHistoryEntry>,
        messages: List<Message>,
        sessionId: String,
    ): List<PromptHistoryEntry> {
        val imported =
            userPromptsWithTime(messages).map { (text, at) ->
                PromptHistoryEntry(text = text, sessionId = sessionId, at = at)
            }
        if (imported.isEmpty()) return existing
        val importedTexts = imported.map { it.text }.toSet()
        val kept =
            existing.filterNot { it.sessionId == sessionId && it.text in importedTexts }
        return (kept + imported)
            .sortedByDescending { it.at }
            .take(PromptHistoryPolicy.MAX_ENTRIES)
    }

    private fun userPromptsWithTime(messages: List<Message>): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        messages.forEachIndexed { index, message ->
            val role = message.role ?: message.info?.role ?: message.type
            if (role != "user") return@forEachIndexed
            val text = MessageEcho.firstText(message).trim()
            if (text.isEmpty()) return@forEachIndexed
            if (out.lastOrNull()?.first == text) return@forEachIndexed
            // No `time.created` (some classic payloads omit it): keep the order stable
            // by using the position, which is what the list shows anyway.
            out += text to (message.time?.created ?: (index.toLong() + 1))
        }
        return out
    }
}
