package com.opencode.android.ui
import androidx.compose.runtime.Immutable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.key
import com.opencode.android.domain.Part
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

internal fun extractToolDetail(part: Part): String? {
    part.title?.takeIf { it.isNotBlank() }?.let { return it }
    val input = part.stateInput() ?: return null
    // asRawString() returns null for JsonNull; `toString().trim('"')` produced
    // the literal string "null" and shadowed the command fallback.
    return input["filePath"].asRawString()?.takeIf { it.isNotBlank() }
        ?: input["command"].asRawString()?.takeIf { it.isNotBlank() }
}

// Basename display mirrors the web tool rows ("todo.md" instead of full path).
internal fun toolBasename(part: Part): String {
    val detail = extractToolDetail(part) ?: return (part.toolName() ?: "Tool")
    return com.opencode.android.util.lastPathSegment(detail)
}

internal fun toolStatus(part: Part): String? =
    part.stateField("status")?.takeIf { it.isNotBlank() }

// Tools the web groups under the "Explored …" summary card.
internal fun isExploreTool(tool: String?): Boolean =
    tool?.lowercase() in setOf("read", "grep", "search", "ripgrep", "glob", "list", "ls")

private val EDIT_TOOLS = setOf("edit", "write", "apply_patch", "multiedit", "patch")

/**
 * Whether a tool row starts expanded, driven by the "Expand shell/edit tool
 * parts" settings (web parity). Unknown tools stay collapsed.
 */
internal fun defaultToolExpanded(
    part: Part,
    shellExpanded: Boolean,
    editExpanded: Boolean,
): Boolean {
    val tool = part.toolName()?.lowercase()
    return when {
        tool == "bash" || tool == "shell" -> shellExpanded
        tool != null && tool in EDIT_TOOLS -> editExpanded
        else -> false
    }
}

internal fun toolSummaryLabel(parts: List<Part>): String {
    fun isRead(tool: String?) = tool?.lowercase() == "read"
    fun isSearch(tool: String?) = tool?.lowercase() in setOf("grep", "search", "ripgrep")
    fun isList(tool: String?) = tool?.lowercase() in setOf("list", "glob", "ls")
    val reads = parts.count { isRead(it.toolName()) }
    val searches = parts.count { isSearch(it.toolName()) }
    val lists = parts.count { isList(it.toolName()) }
    val others = parts.size - reads - searches - lists
    val sb = StringBuilder("Explored")
    sb.append(", $reads read").append(if (reads == 1) "" else "s")
    sb.append(", $searches search").append(if (searches == 1) "" else "es")
    sb.append(", $lists list").append(if (lists == 1) "" else "s")
    if (others > 0) sb.append(", $others other").append(if (others == 1) "" else "s")
    return sb.toString()
}

// --- Web-accurate tool call rendering -------------------------------------
// Web shows one collapsible row per tool call:
//   [Badge "Shell"] <title>   →   expand: "$ <command>" + output (Copy button)

internal fun kotlinx.serialization.json.JsonElement?.asRawString(): String? {
    val prim = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    if (prim is kotlinx.serialization.json.JsonNull) return null
    return prim.content
}

// Web maps the abort error to a short "Interrupted" label; other errors show
// their server message. Handles both object and plain-string error payloads.
internal fun messageErrorText(error: kotlinx.serialization.json.JsonElement?): String? {
    if (error == null || error is kotlinx.serialization.json.JsonNull) return null
    (error as? kotlinx.serialization.json.JsonPrimitive)?.let { return it.content }
    val obj = error as? kotlinx.serialization.json.JsonObject ?: return null
    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val data = obj["data"] as? kotlinx.serialization.json.JsonObject
    val msg = (data?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
    return when {
        name == "MessageAbortedError" -> "Interrupted"
        !msg.isNullOrBlank() -> msg
        !name.isNullOrBlank() -> name
        else -> null
    }
}

internal fun Part.stateObject(): kotlinx.serialization.json.JsonObject? =
    state as? kotlinx.serialization.json.JsonObject

internal fun Part.stateInput(): kotlinx.serialization.json.JsonObject? =
    stateObject()?.get("input") as? kotlinx.serialization.json.JsonObject

internal fun Part.stateField(name: String): String? = stateObject()?.get(name).asRawString()

internal fun Part.inputField(name: String): String? = stateInput()?.get(name).asRawString()

// Tool ids → web display labels. Web shows "Shell" for bash and
// "Called `delegate_task`" for anything without a friendly name.
internal fun toolDisplayName(tool: String): String = when (tool.lowercase()) {
    "bash", "shell" -> "Shell"
    else -> "Called `$tool`"
}

// Input keys that the web uses as the row title (first non-blank wins).
internal val TOOL_PRIMARY_KEYS = listOf("command", "filePath", "description", "pattern", "query", "path")

internal fun toolPrimaryKey(part: Part): String? =
    TOOL_PRIMARY_KEYS.firstOrNull { part.inputField(it)?.isNotBlank() == true }

internal fun toolTitleText(part: Part): String {
    part.stateField("title")?.takeIf { it.isNotBlank() }?.let { return it }
    toolPrimaryKey(part)?.let { key ->
        part.inputField(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return part.toolName() ?: "Tool"
}

// Remaining input params shown as "key=value" lines (web: agent=Planner, prompt=…).
// bash keeps them hidden (its command is already the title).
internal fun toolInputParams(part: Part): List<Pair<String, String>> {
    val tool = part.toolName()?.lowercase()
    if (tool == "bash" || tool == "shell") return emptyList()
    val input = part.stateInput() ?: return emptyList()
    val primary = toolPrimaryKey(part)
    // The subagent is rendered as its own badge, not as a key=value line.
    val subagentKey = when (tool) {
        "task" -> "subagent_type"
        "delegate_task" -> "agent"
        else -> null
    }
    return input.entries
        .filter { (k, _) -> k != primary && k != subagentKey }
        .mapNotNull { (k, v) -> v.asRawString()?.let { k to it } }
}

// Subagent invoked by task/delegate_task (web shows it as a badge, e.g. "explorer").
internal fun toolSubagent(part: Part): String? = when (part.toolName()?.lowercase()) {
    "task" -> part.inputField("subagent_type")
    "delegate_task" -> part.inputField("agent")
    else -> null
}

// The subagent's own session id (metadata.sessionId) — web links to it.
internal fun toolSubagentSessionId(part: Part): String? {
    val md = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
        ?: return null
    return (md["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

internal fun toolCommandText(part: Part): String? =
    part.inputField("command")?.takeIf { it.isNotBlank() }

// Web shows "+20 -1" behind edit/write tool calls. Derived from metadata.diff.
internal fun toolDiffStat(part: Part): Pair<Int, Int>? {
    val md = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
        ?: return null
    val diff = (md["diff"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
    var added = 0
    var removed = 0
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> Unit
            line.startsWith("+") -> added++
            line.startsWith("-") -> removed++
        }
    }
    return if (added == 0 && removed == 0) null else added to removed
}

// Web "Changed files" summary: per-file +/- stats + unified diff for
// edit/write/patch tools.
@Immutable
data class ChangedFile(
    val path: String,
    val added: Int,
    val removed: Int,
    val diff: String? = null,
)

internal fun toolDiffText(part: Part): String? {
    val md = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
        ?: return null
    return (md["diff"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

internal fun changedFiles(parts: List<Part>): List<ChangedFile> =
    parts.mapNotNull { part ->
        val tool = part.toolName()?.lowercase() ?: return@mapNotNull null
        if (tool !in setOf("edit", "write", "apply_patch", "multiedit", "patch")) {
            return@mapNotNull null
        }
        val md = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
        val path = (md?.get("filepath") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: part.inputField("filePath")
            ?: return@mapNotNull null
        val (added, removed) = toolDiffStat(part) ?: (0 to 0)
        ChangedFile(path, added, removed, toolDiffText(part))
    }.distinctBy { it.path }

internal fun toolOutputText(part: Part): String? {
    part.stateField("output")?.takeIf { it.isNotBlank() }?.let { return it }
    val meta = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
    return meta?.get("output").asRawString()?.takeIf { it.isNotBlank() }
}

// Hard cap on rendered diff lines. Every diff line used to become its own Text
// composable inside a plain (non-lazy) Column, so a large edit — a generated
// file, a big refactor — created thousands of composables in one shot and could
// exhaust memory on a phone. The full diff stays available via the tool output
// and the Changes tab; the card just stops rendering the tail.
internal const val MAX_DIFF_LINES = 400

// Tool outputs can be megabytes (a big file read, a verbose command). Laying
// the whole string out in a single Text can exhaust memory, so display only a
// bounded prefix — the Copy button still copies the full text.
internal const val MAX_TOOL_OUTPUT_CHARS = 20_000

internal fun cappedForDisplay(text: String): String =
    if (text.length <= MAX_TOOL_OUTPUT_CHARS) {
        text
    } else {
        text.take(MAX_TOOL_OUTPUT_CHARS) +
            "\n" + com.opencode.android.util.ToolText.truncationSuffix(text.length - MAX_TOOL_OUTPUT_CHARS)
    }


/**
 * Collapses consecutive calls of the same tool into one entry carrying the
 * newest part plus a repeat count. A tool storm (the model reading ten files in
 * a row) otherwise rendered ten near-identical rows and recomposed the whole
 * live tool column on every arrival. Pure, so the grouping is unit-tested.
 */
internal fun coalesceConsecutiveTools(parts: List<Part>): List<Pair<Part, Int>> {
    val out = ArrayList<Pair<Part, Int>>()
    for (part in parts) {
        val name = part.toolName()
        val last = out.lastOrNull()
        if (last != null && name != null && last.first.toolName() == name) {
            // Keep the NEWEST part (it carries the current status) and count.
            out[out.size - 1] = part to (last.second + 1)
        } else {
            out.add(part to 1)
        }
    }
    return out
}
