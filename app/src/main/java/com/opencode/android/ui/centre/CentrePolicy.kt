package com.opencode.android.ui.centre

import androidx.compose.runtime.Immutable
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionQuestion

/** Why a session needs the user right now. */
enum class NeedKind {
    /** The agent is waiting for a permission decision. */
    Permission,

    /** The agent asked a question (V2 form) and cannot continue without an answer. */
    Question,

    /** A send that failed offline is still waiting in the outbox. */
    Outbox,
}

/** One item of "needs you" — always tied to a session the user can open. */
@Immutable
data class CentreNeed(
    val kind: NeedKind,
    val sessionId: String,
    val title: String,
    val detail: String? = null,
)

/** One session row of the cockpit. */
@Immutable
data class CentreRow(
    val sessionId: String,
    val title: String,
    val directory: String? = null,
    val running: Boolean = false,
    val retrying: Boolean = false,
    val cost: Double? = null,
    /** Unix ms of the last activity, for the "newest first" order. */
    val updatedAt: Long = 0L,
)

/**
 * Pure rules behind the cockpit screen.
 *
 * Kept free of Android and of the network so the ordering (what the user sees first) and
 * the "needs you" list can be unit-tested — they are the whole value of the screen.
 */
object CentrePolicy {
    /**
     * Rows in display order: whatever is RUNNING first (that is what the cockpit is for),
     * then everything else newest-first. A stable partition keeps the server's order
     * inside both groups.
     */
    fun sort(rows: List<CentreRow>): List<CentreRow> {
        val (busy, idle) = rows.partition { it.running || it.retrying }
        return busy.sortedByDescending { it.updatedAt } + idle.sortedByDescending { it.updatedAt }
    }

    /**
     * Session ids that a "stop everything" must interrupt, in DISPLAY order.
     *
     * Sorted internally so the order holds for every caller, not only for the screen that
     * happens to pass an already sorted list.
     */
    fun runningIds(rows: List<CentreRow>): List<String> = sort(rows).filter { it.running || it.retrying }.map { it.sessionId }

    /** Sessions to consider active: everything the server reported with a live status. */
    fun rows(
        sessions: List<Session>,
        statuses: Map<String, com.opencode.android.domain.SessionStatus>,
    ): List<CentreRow> =
        sort(
            sessions.map { session ->
                val status = statuses[session.id]?.type
                CentreRow(
                    sessionId = session.id,
                    title = session.title?.takeIf { it.isNotBlank() } ?: session.id,
                    directory = session.directory ?: session.path,
                    running = status == "busy",
                    retrying = status == "retry",
                    cost = session.cost,
                    updatedAt = session.time?.updated ?: session.time?.created ?: 0L,
                )
            },
        )

    /**
     * Everything that is waiting for the user, **grouped by session**.
     *
     * One row per waiting session, never one per item: the server returned five pending
     * forms in one session, which rendered as five near-identical rows that named their
     * first FIELD ("Num", "Bool", …) instead of what was being asked. The session is the
     * unit the user can act on — tapping opens it, and the session's own screen shows the
     * pending cards.
     *
     * Order: blocked turns first (a permission or an open form stops the agent RIGHT NOW),
     * then sends still waiting in the outbox.
     */
    fun needs(
        permissions: List<PermissionRequest>,
        questions: List<SessionQuestion>,
        outboxCounts: Map<String, Int>,
        sessionTitles: Map<String, String> = emptyMap(),
    ): List<CentreNeed> {
        fun label(sessionId: String): String = sessionTitles[sessionId]?.takeIf { it.isNotBlank() } ?: "Session …${sessionId.takeLast(6)}"

        fun counts(
            count: Int,
            singular: String,
            plural: String,
        ) = "$count $singular" + if (count == 1) "" else "s"

        val permsBySession =
            permissions
                .filter { !it.sessionId.isNullOrBlank() }
                .distinctBy { it.id ?: it.hashCode() }
                .groupBy { it.sessionId!! }
        val formsBySession =
            questions
                .filter { !it.sessionId.isNullOrBlank() }
                .distinctBy { it.id }
                .groupBy { it.sessionId!! }

        val sessionIds =
            (permsBySession.keys + formsBySession.keys + outboxCounts.filterValues { it > 0 }.keys).distinct()

        val needs =
            sessionIds.map { sessionId ->
                val sessionLabel = label(sessionId)
                val perms = permsBySession[sessionId].orEmpty()
                val forms = formsBySession[sessionId].orEmpty()
                val waiting = outboxCounts[sessionId] ?: 0
                val kind =
                    when {
                        perms.isNotEmpty() -> NeedKind.Permission
                        forms.isNotEmpty() -> NeedKind.Question
                        else -> NeedKind.Outbox
                    }
                // A single question reads best as itself; with several, the session is the
                // headline and the count is the detail.
                val title =
                    when {
                        perms.isNotEmpty() -> {
                            perms.first().title?.takeIf { it.isNotBlank() }
                                ?: perms.first().permission?.takeIf { it.isNotBlank() }
                                ?: sessionLabel
                        }

                        forms.size == 1 -> {
                            forms.first().title?.takeIf { it.isNotBlank() }
                                ?: forms
                                    .first()
                                    .questions
                                    .firstOrNull()
                                    ?.question
                                    ?.takeIf { it.isNotBlank() }
                                ?: sessionLabel
                        }

                        else -> {
                            sessionLabel
                        }
                    }
                val detail =
                    listOfNotNull(
                        sessionLabel.takeIf { it != title },
                        counts(perms.size, "permission", "permissions").takeIf { perms.isNotEmpty() },
                        counts(forms.size, "open form", "open forms").takeIf { forms.isNotEmpty() },
                        counts(waiting, "message waiting", "messages waiting").takeIf { waiting > 0 },
                    ).joinToString(" · ")
                CentreNeed(kind = kind, sessionId = sessionId, title = title, detail = detail)
            }
        return needs.sortedBy { it.kind.ordinal }
    }

    /**
     * Drops the needs whose session no longer exists on the server.
     *
     * Proven live: a deleted session left five pending forms behind, so "Needs you" offered
     * entries that opened a 404. The session is the only thing a need can act on — without it
     * the row is a dead end and must not be shown. [missing] is filled by the caller, which
     * is the only place that can ask the server.
     */
    fun withoutMissing(
        needs: List<CentreNeed>,
        missing: Set<String>,
    ): List<CentreNeed> = needs.filterNot { it.sessionId in missing }

    /**
     * Directories to ask for pending work, most recent first.
     *
     * `GET /api/form` and `GET /api/permission/request` are scoped per directory, so asking
     * only the first session's directory hid every other project — the server returned five
     * pending forms for one directory while another project's items stayed invisible.
     */
    fun directoriesToScan(
        sessions: List<Session>,
        limit: Int = 5,
    ): List<String> =
        sessions
            .mapNotNull { it.directory ?: it.path }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)

    /** `$1.23` — the same shape the glance surfaces use. */
    fun costLabel(cost: Double?): String? = cost?.let { "$" + String.format(java.util.Locale.ENGLISH, "%.2f", it) }

    /** How many rows a section lists before it folds (the list is a glance, not a report). */
    const val PREVIEW_ROWS = 5
}
