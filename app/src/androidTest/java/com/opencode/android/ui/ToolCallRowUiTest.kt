package com.opencode.android.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opencode.android.R
import com.opencode.android.domain.Part
import com.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Expand shell/edit tool parts" settings only work if ToolCallRow honours
 * the `initiallyExpanded` argument. The copy button lives in the expanded
 * section, so its presence is a reliable proxy for the expansion state.
 */
@RunWith(AndroidJUnit4::class)
class ToolCallRowUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val copyLabel: String =
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.copy)

    private fun setRow(initiallyExpanded: Boolean) {
        rule.setContent {
            OpenCodeTheme {
                ToolCallRow(
                    part = Part(id = "p1", type = "tool", tool = "bash"),
                    initiallyExpanded = initiallyExpanded,
                )
            }
        }
    }

    @Test
    fun startsExpandedWhenRequested() {
        setRow(initiallyExpanded = true)
        rule.onNodeWithContentDescription(copyLabel).assertExists()
    }

    @Test
    fun startsCollapsedByDefault() {
        setRow(initiallyExpanded = false)
        rule.onNodeWithContentDescription(copyLabel).assertDoesNotExist()
    }
}
