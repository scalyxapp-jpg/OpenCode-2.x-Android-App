package com.opencode.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    /** Sessions the decision was handed to, newest first as recorded. */
    fun targets(): List<String> = injectedInto
}

/** Whether a captured decision already reached an agent. */
enum class JournalStatus {
    NotInjected,
    Injected,
}

/** Status of one entry plus how many sessions carry it. */
data class JournalStatusInfo(
    val status: JournalStatus,
    val sessions: Int,
)

/**
 * Rules for the decision journal: what is kept, how it is grouped and how an export
 * looks. Pure, so the wording/formatting is unit-tested.
 */
object JournalPolicy {
    const val MAX_ENTRIES_PER_PROJECT = 200

    /**
     * Project key of the journal.
     *
     * `directory` wins, then `path`, else the GLOBAL key (""). The fallback matters: a
     * session opened from the offline cache has no directory, and returning null there was
     * the silent "decisions are not stored" bug. Global entries are visible everywhere.
     */
    fun projectKey(
        directory: String?,
        path: String?,
    ): String =
        directory?.takeIf { it.isNotBlank() }
            ?: path?.takeIf { it.isNotBlank() }
            ?: ""

    /**
     * Newest first, one project at a time (decisions are project-bound on purpose).
     *
     * `projectDir` semantics:
     *  - `null` or blank → **everything** (no project context: an offline session has neither
     *    directory nor path; hiding entries there looked like "not persisted").
     *  - a project → its own entries **plus** the global ones (captured without a resolvable
     *    project), so nothing the user captured can be missing from the list.
     */
    fun visible(
        entries: List<JournalEntry>,
        projectDir: String?,
        query: String = "",
    ): List<JournalEntry> {
        val scope = projectDir?.takeIf { it.isNotBlank() }
        return entries
            .filter { scope == null || it.projectDir == scope || it.projectDir.isBlank() }
            .filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
            .sortedByDescending { it.createdAt }
    }

    fun add(
        existing: List<JournalEntry>,
        entry: JournalEntry,
    ): List<JournalEntry> {
        val forProject = existing.filter { it.projectDir == entry.projectDir }
        val others = existing.filterNot { it.projectDir == entry.projectDir }
        val trimmed =
            (listOf(entry) + forProject.sortedByDescending { it.createdAt })
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

    /**
     * Captured decisions that were never handed to an agent.
     *
     * Feeds the ambient glance/tile count: "No decisions open" while the journal holds
     * entries nobody handed over was plainly wrong — a decision on the phone only is exactly
     * the kind of thing those surfaces exist for.
     */
    fun notInjectedCount(entries: List<JournalEntry>): Int = entries.count { it.injectedInto.isEmpty() }

    /**
     * Whether this decision already reached an agent, and how many sessions carry it.
     *
     * The list renders a status label from this: a captured decision that was handed over
     * must be distinguishable at a glance from one that is still only on the phone — the
     * same decision could otherwise look "done" while nobody knows about it.
     */
    fun status(entry: JournalEntry): JournalStatusInfo =
        if (entry.injectedInto.isEmpty()) {
            JournalStatusInfo(JournalStatus.NotInjected, 0)
        } else {
            JournalStatusInfo(JournalStatus.Injected, entry.injectedInto.size)
        }

    /**
     * The session a decision was handed to, shortened for a label ("…kwMwYk").
     *
     * `null` while it was never handed over or when several sessions carry it (then the
     * count is the label, not one arbitrary id).
     */
    fun targetHint(entry: JournalEntry): String? =
        entry.injectedInto
            .singleOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { "…" + it.takeLast(6) }

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
                    val when_ =
                        java.text
                            .SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH)
                            .format(java.util.Date(entry.createdAt))
                    val where =
                        entry.description
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " — $it" }
                            .orEmpty()
                    "- **$when_**$where: ${entry.text}"
                }
        return "## Decisions — $projectLabel\n\n$body\n"
    }
}

object DecisionJournalStore {
    private const val KEY = "journal.entries"

    /** Entries of a session/project that could not be resolved (offline, cached). */
    const val GLOBAL_PROJECT = ""

    private val _state = MutableStateFlow<List<JournalEntry>>(emptyList())
    val state: StateFlow<List<JournalEntry>> = _state

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loadedFromDisk = false

    fun init(context: Context) {
        ensureContext(context)
        // Load ONCE. A late init (AppStartup runs on IO) used to overwrite whatever the
        // user had already captured in this process — with an older or empty list.
        if (loadedFromDisk) return
        loadedFromDisk = true
        _state.value = decode(prefs()?.getString(KEY, null))
    }

    /**
     * Makes sure the store can WRITE, even when it is used before [init] finished.
     *
     * `persist()` silently did nothing while [appContext] was still null, which is why a
     * decision could show up in the list and be gone after an app restart. Callers that
     * have an application context (the ViewModel does) pass it here first.
     */
    fun ensureContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun add(entry: JournalEntry) = persist(JournalPolicy.add(_state.value, entry))

    fun remove(id: String) = persist(_state.value.filterNot { it.id == id })

    fun markInjected(
        id: String,
        sessionId: String,
    ) = persist(JournalPolicy.markInjected(_state.value, id, sessionId))

    private fun persist(entries: List<JournalEntry>) {
        _state.value = entries
        val raw =
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(JournalEntry.serializer()),
                entries,
            )
        val editor = prefs()?.edit()
        if (editor == null) {
            // Never swallow this: a decision the user captured must not vanish silently —
            // that was exactly the "decisions are not persisted" report.
            com.opencode.android.util.AppLog.e(
                com.opencode.android.util.APP_LOG_TAG,
                "journal: no context, entry NOT persisted (${entries.size} kept in memory)",
            )
            return
        }
        // commit() (not apply()) on IO: the journal is tiny and written only on user action,
        // and this way the entry is on disk before the call returns — a fast force-stop right
        // after saving could otherwise lose an apply()-based write.
        writeScope.launch {
            runCatching { editor.putString(KEY, raw).commit() }
        }
    }

    /** Own IO scope: persistence must not block the caller (the writer runs on the main thread). */
    private val writeScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )

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
