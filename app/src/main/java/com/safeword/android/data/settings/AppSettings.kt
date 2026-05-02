package com.safeword.android.data.settings

/**
 * Application settings data class.
 *
 * Model selection, inference threads, GPU layers, and grammar correction are
 * no longer user-configurable — they are hardcoded via [ModelInfo] constants.
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

    // Streaming transcription update interval (Moonshine SDK).
    // Controls how frequently partial line text is emitted as a Streaming state.
    // 300 = Fast (responsive but more UI updates), 500 = Normal, 1000 = Smooth.
    val streamingUpdateIntervalMs: Int = 500,

    // P3: Enable per-word timestamps from the Moonshine SDK.
    // Required for confidence-aware ConfusionSetCorrector corrections.
    // Small latency overhead (~5 ms per utterance). Default true.
    val enableWordTimestamps: Boolean = true,

    // P2: Command-mode profile — shortens VAD segment duration and transcription interval
    // for faster voice command detection at the cost of dictation naturalness.
    // When true: vad_max_segment_duration=5s, transcription_interval=100ms
    // When false: vad_max_segment_duration=15s, transcription_interval=<streamingUpdateIntervalMs>
    val commandModeEnabled: Boolean = false,
)
