package com.opencode.android.util

/**
 * Canonical form of a project (and session) directory for identity comparisons.
 *
 * Why: the same directory arrives from several sources with different spelling — the
 * server's project payload may end with a slash, a locally added path may contain
 * doubled separators, and `LastSessionStore` stores whatever the session reported. A
 * plain string compare therefore missed matches, which is how a deleted project
 * "came back" on the next refresh and how one project could be listed twice.
 *
 * Rules: trim whitespace, collapse runs of `/`, drop a trailing `/`, and treat an
 * empty result as the filesystem root.
 */
fun normalizeProjectDir(dir: String?): String {
    val collapsed = dir?.trim().orEmpty().replace(Regex("/{2,}"), "/")
    val withoutTrailing = if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    return withoutTrailing.ifEmpty { "/" }
}

/** True when both arguments point at the same directory. */
fun sameProjectDir(
    a: String?,
    b: String?,
): Boolean = normalizeProjectDir(a) == normalizeProjectDir(b)
