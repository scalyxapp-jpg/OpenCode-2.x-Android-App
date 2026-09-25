package com.opencode.android.data

import com.opencode.android.data.AppSettingsStore.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings transfer is file-based, so the document format is the contract. A
 * silent field drop would look like "my settings did not move".
 */
class AppSettingsTransferTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `round trip preserves every field`() {
        val original = AppSettings(
            language = "de",
            autoApprove = true,
            terminalShell = "/bin/zsh",
            showReasoningSummaries = false,
            shellToolPartsExpanded = true,
            editToolPartsExpanded = true,
            colorScheme = "dark",
            theme = "oc-3",
            fontSize = 17,
            sansFont = "Inter",
            monoFont = "JetBrains Mono",
            terminalFont = "Fira Code",
            notifyAgent = false,
            notifyPermissions = false,
            notifyErrors = true,
            soundAgentEnabled = false,
            soundAgent = "staplebops-03",
            soundPermissionsEnabled = false,
            soundPermissions = "staplebops-01",
            soundErrorsEnabled = false,
            soundErrors = "nope-01",
            showFileTree = true,
            showCommandPalette = true,
            showServerStatus = true,
            showCustomAgents = false,
        )
        val text = json.encodeToString(AppSettings.serializer(), original)
        assertEquals(original, json.decodeFromString(AppSettings.serializer(), text))
    }

    @Test
    fun `an unknown key from a newer build does not fail the import`() {
        val text = """{"language":"fr","someFutureFlag":true}"""
        val parsed = json.decodeFromString(AppSettings.serializer(), text)
        assertEquals("fr", parsed.language)
        assertTrue(parsed.notifyAgent) // untouched field keeps its default
    }

    @Test
    fun `defaults round trip`() {
        val d = AppSettings()
        val text = json.encodeToString(AppSettings.serializer(), d)
        assertEquals(d, json.decodeFromString(AppSettings.serializer(), text))
    }
}
