package com.opencode.android.util

/**
 * Short relative timestamps for list rows ("5m ago", "3h ago", "2d ago").
 *
 * Pure and time-injected so the boundaries are unit-testable; a session list is
 * exactly where a wrong boundary is most visible. Returns null beyond the
 * relative window so the caller can fall back to an absolute date instead of
 * showing "412d ago".
 */
object RelativeTime {
    const val MAX_RELATIVE_MS = 7L * 24 * 60 * 60 * 1_000

    fun label(epochMillis: Long, nowMillis: Long): String? {
        val diff = nowMillis - epochMillis
        return when {
            // Clock skew (a session "from the future") must not render as "-3m".
            diff < 60_000L -> "just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            diff < MAX_RELATIVE_MS -> "${diff / 86_400_000L}d ago"
            else -> null
        }
    }
}
