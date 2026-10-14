package com.opencode.android.ui

import com.opencode.android.domain.Part
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TUI parity of the tool rows, the changed-files data and the truncated output.
 *
 * Every fixture is the payload shape captured from the running server (OpenCode
 * 2.0.26): the per-file diff info lives in `state.metadata.files[]`, not in
 * `metadata.diff`, and the input of an edit uses `path`, not `filePath`. Reading only
 * the older shape is what left the rows without a "+8 -0" stat, the Changes card empty
 * and the path unreadable.
 */
class ToolChangedFilesTest {
    private fun part(
        name: String,
        state: String,
    ) = Part(id = "p1", type = "tool", tool = name, name = name, state = Json.parseToJsonElement(state))

    /** The exact shape of a completed `edit` on this server (three files in one call). */
    private val multiFileEdit =
        """
        {"status":"completed","input":{"path":"app/build.gradle.kts",
           "oldString":"versionCode = 29","newString":"versionCode = 30"},
         "metadata":{"files":[
            {"file":"app/build.gradle.kts","additions":2,"deletions":2,"status":"modified",
             "patch":"Index: app/build.gradle.kts\n--- app/build.gradle.kts\n+++ app/build.gradle.kts\n@@ -43,10 +43,10 @@\n-        versionCode = 29\n-        versionName = \"1.5.19\"\n+        versionCode = 30\n+        versionName = \"1.5.20\"\n"},
            {"file":"docs/CHANGELOG.md","additions":8,"deletions":0,"status":"modified",
             "patch":"Index: docs/CHANGELOG.md\n--- docs/CHANGELOG.md\n+++ docs/CHANGELOG.md\n@@ -1,2 +1,10 @@\n # Changelog\n+x\n"},
            {"file":"app/src/main/AndroidManifest.xml","additions":0,"deletions":1,"status":"modified",
             "patch":"Index: app/src/main/AndroidManifest.xml\n--- app/src/main/AndroidManifest.xml\n+++ app/src/main/AndroidManifest.xml\n@@ -1,2 +1,1 @@\n-    <uses-permission/>\n"}]}}
        """.trimIndent()

    @Test
    fun `every changed file of a multi-file edit is reported with the server counts`() {
        val files = changedFiles(listOf(part("edit", multiFileEdit)))
        assertEquals(
            listOf("app/build.gradle.kts", "docs/CHANGELOG.md", "app/src/main/AndroidManifest.xml"),
            files.map { it.path },
        )
        assertEquals(listOf(2, 8, 0), files.map { it.added })
        assertEquals(listOf(2, 0, 1), files.map { it.removed })
        assertEquals(files.sumOf { it.added }, toolDiffStat(part("edit", multiFileEdit))?.first)
    }

    @Test
    fun `the row stat adds up the files and counts the patch when the server sends no counts`() {
        assertEquals(10 to 3, toolDiffStat(part("edit", multiFileEdit)))
        val withoutCounts =
            """{"status":"completed","metadata":{"files":[{"file":"a.kt","patch":"--- a/a.kt\n+++ b/a.kt\n@@ -1,2 +1,2 @@\n-a\n+b\n"}]}}"""
        assertEquals(1 to 1, toolDiffStat(part("edit", withoutCounts)))
    }

    @Test
    fun `a write without metadata reports its input path and all added lines`() {
        val state = """{"status":"completed","input":{"path":"/tmp/x.sh","content":"#!/bin/bash\n\necho hi\n"}}"""
        val files = changedFiles(listOf(part("write", state)))
        assertEquals(1, files.size)
        assertEquals("/tmp/x.sh", files.single().path)
        assertEquals(3 to 0, toolDiffStat(part("write", state)))
    }

    @Test
    fun `an older build with only metadata diff and filepath still resolves`() {
        val state = """{"status":"completed","metadata":{"filepath":"src/old.kt","diff":"--- a/src/old.kt\n+++ b/src/old.kt\n@@ -1,2 +1,2 @@\n-a\n+b\n"}}"""
        val files = changedFiles(listOf(part("edit", state)))
        assertEquals("src/old.kt", files.single().path)
        assertEquals(1 to 1, toolDiffStat(part("edit", state)))
    }

    @Test
    fun `the path comes from the patch header when the entry has none`() {
        assertEquals(
            "src/from-header.ts",
            patchHeaderPath("Index: src/from-header.ts\n--- a/src/from-header.ts\n+++ b/src/from-header.ts\n"),
        )
        assertEquals("src/plus.ts", patchHeaderPath("--- a/src/plus.ts\n+++ b/src/plus.ts\n"))
        assertNull(patchHeaderPath("@@ -1,1 +1,1 @@\n-a\n+b"))
    }

    @Test
    fun `tools that change no file contribute nothing`() {
        assertEquals(
            emptyList<ChangedFile>(),
            changedFiles(listOf(part("read", """{"status":"completed"}"""), part("shell", """{"status":"completed"}"""))),
        )
        assertNull(toolDiffStat(part("read", """{"status":"completed","metadata":{"truncated":true}}""")))
    }

    // --- row badge ---------------------------------------------------------

    @Test
    fun `the tool badge names the tool like the TUI`() {
        assertEquals("Edit", toolDisplayName("edit"))
        assertEquals("Read", toolDisplayName("read"))
        assertEquals("Grep", toolDisplayName("grep"))
        assertEquals("Glob", toolDisplayName("glob"))
        assertEquals("Task", toolDisplayName("task"))
        assertEquals("Webfetch", toolDisplayName("webfetch"))
        assertEquals("Write", toolDisplayName("write"))
        assertEquals("Shell", toolDisplayName("bash"))
        // An unknown tool keeps its raw id - no information is lost.
        assertEquals("my_plugin_tool", toolDisplayName("my_plugin_tool"))
    }

    // --- truncated output --------------------------------------------------

    @Test
    fun `a finished shell call without output says no output`() {
        assertEquals(NO_OUTPUT, toolOutputText(part("bash", """{"status":"completed","metadata":{}}""")))
        // While it still runs there is simply nothing yet.
        assertNull(toolOutputText(part("bash", """{"status":"running"}""")))
        // A non-shell tool stays silent instead of claiming "(no output)".
        assertNull(toolOutputText(part("read", """{"status":"completed"}""")))
    }

    @Test
    fun `a result the server truncated shows its save path in the server wording`() {
        val state =
            """{"status":"completed","output":"first line","metadata":{"truncated":true,"outputPath":"/tmp/tool-output/tool_1354d9fa"}}"""
        assertEquals(
            "...output truncated...\n\nFull output saved to: /tmp/tool-output/tool_1354d9fa",
            toolOutputNotice(part("read", state)),
        )
        // The server already wrote the notice into the output text: no second copy.
        val already = """{"status":"completed","output":"...output truncated...\n\nFull output saved to: /tmp/x\n\nfirst","metadata":{"truncated":true,"outputPath":"/tmp/x"}}"""
        assertNull(toolOutputNotice(part("read", already)))
        // Nothing truncated, nothing to say.
        assertNull(toolOutputNotice(part("read", """{"status":"completed","output":"ok","metadata":{}}""")))
    }

    // --- thinking block ----------------------------------------------------

    @Test
    fun `reasoning drops the servers redaction markers like the TUI`() {
        // The TUI only removes the marker (`replace(/\[REDACTED\]/g, "")`) and does not
        // re-flow the text, so the spaces around it stay.
        assertEquals("I plan the  change", tuiReasoningText("I plan the [REDACTED] change"))
        assertEquals("plain", tuiReasoningText("  plain  "))
    }

    // --- failed tool calls ------------------------------------------------

    @Test
    fun `a failed tool call shows its reason and starts open`() {
        val failed =
            part(
                "bash",
                """{"status":"error","input":{"command":"false"},"error":{"name":"ToolError","data":{"message":"command exited with 1"}}}""",
            )
        assertEquals("command exited with 1", toolErrorText(failed))
        assertNull(toolErrorText(part("bash", """{"status":"completed","output":"ok"}""")))
        // The reason is the point of the row, so a failed call is never collapsed -
        // regardless of the "expand shell/edit tool parts" switches.
        assertEquals(true, defaultToolExpanded(failed, shellExpanded = false, editExpanded = false))
        // An aborted call keeps the short label the TUI/web use.
        val aborted = part("bash", """{"status":"error","error":{"name":"MessageAbortedError"}}""")
        assertEquals("Interrupted", toolErrorText(aborted))
    }

    // --- large diff guard -------------------------------------------------

    @Test
    fun `the guard counts the changed lines of a diff`() {
        val rows =
            diffLineNumbers(
                "@@ -1,4 +1,4 @@\n keep\n-old one\n+new one\n+second\n keep\n",
            )
        assertEquals(3, countChangedRows(rows))
        assertEquals(true, diffTooLarge(changedLines = 3, limit = 2))
        assertEquals(false, diffTooLarge(changedLines = 2, limit = 2))
    }
}
