package com.safeword.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

val AppGlassLightColorScheme: ColorScheme = lightColorScheme(
    primary = CobaltDeep,
    onPrimary = GlassWhite,
    primaryContainer = CobaltGlow,
    onPrimaryContainer = GlassDarkSurface,
    secondary = SilverMid,
    onSecondary = GlassWhite,
    secondaryContainer = Color(0xFFDDE5F2),
    onSecondaryContainer = GlassDarkSurface,
    tertiary = CobaltBright,
    onTertiary = GlassWhite,
    background = Color(0xFFF3F7FC),
    onBackground = Color(0xFF0C1018),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0C1018),
    surfaceContainer = Color(0xFFE9F0FA),
    surfaceContainerHigh = Color(0xFFDCE6F5),
    surfaceVariant = Color(0xFFCCD8EA),
    onSurfaceVariant = Color(0xFF32445F),
    outline = Color(0xFF7E8EA7),
    outlineVariant = Color(0xFFA8B8CE),
    error = ErrorRed,
    onError = GlassWhite,
    scrim = GlassScrimColor,
)

// ---------------------------------------------------------------------------
// SafeWordTheme — app-level Compose theme (always dark glass)
// ---------------------------------------------------------------------------

/**
 * App-level Compose theme.
 *
 * @param mode User preference from settings: `system`, `light`, or `dark`.
 */
@Composable
fun SafeWordTheme(
    mode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (darkTheme) AppGlassColorScheme else AppGlassLightColorScheme,
        content = content,
    )
}
