package com.opencode.android.ui.session

import com.opencode.android.domain.InboxItem
import com.opencode.android.domain.InboxPayload
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The queue rule.
 *
 * Reported: prompts taken from the queue stayed there after they had been injected into the
 * chat. Verified live against the server, a `delivery:"queue"` prompt leaves the inbox the
 * moment it is delivered and becomes a message with the SAME id — so the transcript decides
 * what is still queued.
 */
class QueuePolicyTest {
    private fun item(
        id: String?,
        type: String = "user",
    ) = InboxItem(id = id, sessionId = "ses_1", type = type, payload = InboxPayload(text = "text"))

    @Test
    fun `a prompt that is already a message is not queued any more`() {
        val queue = listOf(item("msg_a"), item("msg_b"), item("msg_c"))
        // msg_b was delivered: it is in the transcript with the same id.
        assertEquals(listOf("msg_a", "msg_c"), QueuePolicy.pending(queue, setOf("msg_b")).map { it.id })
    }

    @Test
    fun `the whole queue disappears once everything is delivered`() {
        val queue = listOf(item("msg_a"), item("msg_b"))
        assertEquals(emptyList<InboxItem>(), QueuePolicy.pending(queue, setOf("msg_a", "msg_b")))
        // …and nothing is filtered while nothing was delivered.
        assertEquals(queue, QueuePolicy.pending(queue, setOf("msg_x")))
    }

    @Test
    fun `an entry without an id is kept`() {
        // It cannot be matched against the transcript; hiding it would drop a prompt the
        // user is still waiting for.
        assertEquals(listOf(null), QueuePolicy.pending(listOf(item(null)), setOf("msg_a", "msg_b")).map { it.id })
    }

    @Test
    fun `only user prompts count as queued messages`() {
        val items = listOf(item("msg_a"), item("msg_syn", type = "synthetic"), item("msg_c", type = "compaction"))
        assertEquals(listOf("msg_a"), QueuePolicy.userPrompts(items).map { it.id })
    }
}
