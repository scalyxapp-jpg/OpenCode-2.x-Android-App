package com.opencode.android.data

import android.media.RingtoneManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The web sound names are mapped onto device ringtone types; "nope" is the
 * error sound. Guards the mapping used by the previously-inert sound settings.
 */
class NotifierSoundTest {

    @Test
    fun `error sound uses the alarm ringtone`() {
        assertEquals(RingtoneManager.TYPE_ALARM, Notifier.ringtoneTypeFor("nope-03"))
    }

    @Test
    fun `default sounds use the notification ringtone`() {
        assertEquals(RingtoneManager.TYPE_NOTIFICATION, Notifier.ringtoneTypeFor("staplebops-01"))
        assertEquals(RingtoneManager.TYPE_NOTIFICATION, Notifier.ringtoneTypeFor("staplebops-02"))
    }
}
