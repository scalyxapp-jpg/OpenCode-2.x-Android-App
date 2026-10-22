package com.opencode.android.ui.media

import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageFile
import com.opencode.android.domain.MessageFileSource
import com.opencode.android.domain.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media extraction is the fragile part of showing generated files in chat: the same
 * artifact arrives as a tool `content` item, as a markdown image, as a bare path in
 * tool output or as an inline base64 attachment. These tests pin the four shapes and
 * the deduplication, so a screenshot can never silently disappear again.
 */
class MediaRefsTest {
    private fun state(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `tool file content item becomes an image`() {
        val part =
            Part(
                type = "tool",
                tool = "take_screenshot",
                state =
                    state(
                        """{"status":"completed","content":[{"type":"file","uri":"data:image/png;base64,AAAA","mime":"image/png","name":"shot.png"}]}""",
                    ),
            )
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(1, refs.size)
        assertEquals(MediaKind.IMAGE, refs[0].kind)
        assertEquals("shot.png", refs[0].displayName)
        assertTrue(refs[0].source is MediaSource.Inline)
    }

    @Test
    fun `tool file content with an absolute path stays a server file`() {
        val part =
            Part(
                type = "tool",
                tool = "generate_video",
                state = state("""{"status":"completed","content":[{"type":"file","uri":"/tmp/render/clip.mp4","mime":"video/mp4"}]}"""),
            )
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(MediaKind.VIDEO, refs.single().kind)
        assertEquals(MediaSource.ServerFile("/tmp/render/clip.mp4"), refs.single().source)
    }

    @Test
    fun `a path printed in tool output is found`() {
        val part =
            Part(
                type = "tool",
                tool = "generate_image",
                state =
                    state(
                        """{"status":"completed","content":[{"type":"text","text":"Saved to /home/me/Pictures/fox.png (1.2 MB)"}]}""",
                    ),
            )
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(MediaKind.IMAGE, refs.single().kind)
        assertEquals(MediaSource.ServerFile("/home/me/Pictures/fox.png"), refs.single().source)
    }

    @Test
    fun `markdown image in assistant text is found`() {
        val part = Part(type = "text", text = "Done!\n\n![screenshot](/tmp/shots/home.png)")
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(MediaSource.ServerFile("/tmp/shots/home.png"), refs.single().source)
        assertEquals(MediaKind.IMAGE, refs.single().kind)
    }

    @Test
    fun `inline data uri in text is found`() {
        val part = Part(type = "text", text = "![x](data:image/webp;base64,BBBB)")
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(MediaKind.IMAGE, refs.single().kind)
        assertTrue(refs.single().source is MediaSource.Inline)
    }

    @Test
    fun `remote video url becomes a remote ref`() {
        val part = Part(type = "text", text = "see https://cdn.example.com/demo.mp4?token=1 for the clip")
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(MediaKind.VIDEO, refs.single().kind)
        assertEquals(MediaSource.Remote("https://cdn.example.com/demo.mp4?token=1"), refs.single().source)
    }

    @Test
    fun `relative audio path is found`() {
        val part = Part(type = "text", text = "voiceover written to assets/intro.mp3")
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(MediaKind.AUDIO, refs.single().kind)
        assertEquals(MediaSource.ServerFile("assets/intro.mp3"), refs.single().source)
    }

    @Test
    fun `user attachment with inline payload is a ref`() {
        val message =
            Message(
                role = "user",
                text = "look",
                files =
                    listOf(
                        MessageFile(data = "AAAA", mime = "image/jpeg", name = "photo.jpg"),
                    ),
            )
        val refs = MediaRefs.forMessage(message)
        assertEquals(MediaKind.IMAGE, refs.single().kind)
        assertEquals("photo.jpg", refs.single().displayName)
        assertEquals(MediaSource.Inline("data:image/jpeg;base64,AAAA"), refs.single().source)
    }

    @Test
    fun `attachment without payload falls back to its source uri`() {
        val message =
            Message(
                role = "user",
                files =
                    listOf(
                        MessageFile(
                            mime = "audio/mpeg",
                            name = "memo.mp3",
                            source = MessageFileSource(type = "uri", uri = "/tmp/memo.mp3"),
                        ),
                    ),
            )
        assertEquals(MediaSource.ServerFile("/tmp/memo.mp3"), MediaRefs.forMessage(message).single().source)
    }

    @Test
    fun `file uri scheme is stripped`() {
        val refs = MediaRefs.fromText("shot: file:///tmp/a.png")
        assertEquals(MediaSource.ServerFile("/tmp/a.png"), refs.single().source)
    }

    @Test
    fun `the same file in content and output is shown once`() {
        val part =
            Part(
                type = "tool",
                tool = "screenshot",
                state =
                    state(
                        """{"status":"completed",
                            "content":[{"type":"file","uri":"/tmp/a.png","mime":"image/png"}],
                            "output":"saved /tmp/a.png"}""",
                    ),
            )
        assertEquals(1, MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part))).size)
    }

    @Test
    fun `a file content item does not produce a second tile from its own name`() {
        // Device finding: the read tool returns {type:"file", mime, name:"/abs/path.png",
        // uri:"data:..."}. Scanning the state as text found the NAME as a server path,
        // so the same picture appeared twice in one strip.
        val part =
            Part(
                type = "tool",
                tool = "read",
                state =
                    state(
                        """{"status":"completed","content":[
                            {"type":"text","text":"Image read successfully"},
                            {"type":"file","mime":"image/jpeg","name":"/home/me/Pictures/fox.jpg","uri":"data:image/jpeg;base64,AAAA"}
                        ]}""",
                    ),
            )
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part)))
        assertEquals(1, refs.size)
        assertTrue(refs.single().source is MediaSource.Inline)
    }

    @Test
    fun `tool input paths are not treated as generated media`() {
        val part =
            Part(
                type = "tool",
                tool = "read",
                state = state("""{"status":"completed","input":{"filePath":"/tmp/old.png"},"content":[{"type":"text","text":"ok"}]}"""),
            )
        assertTrue(MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part))).isEmpty())
    }

    @Test
    fun `plain text without media yields nothing`() {
        val part = Part(type = "text", text = "All tests pass. No files changed.")
        assertTrue(MediaRefs.forMessage(Message(role = "assistant", parts = listOf(part))).isEmpty())
    }

    @Test
    fun `kind is decided by mime before extension`() {
        assertEquals(MediaKind.VIDEO, MediaRefs.kindOf("video/mp4", "cover.png"))
        assertEquals(MediaKind.IMAGE, MediaRefs.kindOf(null, "cover.PNG"))
        assertNull(MediaRefs.kindOf("application/pdf", "doc.pdf"))
    }

    @Test
    fun `the per message cap is enforced`() {
        val parts = (1..20).map { Part(type = "text", text = "![x](/tmp/$it.png)") }
        val refs = MediaRefs.forMessage(Message(role = "assistant", parts = parts))
        assertEquals(MediaRefs.MAX_PER_MESSAGE, refs.size)
    }
}
