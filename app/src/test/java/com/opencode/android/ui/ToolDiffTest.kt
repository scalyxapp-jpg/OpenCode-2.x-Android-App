package com.opencode.android.ui

import com.opencode.android.domain.Part
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * edit/write/patch tool parts carry a unified diff in `state.metadata.diff`.
 * The expanded tool row renders it (it only showed the result before), so the
 * extraction must return the raw diff and its +/− counts.
 */
class ToolDiffTest {

    private val diff = "@@ -1,2 +1,3 @@\n-old line\n+new line\n+extra line\n"

    private fun editPart(diff: String?) = Part(
        id = "p1",
        type = "tool",
        tool = "edit",
        state = buildJsonObject {
            putJsonObject("metadata") {
                if (diff != null) put("diff", diff)
            }
        },
    )

    @Test
    fun `returns the unified diff`() {
        assertEquals(diff, toolDiffText(editPart(diff)))
    }

    @Test
    fun `counts additions and removals`() {
        assertEquals(2 to 1, toolDiffStat(editPart(diff)))
    }

    @Test
    fun `no diff returns null`() {
        assertNull(toolDiffText(editPart(null)))
        assertNull(toolDiffStat(editPart(null)))
    }
}
