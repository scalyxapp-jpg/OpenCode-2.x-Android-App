package com.opencode.android.ui.session

import com.opencode.android.domain.BUILTIN_SOURCE
import com.opencode.android.domain.CommandEntry

/** A "/name args" line the user typed, resolved against the known commands. */
data class SlashCommand(
    val name: String,
    val arguments: String,
)

/**
 * Parses a composer line into a SERVER slash command, or null when it is ordinary
 * text (or a client-side built-in such as `/compact`, which the app handles
 * itself).
 *
 * The TUI runs `/name args` through the server's command endpoint, which expands
 * the command's template into a prompt; typing the same line as a normal message
 * does NOT expand it — which is why commands appeared to do nothing.
 *
 * Only names the server actually offers are treated as commands, so "/etc/hosts"
 * or an unknown "/foo" is still sent as an ordinary message.
 */
fun parseSlashCommand(
    text: String,
    commands: List<CommandEntry>,
): SlashCommand? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return null
    val body = trimmed.removePrefix("/")
    val name = body.substringBefore(' ').trim()
    if (name.isEmpty()) return null
    // Built-ins own their handling on the client; everything else must be a
    // command the server listed.
    val server = commands.firstOrNull { it.name == name && it.source != BUILTIN_SOURCE } ?: return null
    return SlashCommand(server.name, body.substringAfter(' ', "").trim())
}

/**
 * The commands the "/" picker offers for [query]: prefix matches first (what the
 * TUI/web match on), then substring matches, so a partially typed name still
 * finds its command without hiding the closer ones.
 */
fun filterCommands(
    commands: List<CommandEntry>,
    query: String,
): List<CommandEntry> {
    val q = query.lowercase()
    if (q.isBlank()) return commands
    val prefix = commands.filter { it.name.lowercase().startsWith(q) }
    val rest = commands.filterNot { it.name.lowercase().startsWith(q) }
        .filter { it.name.lowercase().contains(q) }
    return prefix + rest
}

/**
 * The CLIENT built-in a typed line refers to ("/compact" -> "compact"), or null.
 *
 * Picking a built-in from the "/" list always ran it locally, but TYPING the same
 * line fell through to the prompt path and posted "/compact" to the model as
 * literal text.
 */
fun parseBuiltinCommand(
    text: String,
    commands: List<CommandEntry>,
): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return null
    val name = trimmed.removePrefix("/").substringBefore(' ').trim()
    if (name.isEmpty()) return null
    return commands.firstOrNull { it.name == name && it.source == BUILTIN_SOURCE }?.name
}
