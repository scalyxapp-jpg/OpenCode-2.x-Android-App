package com.opencode.android.ui
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
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
    return com.opencode.android.util
        .lastPathSegment(detail)
}

internal fun toolStatus(part: Part): String? = part.stateField("status")?.takeIf { it.isNotBlank() }

// Tools the web groups under the "Explored …" summary card.
internal fun isExploreTool(tool: String?): Boolean = tool?.lowercase() in setOf("read", "grep", "search", "ripgrep", "glob", "list", "ls")

private val EDIT_TOOLS = setOf("edit", "write", "apply_patch", "multiedit", "patch")

/**
 * A tool call's failure, in the words of the reference clients.
 *
 * The chat never rendered `state.error` at all — a failed tool call showed a red
 * title and no reason. The message comes from the server (`error.data.message`, or a
 * plain string); `MessageAbortedError` keeps the short "Interrupted" label the TUI/web
 * also use. `null` when the call did not fail.
 */
internal fun toolErrorText(part: Part): String? =
    if (part.stateField("status") == "error") messageErrorText(part.stateObject()?.get("error")) else null

/**
 * Whether a tool row starts expanded, driven by the "Expand shell/edit tool
 * parts" settings (web parity). Unknown tools stay collapsed.
 *
 * A failed call always starts open: the reason it failed is the whole point of
 * the row, and hiding it behind a tap was the previous behaviour.
 */
internal fun defaultToolExpanded(
    part: Part,
    shellExpanded: Boolean,
    editExpanded: Boolean,
): Boolean {
    val tool = part.toolName()?.lowercase()
    return when {
        toolErrorText(part) != null -> true
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

/**
 * A numeric field of a tool's state/metadata, tolerating the number-as-text the
 * server sometimes sends. `null` when absent — callers then fall back to
 * counting the patch lines themselves.
 */
internal fun kotlinx.serialization.json.JsonElement?.asIntOrNull(): Int? = asRawString()?.trim()?.toIntOrNull()

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

internal fun Part.stateObject(): kotlinx.serialization.json.JsonObject? = state as? kotlinx.serialization.json.JsonObject

internal fun Part.stateInput(): kotlinx.serialization.json.JsonObject? =
    stateObject()?.get("input") as? kotlinx.serialization.json.JsonObject

internal fun Part.stateField(name: String): String? = stateObject()?.get(name).asRawString()

internal fun Part.inputField(name: String): String? = stateInput()?.get(name).asRawString()

/**
 * The row label of a tool call, Title Case like the web's tool headers
 * ("Shell", "Read", "Edit"). Unknown tools keep their raw id so no information
 * is lost, an empty id falls back to a neutral "Tool".
 */
internal fun toolRowTitle(tool: String?): String =
    com.opencode.android.util.ToolText
        .toolTitle(tool)

/**
 * The tool badge of a chat row.
 *
 * TUI parity: the TUI names the tool ("Edit", "Read", "Shell"), and so does the rest
 * of the app (live rows, notifications, Changes card). This used to render
 * "Called `edit`" for every tool that is not bash — the same information in a form
 * that appears nowhere else in the TUI. Unknown tools keep their raw id.
 */
internal fun toolDisplayName(tool: String): String = toolRowTitle(tool)

// Input keys that the web uses as the row title (first non-blank wins).
internal val TOOL_PRIMARY_KEYS = listOf("command", "filePath", "description", "pattern", "query", "path")

internal fun toolPrimaryKey(part: Part): String? = TOOL_PRIMARY_KEYS.firstOrNull { part.inputField(it)?.isNotBlank() == true }

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
    val subagentKey =
        when (tool) {
            "task" -> "subagent_type"
            "delegate_task" -> "agent"
            else -> null
        }
    return input.entries
        .filter { (k, _) -> k != primary && k != subagentKey }
        .mapNotNull { (k, v) -> v.asRawString()?.let { k to it } }
}

// Subagent invoked by task/delegate_task (web shows it as a badge, e.g. "explorer").
internal fun toolSubagent(part: Part): String? =
    when (part.toolName()?.lowercase()) {
        "task" -> part.inputField("subagent_type")
        "delegate_task" -> part.inputField("agent")
        else -> null
    }

// The subagent's own session id (metadata.sessionId) — web links to it.
internal fun toolSubagentSessionId(part: Part): String? {
    val md =
        part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
            ?: return null
    return (md["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

internal fun toolCommandText(part: Part): String? = part.inputField("command")?.takeIf { it.isNotBlank() }

/**
 * Tool ids that change files (the ones with a diff and with the "Changed files" card).
 */
private val FILE_CHANGING_TOOLS = setOf("edit", "write", "apply_patch", "multiedit", "patch")

/**
 * One changed file of a tool call, as far as the app needs it.
 *
 * OpenCode 2.0.26 reports these in `state.metadata.files[]`
 * (`file`, `additions`, `deletions`, `patch`) — a single `edit` can change several files,
 * so this is a list and not a single value.
 */
internal data class ChangedFileMeta(
    val path: String,
    val added: Int,
    val removed: Int,
    val patch: String? = null,
)

private val RE_PATCH_FILE_HEADER =
    Regex("""^(?:Index:\s*|\+\+\+ b/|\-\-\- a/)(.+?)\s*$""", RegexOption.MULTILINE)

/** The file a unified diff belongs to, taken from its `Index:`/`+++`/`---` header. */
internal fun patchHeaderPath(patch: String?): String? =
    patch
        ?.lineSequence()
        ?.mapNotNull { RE_PATCH_FILE_HEADER.find(it.trim())?.groupValues?.get(1) }
        ?.firstOrNull { it.isNotBlank() && it != "/dev/null" }

/**
 * The changed files of an edit/write/patch call, with their +/- counts.
 *
 * Source order follows the server: `metadata.files[]` (2.0.26, per file, carries the
 * counts) first; older builds only have `metadata.filepath`/`metadata.diff`, and a
 * `write` has just the input path plus the content it wrote. Empty for every other tool.
 */
internal fun changedFileMeta(part: Part): List<ChangedFileMeta> {
    if (part.toolName()?.lowercase() !in FILE_CHANGING_TOOLS) return emptyList()
    val md = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
    val fromFiles =
        (md?.get("files") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { item ->
                val entry = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val patch = entry["patch"].asRawString()?.takeIf { it.isNotBlank() }
                val path =
                    entry["file"].asRawString()?.takeIf { it.isNotBlank() }
                        ?: entry["path"].asRawString()?.takeIf { it.isNotBlank() }
                        ?: patchHeaderPath(patch)
                        ?: return@mapNotNull null
                ChangedFileMeta(
                    path = path,
                    added = entry["additions"].asIntOrNull() ?: 0,
                    removed = entry["deletions"].asIntOrNull() ?: 0,
                    patch = patch,
                )
            }.orEmpty()
    if (fromFiles.isNotEmpty()) return fromFiles
    // No metadata.files: derive the single path from the older shapes.
    val patch =
        md?.get("diff").asRawString()?.takeIf { it.isNotBlank() }
            ?: part.inputField("patch")?.takeIf { it.isNotBlank() }
            ?: writtenContentPatch(part)
    val path =
        md?.get("filepath").asRawString()?.takeIf { it.isNotBlank() }
            ?: part.inputField("filePath")?.takeIf { it.isNotBlank() }
            ?: part.inputField("path")?.takeIf { it.isNotBlank() }
            ?: patchHeaderPath(patch)
            ?: return emptyList()
    return listOf(ChangedFileMeta(path, 0, 0, patch))
}

/** `+added`/`-removed` of a unified diff, ignoring the `+++`/`---` file headers. */
internal fun countDiffLines(diff: String?): Pair<Int, Int> {
    var added = 0
    var removed = 0
    diff?.lineSequence()?.forEach { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> Unit
            line.startsWith("+") -> added++
            line.startsWith("-") -> removed++
        }
    }
    return added to removed
}

/**
 * Web "Changed files" summary: per-file +/- stats + unified diff for
 * edit/write/patch tools.
 */
@Immutable
data class ChangedFile(
    val path: String,
    val added: Int,
    val removed: Int,
    val diff: String? = null,
)

/**
 * "+20 -1" behind edit/write rows and in the Changes card.
 *
 * The counts come from the server when it sent them (`metadata.files[].additions` /
 * `deletions`); otherwise they are counted from the patch, so a build without those
 * fields still shows a correct stat.
 */
internal fun toolDiffStat(part: Part): Pair<Int, Int>? {
    val files = changedFileMeta(part)
    if (files.isEmpty()) {
        // A diff whose file could not be named (e.g. a `patch` passed as input) still
        // gets its stat counted, so the row never loses the "+N -M" suffix.
        val md = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
        val looseDiff =
            md?.get("diff").asRawString()?.takeIf { it.isNotBlank() }
                ?: part.inputField("patch")?.takeIf { it.isNotBlank() }
                ?: writtenContentPatch(part)
        val (looseAdded, looseRemoved) = countDiffLines(looseDiff)
        return if (looseAdded == 0 && looseRemoved == 0) null else looseAdded to looseRemoved
    }
    var added = 0
    var removed = 0
    files.forEach { file ->
        if (file.added != 0 || file.removed != 0) {
            added += file.added
            removed += file.removed
        } else {
            val (a, r) = countDiffLines(file.patch)
            added += a
            removed += r
        }
    }
    return if (added == 0 && removed == 0) null else added to removed
}

internal fun changedFiles(parts: List<Part>): List<ChangedFile> =
    parts
        .flatMap { part ->
            changedFileMeta(part).map { meta ->
                val (countedAdded, countedRemoved) = countDiffLines(meta.patch)
                ChangedFile(
                    path = meta.path,
                    added = if (meta.added != 0) meta.added else countedAdded,
                    removed = if (meta.removed != 0) meta.removed else countedRemoved,
                    diff = meta.patch,
                )
            }
        }.distinctBy { it.path }

/**
 * The unified diff of an edit/patch tool call.
 *
 * Source order matters and was the bug behind "I no longer see the code with line numbers":
 * OpenCode 2.0.26 sends the patch in `state.metadata.files[].patch` (the same text the TUI
 * prints, hunks included), while other builds put it in `metadata.diff`. Reading only the
 * latter left every edit row without any diff.
 */
internal fun toolDiffText(part: Part): String? {
    val md =
        part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
    val direct = (md?.get("diff") as? kotlinx.serialization.json.JsonPrimitive)?.content
    if (!direct.isNullOrBlank()) return direct
    // 2.0.26: one entry per changed file, each with its own unified patch.
    val files = md?.get("files") as? kotlinx.serialization.json.JsonArray
    val patches =
        files
            ?.mapNotNull { item ->
                ((item as? kotlinx.serialization.json.JsonObject)?.get("patch") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content
                    ?.takeIf { it.isNotBlank() }
            }.orEmpty()
    if (patches.isNotEmpty()) return patches.joinToString("\n")
    // A patch tool carries the diff it applied as its input.
    part.inputField("patch")?.takeIf { it.isNotBlank() }?.let { return it }
    // `write` has no patch (the file is new/overwritten): show the written content as an
    // all-added hunk so the TUI's numbered listing appears instead of nothing.
    return writtenContentPatch(part)
}

/**
 * The content of a `write` call as a numbered, all-added hunk.
 *
 * The server sends `input.content` (the full file) without a diff, so this synthesises the
 * hunk header and marks every line as added — exactly what the TUI shows for a written file.
 */
internal fun writtenContentPatch(part: Part): String? {
    val tool = part.toolName()?.lowercase() ?: return null
    if (tool !in setOf("write", "create", "apply_patch")) return null
    val content = part.inputField("content")?.takeIf { it.isNotBlank() } ?: return null
    val lines = content.trimEnd('\n').split('\n')
    return buildString {
        append("@@ -1,")
            .append(lines.size)
            .append(" +1,")
            .append(lines.size)
            .append(" @@\n")
        lines.forEach { append('+').append(it).append('\n') }
    }
}

internal fun toolOutputText(part: Part): String? {
    part.stateField("output")?.takeIf { it.isNotBlank() }?.let { return it }
    // OpenCode 2.x tool state carries its output as `content: [{type,text}]`.
    val content = part.stateObject()?.get("content") as? kotlinx.serialization.json.JsonArray
    val fromContent =
        content
            ?.mapNotNull { item ->
                (item as? kotlinx.serialization.json.JsonObject)
                    ?.get("text")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.takeIf { it.isNotBlank() }
            }?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
    if (fromContent != null) return fromContent
    val meta = part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject
    meta
        ?.get("output")
        .asRawString()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    // A finished shell call without output says so, like the server/TUI ("(no output)")
    // — an empty box would look like a row that failed to load. Only once the call is
    // done: while it runs there is simply nothing yet.
    if (part.toolName()?.lowercase() in SHELL_TOOLS && part.stateField("status") == "completed") {
        return NO_OUTPUT
    }
    return null
}

private val SHELL_TOOLS = setOf("bash", "shell", "execute")

/** The server's own placeholder for a command that printed nothing. */
internal const val NO_OUTPUT = "(no output)"

/**
 * The truncation notice for a result the server itself cut short, in the server's
 * wording (`...output truncated...` / `Full output saved to: <path>`, read out of the
 * opencode bundle). `null` when the server did not truncate anything or when its own
 * output text already carries the notice — a second copy would just be noise.
 */
internal fun toolOutputNotice(part: Part): String? {
    val md =
        part.stateObject()?.get("metadata") as? kotlinx.serialization.json.JsonObject ?: return null
    if (md["truncated"] != kotlinx.serialization.json.JsonPrimitive(true)) return null
    val savedTo = md["outputPath"].asRawString()?.takeIf { it.isNotBlank() } ?: return null
    if (toolOutputText(part).orEmpty().contains("output truncated")) return null
    return "...output truncated...\n\nFull output saved to: $savedTo"
}

/**
 * Reasoning text as the TUI prints it.
 *
 * The server replaces redacted stretches with `[REDACTED]`; the TUI strips those
 * markers before showing the text, so the app does the same instead of leaking
 * placeholder brackets into the thinking block.
 */
internal fun tuiReasoningText(raw: String): String = raw.replace("[REDACTED]", "").trim()

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
            "\n" +
            com.opencode.android.util.ToolText
                .truncationSuffix(text.length - MAX_TOOL_OUTPUT_CHARS)
    }

/**
 * Collapses consecutive calls of the same tool into one entry carrying the
 * newest part plus a repeat count. A tool storm (the model reading ten files in
 * a row) otherwise rendered ten near-identical rows and recomposed the whole
 * live tool column on every arrival. Pure, so the grouping is unit-tested.
 */
internal fun coalesceConsecutiveTools(parts: List<Part>): List<ToolGroup> {
    val out = ArrayList<ToolGroup>()
    for (part in parts) {
        val name = part.toolName()
        val last = out.lastOrNull()
        if (last != null && name != null && last.part.toolName() == name) {
            // Keep the NEWEST part (it carries the current status), but keep the
            // FIRST part's id as the group identity: the UI keys its row state on it,
            // and a changing key collapsed an expanded tool output on every call.
            out[out.size - 1] = last.copy(part = part, count = last.count + 1)
        } else {
            out.add(ToolGroup(id = part.id ?: "", part = part, count = 1))
        }
    }
    return out
}

/**
 * One consecutive-run of same-named tool calls. [id] is the identity of the FIRST
 * call in the run (stable while the run grows).
 */
data class ToolGroup(
    val id: String,
    val part: Part,
    val count: Int,
)
