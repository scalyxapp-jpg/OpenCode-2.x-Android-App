package com.opencode.android.data

/**
 * Outcome of a central action.
 *
 * Every surface (Centre, notification, widget, chat) gets the same three answers, so a caller
 * never has to know why something did or did not happen.
 */
sealed interface ActionResult {
    /** Done (or fired and forgotten, where the server owns the outcome). */
    data object Ok : ActionResult

    /** Nothing to do: the action was disabled, or there was nothing to act on. */
    data class Skipped(
        val reason: String,
    ) : ActionResult

    /** The server (or the link) refused; [message] is user-readable. */
    data class Failed(
        val message: String,
    ) : ActionResult
}

/** The switches the central actions respect. */
enum class CentralSetting {
    /** "Hand decisions to the agent" — the master switch for injections. */
    JournalInjectEnabled,

    /** "Offer compaction from insights" — the switch behind the Centre's Compact action. */
    InsightCompactEnabled,
}

/**
 * Reads the switches [CentralActions] respects straight from the settings store.
 *
 * A top-level function (not a member) so the action class stays free of Android and testable
 * with a stub.
 */
internal fun centralSettingValue(setting: CentralSetting): Boolean =
    when (setting) {
        CentralSetting.JournalInjectEnabled -> AppSettingsStore.state.value.journalInjectEnabled
        CentralSetting.InsightCompactEnabled -> AppSettingsStore.state.value.insightCompactEnabled
    }

/**
 * The ONE place that performs the actions the app offers.
 *
 * Why central: stopping turns, steering a queued prompt, compacting a session and handing a
 * decision to an agent are the same operations wherever they are triggered — from the Centre,
 * from a notification, from the widget, from the chat. Doing them inline at each call site is
 * how they drifted apart before (several places built the same "stop" request; one forgot the
 * audit entry, another forgot the feature switch). Here the guards, the audit entries and the
 * error handling live once.
 *
 * Transport-free on purpose: the API calls come in as suspend lambdas, so the policy is
 * unit-testable without Retrofit — the same shape
 * [com.opencode.android.ui.session.PromptSender] uses for the send path.
 */
class CentralActions(
    private val interrupt: suspend (sessionId: String) -> Unit,
    private val steer: suspend (sessionId: String, inboxId: String) -> Boolean,
    private val compact: suspend (sessionId: String, directory: String?) -> Unit,
    private val handDecisionOver: suspend (
        sessionId: String,
        text: String,
        description: String?,
        directory: String?,
    ) -> Boolean,
    /** Reads a feature switch. Injected so tests can pin both states. */
    private val setting: (CentralSetting) -> Boolean = ::centralSettingValue,
) {
    /**
     * Ends one turn. The agent keeps the work it already did.
     *
     * Recorded in the audit log: stopping is the user overriding the agent, which is exactly
     * what that log exists for.
     */
    suspend fun stop(
        sessionId: String,
        title: String,
    ): ActionResult =
        run("stop") {
            interrupt(sessionId)
            AuditLogStore.record(
                kind = AuditKind.Stop,
                title = title.ifBlank { sessionId },
                sessionId = sessionId,
            )
            ActionResult.Ok
        }

    /**
     * The emergency stop: interrupts every listed turn, sequentially.
     *
     * Sequential on purpose: ten parallel interrupts to one server add load without finishing
     * any earlier, and the audit entry must name how many actually stopped.
     */
    suspend fun stopAll(turns: List<Pair<String, String>>): ActionResult {
        if (turns.isEmpty()) return ActionResult.Skipped("nothing running")
        var stopped = 0
        turns.forEach { (sessionId, _) ->
            val result =
                run("stop:all") {
                    interrupt(sessionId)
                    ActionResult.Ok
                }
            if (result is ActionResult.Ok) stopped++
        }
        AuditLogStore.record(
            kind = AuditKind.StopAll,
            title = "$stopped of ${turns.size} turns",
            detail = turns.take(5).joinToString(", ") { it.second.ifBlank { it.first } },
        )
        return if (stopped == turns.size) {
            ActionResult.Ok
        } else {
            ActionResult.Failed("$stopped of ${turns.size} could be stopped")
        }
    }

    /**
     * Releases one queued prompt without waiting for the turn to end
     * (`PATCH …/inbox/{id}` with `delivery:"steer"`).
     */
    suspend fun steer(
        sessionId: String,
        inboxId: String,
        title: String,
    ): ActionResult =
        run("steer") {
            if (!steer(sessionId, inboxId)) {
                ActionResult.Failed("the server rejected the steer")
            } else {
                AuditLogStore.record(
                    kind = AuditKind.Send,
                    title = "Steered a queued prompt",
                    sessionId = sessionId,
                    detail = title.take(80).ifBlank { inboxId },
                )
                ActionResult.Ok
            }
        }

    /**
     * Compacts a session's context (`POST /api/session/{id}/compact`).
     *
     * Offered when the context is nearly full (the Centre's insights): far cheaper than running
     * the next round against a full window. Audit-logged because it changes what the agent
     * remembers.
     */
    suspend fun compact(
        sessionId: String,
        title: String,
        directory: String? = null,
    ): ActionResult {
        if (!setting(CentralSetting.InsightCompactEnabled)) {
            return ActionResult.Skipped("compacting from insights is switched off")
        }
        return run("compact") {
            compact(sessionId, directory)
            AuditLogStore.record(
                kind = AuditKind.Compact,
                title = title.ifBlank { sessionId },
                sessionId = sessionId,
                detail = "context compaction requested",
            )
            ActionResult.Ok
        }
    }

    /**
     * Hands a captured decision to a session's agent (`POST …/synthetic`).
     *
     * Respects the master switch: a disabled feature must not be reachable through a shortcut.
     * Marks the journal entry as injected, so its status label stays true no matter which
     * surface performed the hand-over.
     */
    suspend fun handOverDecision(
        sessionId: String,
        sessionTitle: String,
        entry: JournalEntry,
        directory: String? = null,
    ): ActionResult {
        if (!setting(CentralSetting.JournalInjectEnabled)) {
            return ActionResult.Skipped("handing decisions over is switched off")
        }
        return run("hand-over") {
            if (!handDecisionOver(sessionId, entry.text, entry.description, directory)) {
                ActionResult.Failed("the server rejected the hand-over")
            } else {
                DecisionJournalStore.markInjected(entry.id, sessionId)
                AuditLogStore.record(
                    kind = AuditKind.Inject,
                    title = entry.text.take(80),
                    sessionId = sessionId,
                    detail = "handed over to ${sessionTitle.ifBlank { sessionId }}",
                )
                ActionResult.Ok
            }
        }
    }

    /** Wraps a transport failure into the same [ActionResult.Failed] every caller expects. */
    private suspend inline fun run(
        label: String,
        block: () -> ActionResult,
    ): ActionResult =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.opencode.android.util.AppLog.e(
                com.opencode.android.util.APP_LOG_TAG,
                "central action '$label' failed: ${e.message}",
            )
            ActionResult.Failed(e.message ?: "request failed")
        }
}
