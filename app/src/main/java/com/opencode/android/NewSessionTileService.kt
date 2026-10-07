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
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_NEW_SESSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
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
    }
}
