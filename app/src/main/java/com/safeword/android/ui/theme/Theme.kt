package com.safeword.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// ---------------------------------------------------------------------------
// AppGlassColorScheme — fixed futuristic dark-glass Material 3 scheme
// ---------------------------------------------------------------------------

/**
 * Fixed Material 3 dark colour scheme for all app UI screens.
 *
 * Palette: cobalt-blue primary · medium-silver secondary · deep-charcoal surfaces.
 * All colour pairs meet or exceed WCAG 2.1 AA contrast ratios:
 *   - [GlassWhite] on [GlassPanel]   ≈ 15 : 1  (AAA)
 *   - [SilverBright] on [GlassPanel] ≈  9 : 1  (AAA)
 *   - [SilverMid] on [GlassPanel]    ≈  5.7 : 1 (AA)
 *   - [CobaltBright] on [GlassPanel] ≈  5 : 1  (AA — large/bold text)
 *   - White on [CobaltBright]        ≈  5 : 1  (AA — buttons)
 */
val AppGlassColorScheme: ColorScheme = darkColorScheme(
    primary              = CobaltBright,
    onPrimary            = GlassWhite,
    primaryContainer     = CobaltContainer,
    onPrimaryContainer   = CobaltGlow,
    secondary            = SilverBright,
    onSecondary          = GlassDarkSurface,
    secondaryContainer   = SilverDim,
    onSecondaryContainer = SilverBright,
    tertiary             = CobaltGlow,
    onTertiary           = GlassDarkSurface,
    background           = GlassBg,
    onBackground         = GlassWhite,
    surface              = GlassDarkSurface,
    onSurface            = GlassWhite,
    surfaceContainer     = GlassPanel,
    surfaceContainerHigh = GlassPanelHigh,
    surfaceVariant       = GlassPanelHigh,
    onSurfaceVariant     = GlassDimText,
    outline              = GlassOutlineColor,
    outlineVariant       = GlassOutlineVar,
    error                = ErrorRed,
    onError              = GlassWhite,
    scrim                = GlassScrimColor,
)

// ---------------------------------------------------------------------------
// SafeWordTheme — app-level Compose theme (always dark glass)
// ---------------------------------------------------------------------------

/**
 * App-level Compose theme. Always applies [AppGlassColorScheme] — the futuristic
 * dark-glass look (cobalt blue / medium silver / deep charcoal).
 */
@Composable
fun SafeWordTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppGlassColorScheme,
        content = content,
    )
}
