package com.opencode.android.data

import android.app.Activity
import android.app.Application

/**
 * Whether the app currently has a visible Activity — "in focus" or not.
 *
 * The background alerts (step progress, finished summary) must stay silent while
 * the user is looking at the screen: a heads-up for something that is already
 * rendered in the chat is noise. Android has no lookup for this without a
 * Context, so the state is derived from the activity lifecycle.
 *
 * The default is [isForeground] `true`: a cold start has no started Activity yet,
 * and assuming "visible" only suppresses alerts until the first real transition
 * (which is the harmless direction).
 *
 * The counter is public-internal so the transitions are unit-testable without an
 * Android runtime.
 */
object AppVisibility {
    private var startedActivities = 0

    @Volatile
    private var foreground = true

    /**
     * Invoked with the NEW value whenever the visibility flips. The watcher uses
     * it to start collecting only when the app leaves the foreground.
     */
    @Volatile
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    val isForeground: Boolean
        get() = foreground

    val isBackground: Boolean
        get() = !foreground

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = onStarted()

                override fun onActivityStopped(activity: Activity) = onStopped()

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: android.os.Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: android.os.Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** One more visible Activity (the first one means "the user is here"). */
    internal fun onStarted() {
        synchronized(this) { startedActivities += 1 }
        publish(true)
    }

    /** One Activity less; zero started Activities means the app is in the background. */
    internal fun onStopped() {
        val stillVisible =
            synchronized(this) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                startedActivities > 0
            }
        publish(stillVisible)
    }

    private fun publish(visible: Boolean) {
        val changed = foreground != visible
        foreground = visible
        if (changed) onVisibilityChanged?.invoke(visible)
    }
}
