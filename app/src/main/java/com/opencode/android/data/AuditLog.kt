package com.opencode.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Kinds of consequential actions the audit log records.
 *
 * The point is not to log everything (the server already logs the model) but to keep a
 * local, exportable answer to "what did I do to which session, and when" — the actions
 * that change state or spend money. Nothing here is captured automatically.
 */
@Serializable
enum class AuditKind {
    /** One session was stopped by hand. */
    Stop,

    /** The emergency stop stopped several sessions at once. */
    StopAll,

    /** A permission request was approved. */
    Approve,

    /** A permission request was denied. */
    Deny,

    /** File changes were reverted. */
    Revert,

    /** A session was branched from a message. */
    Fork,

    /** A decision/inbox entry was handed to the agent. */
    Inject,

    /** An MCP server was connected or disconnected. */
    Mcp,

    /** The session guard was adopted or dropped. */
    Guard,

    /** A prompt left the outbox/failed queue. */
    Send,

    /** The context was compacted (asked for by the user, from the Centre's insights). */
    Compact,
}

/** One recorded action. Newest first in the store. */
@Serializable
data class AuditEvent(
    val id: String,
    /** `System.currentTimeMillis()` of the action. */
    val at: Long,
    val kind: AuditKind,
    /** Session the action belongs to, when it had one (the emergency stop has none). */
    val sessionId: String? = null,
    /** Short human line, e.g. the session title or the tool a permission was for. */
    val title: String,
    /** Optional second line (a path, a reason, a count). */
    val detail: String? = null,
)

/**
 * Pure rules of the audit log: ordering, the cap and the exported text. Kept free of
 * Android so the behaviour is unit-testable.
 */
object AuditPolicy {
    /**
     * Upper bound of kept events.
     *
     * The log lives in SharedPreferences and is rewritten on every record, so it must not
     * grow without bound; 400 entries are weeks of hand-made decisions and stay tiny.
     */
    const val MAX_EVENTS = 400

    /** Newest first — the list is read top-down, so the newest event is the first row. */
    fun add(
        events: List<AuditEvent>,
        event: AuditEvent,
    ): List<AuditEvent> = (listOf(event) + events).take(MAX_EVENTS)

    /** Short label of a kind, as the list and the export render it. */
    fun label(kind: AuditKind): String =
        when (kind) {
            AuditKind.Stop -> "Stopped"
            AuditKind.StopAll -> "Stopped all"
            AuditKind.Approve -> "Approved"
            AuditKind.Deny -> "Denied"
            AuditKind.Revert -> "Reverted"
            AuditKind.Fork -> "Forked"
            AuditKind.Inject -> "Handed to agent"
            AuditKind.Mcp -> "MCP"
            AuditKind.Guard -> "Guard"
            AuditKind.Send -> "Sent"
            AuditKind.Compact -> "Compacted"
        }

    /** One-line summary for the section header, e.g. "12 events · 3 stops". */
    fun summary(events: List<AuditEvent>): String {
        if (events.isEmpty()) return "No events yet"
        val stops = events.count { it.kind == AuditKind.Stop || it.kind == AuditKind.StopAll }
        val base = "${events.size} event" + if (events.size == 1) "" else "s"
        return if (stops > 0) "$base · $stops stop" + if (stops == 1) "" else "s" else base
    }

    /** The whole log as Markdown, for sharing/export. */
    fun export(events: List<AuditEvent>): String {
        if (events.isEmpty()) return "## Audit\n\nNo events recorded.\n"
        val body =
            events.joinToString("\n") { event ->
                val when_ =
                    java.text
                        .SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH)
                        .format(java.util.Date(event.at))
                val detail =
                    event.detail
                        ?.takeIf { it.isNotBlank() }
                        ?.let { " — $it" }
                        .orEmpty()
                "- **$when_** · ${label(event.kind)}: ${event.title}$detail"
            }
        return "## Audit\n\n$body\n"
    }

    /** How many events happened on the same local day as [dayStart]…[dayEnd]. */
    fun countBetween(
        events: List<AuditEvent>,
        fromMs: Long,
        toMs: Long,
    ): Int = events.count { it.at in fromMs until toMs }
}

/**
 * The local audit log.
 *
 * Follows [DecisionJournalStore]: one SharedPreferences key, a StateFlow for the UI, and a
 * `commit()` on an IO scope so an entry survives a force-stop right after the action.
 * Recording is a no-op while the "Audit log" switch in Settings is off — a switchable
 * feature must be switchable at the source, not only in the UI.
 */
object AuditLogStore {
    private const val KEY = "audit.events"
    private const val PREFS = "opencode.audit"

    private val _state = MutableStateFlow<List<AuditEvent>>(emptyList())
    val state: StateFlow<List<AuditEvent>> = _state

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loadedFromDisk = false

    fun init(context: Context) {
        ensureContext(context)
        // Load ONCE: a late init (AppStartup runs on IO) must not overwrite events the user
        // already produced in this process.
        if (loadedFromDisk) return
        loadedFromDisk = true
        _state.value = decode(prefs()?.getString(KEY, null))
    }

    /** Makes sure the store can WRITE even before [init] finished. */
    fun ensureContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Records a consequential action. Never throws into the caller and never blocks:
     * every call site is in the middle of an action the user already started.
     */
    fun record(
        kind: AuditKind,
        title: String,
        sessionId: String? = null,
        detail: String? = null,
    ) {
        if (!AppSettingsStore.state.value.auditLogEnabled) return
        runCatching {
            val event =
                AuditEvent(
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    at = System.currentTimeMillis(),
                    kind = kind,
                    sessionId = sessionId,
                    title = title,
                    detail = detail,
                )
            persist(AuditPolicy.add(_state.value, event))
        }.onFailure {
            com.opencode.android.util.AppLog.e(
                com.opencode.android.util.APP_LOG_TAG,
                "audit: record failed (${it.message})",
            )
        }
    }

    fun clear() = persist(emptyList())

    private fun persist(events: List<AuditEvent>) {
        _state.value = events
        val raw =
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(AuditEvent.serializer()),
                events,
            )
        val editor = prefs()?.edit()
        if (editor == null) {
            com.opencode.android.util.AppLog.e(
                com.opencode.android.util.APP_LOG_TAG,
                "audit: no context, event NOT persisted (${events.size} kept in memory)",
            )
            return
        }
        writeScope.launch {
            runCatching { editor.putString(KEY, raw).commit() }
        }
    }

    /** Own IO scope: persistence must not block the caller (actions run on the main thread). */
    private val writeScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )

    private fun decode(raw: String?): List<AuditEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AuditEvent.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
