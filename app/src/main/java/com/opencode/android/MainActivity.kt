package com.opencode.android

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.android.data.AppSettingsStore
import com.opencode.android.ui.OpenCodeApp
import com.opencode.android.ui.theme.OpenCodeTheme

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Apply the persisted appearance settings (color scheme, theme,
            // font size and fonts). Without this the Settings screen was inert.
            val settings by AppSettingsStore.state.collectAsStateWithLifecycle()
            val darkTheme = when (settings.colorScheme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // Android 13+ requires a runtime grant for notifications.
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            // Theme/mode switches apply via full activity recreate: a plain
            // recompose left stale colours on screen (stored value changed,
            // headers kept the old accent until restart). Recreate is the
            // platform-standard path for theme switches.
            val appliedLook = remember { mutableStateOf(settings.theme to settings.colorScheme) }
            LaunchedEffect(settings.theme, settings.colorScheme) {
                val current = settings.theme to settings.colorScheme
                if (appliedLook.value != current) {
                    appliedLook.value = current
                    recreate()
                }
            }
            OpenCodeTheme(
                darkTheme = darkTheme,
                themeId = settings.theme,
                fontScale = settings.fontSize / 14f,
                sansFont = settings.sansFont,
                monoFont = settings.monoFont,
            ) {
                OpenCodeApp()
                // The first frame is on screen, so the content is usable.
                // Without this the platform only records time-to-INITIAL-display
                // and startup regressions hide behind the empty window.
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    reportFullyDrawn()
                }
            }
        }
    }
}