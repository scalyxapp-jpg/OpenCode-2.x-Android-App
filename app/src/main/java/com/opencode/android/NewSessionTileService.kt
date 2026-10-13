package com.opencode.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that drops the user straight into a new session. Tapping
 * the tile opens the app with [MainActivity.ACTION_NEW_SESSION], which routes
 * to the Home/session list regardless of the current screen.
 */
class NewSessionTileService : TileService() {
    // The Intent overload is used only on API < 34, where the PendingIntent
    // overload does not exist yet (see the version guard below).
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        // Subtitle = ambient decision count, so the tile doubles as the glance
        // surface instead of adding a second tile.
        runCatching { updateSubtitle() }
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_NEW_SESSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        // startActivityAndCollapse can throw in restricted states (app standby
        // bucket, system UI busy); an uncaught throw here hits the QS host.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ requires the PendingIntent overload; the Intent overload is
                // deprecated and throws on some OEM builds.
                val pending =
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { error ->
            com.opencode.android.util.AppLog.w(com.opencode.android.util.APP_LOG_TAG) {
                "NewSessionTileService: could not start activity: ${error.message}"
            }
        }
    }

    /**
     * Shows the open decisions in the tile's subtitle (API 29+); a no-op below that.
     * Counters only — the tile never carries prompt text.
     */

    /**
     * Refreshes the subtitle whenever the Quick Settings panel opens.
     *
     * The subtitle used to be written only in [onClick] — i.e. at the moment the tile
     * collapses into the app, so nobody ever saw it. Reading the ambient count here is
     * what makes the tile a glance surface.
     */
    override fun onStartListening() {
        super.onStartListening()
        runCatching { updateSubtitle() }
    }

    private fun updateSubtitle() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val pending =
            com.opencode.android.data.GlanceStore
                .facts()
                .pendingDecisions
        val text =
            if (pending > 0) {
                getString(com.opencode.android.R.string.glance_tile_pending, pending)
            } else {
                getString(com.opencode.android.R.string.glance_tile_idle)
            }
        qsTile?.subtitle = text
    }
}
