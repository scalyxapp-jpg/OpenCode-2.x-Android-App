package com.opencode.android.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "/" picker must never offer a command that does nothing. Client built-ins
 * are only listed when the app implements them, so the advertised list and the
 * handlers in `ChatViewModel.runBuiltinCommand` have to stay in lockstep.
 */
class BuiltinCommandsTest {
    @Test
    fun `every advertised built-in is implemented`() {
        val unimplemented = BUILTIN_COMMANDS.map { it.name }.filterNot { it in IMPLEMENTED_BUILTIN_COMMANDS }
        assertTrue("built-ins listed without a handler: $unimplemented", unimplemented.isEmpty())
    }

    @Test
    fun `no handler is advertised that is not offered`() {
        val advertised = BUILTIN_COMMANDS.map { it.name }.toSet()
        val orphanHandlers = IMPLEMENTED_BUILTIN_COMMANDS.filterNot { it in advertised }
        assertTrue("handlers with no list entry: $orphanHandlers", orphanHandlers.isEmpty())
    }
}
