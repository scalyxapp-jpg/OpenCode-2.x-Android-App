package com.opencode.android.util

/**
 * Single logcat tag for the whole app. 78 call sites repeated the literal
 * "OpenCodeApp"; the crash logger deliberately keeps its own tag so crash lines
 * stay greppable on their own.
 */
const val APP_LOG_TAG = "OpenCodeApp"
