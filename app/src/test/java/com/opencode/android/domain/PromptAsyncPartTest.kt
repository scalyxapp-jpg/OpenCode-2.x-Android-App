package com.opencode.android.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the prompt_async file-part shape verified against the web client. */
class PromptAsyncPartTest {

    @Test
    fun `file part serializes web-compatible fields`() {
        val json = Json.encodeToString(
            PromptAsyncPart(
                id = "prt_test",
                type = "file",
                mime = "image/png",
                url = "data:image/png;base64,AAAA",
                filename = "shot.png",
            )
        )
        assertTrue(json.contains("\"type\":\"file\""))
        assertTrue(json.contains("\"mime\":\"image/png\""))
        assertTrue(json.contains("\"filename\":\"shot.png\""))
        assertTrue(json.contains("\"url\":\"data:image/png;base64,AAAA\""))
        assertEquals(false, json.contains("\"text\""))
    }

    @Test
    fun `text part still serializes`() {
        val json = Json.encodeToString(
            PromptAsyncPart(id = "prt_test", type = "text", text = "hello")
        )
        assertTrue(json.contains("\"type\":\"text\""))
        assertTrue(json.contains("\"text\":\"hello\""))
    }
}
