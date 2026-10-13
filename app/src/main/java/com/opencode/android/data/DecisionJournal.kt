package com.opencode.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A decision captured outside the chat (meeting, issue, walking) that should be binding
 * for a project's agent work.
 */
@kotlinx.serialization.Serializable
data class JournalEntry(
    val id: String,
    val projectDir: String,
    val text: String,
    val description: String? = null,
    val createdAt: Long = 0L,
    /** Session ids the decision was handed to (the row shows a badge from it). */
    val injectedInto: List<String> = emptyList(),
) {
    /** Handed over at least once? */
    fun sessionIdHint(): List<String> = injectedInto
}

/**
 * Rules for the decision journal: what is kept, how it is grouped and how an export
 * looks. Pure, so the wording/formatting is unit-tested.
 */
object JournalPolicy {
    const val MAX_ENTRIES_PER_PROJECT = 200

    /** Newest first, one project at a time (decisions are project-bound on purpose). */
    fun visible(
        entries: List<JournalEntry>,
        projectDir: String?,
        query: String = "",
    ): List<JournalEntry> =
        entries
            .filter { projectDir == null || it.projectDir == projectDir }
            .filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
            .sortedByDescending { it.createdAt }

    fun add(
        existing: List<JournalEntry>,
        entry: JournalEntry,
    ): List<JournalEntry> {
        val forProject = existing.filter { it.projectDir == entry.projectDir }
        val others = existing.filterNot { it.projectDir == entry.projectDir }
        val trimmed = (listOf(entry) + forProject.sortedByDescending { it.createdAt })
            .take(MAX_ENTRIES_PER_PROJECT)
        return others + trimmed
    }

    fun markInjected(
        entries: List<JournalEntry>,
        id: String,
        sessionId: String,
    ): List<JournalEntry> =
        entries.map {
            if (it.id == id && sessionId !in it.injectedInto) {
                it.copy(injectedInto = it.injectedInto + sessionId)
            } else {
                it
            }
        }

    /** Markdown export ("decisions of this week"), oldest first so it reads like a log. */
    fun export(
        entries: List<JournalEntry>,
        projectLabel: String,
    ): String {
        if (entries.isEmpty()) return ""
        val body =
            entries
                .sortedBy { it.createdAt }
                .joinToString("\n\n") { entry ->
                    val when_ = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH)
                        .format(java.util.Date(entry.createdAt))
                    val where = entry.description?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                    "- **$when_**$where: ${entry.text}"
                }
        return "## Decisions — $projectLabel\n\n$body\n"
    }
}

object DecisionJournalStore {
    private const val KEY = "journal.entries"

    private val _state = MutableStateFlow<List<JournalEntry>>(emptyList())
    val state: StateFlow<List<JournalEntry>> = _state

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _state.value = decode(prefs()?.getString(KEY, null))
    }

    fun add(entry: JournalEntry) = persist(JournalPolicy.add(_state.value, entry))

    fun remove(id: String) = persist(_state.value.filterNot { it.id == id })

    fun markInjected(
        id: String,
        sessionId: String,
    ) = persist(JournalPolicy.markInjected(_state.value, id, sessionId))

    private fun persist(entries: List<JournalEntry>) {
        _state.value = entries
        val raw = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(JournalEntry.serializer()),
            entries,
        )
        prefs()?.edit()?.putString(KEY, raw)?.apply()
    }

    private fun decode(raw: String?): List<JournalEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(JournalEntry.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }

    private fun prefs() = appContext?.getSharedPreferences("opencode.journal", Context.MODE_PRIVATE)
}
