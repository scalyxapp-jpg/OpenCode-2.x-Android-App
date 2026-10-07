package com.opencode.android.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.opencode.android.MainActivity
import com.opencode.android.data.LastSessionStore
import com.opencode.android.data.Notifier
import com.opencode.android.data.WidgetStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget: the last session's title, its live status and the active
 * model, plus a one-tap "New session" action. Tapping the card reopens the last
 * conversation (same deep link the notifications use).
 *
 * The widget is a read-only projection of [LastSessionStore] +
 * [WidgetStateStore]; the app pushes refreshes through [refresh].
 */
class SessionWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent { SessionWidgetContent() }
    }
}

@Composable
private fun SessionWidgetContent() {
    val context = LocalContext.current
    val snapshot = WidgetStateStore.snapshot()
    val sessionId = LastSessionStore.sessionId()
    val title = LastSessionStore.title() ?: "OpenCode"
    val status =
        when {
            snapshot.generating -> snapshot.status ?: "Working…"
            sessionId != null -> snapshot.status ?: "Idle"
            else -> "No session yet"
        }
    val model = snapshot.model

    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF1C2025)))
                .padding(16.dp)
                .clickable(actionStartActivity(openSessionIntent(context, sessionId))),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "OpenCode",
            style =
                TextStyle(
                    color = ColorProvider(Color(0xFFC9D2DA)),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
        )
        Spacer(GlanceModifier.height(8.dp))
        Text(
            text = title,
            style = TextStyle(color = ColorProvider(Color(0xFFE6EAEE)), fontSize = 14.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = if (model != null) "$status · $model" else status,
            style = TextStyle(color = ColorProvider(Color(0xFF8B97A3)), fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(12.dp))
        Text(
            text = "＋ New session",
            modifier = GlanceModifier.clickable(actionStartActivity(newSessionIntent(context))),
            style =
                TextStyle(
                    color = ColorProvider(Color(0xFFC9D2DA)),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                ),
        )
    }
}

private fun openSessionIntent(
    context: Context,
    sessionId: String?,
): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (!sessionId.isNullOrBlank()) putExtra(Notifier.EXTRA_SESSION_ID, sessionId)
    }

private fun newSessionIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        action = MainActivity.ACTION_NEW_SESSION
    }

class SessionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SessionWidget()
}

// One process-wide scope instead of a fresh one per refresh: a hung updateAll
// used to leak a new scope/job on every call.
private val widgetRefreshScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + com.opencode.android.util.LogAndSwallow)

/**
 * Re-renders every placed widget from the current stores. Best-effort: a widget
 * update must never crash the caller (e.g. during app teardown) and is bounded
 * so a stuck render cannot pile up.
 */
fun refreshSessionWidget(context: Context) {
    val app = context.applicationContext
    widgetRefreshScope.launch {
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) { SessionWidget().updateAll(app) }
        }
    }
}
