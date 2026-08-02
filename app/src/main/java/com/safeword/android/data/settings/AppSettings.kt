package com.safeword.android.data.settings

/**
 * Application settings data class.
 *
 * Model selection, inference threads, GPU layers, and grammar correction are
 * hardcoded via [ModelInfo] constants.
 */
data class AppSettings(
    // Audio settings
    val maxRecordingDurationSec: Int = 600,

    // Output settings
    val autoCopyToClipboard: Boolean = true,
    val autoInsertText: Boolean = true,
    val saveToHistory: Boolean = true,

    // UI settings
    val darkMode: String = "system", // "system", "light", "dark"

    // Floating overlay settings
    val overlayEnabled: Boolean = false,

    // Auto-stop recording after silence. 0 = disabled.
    // The SDK's built-in VAD handles speech segmentation internally.
    val autoStopSilenceMs: Int = 2000,

    // Personalization
    val hotwordBoostEnabled: Boolean = true,
    val autoLearnDictionaryEnabled: Boolean = true,
)
