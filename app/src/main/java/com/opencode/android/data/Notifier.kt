package com.opencode.android.data
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opencode.android.MainActivity
import com.opencode.android.NotificationActionReceiver
import com.opencode.android.R
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog

/**
 * System notifications + sound effects, driven by the Settings toggles
 * (notifyAgent / notifyPermissions / notifyErrors and the sound* pairs).
 *
 * Channels are created silently and the sound is played explicitly, so the
 * sound setting is independent of the notification setting (as in the web UI).
 *
 * Permission notifications are **actionable**: Allow once / Always / Deny are
 * posted straight to the server by [NotificationActionReceiver], so a pending
 * tool approval can be cleared from the shade without opening the app.
 */
object Notifier {
    private const val CH_AGENT = "agent"
    private const val CH_PERMISSIONS = "permissions"
    private const val CH_ERRORS = "errors"
    private const val CH_CONNECTION = "connection"

    private const val ID_AGENT = 1001
    private const val ID_PERMISSIONS = 1002
    private const val ID_ERRORS = 1003
    const val ID_CONNECTION = 1004

    /** Extras on the tap intent so the app opens the session the event belongs to. */
    const val EXTRA_SESSION_ID = "com.opencode.android.extra.SESSION_ID"
    const val EXTRA_REQUEST_ID = "com.opencode.android.extra.REQUEST_ID"
    const val EXTRA_REPLY = "com.opencode.android.extra.REPLY"

    /** Receiver actions. */
    const val ACTION_PERMISSION_REPLY = "com.opencode.android.action.PERMISSION_REPLY"

    /** Protocol reply values for a permission request. */
    const val REPLY_ONCE = "once"
    const val REPLY_ALWAYS = "always"
    const val REPLY_REJECT = "reject"

    @Volatile
    private var appContext: Context? = null

    // The same turn can end via several event names (session.status idle,
    // session.idle); don't play the sound repeatedly. Step boundaries
    // (session.next.step.ended) no longer notify — a turn has many steps.
    @Volatile
    private var lastSoundAt = 0L

    // Serialises the throttle check-and-set and lets the previous ringtone be
    // stopped before a new one plays (and never leaked).
    private val soundLock = Any()

    @Volatile
    private var currentRingtone: android.media.Ringtone? = null

    // Request ids already posted. The server re-serves the whole pending list on
    // every load, so without this each reconcile re-notified the same prompt.
    // Capped so a long-lived process cannot grow them without bound.
    private const val MAX_NOTIFIED_IDS = 500
    private val notifiedPermissions: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    private val notifiedQuestions: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

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
    private fun createChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    ) {
        val channel = NotificationChannel(id, name, importance)
        // Sound is played explicitly (see playSound) so it stays independent of
        // the notification switch.
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    /**
     * Channel for the ongoing "connected" notification of [ConnectionService].
     * IMPORTANCE_MIN: it must exist but must not make a sound or pop up.
     */
    fun ensureConnectionChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager, CH_CONNECTION, "Connection", NotificationManager.IMPORTANCE_MIN)
    }

    /** The ongoing notification that keeps the foreground service alive. */
    fun connectionNotification(): android.app.Notification {
        val context = appContext ?: error("Notifier.init was not called")
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.connection_active))
            .setContentText(context.getString(R.string.connection_active_sub))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
            .build()
    }

    /** The agent finished a turn or needs attention. */
    fun agent(
        title: String,
        text: String,
        sessionId: String? = null,
    ) {
        val settings = AppSettingsStore.state.value
        if (settings.notifyAgent) post(CH_AGENT, ID_AGENT, title, text, sessionId)
        if (settings.soundAgentEnabled) playSound(settings.soundAgent)
    }

    /**
     * Posts one actionable notification per newly seen pending tool permission.
     * Idempotent per request id, so repeated list loads do not re-alert.
     */
    fun permissionRequests(
        sessionId: String?,
        requests: List<PermissionRequest>,
    ) {
        if (!AppSettingsStore.state.value.notifyPermissions) return
        val context = appContext ?: return
        // Bound the dedupe set: a long-lived process could accumulate ids for
        // requests that are never answered/cleared.
        if (notifiedPermissions.size > MAX_NOTIFIED_IDS) notifiedPermissions.clear()
        requests.forEach { request ->
            val requestId = request.id ?: return@forEach
            if (!notifiedPermissions.add(requestId)) return@forEach
            val title = request.title?.ifBlank { null } ?: "Permission required"
            val text =
                request.description?.ifBlank { null }
                    ?: "${request.permission ?: "A tool"} needs your approval"
            val open = openAppIntent(context, sessionId, requestId.hashCode())
            val notification =
                NotificationCompat
                    .Builder(context, CH_PERMISSIONS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    // Tool/permission text can include paths and commands; keep
                    // it off the lock screen by default.
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .addAction(0, "Allow once", permissionAction(context, sessionId, requestId, REPLY_ONCE))
                    .addAction(0, "Always", permissionAction(context, sessionId, requestId, REPLY_ALWAYS))
                    .addAction(0, "Deny", permissionAction(context, sessionId, requestId, REPLY_REJECT))
                    .build()
            try {
                NotificationManagerCompat
                    .from(context)
                    .notify(permissionNotificationId(requestId), notification)
            } catch (e: SecurityException) {
                AppLog.e(APP_LOG_TAG, "Notifier: permission notify denied: ${e.message}")
            }
        }
    }

    /** Plays the permission alert sound (posted on the `permission.asked` event). */
    fun permissionSound() {
        val settings = AppSettingsStore.state.value
        if (settings.soundPermissionsEnabled) playSound(settings.soundPermissions)
    }

    /** Cancels the notification for a request that was answered (or expired). */
    fun clearPermission(requestId: String) {
        notifiedPermissions.remove(requestId)
        val context = appContext ?: return
        try {
            NotificationManagerCompat.from(context).cancel(permissionNotificationId(requestId))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked — nothing to cancel.
        }
    }

    /**
     * The agent asked a question and is blocked until it is answered. Answering
     * needs the in-app form, so the notification only deep-links to the session.
     */
    fun question(
        question: SessionQuestion,
        sessionId: String? = null,
    ) {
        if (!AppSettingsStore.state.value.notifyPermissions) return
        if (notifiedQuestions.size > MAX_NOTIFIED_IDS) notifiedQuestions.clear()
        if (!notifiedQuestions.add(question.id)) return
        val context = appContext ?: return
        val text =
            question.questions
                .firstOrNull()
                ?.question
                ?.ifBlank { null }
                ?: "The agent needs an answer to continue"
        val title =
            question.questions
                .firstOrNull()
                ?.header
                ?.ifBlank { null }
                ?: "Question from the agent"
        val open = openAppIntent(context, sessionId ?: question.sessionId, question.id.hashCode())
        val notification =
            NotificationCompat
                .Builder(context, CH_PERMISSIONS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .addAction(0, "Answer", open)
                .build()
        try {
            NotificationManagerCompat
                .from(context)
                .notify(questionNotificationId(question.id), notification)
        } catch (e: SecurityException) {
            AppLog.e(APP_LOG_TAG, "Notifier: question notify denied: ${e.message}")
        }
    }

    /** A provider/session error occurred. */
    fun error(
        title: String,
        text: String,
        sessionId: String? = null,
    ) {
        val settings = AppSettingsStore.state.value
        if (settings.notifyErrors) post(CH_ERRORS, ID_ERRORS, title, text, sessionId)
        if (settings.soundErrorsEnabled) playSound(settings.soundErrors)
    }

    private fun post(
        channel: String,
        id: Int,
        title: String,
        text: String,
        sessionId: String?,
    ) {
        val context = appContext ?: return
        val openApp = openAppIntent(context, sessionId, stableRequestCode(sessionId ?: "agent"))
        val notification =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Agent/error text can carry user content; keep it off the lock screen.
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to do.
            AppLog.e(APP_LOG_TAG, "Notifier: notify denied: ${e.message}")
        }
    }

    /**
     * Tap intent that opens [MainActivity] on the session. Distinct request code
     * per session/request so two notifications do not overwrite each other's
     * extras (FLAG_UPDATE_CURRENT would otherwise clobber them).
     */
    private fun openAppIntent(
        context: Context,
        sessionId: String?,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!sessionId.isNullOrBlank()) {
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    // `data` participates in PendingIntent.filterEquals, so two
                    // different sessions can never collapse into one intent.
                    data = android.net.Uri.parse("opencode://session/$sessionId")
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Broadcast intent that answers a permission request without opening the app. */
    private fun permissionAction(
        context: Context,
        sessionId: String?,
        requestId: String,
        reply: String,
    ): PendingIntent {
        val intent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_PERMISSION_REPLY
                // The data URI is what makes filterEquals distinguish the three
                // actions: request-code arithmetic was not collision-free (and
                // could be negative), so FLAG_UPDATE_CURRENT could overwrite one
                // action's extras with another's — tapping "Allow once" could
                // send the wrong reply.
                data = android.net.Uri.parse("opencode://permission/$requestId/$reply")
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_REPLY, reply)
                if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
            }
        return PendingIntent.getBroadcast(
            context,
            stableRequestCode("$requestId:$reply"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Non-negative, stable request code (hashCode can be negative). */
    private fun stableRequestCode(key: String): Int = key.hashCode() and 0x7FFFFFFF

    // Bases far above the fixed 1001..1004 ids so a request hash can never
    // collide with the agent/error/connection notifications.
    private fun permissionNotificationId(requestId: String): Int = 200_000 + (requestId.hashCode() and 0x0FFFFFFF)

    private fun questionNotificationId(requestId: String): Int = 300_000 + (requestId.hashCode() and 0x0FFFFFFF)

    /**
     * Plays the configured sound. The web sound names are mapped onto the
     * device's notification/alarm ringtones (the web assets are not shipped).
     */
    private fun playSound(name: String) {
        val context = appContext ?: return
        // Atomic throttle + retain the Ringtone so it can be stopped: the old
        // code played a fresh ringtone and discarded the reference, so the alarm
        // stream (used for the "nope" error sound) kept playing and rapid
        // notifications overlapped.
        synchronized(soundLock) {
            val now = System.currentTimeMillis()
            if (now - lastSoundAt < 1_500L) return
            lastSoundAt = now
            try {
                currentRingtone?.stop()
                currentRingtone = null
                val uri = RingtoneManager.getDefaultUri(ringtoneTypeFor(name)) ?: return
                RingtoneManager.getRingtone(context, uri)?.also {
                    // Ringtone#setLooping was added in API 28. Below that the
                    // notification ringtone does not loop anyway, so skip it
                    // instead of crashing on Android 7.0–8.1.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        it.setLooping(false)
                    }
                    it.play()
                    currentRingtone = it
                }
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "Notifier: sound failed: ${e.message}")
            }
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
