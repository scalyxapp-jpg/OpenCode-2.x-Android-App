package com.opencode.android.util

import java.util.Locale

/**
 * Tool-output presentation strings. Kept Android-free so they can be unit
 * tested (the truncation note used to be an inline German literal).
 */
object ToolText {
    fun truncationSuffix(hiddenChars: Int): String =
        String.format(
            Locale.ENGLISH,
            "… (%d more characters — copy for the full output)",
            hiddenChars,
        )
}
