package com.opencode.android.data
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App settings, mirroring the web's localStorage "settings.v3".
 *
 * Verified structure (Playwright):
 * {
 *   general: { autoSave, releaseNotes, followup, showFileTree, showNavigation,
 *              showSearch, showStatus, showTerminal, showReasoningSummaries,
 *              shellToolPartsExpanded, editToolPartsExpanded, showCustomAgents, ... },
 *   appearance: { fontSize, mono, sans, terminal },
 *   keybinds: {},
 *   permissions: { autoApprove },
 *   notifications: { agent, permissions, errors },
 *   sounds: { agentEnabled, agent, permissionsEnabled, permissions, errorsEnabled, errors }
 * }
 *
 * All of these are purely client-side: the web makes NO write API call when a
 * setting is toggled (only GET /pty/shells is fetched to fill the shell dropdown).
 */
object AppSettingsStore {
    private const val PREFS = "opencode.settings"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = load()
    }

    @kotlinx.serialization.Serializable
    @androidx.compose.runtime.Immutable
    data class AppSettings(
        // general
        val language: String = "en",
        val autoApprove: Boolean = false,
        val terminalShell: String = "/bin/bash",
        val showReasoningSummaries: Boolean = true,
        val shellToolPartsExpanded: Boolean = false,
        val editToolPartsExpanded: Boolean = false,
        /**
         * Whether the model's thinking starts EXPANDED in the timeline. Default
         * false: "Thinking…" starts collapsed (the same shape as the shell/edit
         * tool switches, only inverted by name).
         */
        val expandThinkingBlocks: Boolean = false,
        /**
         * Hide the Session Guard's status row and actions in the chat. Meaningful
         * only when a guard was detected; the guard itself keeps working (the
         * proxy still pins the selection), only the chrome disappears.
         */
        val hideSessionGuardInChat: Boolean = false,
        /**
         * What a follow-up does while a turn runs: `queue` (wait for the turn to
         * finish — the app's default and the queue button's behaviour so far) or
         * `steer` (hand it to the running turn without interrupting, which the live
         * server accepts without an interrupt call). Web parity: Settings →
         * Preferences → Follow-up behavior.
         */
        val followUpBehavior: String = "queue",
        /**
         * How much detail the timeline shows by default: `compact` (all tool and
         * thinking blocks collapsed) or `advanced` (expanded). A preset over the
         * three individual switches, which stay available for fine-tuning.
         */
        val timelineDetail: String = "compact",
        /**
         * Wrap long lines in tool output/code instead of scrolling horizontally
         * (web: Preferences → Wrap lines).
         */
        val wrapLines: Boolean = false,
        /**
         * Show the unchanged context lines around a change in a diff.
         *
         * The reference clients offer the opposite switch ("Hide non-diff lines",
         * `ui.sessionReviewV2.hideNonDiffLines`). On a phone a long refactor with three
         * lines of context is mostly noise, so the default stays "show" and the switch
         * reduces a diff to its +/- lines when that is what you want.
         */
        val showContextLines: Boolean = true,
        /**
         * Keep a local audit log of the actions that change state or spend money
         * (stopping turns, approvals, reverts, forks, handed-over decisions, MCP toggles).
         *
         * Local and exportable, so "what did I do to which session" stays answerable
         * without asking the server. Off = nothing is recorded (the store checks this
         * flag itself, so no call site can forget it).
         */
        val auditLogEnabled: Boolean = true,
        /**
         * Hand every captured decision to the agent immediately.
         *
         * On (default): capturing a decision injects it into the open session right away -
         * one less step, and the decision is binding for the very next prompt. It starts a
         * turn, so it costs tokens; off leaves the decision pending and the journal row says
         * so, with the manual "hand over" action still available.
         */
        val journalAutoInject: Boolean = true,
        // appearance
        val colorScheme: String = "system",
        val theme: String = "default",
        val fontSize: Int = 14,
        // Web settings.v3.appearance: free-form font family names ("" = system).
        val sansFont: String = "",
        val monoFont: String = "",
        val terminalFont: String = "",
        // notifications
        /**
         * Master switch for ALL app notifications (system notifications and their
         * sounds). The per-event switches below only matter while this is on.
         */
        val notificationsEnabled: Boolean = true,
        // --- live & background -------------------------------------------------
        /**
         * Live status surfaces (widget, ongoing notification, tile): step/progress,
         * waiting decisions, failures and the cost of the day.
         */
        val glanceEnabled: Boolean = true,
        /**
         * Background monitoring: watch the sessions' event stream while the app is not
         * in focus (feeds notifications AND the live surfaces). Off = nothing runs in
         * the background.
         */
        val backgroundMonitoring: Boolean = true,
        /**
         * Keep the connection alive with a foreground service while the app is open
         * (survives backgrounding; Android caps it at ~6 h/day).
         */
        val keepAliveService: Boolean = true,
        /** Explain mode: side questions in their own session. */
        val explainEnabled: Boolean = true,
        /** Handing a captured decision to the agent (injects a synthetic message). */
        val journalInjectEnabled: Boolean = true,
        /** Model race: starts extra sessions and spends tokens. */
        val raceEnabled: Boolean = true,
        /** Offline outbox: send waiting prompts automatically when the network is back. */
        val outboxAutoFlush: Boolean = true,
        val notifyAgent: Boolean = true,
        val notifyPermissions: Boolean = true,
        val notifyErrors: Boolean = false,
        // sounds
        val soundAgentEnabled: Boolean = true,
        val soundAgent: String = "staplebops-01",
        val soundPermissionsEnabled: Boolean = true,
        val soundPermissions: String = "staplebops-02",
        val soundErrorsEnabled: Boolean = true,
        val soundErrors: String = "nope-03",
        // advanced
        val showFileTree: Boolean = false,
        val showCommandPalette: Boolean = false,
        val showServerStatus: Boolean = false,
        val showCustomAgents: Boolean = true,
        // Optional shared secret for the session-guard proxy.
        val guardToken: String = "",
        // Auto-adopt the server model/agent when guard drift is detected.
        val autoAdoptGuard: Boolean = false,
    )

    private fun p(): SharedPreferences? = prefs

    private fun load(): AppSettings {
        val prefs = p() ?: return AppSettings()
        return AppSettings(
            language = prefs.getString("language", "en") ?: "en",
            autoApprove = prefs.getBoolean("autoApprove", false),
            terminalShell = prefs.getString("terminalShell", "/bin/bash") ?: "/bin/bash",
            showReasoningSummaries = prefs.getBoolean("showReasoningSummaries", true),
            shellToolPartsExpanded = prefs.getBoolean("shellToolPartsExpanded", false),
            editToolPartsExpanded = prefs.getBoolean("editToolPartsExpanded", false),
            expandThinkingBlocks = prefs.getBoolean("expandThinkingBlocks", false),
            hideSessionGuardInChat = prefs.getBoolean("hideSessionGuardInChat", false),
            followUpBehavior = prefs.getString("followUpBehavior", "queue") ?: "queue",
            timelineDetail = prefs.getString("timelineDetail", "compact") ?: "compact",
            wrapLines = prefs.getBoolean("wrapLines", false),
            showContextLines = prefs.getBoolean("showContextLines", true),
            auditLogEnabled = prefs.getBoolean("auditLogEnabled", true),
            journalAutoInject = prefs.getBoolean("journalAutoInject", true),
            colorScheme = prefs.getString("colorScheme", "system") ?: "system",
            theme = prefs.getString("theme", "default") ?: "default",
            fontSize = prefs.getInt("fontSize", 14),
            sansFont = prefs.getString("sansFont", "") ?: "",
            monoFont = prefs.getString("monoFont", "") ?: "",
            terminalFont = prefs.getString("terminalFont", "") ?: "",
            notificationsEnabled = prefs.getBoolean("notificationsEnabled", true),
            glanceEnabled = prefs.getBoolean("glanceEnabled", true),
            backgroundMonitoring = prefs.getBoolean("backgroundMonitoring", true),
            keepAliveService = prefs.getBoolean("keepAliveService", true),
            explainEnabled = prefs.getBoolean("explainEnabled", true),
            journalInjectEnabled = prefs.getBoolean("journalInjectEnabled", true),
            raceEnabled = prefs.getBoolean("raceEnabled", true),
            outboxAutoFlush = prefs.getBoolean("outboxAutoFlush", true),
            notifyAgent = prefs.getBoolean("notifyAgent", true),
            notifyPermissions = prefs.getBoolean("notifyPermissions", true),
            notifyErrors = prefs.getBoolean("notifyErrors", false),
            soundAgentEnabled = prefs.getBoolean("soundAgentEnabled", true),
            soundAgent = prefs.getString("soundAgent", "staplebops-01") ?: "staplebops-01",
            soundPermissionsEnabled = prefs.getBoolean("soundPermissionsEnabled", true),
            soundPermissions = prefs.getString("soundPermissions", "staplebops-02") ?: "staplebops-02",
            soundErrorsEnabled = prefs.getBoolean("soundErrorsEnabled", true),
            soundErrors = prefs.getString("soundErrors", "nope-03") ?: "nope-03",
            showFileTree = prefs.getBoolean("showFileTree", false),
            showCommandPalette = prefs.getBoolean("showCommandPalette", false),
            showServerStatus = prefs.getBoolean("showServerStatus", false),
            showCustomAgents = prefs.getBoolean("showCustomAgents", true),
            guardToken =
                prefs
                    .getString("guardToken", "")
                    ?.let { if (it.isBlank()) "" else SecretBox.decrypt(it) }
                    ?: "",
            autoAdoptGuard = prefs.getBoolean("autoAdoptGuard", false),
        )
    }

    // Serialises update/reset/importJson: they are read-modify-write on the same
    // SharedPreferences, and `_state.value = load()` from one thread could
    // otherwise overwrite the freshly published state of another with a
    // snapshot taken in between.
    private val lock = Any()

    private fun update(block: (SharedPreferences.Editor) -> Unit) {
        synchronized(lock) {
            val prefs = p() ?: return
            prefs.edit { block(this) }
            _state.value = load()
        }
    }

    fun setLanguage(v: String) = update { it.putString("language", v) }

    fun setAutoApprove(v: Boolean) = update { it.putBoolean("autoApprove", v) }

    fun setTerminalShell(v: String) = update { it.putString("terminalShell", v) }

    fun setShowReasoningSummaries(v: Boolean) = update { it.putBoolean("showReasoningSummaries", v) }

    fun setShellToolPartsExpanded(v: Boolean) = update { it.putBoolean("shellToolPartsExpanded", v) }

    fun setEditToolPartsExpanded(v: Boolean) = update { it.putBoolean("editToolPartsExpanded", v) }

    fun setExpandThinkingBlocks(v: Boolean) = update { it.putBoolean("expandThinkingBlocks", v) }

    fun setHideSessionGuardInChat(v: Boolean) = update { it.putBoolean("hideSessionGuardInChat", v) }

    fun setFollowUpBehavior(v: String) = update { it.putString("followUpBehavior", v) }

    fun setWrapLines(v: Boolean) = update { it.putBoolean("wrapLines", v) }

    fun setShowContextLines(v: Boolean) = update { it.putBoolean("showContextLines", v) }

    fun setAuditLogEnabled(v: Boolean) = update { it.putBoolean("auditLogEnabled", v) }

    fun setJournalAutoInject(v: Boolean) = update { it.putBoolean("journalAutoInject", v) }

    /**
     * Sets the timeline detail AND the three switches it presets, so the control
     * and the fine-tuning never disagree about what is on.
     */
    fun setTimelineDetail(v: String) =
        update {
            val advanced = v == "advanced"
            it.putString("timelineDetail", v)
            it.putBoolean("shellToolPartsExpanded", advanced)
            it.putBoolean("editToolPartsExpanded", advanced)
            it.putBoolean("expandThinkingBlocks", advanced)
        }

    fun setColorScheme(v: String) = update { it.putString("colorScheme", v) }

    fun setTheme(v: String) = update { it.putString("theme", v) }

    fun setFontSize(v: Int) = update { it.putInt("fontSize", v) }

    fun setSansFont(v: String) = update { it.putString("sansFont", v) }

    fun setMonoFont(v: String) = update { it.putString("monoFont", v) }

    fun setTerminalFont(v: String) = update { it.putString("terminalFont", v) }

    fun setNotificationsEnabled(v: Boolean) = update { it.putBoolean("notificationsEnabled", v) }

    fun setGlanceEnabled(v: Boolean) = update { it.putBoolean("glanceEnabled", v) }

    fun setBackgroundMonitoring(v: Boolean) = update { it.putBoolean("backgroundMonitoring", v) }

    fun setKeepAliveService(v: Boolean) = update { it.putBoolean("keepAliveService", v) }

    fun setExplainEnabled(v: Boolean) = update { it.putBoolean("explainEnabled", v) }

    fun setJournalInjectEnabled(v: Boolean) = update { it.putBoolean("journalInjectEnabled", v) }

    fun setRaceEnabled(v: Boolean) = update { it.putBoolean("raceEnabled", v) }

    fun setOutboxAutoFlush(v: Boolean) = update { it.putBoolean("outboxAutoFlush", v) }

    fun setNotifyAgent(v: Boolean) = update { it.putBoolean("notifyAgent", v) }

    fun setNotifyPermissions(v: Boolean) = update { it.putBoolean("notifyPermissions", v) }

    fun setNotifyErrors(v: Boolean) = update { it.putBoolean("notifyErrors", v) }

    fun setSoundAgentEnabled(v: Boolean) = update { it.putBoolean("soundAgentEnabled", v) }

    fun setSoundAgent(v: String) = update { it.putString("soundAgent", v) }

    fun setSoundPermissionsEnabled(v: Boolean) = update { it.putBoolean("soundPermissionsEnabled", v) }

    fun setSoundPermissions(v: String) = update { it.putString("soundPermissions", v) }

    fun setSoundErrorsEnabled(v: Boolean) = update { it.putBoolean("soundErrorsEnabled", v) }

    fun setSoundErrors(v: String) = update { it.putString("soundErrors", v) }

    fun setShowFileTree(v: Boolean) = update { it.putBoolean("showFileTree", v) }

    fun setShowCommandPalette(v: Boolean) = update { it.putBoolean("showCommandPalette", v) }

    fun setShowServerStatus(v: Boolean) = update { it.putBoolean("showServerStatus", v) }

    fun setShowCustomAgents(v: Boolean) = update { it.putBoolean("showCustomAgents", v) }

    fun setAutoAdoptGuard(v: Boolean) = update { it.putBoolean("autoAdoptGuard", v) }

    fun setGuardToken(v: String) {
        val trimmed = v.trim()
        if (trimmed.isBlank()) {
            update { it.putString("guardToken", "") }
            return
        }
        // encrypt() is fail-closed (null when the Keystore is unavailable).
        // Writing "" then would silently DESTROY the working token; keep the
        // stored value and tell the user instead.
        val encrypted = SecretBox.encrypt(trimmed)
        if (encrypted == null) {
            AppLog.w(APP_LOG_TAG) { "setGuardToken: encryption unavailable; keeping stored token" }
            return
        }
        update { it.putString("guardToken", encrypted) }
    }

    /** Restores every setting to its default ("Reset to defaults"). */
    fun reset() {
        synchronized(lock) {
            val prefs = p() ?: return
            prefs.edit { clear() }
            _state.value = AppSettings()
        }
    }

    // --- Settings transfer -------------------------------------------------
    // Manual export/import so a device change (or a bug report) can carry the
    // whole configuration. Deliberately file-based rather than cloud-based: no
    // credentials, no background sync, no conflict resolution to get wrong.

    private val transferJson =
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

    /**
     * Current settings as a portable JSON document.
     *
     * The guard token is a shared secret and the export is meant to be
     * shareable (bug reports, device moves), so it is redacted here. The
     * decrypted token would otherwise land in a plaintext file.
     */
    fun exportJson(): String {
        val redacted = _state.value.copy(guardToken = "")
        return transferJson.encodeToString(AppSettings.serializer(), redacted)
    }

    /**
     * Applies a document produced by [exportJson]. Unknown keys are ignored
     * so a newer document still imports.
     *
     * @return null on success, otherwise the failure reason (shown to the user).
     */
    fun importJson(text: String): String? {
        val parsed =
            try {
                transferJson.decodeFromString(AppSettings.serializer(), text)
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "importJson failed: ${e.message}")
                return e.message ?: "malformed file"
            }
        val prefs = p() ?: return "settings storage unavailable"
        synchronized(lock) {
            prefs.edit {
                putString("language", parsed.language)
                putBoolean("autoApprove", parsed.autoApprove)
                putString("terminalShell", parsed.terminalShell)
                putBoolean("showReasoningSummaries", parsed.showReasoningSummaries)
                putBoolean("shellToolPartsExpanded", parsed.shellToolPartsExpanded)
                putBoolean("editToolPartsExpanded", parsed.editToolPartsExpanded)
                putBoolean("expandThinkingBlocks", parsed.expandThinkingBlocks)
                putBoolean("hideSessionGuardInChat", parsed.hideSessionGuardInChat)
                putString("followUpBehavior", parsed.followUpBehavior)
                putString("timelineDetail", parsed.timelineDetail)
                putBoolean("wrapLines", parsed.wrapLines)
                putBoolean("showContextLines", parsed.showContextLines)
                putBoolean("auditLogEnabled", parsed.auditLogEnabled)
                putBoolean("journalAutoInject", parsed.journalAutoInject)
                putString("colorScheme", parsed.colorScheme)
                putString("theme", parsed.theme)
                putInt("fontSize", parsed.fontSize)
                putString("sansFont", parsed.sansFont)
                putString("monoFont", parsed.monoFont)
                putString("terminalFont", parsed.terminalFont)
                putBoolean("notificationsEnabled", parsed.notificationsEnabled)
                putBoolean("glanceEnabled", parsed.glanceEnabled)
                putBoolean("backgroundMonitoring", parsed.backgroundMonitoring)
                putBoolean("keepAliveService", parsed.keepAliveService)
                putBoolean("explainEnabled", parsed.explainEnabled)
                putBoolean("journalInjectEnabled", parsed.journalInjectEnabled)
                putBoolean("raceEnabled", parsed.raceEnabled)
                putBoolean("outboxAutoFlush", parsed.outboxAutoFlush)
                putBoolean("notifyAgent", parsed.notifyAgent)
                putBoolean("notifyPermissions", parsed.notifyPermissions)
                putBoolean("notifyErrors", parsed.notifyErrors)
                putBoolean("soundAgentEnabled", parsed.soundAgentEnabled)
                putString("soundAgent", parsed.soundAgent)
                putBoolean("soundPermissionsEnabled", parsed.soundPermissionsEnabled)
                putString("soundPermissions", parsed.soundPermissions)
                putBoolean("soundErrorsEnabled", parsed.soundErrorsEnabled)
                putString("soundErrors", parsed.soundErrors)
                putBoolean("showFileTree", parsed.showFileTree)
                putBoolean("showCommandPalette", parsed.showCommandPalette)
                putBoolean("showServerStatus", parsed.showServerStatus)
                putBoolean("showCustomAgents", parsed.showCustomAgents)
                // Previously omitted: the auto-adopt preference was silently lost
                // on import.
                putBoolean("autoAdoptGuard", parsed.autoAdoptGuard)
                // The guard token is deliberately NOT imported: it is redacted in
                // the export, and overwriting the device's working secret with the
                // empty redacted value would break the guard connection.
            }
            _state.value = load()
        }
        return null
    }
}
