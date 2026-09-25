package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the relative-directory requirement of the /find/file search. */
class PathSearchTest {

    @Test
    fun `home itself maps to dot`() {
        assertEquals(".", relativeSearchDir("/home/user", "/home/user"))
        assertEquals(".", relativeSearchDir("/home/user/", "/home/user"))
    }

    @Test
    fun `subdirectory maps to its relative path`() {
        assertEquals(
            "skills-all",
            relativeSearchDir("/home/user/skills-all", "/home/user"),
        )
        assertEquals(
            "a/b/c",
            relativeSearchDir("/home/user/a/b/c", "/home/user"),
        )
    }

    @Test
    fun `outside home falls back to dot`() {
        assertEquals(".", relativeSearchDir("/etc", "/home/user"))
    }

    @Test
    fun `underHome detection`() {
        assertTrue(isUnderHome("/home/user", "/home/user"))
        assertTrue(isUnderHome("/home/user/a/b", "/home/user"))
        assertFalse(isUnderHome("/home/user2", "/home/user"))
        assertFalse(isUnderHome("/etc", "/home/user"))
    }

    @Test
    fun `lastPathSegment trims a trailing slash`() {
        assertEquals("b", lastPathSegment("/a/b"))
        assertEquals("b", lastPathSegment("/a/b/"))
    }

    @Test
    fun `lastPathSegment keeps the root`() {
        assertEquals("/", lastPathSegment("/"))
        // An empty path stays empty rather than inventing a root.
        assertEquals("", lastPathSegment(""))
    }

    @Test
    fun `lastPathSegment returns a bare name unchanged`() {
        assertEquals("file.kt", lastPathSegment("file.kt"))
    }
}
