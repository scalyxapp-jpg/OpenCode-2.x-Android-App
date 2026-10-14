package com.opencode.android.ui.explain

import com.opencode.android.domain.Part
import com.opencode.android.ui.inputField
import com.opencode.android.ui.toolName

/**
 * Rules for the explain mode: a side question about ONE artifact (a message, a diff, a
 * tool output) that must not pollute the running conversation.
 *
 * The dedicated agents are used for it (`ask` answers questions, `docs` explains code),
 * the prompt carries only the marked snippet — never the whole history — and the whole
 * exchange happens in its own session.
 */
object ExplainPolicy {
    /** Agents that fit this use (they exist on the server, see `GET /api/agent`). */
    const val AGENT_ASK = "ask"
    const val AGENT_DOCS = "docs"

    /** Snippet cap: the point is a cheap side question, not a second full context. */
    const val MAX_SNIPPET_CHARS = 6_000

    enum class Kind {
        /** A prose answer or reasoning. */
        TEXT,

        /** A code block, a diff, a file excerpt. */
        CODE,

        /** Raw tool output (shell output, test log, …). */
        TOOL_OUTPUT,

        /** A shell command that was run (the command itself, with its output). */
        COMMAND,

        /** Any other tool call: what the agent did and why. */
        TOOL_CALL,
    }

    /** What to explain about one artifact: its kind, the text and where it came from. */
    data class Target(
        val kind: Kind,
        val snippet: String,
        val label: String,
    )

    /**
     * Picks what to explain about a tool part - the entry point of "explain this".
     *
     * The three artifacts a reader wants to learn from, in the order they are usually the most
     * interesting: the CODE an edit wrote, the COMMAND a shell call ran, or the TOOL CALL itself.
     * `null` when there is nothing worth explaining (a call with no command, no diff and no
     * output yet).
     */
    fun target(part: Part): Target? {
        val tool = part.toolName().orEmpty()
        val path = part.inputField("filePath") ?: part.inputField("path")
        val diff =
            com.opencode.android.ui
                .toolDiffText(part)
        val command = part.inputField("command")
        val output = part.outputTextForExplain()

        if (!diff.isNullOrBlank()) {
            return Target(
                kind = Kind.CODE,
                snippet = diff,
                label = "code written by ${tool.ifBlank { "the agent" }}" + (path?.let { ": $it" } ?: ""),
            )
        }
        if (!command.isNullOrBlank()) {
            val body =
                buildString {
                    append("$ ").append(command)
                    if (!output.isNullOrBlank()) {
                        append("\n\n--- output ---\n").append(output)
                    }
                }
            return Target(kind = Kind.COMMAND, snippet = body, label = "shell command")
        }
        val params =
            com.opencode.android.ui
                .toolInputParams(part)
                .joinToString(", ") { (key, value) -> "$key=$value" }
        // Nothing to learn from a bare tool name: a running call whose input and output are not
        // there yet gets no "explain this" at all (found by the test - "tool: read" explains
        // nothing and would only produce a generic answer).
        if (path.isNullOrBlank() && params.isBlank() && output.isNullOrBlank()) return null
        val body =
            buildString {
                append("tool: ").append(tool.ifBlank { "unknown" })
                path?.takeIf { it.isNotBlank() }?.let { append("\npath: ").append(it) }
                if (params.isNotBlank()) append("\n").append(params)
                if (!output.isNullOrBlank()) append("\n\n--- result ---\n").append(output)
            }
        return Target(kind = Kind.TOOL_CALL, snippet = body, label = "tool call: ${tool.ifBlank { "unknown" }}")
    }

    /**
     * Which agent explains [kind]: code is a `docs` question, everything else an `ask`.
     */
    fun agentFor(kind: Kind): String =
        when (kind) {
            Kind.CODE -> AGENT_DOCS
            Kind.TEXT, Kind.TOOL_OUTPUT, Kind.COMMAND, Kind.TOOL_CALL -> AGENT_ASK
        }

    /**
     * The scoped prompt: the snippet plus ONE instruction that keeps the answer short and
     * prevents the agent from starting work of its own (it must not edit anything).
     */
    fun prompt(
        kind: Kind,
        snippet: String,
        contextLabel: String? = null,
    ): String {
        val trimmed = snippet.trim().take(MAX_SNIPPET_CHARS)
        val head =
            when (kind) {
                Kind.CODE -> {
                    "Explain the following code briefly: what it does, and what to watch out for."
                }

                Kind.TOOL_OUTPUT -> {
                    "Explain this command output briefly: what it means and whether it indicates a problem."
                }

                Kind.TEXT -> {
                    "Explain the following passage briefly and plainly: what does it claim?"
                }

                // "So I can learn from it": a command explanation names the parts, the reason and
                // the risk - reading a command is a skill, not a lookup.
                Kind.COMMAND -> {
                    "Explain this shell command so I can learn from it: what each part and flag does, " +
                        "why it is needed here, what could go wrong, and what a safer or more common " +
                        "variant would look like. Mention the output if it says something about the result."
                }

                Kind.TOOL_CALL -> {
                    "Explain this tool call so I can learn to read such calls: what it does, why an " +
                        "agent would use it at this point, what its arguments mean, and what to watch out for."
                }
            }
        val where = contextLabel?.takeIf { it.isNotBlank() }?.let { "\n(Context: $it)" }.orEmpty()
        return "$head$where\n\n---\n$trimmed\n---\n\nAnswer in a few sentences. Do not use tools, do not modify files, do not start any other work."
    }

    /** Truncation note so a cut snippet is not mistaken for the whole artifact. */
    fun truncated(snippet: String): Boolean = snippet.trim().length > MAX_SNIPPET_CHARS
}

/**
 * The visible output of a tool part, capped.
 *
 * Uses the same extraction the chat rows use (`toolOutputText`), so an explanation always
 * describes what the user is actually looking at.
 */
internal fun com.opencode.android.domain.Part.outputTextForExplain(): String? =
    com.opencode.android.ui
        .toolOutputText(this)
        ?.take(4_000)
