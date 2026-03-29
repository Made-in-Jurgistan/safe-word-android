package com.safeword.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

// ---------------------------------------------------------------------------
// AppPalette — named color palette for the app UI (MaterialTheme)
// ---------------------------------------------------------------------------

@Immutable
data class AppPalette(
    val id: String,
    val displayName: String,
    val dark: ColorScheme,
    val light: ColorScheme,
)

val AppPalettes: List<AppPalette> = listOf(

    AppPalette(
        id = "danger_zone",
        displayName = "Danger Zone",
        dark = darkColorScheme(
            primary = DangerZonePrimary,
            onPrimary = DangerZoneSurfaceDark,
            primaryContainer = DangerZoneSecondary,
            secondary = DangerZoneSecondary,
            surface = DangerZoneSurfaceDark,
            surfaceContainer = DangerZoneSurfaceContainerDark,
            onSurface = DangerZoneOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = DangerZonePrimary,
            onPrimary = DangerZoneSurfaceLight,
            primaryContainer = DangerZonePrimaryLight,
            secondary = DangerZoneSecondary,
            surface = DangerZoneSurfaceLight,
            surfaceContainer = DangerZoneSurfaceContainerLight,
            onSurface = DangerZoneOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "trumpkin",
        displayName = "Trumpkin",
        dark = darkColorScheme(
            primary = TrumpkinPrimary,
            onPrimary = TrumpkinSurfaceDark,
            primaryContainer = TrumpkinSecondary,
            secondary = TrumpkinSecondary,
            surface = TrumpkinSurfaceDark,
            surfaceContainer = TrumpkinSurfaceContainerDark,
            onSurface = TrumpkinOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = TrumpkinPrimary,
            onPrimary = TrumpkinSurfaceLight,
            primaryContainer = TrumpkinPrimaryLight,
            secondary = TrumpkinSecondary,
            surface = TrumpkinSurfaceLight,
            surfaceContainer = TrumpkinSurfaceContainerLight,
            onSurface = TrumpkinOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "cebolla",
        displayName = "Cebolla",
        dark = darkColorScheme(
            primary = CebollaPrimary,
            onPrimary = CebollaSurfaceDark,
            primaryContainer = CebollaSecondary,
            secondary = CebollaSecondary,
            surface = CebollaSurfaceDark,
            surfaceContainer = CebollaSurfaceContainerDark,
            onSurface = CebollaOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = CebollaPrimary,
            onPrimary = CebollaSurfaceLight,
            primaryContainer = CebollaPrimaryLight,
            secondary = CebollaSecondary,
            surface = CebollaSurfaceLight,
            surfaceContainer = CebollaSurfaceContainerLight,
            onSurface = CebollaOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "pink_umbrella",
        displayName = "Pink Umbrella",
        dark = darkColorScheme(
            primary = PinkUmbrellaPrimary,
            onPrimary = PinkUmbrellaSurfaceDark,
            primaryContainer = PinkUmbrellaSecondary,
            secondary = PinkUmbrellaSecondary,
            surface = PinkUmbrellaSurfaceDark,
            surfaceContainer = PinkUmbrellaSurfaceContainerDark,
            onSurface = PinkUmbrellaOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = PinkUmbrellaPrimary,
            onPrimary = PinkUmbrellaSurfaceLight,
            primaryContainer = PinkUmbrellaPrimaryLight,
            secondary = PinkUmbrellaSecondary,
            surface = PinkUmbrellaSurfaceLight,
            surfaceContainer = PinkUmbrellaSurfaceContainerLight,
            onSurface = PinkUmbrellaOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "shepherds_pie",
        displayName = "Shepherd\u2019s Pie",
        dark = darkColorScheme(
            primary = ShepherdsPiePrimary,
            onPrimary = ShepherdsPieSurfaceDark,
            primaryContainer = ShepherdsPieSecondary,
            secondary = ShepherdsPieSecondary,
            surface = ShepherdsPieSurfaceDark,
            surfaceContainer = ShepherdsPieSurfaceContainerDark,
            onSurface = ShepherdsPieOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = ShepherdsPiePrimary,
            onPrimary = ShepherdsPieSurfaceLight,
            primaryContainer = ShepherdsPiePrimaryLight,
            secondary = ShepherdsPieSecondary,
            surface = ShepherdsPieSurfaceLight,
            surfaceContainer = ShepherdsPieSurfaceContainerLight,
            onSurface = ShepherdsPieOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "skittle_shots",
        displayName = "Skittle Shots",
        dark = darkColorScheme(
            primary = SkittleShotsPrimary,
            onPrimary = SkittleShotsSurfaceDark,
            primaryContainer = SkittleShotsSecondary,
            secondary = SkittleShotsSecondary,
            surface = SkittleShotsSurfaceDark,
            surfaceContainer = SkittleShotsSurfaceContainerDark,
            onSurface = SkittleShotsOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = SkittleShotsPrimary,
            onPrimary = SkittleShotsSurfaceLight,
            primaryContainer = SkittleShotsPrimaryLight,
            secondary = SkittleShotsSecondary,
            surface = SkittleShotsSurfaceLight,
            surfaceContainer = SkittleShotsSurfaceContainerLight,
            onSurface = SkittleShotsOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "sand_diggers",
        displayName = "Sand Diggers",
        dark = darkColorScheme(
            primary = SandDiggersPrimary,
            onPrimary = SandDiggersSurfaceDark,
            primaryContainer = SandDiggersSecondary,
            secondary = SandDiggersSecondary,
            surface = SandDiggersSurfaceDark,
            surfaceContainer = SandDiggersSurfaceContainerDark,
            onSurface = SandDiggersOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = SandDiggersPrimary,
            onPrimary = SandDiggersSurfaceLight,
            primaryContainer = SandDiggersPrimaryLight,
            secondary = SandDiggersSecondary,
            surface = SandDiggersSurfaceLight,
            surfaceContainer = SandDiggersSurfaceContainerLight,
            onSurface = SandDiggersOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "la_laguna",
        displayName = "La Laguna",
        dark = darkColorScheme(
            primary = LaLagunaPrimary,
            onPrimary = LaLagunaSurfaceDark,
            primaryContainer = LaLagunaSecondary,
            secondary = LaLagunaSecondary,
            surface = LaLagunaSurfaceDark,
            surfaceContainer = LaLagunaSurfaceContainerDark,
            onSurface = LaLagunaOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = LaLagunaPrimary,
            onPrimary = LaLagunaSurfaceLight,
            primaryContainer = LaLagunaPrimaryLight,
            secondary = LaLagunaSecondary,
            surface = LaLagunaSurfaceLight,
            surfaceContainer = LaLagunaSurfaceContainerLight,
            onSurface = LaLagunaOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    AppPalette(
        id = "portuguese_pimp",
        displayName = "Portuguese Pimp",
        dark = darkColorScheme(
            primary = PortuguesePimpPrimary,
            onPrimary = PortuguesePimpSurfaceDark,
            primaryContainer = PortuguesePimpSecondary,
            secondary = PortuguesePimpSecondary,
            surface = PortuguesePimpSurfaceDark,
            surfaceContainer = PortuguesePimpSurfaceContainerDark,
            onSurface = PortuguesePimpOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = PortuguesePimpPrimary,
            onPrimary = PortuguesePimpSurfaceLight,
            primaryContainer = PortuguesePimpPrimaryLight,
            secondary = PortuguesePimpSecondary,
            surface = PortuguesePimpSurfaceLight,
            surfaceContainer = PortuguesePimpSurfaceContainerLight,
            onSurface = PortuguesePimpOnSurfaceLight,
            error = ErrorRed,
        ),
    ),

    // "dynamic" — actual wallpaper colors injected at runtime via
    // dynamicDarkColorScheme / dynamicLightColorScheme (Android 12+).
    // The schemes below are the Material 3 baseline fallback for older devices.
    AppPalette(
        id = "dynamic",
        displayName = "Dynamic",
        dark = darkColorScheme(
            primary = DynamicPrimary,
            onPrimary = DynamicSurfaceDark,
            primaryContainer = DynamicSecondary,
            secondary = DynamicSecondary,
            surface = DynamicSurfaceDark,
            surfaceContainer = DynamicSurfaceContainerDark,
            onSurface = DynamicOnSurfaceDark,
            error = ErrorRed,
        ),
        light = lightColorScheme(
            primary = DynamicPrimary,
            onPrimary = DynamicSurfaceLight,
            primaryContainer = DynamicPrimaryLight,
            secondary = DynamicSecondary,
            surface = DynamicSurfaceLight,
            surfaceContainer = DynamicSurfaceContainerLight,
            onSurface = DynamicOnSurfaceLight,
            error = ErrorRed,
        ),
    ),
)

/**
 * Look up a palette by ID. Falls back to the Dynamic palette for unknown IDs.
 */
fun paletteById(id: String): AppPalette =
    AppPalettes.firstOrNull { it.id == id } ?: AppPalettes.last()

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
