package com.opencode.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the dropdown row layout: the selected value must render
 * (on the title line) and selecting an option must report the option id.
 */
@RunWith(AndroidJUnit4::class)
class SettingDropdownUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun showsTitleAndSelectedValue() {
        rule.setContent {
            OpenCodeTheme {
                SettingDropdown(
                    title = "Color scheme",
                    subtitle = "Choose whether OpenCode follows the system, light, or dark theme",
                    value = "system",
                    options = listOf("system" to "System", "dark" to "Dark"),
                    onSelect = {},
                )
            }
        }
        rule.onNodeWithText("Color scheme").assertIsDisplayed()
        rule.onNodeWithText("System").assertIsDisplayed()
    }

    @Test
    fun selectingAnOptionReportsItsId() {
        var selected = ""
        rule.setContent {
            OpenCodeTheme {
                SettingDropdown(
                    title = "Theme",
                    subtitle = "Customise how OpenCode is themed",
                    value = "oc-2",
                    options = listOf("oc-2" to "OC-2", "oc-1" to "OC-1"),
                    onSelect = { selected = it },
                )
            }
        }
        rule.onNodeWithText("OC-2").performClick()
        rule.onNodeWithText("OC-1").performClick()
        assertEquals("oc-1", selected)
    }
}
