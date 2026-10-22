package com.opencode.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `api/fs/read/{path}` needs the whole path in ONE slot: a slash that arrives as a
 * literal separator becomes an extra segment and the server answers 404. That is
 * exactly what happened on the device for a generated audio file — so the encoding is
 * pinned here.
 */
class FsPathEncodingTest {
    @Test
    fun `slashes are percent-encoded`() {
        assertEquals(
            "%2Fhome%2Fme%2FAudio%2Fclip.mp3",
            OpenCodeApiAdapter.encodeFsPath("/home/me/Audio/clip.mp3"),
        )
    }

    @Test
    fun `relative paths keep their separators encoded too`() {
        assertEquals("docs%2FCI-GUIDE.md", OpenCodeApiAdapter.encodeFsPath("docs/CI-GUIDE.md"))
    }

    @Test
    fun `spaces become percent-20 not plus`() {
        assertEquals("%2Ftmp%2Fa%20b.png", OpenCodeApiAdapter.encodeFsPath("/tmp/a b.png"))
    }

    @Test
    fun `non-ascii names survive`() {
        assertEquals("%2Ftmp%2FGr%C3%BC%C3%9Fe.png", OpenCodeApiAdapter.encodeFsPath("/tmp/Grüße.png"))
    }

    @Test
    fun `query characters cannot break out of the path slot`() {
        assertEquals("%2Ftmp%2Fa%3Fb%23c.mp4", OpenCodeApiAdapter.encodeFsPath("/tmp/a?b#c.mp4"))
    }
}
