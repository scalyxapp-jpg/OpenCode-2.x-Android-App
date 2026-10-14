package com.opencode.android.ui

import com.opencode.android.domain.Part
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the diff of an edit/write row is found.
 *
 * Regression guard for "I no longer see the code with line numbers": OpenCode 2.0.26 carries
 * the patch in `state.metadata.files[].patch`, not in `metadata.diff` — reading only the
 * latter left every edit row without a diff.
 */
class ToolDiffTextTest {
    private fun part(
        name: String,
        state: String,
    ) = Part(id = "p1", type = "tool", tool = name, name = name, state = Json.parseToJsonElement(state))

    /** The exact shape captured from the running server (2.0.26). */
    private val realEditState =
        """
        {"status":"completed","input":{"path":"app/build.gradle.kts",
          "oldString":"        versionCode = 29","newString":"        versionCode = 30"},
         "metadata":{"files":[{"file":"app/build.gradle.kts","patch":
           "Index: app/build.gradle.kts\n--- a\n+++ b\n@@ -43,10 +43,10 @@\n     defaultConfig {\n-        versionCode = 29\n+        versionCode = 30\n"}]},
         "content":[{"type":"text","text":"Edited app/build.gradle.kts (1 replacement)"}]}
        """.trimIndent()

    @Test
    fun `the patch from metadata files is used when metadata diff is absent`() {
        val diff = toolDiffText(part("edit", realEditState))
        assertTrue("diff missing: $diff", diff?.contains("@@ -43,10 +43,10 @@") == true)
        assertTrue(diff?.contains("-        versionCode = 29") == true)
        assertTrue(diff?.contains("+        versionCode = 30") == true)
    }

    @Test
    fun `the parsed rows keep the gutter line numbers of the hunk`() {
        val rows = diffLineNumbers(toolDiffText(part("edit", realEditState)).orEmpty())
        // `--- a` is the file header (no numbers), so the content line is the interesting one.
        val removed = rows.first { it.line.startsWith("-") && !it.line.startsWith("---") }
        val added = rows.first { it.line.startsWith("+") && !it.line.startsWith("+++") }
        // Hunk `@@ -43,10 +43,10 @@`: the first context line is old/new 43, so the removed
        // and added `versionCode` lines that follow are both number 44.
        assertEquals(44, removed.oldNum)
        assertEquals(44, added.newNum)
        assertEquals(null, removed.newNum)
    }

    @Test
    fun `metadata diff still wins when a server sends it`() {
        val state =
            """{"status":"completed","metadata":{"diff":"@@ -1,1 +1,1 @@\n-a\n+b"}}"""
        assertEquals("@@ -1,1 +1,1 @@\n-a\n+b", toolDiffText(part("edit", state)))
    }

    @Test
    fun `a write becomes a numbered all-added hunk`() {
        val state =
            """{"status":"completed","input":{"path":"/tmp/x.sh","content":"#!/bin/bash\n\necho hi\n"}}"""
        val diff = toolDiffText(part("write", state)).orEmpty()
        assertTrue(diff.startsWith("@@ -1,3 +1,3 @@"))
        assertTrue(diff.contains("+#!/bin/bash"))
        assertTrue(diff.contains("+echo hi"))
        // Numbered listing: the gutter counts 1..3.
        val rows = diffLineNumbers(diff)
        assertEquals(listOf(1, 2, 3), rows.mapNotNull { it.newNum })
    }

    @Test
    fun `a read or shell call has no diff`() {
        assertNull(toolDiffText(part("read", """{"status":"completed","input":{"filePath":"/tmp/x"}}""")))
        assertNull(toolDiffText(part("shell", """{"status":"completed","input":{"command":"ls"}}""")))
    }
}
