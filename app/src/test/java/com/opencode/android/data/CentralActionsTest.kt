package com.opencode.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The central action layer.
 *
 * Every surface (Centre, notification, widget, chat) performs its writes through it, so what is
 * pinned here is the part that used to drift: the guard for a switched-off feature, the result
 * the caller has to handle, and the fact that a transport failure becomes a readable message
 * instead of an exception thrown into a UI coroutine.
 */
class CentralActionsTest {
    private class Recorder {
        val interrupts = mutableListOf<String>()
        val steers = mutableListOf<Pair<String, String>>()
        val compactions = mutableListOf<String>()
        val handOvers = mutableListOf<String>()
        var interruptFails = false
        var steerSucceeds = true
        var handOverSucceeds = true
        var handOverThrows = false
    }

    private fun actions(
        recorder: Recorder,
        journalInjectEnabled: Boolean = true,
    ) = CentralActions(
        interrupt = { sessionId ->
            if (recorder.interruptFails) error("HTTP 500")
            recorder.interrupts += sessionId
        },
        steer = { sessionId, inboxId ->
            recorder.steers += sessionId to inboxId
            recorder.steerSucceeds
        },
        compact = { sessionId, _ -> recorder.compactions += sessionId },
        handDecisionOver = { sessionId, text, _, _ ->
            if (recorder.handOverThrows) error("network down")
            recorder.handOvers += "$sessionId:$text"
            recorder.handOverSucceeds
        },
        setting = { setting ->
            when (setting) {
                CentralSetting.JournalInjectEnabled -> journalInjectEnabled
            }
        },
    )

    private val entry =
        JournalEntry(
            id = "dec_1",
            projectDir = "/p",
            text = "The schema stays additive",
            description = null,
        )

    @Test
    fun `stopping a turn reaches the server`() =
        runBlocking {
            val recorder = Recorder()
            assertEquals(ActionResult.Ok, actions(recorder).stop("ses_1", "Fix the bug"))
            assertEquals(listOf("ses_1"), recorder.interrupts)
        }

    @Test
    fun `a transport failure becomes a readable result instead of an exception`() =
        runBlocking {
            val recorder = Recorder().apply { interruptFails = true }
            val result = actions(recorder).stop("ses_1", "Fix the bug")
            assertTrue(result is ActionResult.Failed)
            assertTrue((result as ActionResult.Failed).message.isNotBlank())
        }

    @Test
    fun `the emergency stop interrupts every turn and reports how many`() =
        runBlocking {
            val recorder = Recorder()
            assertEquals(
                ActionResult.Ok,
                actions(recorder).stopAll(listOf("ses_1" to "A", "ses_2" to "B")),
            )
            assertEquals(listOf("ses_1", "ses_2"), recorder.interrupts)

            // Nothing running: skipped, not silently "ok".
            assertTrue(actions(Recorder()).stopAll(emptyList()) is ActionResult.Skipped)
        }

    @Test
    fun `steering releases exactly the named queued prompt`() =
        runBlocking {
            val recorder = Recorder()
            assertEquals(ActionResult.Ok, actions(recorder).steer("ses_1", "msg_9", "queued text"))
            assertEquals(listOf("ses_1" to "msg_9"), recorder.steers)

            // The server refused: the caller must know.
            val refused = Recorder().apply { steerSucceeds = false }
            assertTrue(actions(refused).steer("ses_1", "msg_9", "") is ActionResult.Failed)
        }

    @Test
    fun `compacting asks the server for the session`() =
        runBlocking {
            val recorder = Recorder()
            assertEquals(ActionResult.Ok, actions(recorder).compact("ses_7", "Long session", "/p"))
            assertEquals(listOf("ses_7"), recorder.compactions)
        }

    @Test
    fun `handing a decision over respects the master switch`() =
        runBlocking {
            var called = false
            val switchedOff =
                CentralActions(
                    interrupt = {},
                    steer = { _, _ -> true },
                    compact = { _, _ -> },
                    handDecisionOver = { _, _, _, _ ->
                        called = true
                        true
                    },
                    setting = { false },
                )
            val skipped = switchedOff.handOverDecision("ses_1", "Title", entry, null)
            assertTrue(skipped is ActionResult.Skipped)
            // The guard must prevent the request, not just the message.
            assertEquals(false, called)
        }

    @Test
    fun `handing a decision over reports a rejection and swallows a transport error`() =
        runBlocking {
            val refused = Recorder().apply { handOverSucceeds = false }
            val refusedResult = actions(refused).handOverDecision("ses_1", "Title", entry, null)
            assertTrue(refusedResult is ActionResult.Failed)

            val broken = Recorder().apply { handOverThrows = true }
            val brokenResult = actions(broken).handOverDecision("ses_1", "Title", entry, null)
            assertTrue(brokenResult is ActionResult.Failed)
            assertTrue((brokenResult as ActionResult.Failed).message.isNotBlank())
        }
}
