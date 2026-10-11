package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Project-directory identity. A plain string compare against the server's spelling
 * missed the user's own entry, which is how a deleted project came back and how one
 * project could be listed twice.
 */
class ProjectDirsTest {
    @Test
    fun `trailing and doubled separators are collapsed`() {
        assertEquals("/home/u/proj", normalizeProjectDir("/home/u/proj/"))
        assertEquals("/home/u/proj", normalizeProjectDir("/home/u//proj"))
        assertEquals("/home/u/proj", normalizeProjectDir("  /home/u/proj/  "))
    }

    @Test
    fun `root stays root and is not emptied`() {
        assertEquals("/", normalizeProjectDir("/"))
        assertEquals("/", normalizeProjectDir(""))
        assertEquals("/", normalizeProjectDir(null))
        assertEquals("/", normalizeProjectDir("///"))
    }

    @Test
    fun `same directory matches across spellings`() {
        assertTrue(sameProjectDir("/home/u/proj/", "/home/u/proj"))
        assertTrue(sameProjectDir("/home/u/proj", "/home/u//proj/"))
        assertFalse(sameProjectDir("/home/u/proj", "/home/u/other"))
        assertFalse(sameProjectDir(null, "/home/u/proj"))
    }
}
