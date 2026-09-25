package com.opencode.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Modern, airy type scale with slightly tighter headings and more readable body.
val OpenCodeTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * Monospace family used for code blocks, diffs and tool output. Defaults to the
 * platform monospace; the "Code Font" setting overrides it with a device font.
 */
val LocalCodeFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/**
 * Builds the type scale with an optional UI font family. A blank/absent setting
 * keeps the platform default (returns the base typography unchanged).
 */
fun openCodeTypography(sansFont: String): Typography {
    val family = deviceFontFamily(sansFont) ?: return OpenCodeTypography
    fun TextStyle.withFamily() = copy(fontFamily = family)
    return Typography(
        displayLarge = OpenCodeTypography.displayLarge.withFamily(),
        displayMedium = OpenCodeTypography.displayMedium.withFamily(),
        displaySmall = OpenCodeTypography.displaySmall.withFamily(),
        headlineLarge = OpenCodeTypography.headlineLarge.withFamily(),
        headlineMedium = OpenCodeTypography.headlineMedium.withFamily(),
        headlineSmall = OpenCodeTypography.headlineSmall.withFamily(),
        titleLarge = OpenCodeTypography.titleLarge.withFamily(),
        titleMedium = OpenCodeTypography.titleMedium.withFamily(),
        titleSmall = OpenCodeTypography.titleSmall.withFamily(),
        bodyLarge = OpenCodeTypography.bodyLarge.withFamily(),
        bodyMedium = OpenCodeTypography.bodyMedium.withFamily(),
        bodySmall = OpenCodeTypography.bodySmall.withFamily(),
        labelLarge = OpenCodeTypography.labelLarge.withFamily(),
        labelMedium = OpenCodeTypography.labelMedium.withFamily(),
        labelSmall = OpenCodeTypography.labelSmall.withFamily(),
    )
}

/**
 * Resolves a user-entered font family name against the device's installed
 * families. Returns null for a blank setting or when the platform refuses the
 * name, so callers fall back to the default instead of crashing.
 */
fun deviceFontFamily(name: String): FontFamily? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    return try {
        FontFamily(Font(androidx.compose.ui.text.font.DeviceFontFamilyName(trimmed)))
    } catch (_: Exception) {
        null
    }
}

/** Code font family for the "Code Font" setting (Monospace when unset). */
@Composable
@ReadOnlyComposable
fun codeFont(): FontFamily = LocalCodeFont.current

// Softer, rounder shapes for a modern feel.
val OpenCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
