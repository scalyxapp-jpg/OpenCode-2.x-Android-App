package com.opencode.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One prompt waiting in the offline outbox.
 *
 * @param id client-side id (also the optimistic message id once it is sent)
 * @param sessionId session it belongs to — the outbox is per session and flushes when
 *   that session is open (a prompt in a session you are not looking at would otherwise
 *   start a turn behind your back)
 * @param text the prompt as typed
 * @param agent/selection the selection at typing time, so a later flush does not
 *   silently switch models
 * @param attempts finished delivery attempts
 * @param lastAttemptAt when the last attempt ran (drives the backoff)
 * @param lastError last failure text, shown on the row so the user knows why it waits
 */
@kotlinx.serialization.Serializable
data class OutboxItem(
    val id: String,
    val sessionId: String,
    val text: String,
    val agent: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val createdAt: Long = 0L,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null,
)

/** Pure JSON codec so the persistence format is testable without Android. */
object OutboxCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(items: List<OutboxItem>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(OutboxItem.serializer()), items)

    fun decode(raw: String?): List<OutboxItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(OutboxItem.serializer()),
                raw,
            )
        } catch (e: Exception) {
            // A corrupt entry must not brick the app: an unreadable outbox is an
            // empty outbox (the prompts themselves live in the composer/cache).
            emptyList()
        }
    }
}

/**
 * Persists the offline outbox.
 *
 * Deliberately tiny (SharedPreferences + a pure codec): the behaviour that matters —
 * what may be queued, when to retry — lives in [OutboxPolicy], which is unit-tested.
 */
object OutboxStore {
    private const val KEY = "outbox.items"

    private val _state = MutableStateFlow<List<OutboxItem>>(emptyList())
    val state: StateFlow<List<OutboxItem>> = _state

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _state.value = OutboxCodec.decode(prefs()?.getString(KEY, null))
    }

    /** Items of one session, oldest first (the order they were typed in). */
    fun forSession(sessionId: String): List<OutboxItem> = _state.value.filter { it.sessionId == sessionId }

    fun add(item: OutboxItem) {
        val items = _state.value + item
        val trimmed =
            if (items.count { it.sessionId == item.sessionId } > OutboxPolicy.MAX_ITEMS_PER_SESSION) {
                // Per-session cap: drop the oldest of THIS session only.
                val keep =
                    items
                        .filter { it.sessionId == item.sessionId }
                        .takeLast(OutboxPolicy.MAX_ITEMS_PER_SESSION)
                        .map { it.id }
                        .toSet()
                items.filter { it.sessionId != item.sessionId || it.id in keep }
            } else {
                items
            }
        persist(trimmed)
    }

    fun remove(id: String) {
        persist(_state.value.filterNot { it.id == id })
    }

    fun clearSession(sessionId: String) {
        persist(_state.value.filterNot { it.sessionId == sessionId })
    }

    /** Records a failed attempt (and the reason) so the row can show it. */
    fun markAttempt(
        id: String,
        nowMs: Long,
        error: String?,
    ) {
        persist(
            _state.value.map {
                if (it.id == id) {
                    it.copy(attempts = it.attempts + 1, lastAttemptAt = nowMs, lastError = error)
                } else {
                    it
                }
            },
        )
    }

    private fun persist(items: List<OutboxItem>) {
        _state.value = items
        prefs()?.edit()?.putString(KEY, OutboxCodec.encode(items))?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences("opencode.outbox", Context.MODE_PRIVATE)
}
