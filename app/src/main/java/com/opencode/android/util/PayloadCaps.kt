package com.opencode.android.util

import com.opencode.android.domain.ContentPart
import com.opencode.android.domain.Message
import com.opencode.android.domain.Part
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bounds the size of message payloads the app RETAINS in memory.
 *
 * The server returns tool parts in full: a `read` of a large file, a verbose
 * `bash` command, or a generated diff can each be several megabytes. The UI
 * only ever draws [MAX_PART_TEXT_CHARS]/tool output prefixes, but the message
 * list used to keep the entire payload for every one of up to 120 messages.
 * A busy session therefore held hundreds of megabytes of text nobody renders,
 * and the process hit `OutOfMemoryError` at the 512 MB large-heap ceiling
 * (confirmed in the client crash reports: "target footprint 536870912").
 *
 * Truncating at ingestion keeps the conversation bounded no matter what the
 * server sends. The caps are far above what is drawn (20 000 chars), so the
 * visible output and the Copy button still see the full text for normal tool
 * calls; only pathological multi-megabyte payloads are shortened.
 *
 * Pure so the boundary rule is unit-tested.
 */
object PayloadCaps {
    /** Reasoning/text part bodies. */
    const val MAX_PART_TEXT_CHARS = 128_000

    /** Tool `state.output` / `state.metadata.output`. */
    const val MAX_TOOL_OUTPUT_CHARS = 200_000

    /** Tool `state.metadata.diff` (inline diff cards). */
    const val MAX_TOOL_DIFF_CHARS = 200_000

    /** Caps every message's retained payloads; returns the same list when clean. */
    fun capMessages(messages: List<Message>): List<Message> {
        var changed = false
        val out = ArrayList<Message>(messages.size)
        for (m in messages) {
            val capped = capMessage(m)
            if (capped !== m) changed = true
            out.add(capped)
        }
        return if (changed) out else messages
    }

    fun capMessage(message: Message): Message {
        var changed = false
        val parts = capParts(message.parts) { changed = true }
        val content = capContent(message.content) { changed = true }
        val text = capString(message.text, MAX_PART_TEXT_CHARS) { changed = true }
        return if (changed) message.copy(text = text, parts = parts, content = content) else message
    }

    /** Caps a single streaming part (used by the live-stream reducer). */
    fun capPart(part: Part): Part {
        var changed = false
        val text = capString(part.text, MAX_PART_TEXT_CHARS) { changed = true }
        val state = capState(part.state) { changed = true }
        return if (changed) part.copy(text = text, state = state) else part
    }

    private fun capParts(
        parts: List<Part>,
        onChanged: () -> Unit,
    ): List<Part> {
        var changed = false
        val out = ArrayList<Part>(parts.size)
        for (p in parts) {
            var pc = false
            val text = capString(p.text, MAX_PART_TEXT_CHARS) { pc = true }
            val state = capState(p.state) { pc = true }
            if (pc) {
                changed = true
                out.add(p.copy(text = text, state = state))
            } else {
                out.add(p)
            }
        }
        if (changed) onChanged()
        return if (changed) out else parts
    }

    private fun capContent(
        content: List<ContentPart>,
        onChanged: () -> Unit,
    ): List<ContentPart> {
        var changed = false
        val out = ArrayList<ContentPart>(content.size)
        for (c in content) {
            var cc = false
            val text = capString(c.text, MAX_PART_TEXT_CHARS) { cc = true }
            val state = capState(c.state) { cc = true }
            if (cc) {
                changed = true
                out.add(c.copy(text = text, state = state))
            } else {
                out.add(c)
            }
        }
        if (changed) onChanged()
        return if (changed) out else content
    }

    /** Truncates the known large string fields inside a tool `state` object. */
    private fun capState(
        state: JsonElement?,
        onChanged: () -> Unit,
    ): JsonElement? {
        val obj = state as? JsonObject ?: return state
        var changed = false
        val rebuilt = LinkedHashMap<String, JsonElement>(obj.size)
        for ((key, value) in obj) {
            val capped =
                when (key) {
                    "output" -> capJsonString(value, MAX_TOOL_OUTPUT_CHARS) { changed = true }
                    "metadata" -> capMetadata(value) { changed = true }
                    else -> value
                }
            rebuilt[key] = capped
        }
        return if (changed) {
            onChanged()
            JsonObject(rebuilt)
        } else {
            state
        }
    }

    private fun capMetadata(
        metadata: JsonElement,
        onChanged: () -> Unit,
    ): JsonElement {
        val obj = metadata as? JsonObject ?: return metadata
        var changed = false
        val rebuilt = LinkedHashMap<String, JsonElement>(obj.size)
        for ((key, value) in obj) {
            val capped =
                when (key) {
                    "output" -> capJsonString(value, MAX_TOOL_OUTPUT_CHARS) { changed = true }
                    "diff" -> capJsonString(value, MAX_TOOL_DIFF_CHARS) { changed = true }
                    else -> value
                }
            rebuilt[key] = capped
        }
        return if (changed) {
            onChanged()
            JsonObject(rebuilt)
        } else {
            metadata
        }
    }

    private fun capJsonString(
        value: JsonElement,
        cap: Int,
        onChanged: () -> Unit,
    ): JsonElement {
        val primitive = value as? JsonPrimitive ?: return value
        if (!primitive.isString) return value
        val text = primitive.content
        if (text.length <= cap) return value
        onChanged()
        return JsonPrimitive(truncate(text, cap))
    }

    private fun capString(
        text: String?,
        cap: Int,
        onChanged: () -> Unit,
    ): String? {
        if (text == null || text.length <= cap) return text
        onChanged()
        return truncate(text, cap)
    }

    /**
     * Truncates so the RESULT is at most [cap] chars. The suffix is budgeted
     * inside the cap: appending it to `take(cap)` made a barely-oversized string
     * longer than the original.
     */
    private fun truncate(
        text: String,
        cap: Int,
    ): String {
        val suffix = "\n… [truncated ${text.length - cap} chars to save memory]"
        val keep = (cap - suffix.length).coerceAtLeast(0)
        return text.take(keep) + suffix
    }
}
