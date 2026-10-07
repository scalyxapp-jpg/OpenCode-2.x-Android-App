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
        // appearance
        val colorScheme: String = "system",
        val theme: String = "default",
        val fontSize: Int = 14,
        // Web settings.v3.appearance: free-form font family names ("" = system).
        val sansFont: String = "",
        val monoFont: String = "",
        val terminalFont: String = "",
        // notifications
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
            colorScheme = prefs.getString("colorScheme", "system") ?: "system",
            theme = prefs.getString("theme", "default") ?: "default",
            fontSize = prefs.getInt("fontSize", 14),
            sansFont = prefs.getString("sansFont", "") ?: "",
            monoFont = prefs.getString("monoFont", "") ?: "",
            terminalFont = prefs.getString("terminalFont", "") ?: "",
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

    private fun update(block: (SharedPreferences.Editor) -> Unit) {
        val prefs = p() ?: return
        prefs.edit { block(this) }
        _state.value = load()
    }

    fun setLanguage(v: String) = update { it.putString("language", v) }

    fun setAutoApprove(v: Boolean) = update { it.putBoolean("autoApprove", v) }

    fun setTerminalShell(v: String) = update { it.putString("terminalShell", v) }

    fun setShowReasoningSummaries(v: Boolean) = update { it.putBoolean("showReasoningSummaries", v) }

    fun setShellToolPartsExpanded(v: Boolean) = update { it.putBoolean("shellToolPartsExpanded", v) }

    fun setEditToolPartsExpanded(v: Boolean) = update { it.putBoolean("editToolPartsExpanded", v) }

    fun setColorScheme(v: String) = update { it.putString("colorScheme", v) }

    fun setTheme(v: String) = update { it.putString("theme", v) }

    fun setFontSize(v: Int) = update { it.putInt("fontSize", v) }

    fun setSansFont(v: String) = update { it.putString("sansFont", v) }

    fun setMonoFont(v: String) = update { it.putString("monoFont", v) }

    fun setTerminalFont(v: String) = update { it.putString("terminalFont", v) }

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
        update {
            it.putString(
                "guardToken",
                // encrypt() is fail-closed (null when the Keystore is
                // unavailable): store "" rather than a plaintext secret.
                if (trimmed.isBlank()) "" else SecretBox.encrypt(trimmed) ?: "",
            )
        }
    }

    /** Restores every setting to its default ("Reset to defaults"). */
    fun reset() {
        val prefs = p() ?: return
        prefs.edit { clear() }
        _state.value = AppSettings()
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
        prefs.edit {
            putString("language", parsed.language)
            putBoolean("autoApprove", parsed.autoApprove)
            putString("terminalShell", parsed.terminalShell)
            putBoolean("showReasoningSummaries", parsed.showReasoningSummaries)
            putBoolean("shellToolPartsExpanded", parsed.shellToolPartsExpanded)
            putBoolean("editToolPartsExpanded", parsed.editToolPartsExpanded)
            putString("colorScheme", parsed.colorScheme)
            putString("theme", parsed.theme)
            putInt("fontSize", parsed.fontSize)
            putString("sansFont", parsed.sansFont)
            putString("monoFont", parsed.monoFont)
            putString("terminalFont", parsed.terminalFont)
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
        return null
    }
}
