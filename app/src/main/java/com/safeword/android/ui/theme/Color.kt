package com.safeword.android.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Status / recording colors — shared across the app
// ---------------------------------------------------------------------------
val RecordingRed = Color(0xFFFF1744)
val TranscribingAmber = Color(0xFFFFAB00)
val DoneGreen = Color(0xFF00E676)
val ErrorRed = Color(0xFFFF5252)

// ---------------------------------------------------------------------------
// Glass UI Theme — Deep Charcoal + Cobalt Blue + Medium Silver
// Applied exclusively to app UI screens.
// ---------------------------------------------------------------------------

// Background / surface layers
val GlassBg             = Color(0xFF070A12)  // Deepest background (screen fill)
val GlassDarkSurface    = Color(0xFF0C1018)  // Primary app surface
val GlassPanel          = Color(0xFF121A28)  // Card / container base
val GlassPanelHigh      = Color(0xFF18243A)  // Elevated / hover panel
val GlassScrimColor     = Color(0xCC000000)  // 80 % modal scrim

// Cobalt Blue — primary accent
val CobaltBright        = Color(0xFF2979FF)  // Buttons, active icons — 5:1 on GlassPanel ✓
val CobaltDeep          = Color(0xFF1654C0)  // Pressed / container cobalt
val CobaltGlow          = Color(0xFF82B1FF)  // Highlights, shimmer, borders (large text use only)
val CobaltContainer     = Color(0xFF0D2347)  // Cobalt-tinted surface container

// Silver / Chrome — secondary accent
val SilverBright        = Color(0xFFB8C8E4)  // Primary silver text — 9:1 on GlassPanel ✓
val SilverMid           = Color(0xFF8898B4)  // Secondary label — 5.7:1 on GlassPanel ✓
val SilverDim           = Color(0xFF4A5568)  // Disabled / outline
val ChromeAccent        = Color(0xFF718096)  // Chrome steel (decorative use only)

// Content on glass
val GlassWhite          = Color(0xFFE8F0FE)  // Headline / primary text — 15:1 ✓
val GlassDimText        = Color(0xFFB0BECC)  // Body / secondary text — 9:1 ✓
val GlassMutedText      = Color(0xFF6B7A8D)  // Hints / non-essential decorative text
val GlassOutlineColor   = Color(0xFF2A3D5C)  // Subtle separator
val GlassOutlineVar     = Color(0xFF1A2840)  // Very subtle outline variant
