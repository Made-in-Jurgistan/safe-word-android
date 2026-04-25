package com.safeword.android.testutil

import com.safeword.android.transcription.StreamingEventListener
import com.safeword.android.transcription.StreamingTranscriptionEngine
import com.safeword.android.transcription.TranscriptionResult

/**
 * Fake [StreamingTranscriptionEngine] for pipeline tests.
 *
 * Allows test code to:
 * - Simulate completed transcription lines via [simulateLineCompleted].
 * - Simulate partial text updates via [simulateLineTextChanged].
 * - Control load/start/stop lifecycle states.
 * - Inject errors via [simulateError].
 * - Capture [feedAudio] calls for assertion.
 */
class FakeStreamingEngine : StreamingTranscriptionEngine {

    private var listener: StreamingEventListener? = null
    private var _isLoaded = false
    private var _isStreaming = false
    private var lineIdCounter = 0L

    /** All audio chunks received via [feedAudio]. */
    val receivedChunks = mutableListOf<FloatArray>()

    /** Transcript accumulated from [simulateLineCompleted] calls. */
    private val completedLines = mutableListOf<String>()

    /** If set, [loadModel] will return false. */
    var loadShouldFail = false

    /** If set, [stopStream] returns this result instead of the default. */
    var stopResult: TranscriptionResult? = null

    override val isLoaded: Boolean get() = _isLoaded

    override suspend fun loadModel(path: String, modelArch: Int): Boolean {
        if (loadShouldFail) return false
        _isLoaded = true
        return true
    }

    override fun startStream() {
        check(_isLoaded) { "Engine not loaded" }
        _isStreaming = true
        completedLines.clear()
        receivedChunks.clear()
    }

    override fun feedAudio(samples: FloatArray, sampleRate: Int) {
        check(_isStreaming) { "Stream not started" }
        receivedChunks.add(samples)
    }

    override suspend fun stopStream(): TranscriptionResult {
        _isStreaming = false
        return stopResult ?: TranscriptionResult.Success(
            text = completedLines.joinToString(" "),
            audioDurationMs = completedLines.size * 1000L,
            inferenceDurationMs = completedLines.size * 100L,
            language = "en",
            timestamp = System.currentTimeMillis(),
        )
    }

    override fun setEventListener(listener: StreamingEventListener?) {
        this.listener = listener
    }

    override suspend fun release() {
        _isLoaded = false
        _isStreaming = false
        listener = null
    }

    // ── Simulation helpers ──────────────────────────────────────────────

    /** Simulate a new line started event. Returns the line ID for follow-up calls. */
    fun simulateLineStarted(text: String = ""): Long {
        val id = lineIdCounter++
        listener?.onLineStarted(id, text)
        return id
    }

    /** Simulate a partial text change on an active line. */
    fun simulateLineTextChanged(lineId: Long, text: String) {
        listener?.onLineTextChanged(lineId, text)
    }

    /** Simulate a completed line — triggers the full post-processing pipeline in coordinator. */
    fun simulateLineCompleted(lineId: Long, lineText: String, fullText: String? = null) {
        completedLines.add(lineText)
        listener?.onLineCompleted(lineId, lineText, fullText ?: completedLines.joinToString(" "))
    }

    /** Simulate an engine error. */
    fun simulateError(cause: Throwable) {
        listener?.onError(cause)
    }
}
