package com.opencode.android.ui.session

import com.opencode.android.domain.InboxItem

/**
 * Which queued prompts are still pending.
 *
 * Why this exists: a prompt queued with `delivery:"queue"` leaves the server inbox the
 * moment it is delivered — verified live, the inbox stays empty and the prompt becomes a
 * regular message with the SAME id. The app's optimistic queue card (the immediate row the
 * composer adds before the round trip) therefore survived until some unrelated inbox reload
 * or `session.inbox.changed` event, and the delivered prompt appeared twice: once as a
 * message, once as a queue row that never left.
 *
 * The transcript is the authority: an entry whose id is already a message HAS been injected
 * into the chat, so it is not queued any more. That is self-healing — no event required.
 * Pure, so the rule is unit-tested.
 */
object QueuePolicy {
    /**
     * [queue] without the entries that already exist as a message in the transcript.
     *
     * Ids are compared verbatim (the server echoes the client's `msg_…` id), and entries
     * without an id are kept: an id-less row cannot be matched, and dropping it would hide a
     * prompt the user is still waiting for.
     */
    fun pending(
        queue: List<InboxItem>,
        transcriptIds: Set<String>,
    ): List<InboxItem> = queue.filter { item -> item.id.isNullOrBlank() || item.id !in transcriptIds }

    /** Only the prompts of an inbox response — other types are not queued messages. */
    fun userPrompts(items: List<InboxItem>): List<InboxItem> = items.filter { it.type == "user" }
}
