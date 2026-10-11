package com.opencode.android.ui.theme

import androidx.annotation.StringRes
import com.opencode.android.R

/**
 * One selectable font family.
 *
 * [id] is the platform font family name resolved via
 * `androidx.compose.ui.text.font.DeviceFontFamilyName` (e.g. `"sans-serif"`);
 * an empty id means "platform default". [labelRes] is the user-facing name.
 */
data class FontOption(
    val id: String,
    @StringRes val labelRes: Int,
)

/**
 * Predefined font families for the appearance settings.
 *
 * Why a fixed list instead of a free-text field: the value is a DEVICE font family
 * name. Typing anything the device does not have silently resolved to the default, so
 * a change looked like it did nothing. The names below are the platform aliases every
 * Android device ships (AOSP `fonts.xml`), so each entry is guaranteed to resolve.
 */
object FontCatalog {
    /** Platform default (no override). */
    const val SYSTEM = ""

    val uiFonts: List<FontOption> =
        listOf(
            FontOption(SYSTEM, R.string.font_system_default),
            FontOption("sans-serif", R.string.font_roboto),
            FontOption("sans-serif-light", R.string.font_sans_light),
            FontOption("sans-serif-medium", R.string.font_sans_medium),
            FontOption("sans-serif-condensed", R.string.font_sans_condensed),
            FontOption("sans-serif-black", R.string.font_sans_black),
            FontOption("sans-serif-smallcaps", R.string.font_sans_small_caps),
            FontOption("serif", R.string.font_serif),
            FontOption("casual", R.string.font_casual),
            FontOption("cursive", R.string.font_cursive),
        )

    val codeFonts: List<FontOption> =
        listOf(
            FontOption(SYSTEM, R.string.font_system_mono),
            FontOption("monospace", R.string.font_monospace),
            FontOption("serif-monospace", R.string.font_serif_monospace),
        )

    /**
     * [base] plus the stored value when it is not part of the list.
     *
     * A value written by an older build (or a hand-edited config) must stay
     * selectable instead of being silently replaced on the next save.
     */
    fun optionsWith(
        stored: String,
        base: List<FontOption>,
    ): List<FontOption> =
        if (stored.isBlank() || base.any { it.id == stored }) {
            base
        } else {
            base + FontOption(stored, R.string.font_custom)
        }
}
