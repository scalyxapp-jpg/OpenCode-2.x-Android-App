package com.opencode.android.util

import java.util.Locale

/**
 * Tool-output presentation strings. Kept Android-free so they can be unit
 * tested (the truncation note used to be an inline German literal).
 */
object ToolText {
    /**
     * Note appended when the app caps tool output for DISPLAY.
     *
     * Wording follows the server/TUI ("… output truncated …", "omitted N characters" —
     * taken from the opencode binary), instead of the app's earlier invented sentence, so a
     * truncated row reads the same as in the TUI.
     */
    fun truncationSuffix(hiddenChars: Int): String =
        String.format(
            Locale.ENGLISH,
            "… output truncated … (omitted %d characters)",
            hiddenChars,
        )

    /**
     * Display name of a tool call, Title Case like the web's tool headers
     * ("Shell", "Read", "Edit"). Unknown tools keep their raw id so no
     * information is lost; an empty id becomes a neutral "Tool".
     *
     * Single source of truth: the chat's tool rows and the background
     * notification labels both use it.
     */
    fun toolTitle(tool: String?): String {
        val raw = tool?.trim().orEmpty()
        if (raw.isEmpty()) return "Tool"
        return when (raw.lowercase()) {
            "bash", "shell", "execute" -> "Shell"
            "read" -> "Read"
            "write" -> "Write"
            "edit", "multiedit" -> "Edit"
            "patch", "apply_patch" -> "Patch"
            "grep", "search", "ripgrep" -> "Search"
            "glob", "list", "ls" -> "List"
            "todowrite", "todo_write" -> "Todos"
            "task", "subagent", "delegate_task" -> "Subagent"
            "question" -> "Question"
            "skill" -> "Skill"
            "websearch", "webfetch" -> "Web"
            else -> raw
        }
    }

    /** Longest work detail a notification label keeps before it is cut. */
    private const val MAX_DETAIL = 48

    /**
     * Short label of one tool call for a notification: `Shell · ls -la`.
     *
     * A heads-up line has room for a glance, not for a full command line, so the
     * detail is truncated with a visible ellipsis.
     */
    fun notificationLabel(
        tool: String?,
        detail: String?,
    ): String {
        val title = toolTitle(tool)
        val work = detail?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (work.isEmpty()) return title
        val shown = if (work.length > MAX_DETAIL) work.take(MAX_DETAIL - 1) + "…" else work
        // The first "word" of a shell command is usually the verb; keep it short
        // for commands (a full `bash -lc '…'` line would swamp the notification).
        return "$title · $shown"
    }
}
