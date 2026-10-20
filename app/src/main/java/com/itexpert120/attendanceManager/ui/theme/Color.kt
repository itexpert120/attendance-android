package com.itexpert120.attendanceManager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The PWA's Material 3 colour roles (src/app.css): neutral surfaces with the register green as the only
 * accent, so content stays crisp and colour carries meaning (present / absent / leave).
 */

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF15803D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF14532D),
    inversePrimary = Color(0xFF86EFAC),
    secondary = Color(0xFF4B5563),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7F5EC),
    onSecondaryContainer = Color(0xFF14532D),
    tertiary = Color(0xFF0369A1),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0C4A6E),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFBFBFC),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFECEEF1),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceTint = Color(0xFF15803D),
    inverseSurface = Color(0xFF27272A),
    inverseOnSurface = Color(0xFFF4F4F5),
    outline = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFFE4E4E7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBFBFC),
    surfaceDim = Color(0xFFE5E7EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8F9),
    surfaceContainer = Color(0xFFF2F3F5),
    surfaceContainerHigh = Color(0xFFECEEF1),
    surfaceContainerHighest = Color(0xFFE5E7EB),
)

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFBBF7D0),
    inversePrimary = Color(0xFF15803D),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF1E3A2B),
    onSecondaryContainer = Color(0xFFC6F0D4),
    tertiary = Color(0xFF7DD3FC),
    onTertiary = Color(0xFF082F49),
    tertiaryContainer = Color(0xFF0C4A6E),
    onTertiaryContainer = Color(0xFFE0F2FE),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF111113),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF26262A),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceTint = Color(0xFF4ADE80),
    inverseSurface = Color(0xFFE4E4E7),
    inverseOnSurface = Color(0xFF18181B),
    outline = Color(0xFF71717A),
    outlineVariant = Color(0xFF303036),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2F2F34),
    surfaceDim = Color(0xFF111113),
    surfaceContainerLowest = Color(0xFF1C1C1F),
    surfaceContainerLow = Color(0xFF19191C),
    surfaceContainer = Color(0xFF111113),
    surfaceContainerHigh = Color(0xFF26262A),
    surfaceContainerHighest = Color(0xFF2F2F34),
)

/** Colours the Material 3 scheme has no role for. */
data class ExtraColors(
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightExtraColors = ExtraColors(
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
)

val DarkExtraColors = ExtraColors(
    warningContainer = Color(0xFF713F12),
    onWarningContainer = Color(0xFFFEF3C7),
)
