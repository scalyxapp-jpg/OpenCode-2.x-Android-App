package com.opencode.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One prompt the user already sent.
 *
 * @param text the prompt exactly as it went out
 * @param sessionId session it was sent in (the TUI keeps history per session)
 * @param at when it was sent
 */
@kotlinx.serialization.Serializable
data class PromptHistoryEntry(
    val text: String,
    val sessionId: String,
    val at: Long,
)

/**
 * Rules for the prompt history (TUI parity: the TUI's composer recalls previous prompts
 * with the arrow keys; on a touch device the same list is opened from the composer).
 *
 * Kept pure so the rules are unit-tested: what is recorded, what counts as a duplicate,
 * how the list is capped and how it is filtered for display.
 */
object PromptHistoryPolicy {
    /** Entries kept in total (newest win). */
    const val MAX_ENTRIES = 100

    /**
     * Records a sent prompt.
     *
     * Blank text is ignored, and a prompt that is already the newest entry (same text,
     * same session) is moved to the top instead of being listed twice — re-sending the
     * last prompt is common and should not fill the history with clones.
     */
    fun record(
        existing: List<PromptHistoryEntry>,
        text: String,
        sessionId: String,
        at: Long,
    ): List<PromptHistoryEntry> {
        val clean = text.trim()
        if (clean.isEmpty()) return existing
        val withoutSame = existing.filterNot { it.text == clean && it.sessionId == sessionId }
        return (listOf(PromptHistoryEntry(text = clean, sessionId = sessionId, at = at)) + withoutSame)
            .take(MAX_ENTRIES)
    }

    /**
     * The list as shown: newest first, one row per distinct prompt (a prompt used in
     * several sessions appears once), optionally limited to one session.
     */
    fun visible(
        entries: List<PromptHistoryEntry>,
        sessionId: String?,
        query: String,
    ): List<PromptHistoryEntry> {
        val needle = query.trim()
        return entries
            .asSequence()
            .filter { sessionId == null || it.sessionId == sessionId }
            .filter { needle.isEmpty() || it.text.contains(needle, ignoreCase = true) }
            .distinctBy { it.text }
            .sortedByDescending { it.at }
            .toList()
    }
}

/** Pure JSON codec so the persistence format is testable without Android. */
object PromptHistoryCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(entries: List<PromptHistoryEntry>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PromptHistoryEntry.serializer()),
            entries,
        )

    fun decode(raw: String?): List<PromptHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(PromptHistoryEntry.serializer()),
                raw,
            )
        } catch (e: Exception) {
            // A corrupt history is an empty history, never a crash on startup.
            emptyList()
        }
    }
}

/** Persists the prompt history (SharedPreferences + the pure codec above). */
object PromptHistoryStore {
    private const val KEY = "history.entries"

    private val _state = MutableStateFlow<List<PromptHistoryEntry>>(emptyList())
    val state: StateFlow<List<PromptHistoryEntry>> = _state

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _state.value = PromptHistoryCodec.decode(prefs()?.getString(KEY, null))
    }

    fun record(
        text: String,
        sessionId: String,
        at: Long = System.currentTimeMillis(),
    ) {
        persist(PromptHistoryPolicy.record(_state.value, text, sessionId, at))
    }

    fun clear() {
        persist(emptyList())
    }

    private fun persist(entries: List<PromptHistoryEntry>) {
        _state.value = entries
        prefs()?.edit()?.putString(KEY, PromptHistoryCodec.encode(entries))?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences("opencode.history", Context.MODE_PRIVATE)
}
