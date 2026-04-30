package com.safeword.android.transcription

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import android.content.Context
import com.safeword.android.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MoonshineStreamingEngine — on-device streaming STT via the Moonshine Voice SDK.
 *
 * Wraps [ai.moonshine.Transcriber] with the `SMALL_STREAMING` model architecture.
 * Emits partial (in-progress) lines via [streamingEvents] and completed lines via [completedLines].
 *
 * Lifecycle:
 *  1. Call [load] once to initialise the Transcriber. Idempotent.
 *  2. Call [startStreaming] before forwarding audio chunks.
 *  3. Forward every 32 ms PCM chunk via [addAudio].
 *  4. Call [stopStreaming] to flush and finalise the last line.
 *  5. Call [release] on teardown to free native resources.
 *
 * Thread safety: [addAudio] is called from the IO-thread recording loop. Transcriber
 * callbacks fire on an internal Moonshine SDK thread. All public state is emitted safely
 * via [MutableStateFlow] / [MutableSharedFlow].
 */
@Singleton
class MoonshineStreamingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class StreamingLine(
        val lineId: Long,
        val text: String,
        val isComplete: Boolean,
    )

    companion object {
        /** Subdirectory under filesDir where Moonshine model files are stored. */
        private const val MODELS_SUBDIR = "models"
        private const val SAMPLE_RATE = 16_000
        private val UNKNOWN_OPTION_PATTERN = Pattern.compile("Unknown transcriber option: '([^']+)'")
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** Emits every partial or complete transcript line as it arrives from the Moonshine SDK. */
    private val _streamingEvents = MutableSharedFlow<StreamingLine>(extraBufferCapacity = 64)
    val streamingEvents: SharedFlow<StreamingLine> = _streamingEvents.asSharedFlow()

    /** Emits only finalised (complete) transcript lines — used by coordinator to commit text. */
    private val _completedLines = MutableSharedFlow<StreamingLine>(extraBufferCapacity = 32)
    val completedLines: SharedFlow<StreamingLine> = _completedLines.asSharedFlow()

    @Volatile private var transcriber: Transcriber? = null

    private val listener = object : TranscriptEventListener() {
        override fun onLineStarted(event: TranscriptEvent.LineStarted) = emitLine(event.line)
        override fun onLineUpdated(event: TranscriptEvent.LineUpdated) = emitLine(event.line)
        override fun onLineTextChanged(event: TranscriptEvent.LineTextChanged) = emitLine(event.line)
        override fun onLineCompleted(event: TranscriptEvent.LineCompleted) = emitLine(event.line)
        override fun onError(event: TranscriptEvent.Error) {
            Timber.e("[ERROR] MoonshineStreamingEngine | SDK error")
        }
        private fun emitLine(line: ai.moonshine.voice.TranscriptLine) {
            val streamingLine = StreamingLine(
                lineId = line.id,
                text = line.text,
                isComplete = line.isComplete,
            )
            engineScope.launch {
                _streamingEvents.emit(streamingLine)
                if (line.isComplete) {
                    _completedLines.emit(streamingLine)
                }
            }
        }
    }

    /**
     * Initialise the Moonshine Transcriber. Idempotent — safe to call multiple times.
     * Must be called off the main thread (file I/O).
     *
     * @param modelDir Directory containing the Moonshine streaming model files.
     *   Defaults to [context.filesDir]/models.
     * @param expectedComponents List of required model files to validate before loading.
     * @param updateIntervalMs Desired cadence for streaming updates (milliseconds).
     *   Mapped to Moonshine v2's `transcription_interval` option when supported.
     * @param maxTokensPerSecond Hallucination guard — limits decoder speed to prevent infinite
     *   token loops. A value of 6.5 is the Moonshine v2 baseline for English speech.
     *   0.0 disables the guard.
     * @param enableWordTimestamps Whether to request word-level timestamps from the SDK.
     *   Keep disabled unless needed to avoid unnecessary compute overhead.
     * @param vadThreshold SDK-internal VAD speech threshold (0.0–1.0). The SDK uses its own
     *   built-in VAD for line segmentation — no external SileroVadDetector needed.
     * @param vadMaxSegmentDurationSec Maximum segment duration before the SDK forces a line break.
     *   The SDK linearly decreases the VAD threshold as the segment approaches this limit,
     *   finding natural break points instead of hard-cutting.
     */
    suspend fun load(
        modelDir: File = File(context.filesDir, MODELS_SUBDIR),
        expectedComponents: List<String> = emptyList(),
        updateIntervalMs: Int = 500,
        maxTokensPerSecond: Double = 6.5,
        enableWordTimestamps: Boolean = false,
        vadThreshold: Double = 0.5,
        vadMaxSegmentDurationSec: Int = 15,
    ) {
        if (_isLoaded.value) {
            Timber.d("[INIT] MoonshineStreamingEngine.load | already loaded — skipping")
            return
        }
        require(modelDir.exists() && modelDir.isDirectory) {
            "Moonshine model directory not found at ${modelDir.absolutePath}. Download the model first."
        }
        if (expectedComponents.isNotEmpty()) {
            val missing = expectedComponents.filterNot { File(modelDir, it).exists() }
            require(missing.isEmpty()) {
                "Moonshine model is missing required files: ${missing.joinToString(", ")}"
            }
        }
        Timber.i("[INIT] MoonshineStreamingEngine.load | loading Moonshine SMALL_STREAMING | modelDir=%s maxTPS=%.1f wordTimestamps=%b vadThreshold=%.2f vadMaxSeg=%d",
            modelDir.absolutePath, maxTokensPerSecond, enableWordTimestamps, vadThreshold, vadMaxSegmentDurationSec)
        val optionSpecs = buildList {
            add("transcription_interval" to (updateIntervalMs / 1000.0).toString())
            if (maxTokensPerSecond > 0.0) {
                add("max_tokens_per_second" to maxTokensPerSecond.toString())
            }
            if (enableWordTimestamps) {
                add("word_timestamps" to "true")
            }
            add("identify_speakers" to "false")
            add("return_audio_data" to "false")
            // SDK built-in VAD — replaces external SileroVadDetector
            add("vad_threshold" to vadThreshold.toString())
            add("vad_max_segment_duration" to vadMaxSegmentDurationSec.toString())
            add("vad_window_duration" to "0.5")
            add("vad_look_behind_sample_count" to "8192")
            // Debug audio saving — only in debug builds
            if (BuildConfig.DEBUG) {
                val debugDir = context.cacheDir.resolve("debug_audio").also { it.mkdirs() }
                add("save_input_wav_path" to debugDir.absolutePath)
                add("log_api_calls" to "true")
            }
        }
        val t = loadTranscriberWithOptionFallback(modelDir, optionSpecs)
        transcriber = t
        _isLoaded.value = true
        Timber.i("[INIT] MoonshineStreamingEngine.load | loaded successfully")
    }

    private fun loadTranscriberWithOptionFallback(
        modelDir: File,
        optionSpecs: List<Pair<String, String>>,
    ): Transcriber {
        val rejectedOptions = linkedSetOf<String>()
        while (true) {
            val activeSpecs = optionSpecs.filterNot { rejectedOptions.contains(it.first) }
            val options = activeSpecs.map { TranscriberOption(it.first, it.second) }
            try {
                val transcriber = Transcriber(options)
                transcriber.addListener { event -> event.accept(listener) }
                transcriber.loadFromFiles(modelDir.absolutePath, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
                return transcriber
            } catch (e: RuntimeException) {
                val unknownOption = parseUnknownOption(e.message)
                if (unknownOption == null || rejectedOptions.contains(unknownOption)) {
                    throw e
                }
                rejectedOptions.add(unknownOption)
                Timber.w(
                    "[INIT] MoonshineStreamingEngine.load | SDK rejected option '%s'; retrying with reduced option set",
                    unknownOption,
                )
            }
        }
    }

    private fun parseUnknownOption(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val matcher = UNKNOWN_OPTION_PATTERN.matcher(message)
        if (!matcher.find()) return null
        return matcher.group(1)
    }

    /**
     * Begin a new streaming session. Must be called after [load].
     * The Transcriber accumulates audio until [stopStreaming] is called.
     */
    fun startStreaming() {
        val t = checkNotNull(transcriber) {
            "MoonshineStreamingEngine.startStreaming called before load()"
        }
        if (_isStreaming.value) {
            Timber.w("[WARN] MoonshineStreamingEngine.startStreaming | already streaming — ignoring")
            return
        }
        t.start()
        _isStreaming.value = true
        Timber.i("[STATE] MoonshineStreamingEngine.startStreaming | streaming started")
    }

    /**
     * Forward a 32 ms PCM chunk from [AudioRecorder]'s `onChunkAvailable` callback.
     * No-op if the engine is not loaded and streaming.
     *
     * @param samples PCM audio normalized to [-1.0, 1.0], 512 samples @ 16 kHz.
     * @param sampleRate Must be 16000.
     */
    fun addAudio(samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        val t = transcriber ?: return
        if (!_isStreaming.value) return
        t.addAudio(samples, sampleRate)
    }

    /**
     * Stop streaming and flush the final line. Blocks until the Moonshine SDK finalises output.
     * The last [completedLines] event fires during this call.
     */
    fun stopStreaming() {
        val t = transcriber ?: return
        if (!_isStreaming.value) return
        t.stop()
        _isStreaming.value = false
        Timber.i("[STATE] MoonshineStreamingEngine.stopStreaming | streaming stopped")
    }

    /**
     * Release the Transcriber and free native resources. Call once on component teardown.
     * After release, [load] must be called again before streaming.
     */
    fun release() {
        val t = transcriber ?: return
        t.removeAllListeners()
        transcriber = null
        _isLoaded.value = false
        _isStreaming.value = false
        engineScope.cancel()
        Timber.i("[LIFECYCLE] MoonshineStreamingEngine.release | transcriber released, scope cancelled")
    }


}
