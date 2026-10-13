package com.opencode.android.ui.explain

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
    }

    /**
     * Which agent explains [kind]: code is a `docs` question, everything else an `ask`.
     */
    fun agentFor(kind: Kind): String =
        when (kind) {
            Kind.CODE -> AGENT_DOCS
            Kind.TEXT, Kind.TOOL_OUTPUT -> AGENT_ASK
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
                Kind.CODE -> "Explain the following code briefly: what it does, and what to watch out for."
                Kind.TOOL_OUTPUT -> "Explain this command output briefly: what it means and whether it indicates a problem."
                Kind.TEXT -> "Explain the following passage briefly and plainly: what does it claim?"
            }
        val where = contextLabel?.takeIf { it.isNotBlank() }?.let { "\n(Context: $it)" }.orEmpty()
        return "$head$where\n\n---\n$trimmed\n---\n\nAnswer in a few sentences. Do not use tools, do not modify files, do not start any other work."
    }

    /** Truncation note so a cut snippet is not mistaken for the whole artifact. */
    fun truncated(snippet: String): Boolean = snippet.trim().length > MAX_SNIPPET_CHARS
}
