package com.octopusman.cosic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Mint = Color(0xFF7CF2C9)
private val Amber = Color(0xFFF2C76E)
private val Ink = Color(0xFF0F1115)

// Dark only, no dynamic color. Background, surface, text, primary and error are the brand colors;
// every other role is derived from them so no baseline purple leaks into chips or fields.
private val CosicColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = Color(0xFF1E4A3D),
    onPrimaryContainer = Color(0xFFBDF7E4),
    secondary = Color(0xFF9FB8B0),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF2A3A36),
    onSecondaryContainer = Color(0xFFD4E8E1),
    tertiary = Amber,
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF4A3A12),
    onTertiaryContainer = Color(0xFFFBE8B9),
    background = Ink,
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF232832),
    onSurfaceVariant = Color(0xFFA3A9B5),
    surfaceDim = Ink,
    surfaceBright = Color(0xFF242934),
    surfaceContainerLowest = Color(0xFF0B0D11),
    surfaceContainerLow = Color(0xFF13161C),
    surfaceContainer = Color(0xFF171A21),
    surfaceContainerHigh = Color(0xFF1D212A),
    surfaceContainerHighest = Color(0xFF232832),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Ink,
    inversePrimary = Color(0xFF0D7A5C),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2D0A0A),
    errorContainer = Color(0xFF5C1F1F),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF6E7688),
    outlineVariant = Color(0xFF2E3441),
    scrim = Color.Black,
)

@Composable
fun CosicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CosicColorScheme, content = content)
}

/** Colors for code and status text, which the Material color scheme has no role for. */
object CosicColors {
    val Warning = Amber

    val Comment = Color(0xFF8B93A1)
    val Mini = Amber
    val Number = Color(0xFF7CC5FF)
    val Call = Color(0xFFC792EA)
    val Label = Mint
}
