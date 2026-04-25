package com.safeword.android.transcription

/**
 * JNI declarations for the [moonshine-bridge] native library.
 *
 * Handle semantics: all handles are `Int` (C `int32_t`). A negative value
 * indicates an error; use [MoonshineNativeEngine] for error interpretation.
 *
 * Thread safety: calls are serialised by [MoonshineNativeEngine] on its
 * single-threaded feedDispatcher — do NOT call these concurrently.
 */
internal object MoonshineNativeBridge {

    init {
        try {
            System.loadLibrary("moonshine-bridge")
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Failed to load libmoonshine-bridge.so — ensure arm64-v8a native libs are packaged: ${e.message}",
            )
        }
    }

    /** Loads models from [path] directory using [modelArch]. Returns handle ≥ 0, or error < 0. */
    external fun nativeLoadTranscriber(path: String, modelArch: Int): Int

    /** Creates a stream on [transcriberHandle]. Returns stream handle ≥ 0, or error < 0. */
    external fun nativeCreateStream(transcriberHandle: Int): Int

    /** Starts [streamHandle]. Returns 0 on success, negative on error. */
    external fun nativeStartStream(transcriberHandle: Int, streamHandle: Int): Int

    /** Adds [samples] at [sampleRate] Hz to [streamHandle]. Returns 0 on success, negative on error. */
    external fun nativeAddAudio(
        transcriberHandle: Int,
        streamHandle: Int,
        samples: FloatArray,
        sampleRate: Int,
    ): Int

    /**
     * Runs inference and returns a JSON snapshot:
     * `{"lines":[{"id":N,"text":"...","new":bool,"changed":bool,"complete":bool,"latency":N},...]}`.
     */
    external fun nativeTranscribeStream(transcriberHandle: Int, streamHandle: Int): String

    /** Stops [streamHandle] (finalises processing). Returns 0 on success, negative on error. */
    external fun nativeStopStream(transcriberHandle: Int, streamHandle: Int): Int

    /** Frees [streamHandle] resources. Returns 0 on success, negative on error. */
    external fun nativeFreeStream(transcriberHandle: Int, streamHandle: Int): Int

    /** Frees the transcriber and all associated resources. */
    external fun nativeFreeTranscriber(transcriberHandle: Int)
}
