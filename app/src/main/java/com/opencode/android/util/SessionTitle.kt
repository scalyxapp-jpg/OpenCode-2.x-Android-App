package com.opencode.android.util

// Server-generated titles look like "New session - 2026-09-17T19:03:56.256Z".
private val ISO_SUFFIX = Regex("\\s*-\\s*\\d{4}-\\d{2}-\\d{2}T\\S+$")

/**
 * Human-friendly session title for headers/tabs: drops a trailing
 * " - <ISO timestamp>" so the UI never shows a clipped "New session -".
 * Returns the original when stripping would leave nothing.
 */
fun sessionDisplayTitle(title: String): String {
    // The regex consumes the " - " separator, so no trailing dash remains; a
    // title that genuinely ends in "-" (not a timestamp) is left untouched.
    val cleaned = title.replace(ISO_SUFFIX, "").trim()
    return cleaned.ifBlank { title }
}
