package com.safeword.android.transcription

/**
 * WhisperLib — Kotlin JNI declarations for whisper.cpp native bridge.
 *
 * Mirrors the desktop Safe Word's whisper-rs bindings, but via JNI instead of Rust FFI.
 * The native implementation is in whisper-jni.cpp.
 */
object WhisperLib {
    init {
        System.loadLibrary("whisper-jni")
    }

    /**
     * Load GGML dynamic backends from the given directory.
     * Must be called once before [nativeInit] when built with GGML_BACKEND_DL.
     * On Android, pass [android.content.pm.ApplicationInfo.nativeLibraryDir].
     */
    external fun nativeLoadBackends(searchDir: String)

    /**
     * Initialize whisper context from a GGML model file.
     * @param modelPath path to GGML model file
     * @param useGpu    true to attempt Vulkan GPU inference (falls back to CPU on failure)
     * @return context pointer or 0 on failure
     */
    external fun nativeInit(modelPath: String, useGpu: Boolean): Long

    /**
     * Transcribe float PCM samples.
     *
     * Returns a JSON string with structured results:
     * ```json
     * {"text":"...","no_speech_prob":0.12,"avg_logprob":-0.34,
     *  "segments":[{"text":"...","no_speech_prob":0.1,"avg_logprob":-0.3},...]}
     * ```
     *
     * @param contextPtr         pointer returned by nativeInit
     * @param samples            PCM audio [-1.0, 1.0] at 16kHz
     * @param language           language code ("en", "auto", etc.)
     * @param nThreads           number of CPU threads for inference
     * @param translate          if true, translate to English
     * @param autoDetect         if true, auto-detect language
     * @param initialPrompt      optional prompt to bias decoder vocabulary/style
     * @param useVad             enable native GGML Silero VAD
     * @param vadModelPath       path to ggml-silero VAD model (or empty)
     * @param vadThreshold       VAD speech probability threshold
     * @param vadMinSpeechMs     minimum speech duration ms
     * @param vadMinSilenceMs    minimum silence duration ms
     * @param vadSpeechPadMs     speech padding ms
     * @param noSpeechThreshold  no-speech probability threshold for hallucination suppression
     * @param logprobThreshold   average log-probability threshold
     * @param entropyThreshold   entropy / compression-ratio threshold
     * @return JSON string with transcription results and confidence data
     */
    external fun nativeTranscribe(
        contextPtr: Long,
        samples: FloatArray,
        language: String,
        nThreads: Int,
        translate: Boolean,
        autoDetect: Boolean,
        initialPrompt: String,
        useVad: Boolean,
        vadModelPath: String,
        vadThreshold: Float,
        vadMinSpeechMs: Int,
        vadMinSilenceMs: Int,
        vadSpeechPadMs: Int,
        noSpeechThreshold: Float,
        logprobThreshold: Float,
        entropyThreshold: Float,
    ): String

    /**
     * Transcribe float PCM samples and call [segmentCallback].onSegment(text) for each
     * decoded segment as inference progresses.
     *
     * Parameters are identical to [nativeTranscribe]; returns the same compact JSON
     * once all segments are complete.
     */
    external fun nativeTranscribeStreaming(
        contextPtr: Long,
        samples: FloatArray,
        language: String,
        nThreads: Int,
        translate: Boolean,
        autoDetect: Boolean,
        initialPrompt: String,
        useVad: Boolean,
        vadModelPath: String,
        vadThreshold: Float,
        vadMinSpeechMs: Int,
        vadMinSilenceMs: Int,
        vadSpeechPadMs: Int,
        noSpeechThreshold: Float,
        logprobThreshold: Float,
        entropyThreshold: Float,
        segmentCallback: SegmentCallback,
    ): String

    /** Free whisper context and release resources. */
    external fun nativeFree(contextPtr: Long)

    /** Returns true if real whisper.cpp is compiled in. */
    external fun nativeIsRealWhisper(): Boolean

}

/**
 * Callback interface for per-segment streaming transcription.
 *
 * Implemented as a Kotlin SAM (fun interface) so callers can pass a lambda:
 * ```kotlin
 * WhisperLib.nativeTranscribeStreaming(..., segmentCallback = { text -> … })
 * ```
 *
 * [onSegment] is invoked on the native inference thread for each segment decoded.
 * Implementations must be non-blocking and thread-safe.
 */
fun interface SegmentCallback {
    fun onSegment(text: String)
}
