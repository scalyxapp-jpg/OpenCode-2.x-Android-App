package com.opencode.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a dictation failure is classified.
 *
 * The bug this pins: "heard nothing" (`ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT`) was reported as
 * "no speech recognition available on this device" - a claim about the hardware, shown for an
 * everyday event (a pause, a quiet room, a mis-tap). That is why dictation worked sometimes and
 * insisted the device cannot do it other times.
 */
class SpeechErrorPolicyTest {
    @Test
    fun `heard nothing stays silent`() {
        assertEquals(SpeechError.NoSpeech, SpeechErrorPolicy.classify(SpeechErrorPolicy.NO_MATCH))
        assertEquals(SpeechError.NoSpeech, SpeechErrorPolicy.classify(SpeechErrorPolicy.SPEECH_TIMEOUT))
        assertEquals(false, SpeechErrorPolicy.isReportable(SpeechError.NoSpeech))
    }

    @Test
    fun `a busy recognizer invites a retry instead of a device verdict`() {
        assertEquals(SpeechError.Busy, SpeechErrorPolicy.classify(SpeechErrorPolicy.BUSY))
        assertEquals(true, SpeechErrorPolicy.isReportable(SpeechError.Busy))
    }

    @Test
    fun `network and permission problems are named as such`() {
        assertEquals(SpeechError.Network, SpeechErrorPolicy.classify(SpeechErrorPolicy.NETWORK))
        assertEquals(SpeechError.Network, SpeechErrorPolicy.classify(SpeechErrorPolicy.NETWORK_TIMEOUT))
        assertEquals(SpeechError.Permission, SpeechErrorPolicy.classify(SpeechErrorPolicy.INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `expected noise after releasing the button is not reported`() {
        // Reported bug: "speech recognition failed" appeared although the dictation had worked.
        // The engine emits exactly these after a release, after a result, and from a stale
        // attempt - none of it is worth a message.
        assertEquals(
            false,
            SpeechErrorPolicy.shouldReport(
                error = SpeechError.Failed,
                stale = false,
                stoppedByUser = true,
                delivered = false,
            ),
        )
        assertEquals(
            false,
            SpeechErrorPolicy.shouldReport(
                error = SpeechError.Failed,
                stale = false,
                stoppedByUser = false,
                delivered = true,
            ),
        )
        assertEquals(
            false,
            SpeechErrorPolicy.shouldReport(
                error = SpeechError.Failed,
                stale = true,
                stoppedByUser = false,
                delivered = false,
            ),
        )
        assertEquals(
            false,
            SpeechErrorPolicy.shouldReport(
                error = SpeechError.NoSpeech,
                stale = false,
                stoppedByUser = false,
                delivered = false,
            ),
        )
    }

    @Test
    fun `what was already heard is rescued instead of reporting a failure`() {
        // An engine that fails at the end usually heard most of the sentence: use it.
        assertEquals(true, SpeechErrorPolicy.shouldRescuePartial("die letzten Worte fehlen", delivered = false))
        // Nothing heard -> the failure stands (and gets reported).
        assertEquals(false, SpeechErrorPolicy.shouldRescuePartial("   ", delivered = false))
        // Already delivered -> no duplicate insert.
        assertEquals(false, SpeechErrorPolicy.shouldRescuePartial("schon drin", delivered = true))
    }

    @Test
    fun `a real failure while the button is held still speaks up`() {
        assertEquals(
            true,
            SpeechErrorPolicy.shouldReport(
                error = SpeechError.Network,
                stale = false,
                stoppedByUser = false,
                delivered = false,
            ),
        )
        assertEquals(
            true,
            SpeechErrorPolicy.shouldReport(
                error = SpeechError.Unavailable,
                stale = false,
                stoppedByUser = false,
                delivered = false,
            ),
        )
    }

    @Test
    fun `everything else keeps the recognizer's own reason`() {
        assertEquals(SpeechError.Failed, SpeechErrorPolicy.classify(SpeechErrorPolicy.AUDIO))
        assertEquals(SpeechError.Failed, SpeechErrorPolicy.classify(SpeechErrorPolicy.SERVER))
        assertEquals(SpeechError.Failed, SpeechErrorPolicy.classify(SpeechErrorPolicy.CLIENT))
        assertEquals(SpeechError.Failed, SpeechErrorPolicy.classify(12345))
        // Only "unavailable" is reserved for a genuine "no recognizer could be created".
        assertEquals(true, SpeechErrorPolicy.isReportable(SpeechError.Unavailable))
    }
}
