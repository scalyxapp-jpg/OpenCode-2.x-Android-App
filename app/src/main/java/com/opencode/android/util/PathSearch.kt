package com.opencode.android.util

/**
 * `/find/file` wants a directory RELATIVE to the serve root — an absolute path
 * makes the server return [] (the "Open project search does nothing" bug).
 * Returns "." when [dir] IS the home root.
 */
fun relativeSearchDir(dir: String, home: String): String {
    val base = home.trimEnd('/').ifEmpty { "/" }
    val d = dir.trimEnd('/').ifEmpty { "/" }
    if (d == base) return "."
    // Outside home there is no valid relative base; fall back to the root.
    if (!d.startsWith("$base/")) return "."
    return d.removePrefix("$base/").ifBlank { "." }
}

/** True when [dir] is [home] or inside it (so a relative search is valid). */
fun isUnderHome(dir: String, home: String): Boolean {
    val base = home.trimEnd('/').ifEmpty { "/" }
    val d = dir.trimEnd('/').ifEmpty { "/" }
    return d == base || d.startsWith("$base/")
}

/**
 * Last segment of a path, or the whole path when it has no separator. Fourteen
 * call sites repeated `substringAfterLast('/').ifBlank { … }`; this is that,
 * with the trailing slash trimmed first so "/a/b/" yields "b".
 */
fun lastPathSegment(path: String): String {
    val trimmed = path.trimEnd('/')
    return trimmed.substringAfterLast('/').ifBlank { trimmed.ifBlank { path } }
}
