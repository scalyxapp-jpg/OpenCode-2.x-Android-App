package com.opencode.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density

/*
 * OpenCode brand: black / white / wine red.
 *
 * The scheme is derived from two ramps instead of hand-picked one-off colours,
 * so every role stays in the same family and contrast behaves predictably:
 *
 *   WINE     hue ~345 — the only accent. Primary actions, selection, links.
 *   NEUTRAL  very slightly warm grey — everything structural (surfaces,
 *            outlines, secondary text). Deliberately near-neutral rather than
 *            purple-tinted so the wine accent reads as the single accent.
 *
 * Wine red is also the surface TINT, so elevated surfaces pick up a faint warm
 * cast instead of the default purple-grey.
 */

// --- wine ramp -------------------------------------------------------------

val Wine50 = Color(0xFFFFF0F3)
val Wine100 = Color(0xFFFFD9E0)
val Wine200 = Color(0xFFFFB2C2)
val Wine300 = Color(0xFFF98AA3)
val Wine400 = Color(0xFFE85D7E)
val Wine500 = Color(0xFFC9365E)
val Wine600 = Color(0xFFA81E45)
/** Brand wine red. 8.0:1 on white — passes AA for text. */
val Wine700 = Color(0xFF800020)
val Wine800 = Color(0xFF5E0018)
val Wine900 = Color(0xFF3D000F)

// Back-compat aliases (referenced elsewhere in the app).
val WineRed = Wine700
val WineRedDark = Wine800
val WineRedLight = Wine300
val WineRedContainer = Wine100
val OnWineRedContainer = Wine900

// --- neutral ramp ----------------------------------------------------------

private val Neutral0 = Color(0xFFFFFFFF)
private val Neutral50 = Color(0xFFFAF9FA)
private val Neutral100 = Color(0xFFF4F2F4)
private val Neutral200 = Color(0xFFE8E6E9)
private val Neutral300 = Color(0xFFD6D3D8)
private val Neutral400 = Color(0xFFA9A6AD)
private val Neutral500 = Color(0xFF78767E)
// Outline tones tuned so the colour also clears WCAG AA (4.5:1) as text, not
// just the 3:1 non-text bar — it is used for small labels in a few places.
// Neutral450 on Neutral950 = 4.55:1, Neutral550 on Neutral50 = 4.58:1.
private val Neutral450 = Color(0xFF7D7B84)
private val Neutral550 = Color(0xFF737179)
private val Neutral600 = Color(0xFF5A5860)
private val Neutral700 = Color(0xFF3F3D44)
private val Neutral800 = Color(0xFF2A282E)
private val Neutral900 = Color(0xFF1A191D)
private val Neutral950 = Color(0xFF111013)

// Error stays a true red (not wine) so a failure never reads as brand accent.
private val ErrorLight = Color(0xFFB3261E)
private val ErrorLightContainer = Color(0xFFF9DEDC)
private val OnErrorLightContainer = Color(0xFF410E0B)
private val ErrorDark = Color(0xFFF2B8B5)
private val ErrorDarkContainer = Color(0xFF8C1D18)

internal val LightColors = lightColorScheme(
    primary = Wine700,
    onPrimary = Neutral0,
    primaryContainer = Wine100,
    onPrimaryContainer = Wine900,

    secondary = Neutral600,
    onSecondary = Neutral0,
    secondaryContainer = Neutral100,
    onSecondaryContainer = Neutral900,

    tertiary = Wine500,
    onTertiary = Neutral0,
    tertiaryContainer = Wine50,
    onTertiaryContainer = Wine900,

    background = Neutral50,
    onBackground = Neutral900,
    surface = Neutral50,
    onSurface = Neutral900,
    surfaceVariant = Neutral200,
    onSurfaceVariant = Neutral600,
    surfaceTint = Wine700,

    surfaceBright = Neutral0,
    surfaceDim = Neutral300,
    surfaceContainerLowest = Neutral0,
    surfaceContainerLow = Neutral100,
    surfaceContainer = Color(0xFFEFEDEF),
    surfaceContainerHigh = Neutral200,
    surfaceContainerHighest = Color(0xFFE2E0E3),

    outline = Neutral550,
    outlineVariant = Neutral300,

    inverseSurface = Neutral800,
    inverseOnSurface = Neutral100,
    inversePrimary = Wine300,
    scrim = Color(0xFF000000),

    error = ErrorLight,
    onError = Neutral0,
    errorContainer = ErrorLightContainer,
    onErrorContainer = OnErrorLightContainer,
)

internal val DarkColors = darkColorScheme(
    primary = Wine300,
    onPrimary = Wine900,
    primaryContainer = Wine800,
    onPrimaryContainer = Wine100,

    secondary = Neutral400,
    onSecondary = Neutral900,
    secondaryContainer = Neutral700,
    onSecondaryContainer = Neutral100,

    tertiary = Wine200,
    onTertiary = Wine900,
    tertiaryContainer = Wine700,
    onTertiaryContainer = Wine50,

    background = Neutral950,
    onBackground = Color(0xFFE6E4E8),
    surface = Neutral950,
    onSurface = Color(0xFFE6E4E8),
    surfaceVariant = Neutral800,
    onSurfaceVariant = Neutral400,
    surfaceTint = Wine300,

    surfaceBright = Color(0xFF3A383F),
    surfaceDim = Color(0xFF0C0B0E),
    surfaceContainerLowest = Color(0xFF0B0A0D),
    surfaceContainerLow = Color(0xFF161519),
    surfaceContainer = Color(0xFF1B1A1E),
    surfaceContainerHigh = Color(0xFF252429),
    surfaceContainerHighest = Color(0xFF302E34),

    outline = Neutral450,
    outlineVariant = Color(0xFF45434A),

    inverseSurface = Color(0xFFE6E4E8),
    inverseOnSurface = Neutral800,
    inversePrimary = Wine700,
    scrim = Color(0xFF000000),

    error = ErrorDark,
    onError = Color(0xFF601410),
    errorContainer = ErrorDarkContainer,
    onErrorContainer = ErrorLightContainer,
)

/**
 * Alternate "OC-1" accent: a slightly lighter wine so the two themes are
 * visibly distinct while staying inside the brand family.
 * (Legacy id, kept reading as Default.)
 */
internal val LightColorsOc1 = LightColors.copy(
    primary = Wine600,
    primaryContainer = Wine50,
    tertiary = Wine400,
    surfaceTint = Wine600,
)

internal val DarkColorsOc1 = DarkColors.copy(
    primary = Wine200,
    primaryContainer = Wine700,
    tertiary = Wine100,
    surfaceTint = Wine200,
)

// --- matrix ramp (black / white / green / neon green) -----------------------

val Matrix50 = Color(0xFFF0FDF4)
val Matrix100 = Color(0xFFDCFCE7)
val Matrix200 = Color(0xFFBBF7D0)
val Matrix300 = Color(0xFF86EFAC)
val Matrix400 = Color(0xFF4ADE80)
val Matrix600 = Color(0xFF12933A)
val Matrix700 = Color(0xFF0E6B2A)
val Matrix800 = Color(0xFF0A4D1F)
val Matrix900 = Color(0xFF052E12)
val Matrix950 = Color(0xFF03170B)

internal val LightColorsMatrix = LightColors.copy(
    primary = Matrix700,
    onPrimary = Neutral0,
    primaryContainer = Matrix100,
    onPrimaryContainer = Matrix900,
    tertiary = Color(0xFF16A34A),
    onTertiary = Neutral0,
    tertiaryContainer = Matrix50,
    onTertiaryContainer = Matrix900,
    surfaceTint = Matrix700,
    inversePrimary = Matrix300,
)

internal val DarkColorsMatrix = DarkColors.copy(
    // Film look: pure black void, phosphor-green phosphor (#00FF41),
    // pale green-white text (#CFDAC8). No neutral grey anywhere —
    // every role is black, green, or green-tinted white.
    primary = Color(0xFF00FF41),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF006400),
    onPrimaryContainer = Color(0xFFCFDAC8),
    secondary = Color(0xFF7C8D7C),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF0A2E14),
    onSecondaryContainer = Color(0xFFCFDAC8),
    tertiary = Color(0xFF008F11),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF006400),
    onTertiaryContainer = Color(0xFFCFDAC8),
    background = Color(0xFF000000),
    onBackground = Color(0xFFCFDAC8),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFCFDAC8),
    surfaceVariant = Color(0xFF0A1F10),
    onSurfaceVariant = Color(0xFF7C8D7C),
    surfaceTint = Color(0xFF00FF41),
    surfaceBright = Color(0xFF0D2812),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF020A04),
    surfaceContainer = Color(0xFF04120A),
    surfaceContainerHigh = Color(0xFF0A2412),
    surfaceContainerHighest = Color(0xFF103418),
    outline = Color(0xFF008F11),
    outlineVariant = Color(0xFF0A2E14),
    inverseSurface = Color(0xFFCFDAC8),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF006400),
    scrim = Color(0xFF000000),
)

// --- aqua ramp (dark blue / white / light blue) ------------------------------

val Aqua50 = Color(0xFFF0FAFE)
val Aqua100 = Color(0xFFDFF5FE)
val Aqua200 = Color(0xFFBDE9FA)
val Aqua300 = Color(0xFF8FD8F2)
val Aqua400 = Color(0xFF5CC3E6)
val Aqua500 = Color(0xFF29A3D8)
val Aqua600 = Color(0xFF1B7FB8)
val Aqua700 = Color(0xFF125A8A)
val Aqua800 = Color(0xFF0E3A5D)
val Aqua900 = Color(0xFF0A2540)
val Aqua950 = Color(0xFF06121F)

internal val LightColorsAqua = LightColors.copy(
    // Ice-tinted surfaces, deep-navy ink, cyan primary — same family as
    // the dark side, only the neutrals move.
    primary = Color(0xFF0C6E7D),
    onPrimary = Neutral0,
    primaryContainer = Color(0xFFC9EAF1),
    onPrimaryContainer = Color(0xFF0A2540),
    secondary = Color(0xFF4A7386),
    onSecondary = Neutral0,
    secondaryContainer = Color(0xFFDFF1F6),
    onSecondaryContainer = Color(0xFF0A2540),
    tertiary = Color(0xFF2B6CB0),
    onTertiary = Neutral0,
    tertiaryContainer = Color(0xFFD8E9FA),
    onTertiaryContainer = Color(0xFF0A2540),
    background = Color(0xFFF2F8FB),
    onBackground = Color(0xFF0A2540),
    surface = Color(0xFFF2F8FB),
    onSurface = Color(0xFF0A2540),
    surfaceVariant = Color(0xFFD8E7EE),
    onSurfaceVariant = Color(0xFF4A7386),
    surfaceTint = Color(0xFF0C6E7D),
    surfaceBright = Neutral0,
    surfaceDim = Color(0xFFC4D8E1),
    surfaceContainerLowest = Neutral0,
    surfaceContainerLow = Color(0xFFE7F2F7),
    surfaceContainer = Color(0xFFDFF1F6),
    surfaceContainerHigh = Color(0xFFD2E7EF),
    surfaceContainerHighest = Color(0xFFC2DCE7),
    outline = Color(0xFF4A7386),
    outlineVariant = Color(0xFFB9D4DF),
    inverseSurface = Color(0xFF0A2540),
    inverseOnSurface = Color(0xFFE4F1F6),
    inversePrimary = Color(0xFF36B7C2),
)

internal val DarkColorsAqua = DarkColors.copy(
    // Deep Ocean: navy void, cyan primary, ice text, sea-grey muted.
    // Contrast pairs measured AAA on the reference palette.
    primary = Color(0xFF36B7C2),
    onPrimary = Color(0xFF062026),
    primaryContainer = Color(0xFF0F3D4D),
    onPrimaryContainer = Color(0xFFE4F1F6),
    secondary = Color(0xFF7E9AAC),
    onSecondary = Color(0xFF062026),
    secondaryContainer = Color(0xFF0F2A40),
    onSecondaryContainer = Color(0xFFE4F1F6),
    tertiary = Color(0xFF5BA7F7),
    onTertiary = Color(0xFF0A1A33),
    tertiaryContainer = Color(0xFF1B3A66),
    onTertiaryContainer = Color(0xFFE4F1F6),
    background = Color(0xFF071A2B),
    onBackground = Color(0xFFE4F1F6),
    surface = Color(0xFF071A2B),
    onSurface = Color(0xFFE4F1F6),
    surfaceVariant = Color(0xFF0F2A40),
    onSurfaceVariant = Color(0xFF7E9AAC),
    surfaceTint = Color(0xFF36B7C2),
    surfaceBright = Color(0xFF1B3A52),
    surfaceDim = Color(0xFF040F18),
    surfaceContainerLowest = Color(0xFF040F18),
    surfaceContainerLow = Color(0xFF0A2233),
    surfaceContainer = Color(0xFF0F2A40),
    surfaceContainerHigh = Color(0xFF16344C),
    surfaceContainerHighest = Color(0xFF1E3E58),
    outline = Color(0xFF7E9AAC),
    outlineVariant = Color(0xFF1B3A52),
    inverseSurface = Color(0xFFE4F1F6),
    inverseOnSurface = Color(0xFF071A2B),
    inversePrimary = Color(0xFF0C6E7D),
)

/** Theme ids: "default" (black/white/wine red), "matrix", "aqua". */
fun normalizeThemeId(id: String): String = when (id) {
    "matrix", "aqua", "default" -> id
    else -> "default" // legacy "oc-1"/"oc-2" and anything unknown
}

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Android 12+ dynamic colour (Material You) — opt-in and OFF by default:
    // wallpaper colours would replace the black/white/wine-red identity.
    dynamicColor: Boolean = false,
    // User settings (AppSettingsStore). Applied for real so the Settings screen
    // is not a set of inert controls.
    themeId: String = "oc-2",
    fontScale: Float = 1f,
    sansFont: String = "",
    monoFont: String = "",
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val theme = normalizeThemeId(themeId)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> when (theme) {
            "matrix" -> DarkColorsMatrix
            "aqua" -> DarkColorsAqua
            else -> DarkColors
        }
        else -> when (theme) {
            "matrix" -> LightColorsMatrix
            "aqua" -> LightColorsAqua
            else -> LightColors
        }
    }
    val codeFamily: FontFamily = remember(monoFont) {
        deviceFontFamily(monoFont) ?: FontFamily.Monospace
    }
    val typography = remember(sansFont) { openCodeTypography(sansFont) }
    val spacing = remember { Spacing() }
    // Scale the whole type ramp with the "Font size" setting, while preserving
    // the system font scale (accessibility) by multiplying into it.
    val density = LocalDensity.current
    val scaledDensity = remember(density, fontScale) {
        Density(
            density = density.density,
            fontScale = density.fontScale * fontScale.coerceIn(0.5f, 2.0f),
        )
    }
    CompositionLocalProvider(
        LocalSpacing provides spacing,
        LocalCodeFont provides codeFamily,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = OpenCodeShapes,
            content = content,
        )
    }
}
