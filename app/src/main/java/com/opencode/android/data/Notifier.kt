package com.opencode.android.data
import com.opencode.android.util.AppLog
import com.opencode.android.util.APP_LOG_TAG

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opencode.android.R
import com.opencode.android.MainActivity

/**
 * System notifications + sound effects, driven by the Settings toggles that
 * previously did nothing (notifyAgent / notifyPermissions / notifyErrors and
 * the sound* pairs).
 *
 * Channels are created silently and the sound is played explicitly, so the
 * sound setting is independent of the notification setting (as in the web UI).
 */
object Notifier {
    private const val CH_AGENT = "agent"
    private const val CH_PERMISSIONS = "permissions"
    private const val CH_ERRORS = "errors"

    private const val ID_AGENT = 1001
    private const val ID_PERMISSIONS = 1002
    private const val ID_ERRORS = 1003

    /** Extra on the tap intent so the app opens the session the event belongs to. */
    const val EXTRA_SESSION_ID = "com.opencode.android.extra.SESSION_ID"

    @Volatile
    private var appContext: Context? = null

    // The same turn can end via several event names (session.status idle,
    // session.idle, session.next.step.ended); don't play the sound repeatedly.
    @Volatile
    private var lastSoundAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        // Channels exist only on O+ (minSdk 25): guard, notifications still
        // work below O via NotificationCompat without a channel.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager, CH_AGENT, context.getString(R.string.settings_agent))
        createChannel(manager, CH_PERMISSIONS, context.getString(R.string.settings_permissions))
        createChannel(manager, CH_ERRORS, context.getString(R.string.settings_errors))
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun createChannel(manager: NotificationManager, id: String, name: String) {
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)
        // Sound is played explicitly (see playSound) so it stays independent of
        // the notification switch.
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    /** The agent finished a turn or needs attention. */
    fun agent(title: String, text: String, sessionId: String? = null) {
        val settings = AppSettingsStore.state.value
        if (settings.notifyAgent) post(CH_AGENT, ID_AGENT, title, text, sessionId)
        if (settings.soundAgentEnabled) playSound(settings.soundAgent)
    }

    /** A tool permission is required. */
    fun permission(title: String, text: String, sessionId: String? = null) {
        val settings = AppSettingsStore.state.value
        if (settings.notifyPermissions) post(CH_PERMISSIONS, ID_PERMISSIONS, title, text, sessionId)
        if (settings.soundPermissionsEnabled) playSound(settings.soundPermissions)
    }

    /** A provider/session error occurred. */
    fun error(title: String, text: String, sessionId: String? = null) {
        val settings = AppSettingsStore.state.value
        if (settings.notifyErrors) post(CH_ERRORS, ID_ERRORS, title, text, sessionId)
        if (settings.soundErrorsEnabled) playSound(settings.soundErrors)
    }

    private fun post(channel: String, id: Int, title: String, text: String, sessionId: String?) {
        val context = appContext ?: return
        val openApp = PendingIntent.getActivity(
            context,
            // Distinct request code per session so two notifications do not
            // overwrite each other's intent extras.
            sessionId?.hashCode() ?: 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to do.
            AppLog.e(APP_LOG_TAG, "Notifier: notify denied: ${e.message}")
        }
    }

    /**
     * Plays the configured sound. The web sound names are mapped onto the
     * device's notification/alarm ringtones (the web assets are not shipped).
     */
    private fun playSound(name: String) {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastSoundAt < 1_500L) return
        lastSoundAt = now
        try {
            val type = ringtoneTypeFor(name)
            val uri = RingtoneManager.getDefaultUri(type) ?: return
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "Notifier: sound failed: ${e.message}")
        }
    }

    /**
     * Maps a web sound name onto a device ringtone type. The web sound assets
     * are not shipped, so "nope" (the error sound) uses the alarm channel and
     * everything else the notification channel.
     */
    internal fun ringtoneTypeFor(name: String): Int =
        if (name.contains("nope", ignoreCase = true)) {
            RingtoneManager.TYPE_ALARM
        } else {
            RingtoneManager.TYPE_NOTIFICATION
        }
}
