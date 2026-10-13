package com.opencode.android.ui

import com.opencode.android.R
import com.opencode.android.domain.CacheTokens
import com.opencode.android.domain.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The compaction block's wording and usage line must match the web exactly —
 * these two pure helpers drive what the user reads after `/compact`.
 */
class CompactionMessageTest {
    @Test
    fun `a finished compaction says compacted`() {
        assertEquals(R.string.compaction_completed, compactionOutcomeRes("completed", null))
    }

    @Test
    fun `a failure says failed, an abort says cancelled`() {
        assertEquals(R.string.compaction_failed, compactionOutcomeRes("failed", "CompactionFailedError"))
        assertEquals(R.string.compaction_cancelled, compactionOutcomeRes("failed", "MessageAbortedError"))
        assertEquals(R.string.compaction_interrupted, compactionOutcomeRes("failed", "Compaction.Interrupted"))
    }

    @Test
    fun `usage counts cache as input and reasoning as output`() {
        val tokens =
            Tokens(
                input = 16_565,
                output = 92,
                reasoning = 48,
                cache = CacheTokens(read = 18_432, write = 0),
            )
        assertEquals("34,997 in · 140 out", compactionUsage(tokens))
    }

    @Test
    fun `usage is omitted without tokens or usage`() {
        assertNull(compactionUsage(null))
        assertNull(compactionUsage(Tokens(input = 0, output = 0, reasoning = 0)))
    }
}
