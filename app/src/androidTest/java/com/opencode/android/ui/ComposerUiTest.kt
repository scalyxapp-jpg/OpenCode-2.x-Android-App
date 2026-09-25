package com.opencode.android.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Regression guard for the composer: the agent name must not be duplicated in
 * the composer. It lives in the chip when shown and in the top bar otherwise.
 */
@RunWith(AndroidJUnit4::class)
class ComposerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setComposer(showAgent: Boolean, generating: Boolean = false) {
        rule.setContent {
            OpenCodeTheme {
                Composer(
                    actions = ComposerActions(
                        onInputChange = {},
                        onSend = {},
                        onInterrupt = {},
                        onAddFiles = {},
                        onRemoveAttachment = {},
                        onPickCommand = {},
                        onAgentSelect = {},
                        onModelSelect = {},
                        onVariantSelect = {},
                    ),
                    inputText = "",
                    attachments = emptyList(),
                    commands = emptyList(),
                    files = emptyList(),
                    isGenerating = generating,
                    showAgent = showAgent,
                    agents = emptyList(),
                    models = emptyList(),
                    variants = emptyList(),
                    selectedAgent = "build",
                    selectedModel = "deepseek/deepseek-v4-flash",
                    selectedVariant = "",
                )
            }
        }
    }

    @Test
    fun agentNameShownOnceWhenChipVisible() {
        setComposer(showAgent = true)
        // The chip shows it; the status line must not duplicate it.
        rule.onAllNodesWithText("build").assertCountEquals(1)
        rule.onNodeWithText("build").assertIsDisplayed()
    }

    @Test
    fun agentNameNotShownWhenChipHidden() {
        setComposer(showAgent = false)
        // The composer no longer carries an agent status line; the top bar
        // owns the label, so the composer must not render it at all.
        rule.onAllNodesWithText("build").assertCountEquals(0)
    }
}
