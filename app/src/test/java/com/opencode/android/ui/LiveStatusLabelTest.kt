package com.opencode.android.ui

import com.opencode.android.domain.Part
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status row used to know only "waiting" and "generating", so a long turn read as
 * "Waiting for the model…" while tools ran. The kind/detail precedence is pure, so it
 * is pinned here.
 */
class LiveStatusLabelTest {
    private fun toolPart(
        name: String,
        status: String?,
        input: JsonObject? = null,
    ) = Part(
        id = name,
        type = "tool",
        tool = name,
        state =
            JsonObject(
                buildMap {
                    status?.let { put("status", JsonPrimitive(it)) }
                    input?.let { put("input", it) }
                },
            ),
    )

    @Test
    fun `a dead connection wins over everything else`() {
        val status =
            liveStatus(
                connected = false,
                compacting = true,
                thinking = true,
                waiting = false,
                parts = listOf(toolPart("bash", "running")),
            )
        assertEquals(LiveStatusKind.DISCONNECTED, status.kind)
    }

    @Test
    fun `a running tool is reported with its name and argument`() {
        val status =
            liveStatus(
                connected = true,
                compacting = false,
                thinking = false,
                waiting = false,
                parts =
                    listOf(
                        toolPart("read", "completed"),
                        toolPart("bash", "running", JsonObject(mapOf("command" to JsonPrimitive("npm test")))),
                    ),
            )
        assertEquals(LiveStatusKind.TOOL, status.kind)
        assertEquals("bash", status.tool)
        assertEquals("npm test", status.detail)
        assertEquals(2, status.toolCount)
    }

    @Test
    fun `a finished tool falls back to the generic phases`() {
        val status =
            liveStatus(
                connected = true,
                compacting = false,
                thinking = false,
                waiting = false,
                parts = listOf(toolPart("read", "completed")),
                streamingChars = 120,
            )
        assertEquals(LiveStatusKind.WRITING, status.kind)
        assertEquals(120, status.streamingChars)
    }

    @Test
    fun `compacting and waiting keep their precedence`() {
        assertEquals(
            LiveStatusKind.COMPACTING,
            liveStatus(true, true, true, false, emptyList()).kind,
        )
        assertEquals(
            LiveStatusKind.THINKING,
            liveStatus(true, false, true, true, emptyList()).kind,
        )
        assertEquals(
            LiveStatusKind.WAITING,
            liveStatus(true, false, false, true, emptyList()).kind,
        )
    }

    @Test
    fun `an unknown tool status does not claim a tool is running`() {
        val status = liveStatus(true, false, false, false, listOf(toolPart("read", null)))
        assertTrue("expected WRITING, was ${status.kind}", status.kind == LiveStatusKind.WRITING)
    }
}
