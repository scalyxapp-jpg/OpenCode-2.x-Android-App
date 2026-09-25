package com.opencode.android
import com.opencode.android.util.AppLog

import android.app.Application
import com.opencode.android.data.MessageCache
import com.opencode.android.data.NetworkMonitor
import com.opencode.android.data.Notifier

@dagger.hilt.android.HiltAndroidApp
class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationInfo)
        installStrictMode()
        installCrashLogger()
        // Stores that only capture the context / register callbacks are cheap.
        MessageCache.init(this)
        NetworkMonitor.init(this)
        Notifier.init(this)
        // Disk-backed stores load off the main thread (StrictMode disk reads).
        AppStartup.initialize(this)
    }

    /**
     * Debug-only StrictMode. This app does real work at startup (network, the
     * on-disk message cache) and has already had main-thread I/O bugs; every
     * accidental disk or network call on the UI thread now shows up as a
     * violation instead of as jank in the field. No-op in release builds.
     */
    private fun installStrictMode() {
        val debuggable = (applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    // A hard crash (native OOM kill, RenderThread fault) can close the app with
    // no dialog, leaving nothing to diagnose. Persist the last uncaught throwable
    // to logcat under a distinct tag and to files/crash_last.txt so it can be
    // read afterwards with:
    //   adb logcat -d -s OpenCodeCrash
    //   adb shell run-as com.opencode.android cat files/crash_last.txt
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLog.e("OpenCodeCrash", "Uncaught in thread ${thread.name}", throwable)
                com.opencode.android.data.ClientLog.report(
                    "error",
                    "uncaught: ${thread.name}: ${throwable::class.java.simpleName}: ${throwable.message}",
                )
                java.io.File(filesDir, "crash_last.txt").writeText(
                    buildString {
                        append(java.util.Date().toString()).append('\n')
                        append("thread=").append(thread.name).append('\n')
                        append(android.util.Log.getStackTraceString(throwable)).append('\n')
                    },
                )
            } catch (_: Throwable) {
                // Never mask the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}