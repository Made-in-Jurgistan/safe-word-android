package com.safeword.android.transcription

/**
 * Contract for real-time streaming STT engines.
 * Implemented by [MoonshineNativeEngine]; consumed by [ModelManager].
 */
interface StreamingTranscriptionEngine {
    val isLoaded: Boolean
    suspend fun loadModel(path: String, modelArch: Int): Boolean
    fun startStream()
    fun feedAudio(samples: FloatArray, sampleRate: Int = 16000)
    suspend fun stopStream(): TranscriptionResult
    fun setEventListener(listener: StreamingEventListener?)
    suspend fun release()

    /**
     * Register a callback invoked by the engine's feed consumer after each audio
     * buffer has been fully consumed by the native layer.  Intended for buffer-pool
     * recycling: the caller should pass [com.safeword.android.audio.AudioRecorder.recycleBuffer].
     *
     * The callback is invoked from the engine's internal feed thread — do not
     * perform expensive work inside it.
     *
     * Pass `null` to clear a previously registered recycler.
     */
    fun setBufferRecycler(recycler: ((FloatArray) -> Unit)?) { /* default: no-op */ }
}

/**
 * Callback interface for streaming transcription events.
 * Implemented by [TranscriptionCoordinator]; passed to [StreamingTranscriptionEngine].
 */
interface StreamingEventListener {
    fun onLineStarted(lineId: Long, text: String)
    fun onLineTextChanged(lineId: Long, text: String)

    /**
     * @param lineId   ID of the completed line.
     * @param lineText The individual completed line's text (for command detection).
     * @param fullText All completed lines joined (for incremental insertion).
     */
    fun onLineCompleted(lineId: Long, lineText: String, fullText: String)
    fun onError(cause: Throwable) {}
}
