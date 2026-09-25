package com.opencode.android.ui

/**
 * One-letter badge for a changed file, matching the web Review panel
 * (A added, M modified, D deleted, R renamed).
 *
 * Pure and top-level so it can be unit-tested without loading Compose.
 */
fun vcsStatusBadge(status: String): String = when (status.lowercase()) {
    "added", "untracked" -> "A"
    "modified" -> "M"
    "deleted" -> "D"
    "renamed" -> "R"
    else -> status.take(1).uppercase()
}
