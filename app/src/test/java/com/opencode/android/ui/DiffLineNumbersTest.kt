package com.opencode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The diff gutter shows the number each line has in the file (TUI parity):
 * hunk headers and file headers carry no number, removed lines their
 * old-file number, added/context lines the new-file number.
 */
class DiffLineNumbersTest {

    private val patch = listOf(
        "--- a/file.kt",
        "+++ b/file.kt",
        "@@ -10,4 +10,5 @@",
        " context",
        "-removed",
        "+added",
        "+added2",
        " context2",
        "@@ -30,2 +31,2 @@",
        "-old2",
        " ctx3",
    ).joinToString("\n")

    @Test
    fun numbersFollowHunks() {
        val rows = diffLineNumbers(patch)
        assertEquals(
            listOf(
                DiffRow("--- a/file.kt", null, null),
                DiffRow("+++ b/file.kt", null, null),
                DiffRow("@@ -10,4 +10,5 @@", null, null),
                DiffRow(" context", 10, 10),
                DiffRow("-removed", 11, null),
                DiffRow("+added", null, 11),
                DiffRow("+added2", null, 12),
                DiffRow(" context2", 12, 13),
                DiffRow("@@ -30,2 +31,2 @@", null, null),
                DiffRow("-old2", 30, null),
                DiffRow(" ctx3", 31, 31),
            ),
            rows,
        )
    }
}
