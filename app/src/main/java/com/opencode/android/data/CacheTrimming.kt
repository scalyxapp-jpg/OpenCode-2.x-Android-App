package com.opencode.android.data

import com.opencode.android.domain.Message

/**
 * Strips the bulk from messages before they are written to the offline cache.
 *
 * A heavy session's 60 messages serialised to 6.2 MB — over the cache's size
 * cap, so the cache was skipped exactly where it would have helped most. Tool
 * `state` (command output) and the legacy `content` duplicate are the
 * overwhelming majority of that and are not needed for a first paint; the
 * authoritative network load replaces them a moment later.
 *
 * Pure (no Android types) so the rule is unit-testable; [MessageCache] owns the
 * file I/O.
 */
object CacheTrimming {
    /** Per-part text ceiling. Long answers stay readable; this is a first paint. */
    const val MAX_PART_TEXT = 8_000

    /**
     * The newest slice of [messages] whose size (as measured by [sizeOf]) fits
     * [maxBytes], dropping the oldest half repeatedly. Always keeps at least the
     * newest message, so a session is never left with no cache at all — a heavy
     * session used to be skipped entirely and every open was a cold network
     * load.
     */
    fun newestFitting(
        messages: List<Message>,
        maxBytes: Int,
        sizeOf: (List<Message>) -> Int,
    ): List<Message> {
        if (messages.isEmpty()) return messages
        var tail = messages
        while (tail.size > 1 && sizeOf(tail) > maxBytes) {
            tail = tail.subList(tail.size / 2, tail.size)
        }
        return tail.toList()
    }

    fun trim(messages: List<Message>): List<Message> =
        messages.map { message ->
            val parts = message.parts.map { it.trimmed() }
            // Only drop the legacy `content` copy when the modern `parts` array is
            // present. A legacy-only message (content populated, parts empty) would
            // otherwise be cached with NO text at all and paint an empty bubble
            // until the network load replaced it.
            val content = if (parts.isNotEmpty()) emptyList() else message.content.map { it.trimmed() }
            message.copy(parts = parts, content = content)
        }

    private fun com.opencode.android.domain.Part.trimmed() =
        copy(
            state = null,
            text = text?.let { if (it.length > MAX_PART_TEXT) it.take(MAX_PART_TEXT) else it },
        )

    private fun com.opencode.android.domain.ContentPart.trimmed() =
        copy(
            state = null,
            text = text?.let { if (it.length > MAX_PART_TEXT) it.take(MAX_PART_TEXT) else it },
        )
}
