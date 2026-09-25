package com.opencode.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChangesTabUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val diff = List(30) { index ->
        VcsDiffFile(file = "src/File$index.kt")
    }
    private val files = List(80) { index ->
        FileEntry(
            name = "file-$index.txt",
            path = "/project/file-$index.txt",
            type = "file",
        )
    }

    @Test
    fun scrollsFromChangedFilesIntoFileTree() {
        rule.setContent {
            OpenCodeTheme {
                ChangesTab(
                    files = files,
                    fileViewer = null,
                    vcsDiff = diff,
                    initialPath = "/project",
                    showFileTree = true,
                    onLoadFiles = {},
                    onOpenFile = {},
                    onDismissViewer = {},
                )
            }
        }

        rule.onNodeWithTag("changes-list")
            .performScrollToNode(hasText("file-79.txt"))
        rule.onNodeWithText("file-79.txt").assertIsDisplayed()
    }

    @Test
    fun resetsScrollWhenDirectoryChanges() {
        val path = mutableStateOf("/one")
        rule.setContent {
            OpenCodeTheme {
                ChangesTab(
                    files = files,
                    fileViewer = null,
                    vcsDiff = diff,
                    initialPath = path.value,
                    showFileTree = true,
                    onLoadFiles = {},
                    onOpenFile = {},
                    onDismissViewer = {},
                )
            }
        }

        rule.onNodeWithTag("changes-list")
            .performScrollToNode(hasText("file-79.txt"))
        rule.runOnIdle { path.value = "/two" }
        rule.waitForIdle()

        rule.onNodeWithText("30 Files Changed").assertIsDisplayed()
    }
}
