package com.safeword.android.data.settings

/**
 * Application settings data class.
 *
 * Model selection, inference threads, GPU layers, and grammar correction are
 * no longer user-configurable — they are hardcoded via [ModelInfo] constants
 * and computed dynamically via [InferenceConfig].
 */
data class AppSettings(
    // Audio settings
    val maxRecordingDurationSec: Int = 600,

    // Language
    val language: String = "en",
    val autoDetectLanguage: Boolean = false,

    // Output settings
    val autoCopyToClipboard: Boolean = true,
    val autoInsertText: Boolean = true,
    val saveToHistory: Boolean = true,

    // UI settings
    val darkMode: String = "system", // "system", "light", "dark"

    // Color palette — one of the ten brand palettes (applies to app UI)
    // "dynamic" (default) follows the system wallpaper on Android 12+; falls back to M3 baseline.
    val colorPalette: String = "dynamic",

    // Floating overlay settings
    val overlayEnabled: Boolean = false,

    // Auto-stop recording after silence (requires VAD). 0 = disabled.
    val autoStopSilenceMs: Int = 2000,

    // Voice Activity Detection (ONNX Silero VAD) — real-time speech probability UI
    val vadEnabled: Boolean = true,
    val vadThreshold: Float = 0.5f,
    val vadMinSpeechDurationMs: Int = 250,
    val vadMinSilenceDurationMs: Int = 500,

    // Native VAD (whisper.cpp built-in Silero VAD v5) — runs inside nativeTranscribe
    val nativeVadEnabled: Boolean = true,
    val nativeVadThreshold: Float = 0.5f,
    val nativeVadMinSpeechMs: Int = 250,
    val nativeVadMinSilenceMs: Int = 500,
    val nativeVadSpeechPadMs: Int = 300,

    // Hallucination suppression thresholds (applied in WhisperEngine)
    val noSpeechThreshold: Float = 0.6f,
    val logprobThreshold: Float = -1.0f,
    val entropyThreshold: Float = 2.4f,

    // Translation
    val translateToEnglish: Boolean = false,

    // Optional decoder prompt to bias Whisper vocabulary/style (empty = no prompt)
    val initialPrompt: String = "",
)
