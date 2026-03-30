package com.safeword.android.transcription

/**
 * Common configuration passed to any [TranscriptionEngine] implementation.
 *
 * Encapsulates all inference-time settings so engine implementations receive
 * a single typed value instead of a growing parameter list.
 */
data class TranscriptionConfig(
    val language: String = "en",
    val nThreads: Int = 4,
    val translate: Boolean = false,
    val autoDetect: Boolean = false,
    val initialPrompt: String = "",
    val useVad: Boolean = false,
    val vadModelPath: String = "",
    val vadThreshold: Float = 0.5f,
    val vadMinSpeechMs: Int = 250,
    val vadMinSilenceMs: Int = 500,
    val vadSpeechPadMs: Int = 300,
    val noSpeechThreshold: Float = 0.6f,
    val logprobThreshold: Float = -1.0f,
    val entropyThreshold: Float = 2.4f,
)

/**
 * Common interface for on-device speech-to-text engine implementations.
 *
 * The default binding is [WhisperEngine]. [MoonshineEngine] is an alternative
 * backed by ONNX Runtime; enable it by swapping the Hilt binding in [AppModule].
 *
 * All suspend functions are safe to call from any coroutine dispatcher —
 * implementations are responsible for switching to the appropriate dispatcher
 * internally (IO for model loading, Default for inference).
 */
interface TranscriptionEngine {

    /** True when a model is loaded and [transcribe] / [transcribeStreaming] are callable. */
    val isLoaded: Boolean

    /**
     * Load a model from [path] on disk.
     *
     * @param path   absolute path to the model file
     * @param useGpu true to request GPU acceleration (implementation may ignore)
     * @return true on success, false if the model could not be loaded
     */
    suspend fun loadModel(path: String, useGpu: Boolean = false): Boolean

    /**
     * Transcribe [samples] (16 kHz mono float32 PCM) and return the full result.
     *
     * @param samples 16 kHz mono float32 PCM in the range [-1.0, 1.0]
     * @param config  inference configuration
     */
    suspend fun transcribe(
        samples: FloatArray,
        config: TranscriptionConfig,
    ): TranscriptionResult

    /**
     * Transcribe [samples] with per-segment streaming callbacks.
     *
     * [onSegment] is called with each new segment text as it is decoded.
     * The returned [TranscriptionResult] contains the final aggregated text and
     * confidence values after all segments are complete.
     *
     * @param samples   16 kHz mono float32 PCM in the range [-1.0, 1.0]
     * @param config    inference configuration
     * @param onSegment called on the inference thread for each new decoded segment
     */
    suspend fun transcribeStreaming(
        samples: FloatArray,
        config: TranscriptionConfig,
        onSegment: (String) -> Unit,
    ): TranscriptionResult

    /**
     * Run a representative silent inference to pre-compile GPU shaders and JIT-warm
     * the compute graph. Should be called after [loadModel] succeeds.
     */
    suspend fun prewarm()

    /** Release engine resources. After calling this, [isLoaded] returns false. */
    suspend fun release()
}
