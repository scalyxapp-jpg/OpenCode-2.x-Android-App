package com.opencode.android.ui

import com.opencode.android.domain.Part

/**
 * What the live status row is currently showing.
 *
 * The row used to know only "waiting" and "generating", so a long turn read as
 * "Waiting for the model…" even while tools ran and files were being edited. The
 * detail below is derived from the live stream state so the user can see WHAT is
 * running.
 */
enum class LiveStatusKind {
    DISCONNECTED,
    COMPACTING,
    TOOL,
    THINKING,
    WRITING,
    WAITING,
}

/**
 * The activity a status row describes.
 *
 * @param tool tool name of the RUNNING call (e.g. `read`, `bash`), when [kind] is [LiveStatusKind.TOOL]
 * @param detail the call's primary argument (file path or command), when known
 * @param toolCount how many tool calls the current step already made
 * @param streamingChars characters streamed into the answer of this step
 */
data class LiveStatus(
    val kind: LiveStatusKind,
    val tool: String? = null,
    val detail: String? = null,
    val toolCount: Int = 0,
    val streamingChars: Int = 0,
    /** True while the label should animate dots (i.e. something is in progress). */
    val animated: Boolean = true,
    /** 1-based step of the running turn (0 when the server sends none). */
    val step: Int = 0,
)

private val RUNNING_TOOL_STATES = setOf("running", "pending", "in_progress")

/**
 * Derives the status from the live state.
 *
 * Order matters:
 *  1. a dead connection wins over everything (the other flags keep their last value
 *     when events stop arriving, which made a stalled turn look like "thinking"),
 *  2. a compaction, then a RUNNING tool call (the concrete activity),
 *  3. reasoning, then waiting for the first token, else writing the answer.
 *
 * Pure and Android-free so the precedence is unit-tested.
 */
fun liveStatus(
    connected: Boolean,
    compacting: Boolean,
    thinking: Boolean,
    waiting: Boolean,
    parts: List<Part>,
    streamingChars: Int = 0,
    step: Int = 0,
): LiveStatus {
    if (!connected) {
        return LiveStatus(LiveStatusKind.DISCONNECTED, animated = true, step = step)
    }
    if (compacting) {
        return LiveStatus(LiveStatusKind.COMPACTING, toolCount = parts.size, step = step)
    }
    val running =
        parts.lastOrNull { part ->
            toolStatus(part)?.trim()?.lowercase() in RUNNING_TOOL_STATES
        }
    if (running != null) {
        return LiveStatus(
            kind = LiveStatusKind.TOOL,
            tool = running.toolName(),
            detail = extractToolDetail(running) ?: running.title,
            toolCount = parts.size,
            step = step,
        )
    }
    if (thinking) {
        return LiveStatus(LiveStatusKind.THINKING, toolCount = parts.size, step = step)
    }
    if (waiting) {
        return LiveStatus(LiveStatusKind.WAITING, toolCount = parts.size, step = step)
    }
    return LiveStatus(
        kind = LiveStatusKind.WRITING,
        toolCount = parts.size,
        streamingChars = streamingChars,
        step = step,
    )
}
