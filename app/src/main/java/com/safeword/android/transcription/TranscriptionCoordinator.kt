package com.safeword.android.transcription

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.safeword.android.audio.AudioRecorder
import com.safeword.android.audio.SileroVadDetector
import com.safeword.android.data.db.TranscriptionDao
import com.safeword.android.data.db.TranscriptionEntity
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.data.model.ModelType
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.R
import com.safeword.android.service.SafeWordAccessibilityService
import com.safeword.android.service.RecordingService
import com.safeword.android.service.ThermalMonitor
import com.safeword.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * TranscriptionCoordinator — mirrors the desktop Safe Word TranscriptionCoordinator.
 *
 * Manages the state machine: Idle → Recording → Transcribing → Done → Idle
 * Coordinates between AudioRecorder, WhisperEngine, and output actions.
 * Wires user settings (language, threads, translate, auto-detect) through to whisper.
 * Performs post-transcription actions: clipboard copy, accessibility insert, DB save.
 */
@Singleton
class TranscriptionCoordinator @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val whisperEngine: WhisperEngine,
    private val settingsRepository: SettingsRepository,
    private val transcriptionDao: TranscriptionDao,
    private val vadDetector: SileroVadDetector,
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val thermalMonitor: ThermalMonitor,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private data class InputContext(
        val packageName: String,
        val hintText: String,
        val className: String,
    )

    companion object {
        /** Polling interval for recording duration updates (ms). */
        private const val DURATION_POLL_INTERVAL_MS = 200L
        /** Minimum audio duration in seconds for Whisper to produce reliable output. */
        private const val MIN_AUDIO_DURATION_SEC = 1.25
        /** Cooldown between backend switches to avoid oscillation. */
        private const val BACKEND_SWITCH_COOLDOWN_MS = 30_000L
        /** Skip inference when VAD speech content is below this fraction of total audio. */
        private const val NO_SPEECH_DENSITY_THRESHOLD = 0.05f
        /** Consecutive slow-RTF transcriptions required before switching backend. */
        private const val BACKEND_SWITCH_HYSTERESIS = 3
        /** Maximum samples per transcription chunk (30 s at 16 kHz). Long audio is split to avoid Whisper encoder overflow. */
        private const val CHUNK_SAMPLES = 30 * 16_000
    }

    private var recordingJob: Job? = null
    private var durationJob: Job? = null
    private var preloadJob: Job? = null
    private var resetJob: Job? = null
    private var recordingStartTime: Long = 0
    private var lastBackendSwitchMs: Long = 0
    /** Consecutive slow-RTF observations before switching backend. */
    private var consecutiveSlowRtfCount: Int = 0

    /** Reusable padding buffer for short audio clips (avoids ~78 KB alloc per transcription). */
    private var paddingBuffer: FloatArray? = null

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    /** All completed transcriptions this session. */
    private val _history = MutableStateFlow<List<TranscriptionResult>>(emptyList())
    val history: StateFlow<List<TranscriptionResult>> = _history.asStateFlow()

    /**
     * Eagerly load VAD + Whisper before the user presses the mic button.
     *
     * Idempotent: no-ops if models are already loaded or a preload is already in progress.
     * Called from FloatingOverlayService.onCreate() so models are pre-loaded
     * before the first mic press — eliminating the 2-second model-loading head-cut.
     */
    fun preloadModels() {
        if (preloadJob?.isActive == true) {
            Timber.d("[INIT] TranscriptionCoordinator.preloadModels | preload already in progress, skipping")
            return
        }
        preloadJob = scope.launch(Dispatchers.IO) {
            Timber.i("[INIT] TranscriptionCoordinator.preloadModels | background preload starting")
            if (!vadDetector.isLoaded) {
                Timber.d("[INIT] TranscriptionCoordinator.preloadModels | loading VAD")
                vadDetector.load()
            }
            if (!whisperEngine.isReady()) {
                val path = resolveModelPath() ?: run {
                    Timber.w("[INIT] TranscriptionCoordinator.preloadModels | no downloaded model — skipping")
                    return@launch
                }
                // CPU with ARM NEON is faster than Vulkan on mobile for
                // autoregressive decoder models like Whisper — Vulkan kernel-launch
                // overhead per decoder step dominates on mobile GPUs.
                val useGpu = false
                Timber.i("[INIT] TranscriptionCoordinator.preloadModels | loading Whisper model (CPU+NEON)")
                val loaded = whisperEngine.loadModel(path, useGpu = useGpu)
                if (loaded) {
                    Timber.i("[INIT] TranscriptionCoordinator.preloadModels | model loaded — running prewarm for CPU cache/graph warmup")
                    whisperEngine.prewarm()
                }
            }

            Timber.i("[INIT] TranscriptionCoordinator.preloadModels | preload complete")
        }
    }

    /**
     * Start recording. Transitions: Idle → Recording.
     */
    fun startRecording() {
        val currentState = _state.value
        if (currentState !is TranscriptionState.Idle &&
            currentState !is TranscriptionState.Done &&
            currentState !is TranscriptionState.Error &&
            currentState !is TranscriptionState.CommandDetected
        ) {
            Timber.w("[STATE] startRecording | blocked from state=%s", currentState)
            return
        }

        Timber.i("[ENTER] TranscriptionCoordinator.startRecording | currentState=%s", currentState)
        resetJob?.cancel()
        resetJob = null
        _state.value = TranscriptionState.Recording()
        recordingStartTime = System.currentTimeMillis()

        // Start foreground service for background mic access
        try {
            val serviceIntent = Intent(context, RecordingService::class.java)
            context.startForegroundService(serviceIntent)
            Timber.d("[SERVICE] startRecording | RecordingService started")
        } catch (e: Exception) {
            Timber.w(e, "[SERVICE] startRecording | could not start RecordingService")
        }

        // Track recording duration
        durationJob = scope.launch {
            val maxMs = settingsRepository.settings.first().maxRecordingDurationSec * 1000L
            while (true) {
                delay(DURATION_POLL_INTERVAL_MS)
                val current = _state.value
                if (current is TranscriptionState.Recording) {
                    val elapsed = System.currentTimeMillis() - recordingStartTime
                    _state.value = current.copy(durationMs = elapsed)

                    // Auto-stop at max duration
                    if (elapsed >= maxMs) {
                        Timber.i("[RECORDING] durationJob | max duration reached maxMs=%d elapsedMs=%d", maxMs, elapsed)
                        stopRecording()
                        break
                    }
                } else {
                    break
                }
            }
        }

        recordingJob = scope.launch {
            try {
                val settings = settingsRepository.settings.first()
                val useVad = if (settings.vadEnabled) {
                    if (!vadDetector.isLoaded) {
                        withContext(Dispatchers.IO) { vadDetector.load() }
                    } else {
                        true
                    }
                } else {
                    false
                }

                // Auto-load the single downloaded model if Whisper engine is not yet ready.
                // loadModel() is idempotent when the same model is already loaded.
                if (!whisperEngine.isReady()) {
                    withContext(Dispatchers.IO) {
                        val path = resolveModelPath()
                        if (path != null) {
                            val useGpu = !thermalMonitor.isThrottled
                            Timber.i("[MODEL] startRecording | auto-loading model")
                            whisperEngine.loadModel(path, useGpu = useGpu)
                        } else {
                            Timber.e("[MODEL] startRecording | no downloaded model — cannot transcribe")
                        }
                    }
                    if (!whisperEngine.isReady()) {
                        _state.value = TranscriptionState.Error(context.getString(R.string.error_no_model_downloaded))
                        scheduleResetToIdle()
                        stopForegroundService()
                        return@launch
                    }
                }

                // Wire VAD into recorder for real-time speech probability
                audioRecorder.vadDetector = if (useVad) vadDetector else null

                // Launch amplitude monitoring
                launch {
                    audioRecorder.amplitudeDb.collect { db ->
                        val current = _state.value
                        if (current is TranscriptionState.Recording) {
                            _state.value = current.copy(amplitudeDb = db)
                        }
                    }
                }

                // Launch speech probability monitoring
                launch {
                    audioRecorder.speechProbability.collect { prob ->
                        val current = _state.value
                        if (current is TranscriptionState.Recording) {
                            _state.value = current.copy(speechProbability = prob)
                        }
                    }
                }

                // Auto-stop after sustained silence (only when VAD is active)
                val silenceTimeoutMs = settings.autoStopSilenceMs.toLong()
                if (useVad && silenceTimeoutMs > 0) {
                    launch {
                        var speechDetected = false
                        var silenceStartMs = 0L
                        audioRecorder.speechProbability.collect { prob ->
                            if (_state.value !is TranscriptionState.Recording) return@collect
                            if (prob >= settings.vadThreshold) {
                                speechDetected = true
                                silenceStartMs = 0L
                            } else if (speechDetected) {
                                val now = System.currentTimeMillis()
                                if (silenceStartMs == 0L) {
                                    silenceStartMs = now
                                } else if (now - silenceStartMs >= silenceTimeoutMs) {
                                    Timber.i("[RECORDING] auto-stop on silence | silenceMs=%d threshold=%d",
                                        now - silenceStartMs, silenceTimeoutMs)
                                    stopRecording()
                                }
                            }
                        }
                    }
                }

                // Start recording — this suspends until cancelled
                audioRecorder.record()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "[RECORDING] recordingJob | recording error")
                _state.value = TranscriptionState.Error(
                    message = e.message ?: context.getString(R.string.error_recording_failed),
                    previousState = TranscriptionState.Recording(),
                )
                scheduleResetToIdle()
                stopForegroundService()
            }
        }
    }

    /**
     * Stop recording and begin transcription. Transitions: Recording → Transcribing → Done.
     *
     * Gets all recorded samples and runs the full transcription pipeline.
     */
    fun stopRecording() {
        val currentState = _state.value
        if (currentState !is TranscriptionState.Recording) {
            Timber.w("[STATE] stopRecording | blocked from state=%s", currentState)
            return
        }

        Timber.i("[ENTER] TranscriptionCoordinator.stopRecording | elapsedMs=%d",
            System.currentTimeMillis() - recordingStartTime)
        durationJob?.cancel()
        durationJob = null

        // Stop the AudioRecorder first so any pending read() unblocks
        audioRecorder.stop()
        // Cancel the recording coroutine so its while(isActive) loop exits
        recordingJob?.cancel()
        // Cancel preload if still running (model loading on IO).
        preloadJob?.cancel()

        scope.launch {
            try {
                // Wait for recording coroutine to finish cleanup (finally block)
                recordingJob?.join()
                recordingJob = null

                batchTranscribeFallback()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "[TRANSCRIPTION] stopRecording | error after stopping recording")
                _state.value = TranscriptionState.Error(e.message ?: context.getString(R.string.error_transcription_failed))
                scheduleResetToIdle()
            } finally {
                stopForegroundService()
            }
        }
    }

    /**
     * Resolve the Whisper model path.
     * Uses the hardcoded model; falls back to any downloaded Whisper model.
     * Never returns a VAD model path — loading a non-Whisper model as Whisper
     * causes a fatal GGML assertion (ggml_ftype_to_ggml_type abort).
     */
    private fun resolveModelPath(): String? {
        val modelId = if (modelRepository.isModelDownloaded(ModelInfo.WHISPER_MODEL_ID)) {
            ModelInfo.WHISPER_MODEL_ID
        } else {
            modelRepository.getDownloadedModels()
                .filter { it.modelType == ModelType.WHISPER }
                .firstOrNull()?.id
        }
        return modelId?.let { modelRepository.getModelPath(it) }
    }

    /**
     * Resolve the path to the GGML Silero VAD model for whisper.cpp native VAD.
     * Returns empty string if not downloaded (whisper.cpp will skip native VAD).
     */
    private fun resolveVadModelPath(): String {
        if (!modelRepository.isModelDownloaded(ModelInfo.VAD_MODEL_ID)) return ""
        return modelRepository.getModelPath(ModelInfo.VAD_MODEL_ID)
    }

    private fun currentInputContext(): InputContext {
        val snap = SafeWordAccessibilityService.inputContextSnapshot()
        return InputContext(
            packageName = snap.packageName,
            hintText = snap.hintText,
            className = snap.className,
        )
    }

    private fun buildContextAwarePrompt(basePrompt: String, ctx: InputContext): String {
        val hints = mutableListOf<String>()
        val pkg = ctx.packageName.lowercase()
        val hint = ctx.hintText.lowercase()
        val browserContext = pkg.contains("chrome") ||
            pkg.contains("browser") ||
            pkg.contains("firefox") ||
            pkg.contains("brave") ||
            pkg.contains("edge") ||
            pkg.contains("opera")
        val searchContext = hint.contains("search") || hint.contains("address") || hint.contains("url")

        if (browserContext || searchContext) {
            hints += "Context: browser search/address field. Preserve literal query words. Prefer short command-like terms exactly as spoken."
        } else if (pkg.contains("gmail") || pkg.contains("outlook") || pkg.contains("mail")) {
            hints += "Context: email composition. Preserve names, product terms, and punctuation boundaries."
        } else if (pkg.contains("message") || pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("signal")) {
            hints += "Context: messaging. Preserve colloquial words and short utterances verbatim when plausible."
        }

        if (hints.isEmpty()) return basePrompt
        val merged = buildString {
            if (basePrompt.isNotBlank()) {
                append(basePrompt.trim())
                append(' ')
            }
            append(hints.joinToString(" "))
        }
        // Keep prompt bounded to avoid prompt bloat on mobile.
        return merged.take(320)
    }

    private fun maybeSwitchBackendForLatency(audioDurationMs: Long, rtf: Float) {
        if (audioDurationMs < 2000L) return
        if (rtf <= 2.0f) {
            consecutiveSlowRtfCount = 0
            return
        }
        consecutiveSlowRtfCount++
        if (consecutiveSlowRtfCount < BACKEND_SWITCH_HYSTERESIS) {
            Timber.d("[PERF] maybeSwitchBackendForLatency | slow rtf=%.2f count=%d/%d — waiting for hysteresis",
                rtf, consecutiveSlowRtfCount, BACKEND_SWITCH_HYSTERESIS)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackendSwitchMs < BACKEND_SWITCH_COOLDOWN_MS) return

        val currentlyGpu = whisperEngine.isUsingGpu()
        val targetGpu = if (currentlyGpu) {
            false
        } else {
            !thermalMonitor.isThrottled
        }
        if (targetGpu == currentlyGpu) return

        val path = resolveModelPath() ?: return
        lastBackendSwitchMs = now
        consecutiveSlowRtfCount = 0
        scope.launch(Dispatchers.IO) {
            Timber.w(
                "[PERF] maybeSwitchBackendForLatency | slow rtf=%.2f audioMs=%d switching backend gpu=%b -> %b",
                rtf,
                audioDurationMs,
                currentlyGpu,
                targetGpu,
            )
            whisperEngine.loadModel(path, useGpu = targetGpu)
        }
    }

    /** Batch transcription fallback — get all recorded samples and run full pipeline. */
    private suspend fun batchTranscribeFallback() {
        val samples = audioRecorder.getRecordedSamples()
        if (samples.isEmpty()) {
            Timber.w("[RECORDING] batchTranscribeFallback | no audio recorded — sampleCount=0")
            _state.value = TranscriptionState.Error(context.getString(R.string.error_no_audio_recorded))
            scheduleResetToIdle()
            return
        }
        Timber.d("[RECORDING] batchTranscribeFallback | sampleCount=%d launching transcribe", samples.size)
        transcribe(samples)
    }

    /**
     * Cancel current operation and return to Idle.
     */
    fun cancel() {
        Timber.i("[STATE] cancel | cancelling current operation → Idle")
        resetJob?.cancel()
        resetJob = null
        durationJob?.cancel()
        durationJob = null
        audioRecorder.stop()
        recordingJob?.cancel()
        recordingJob = null
        _state.value = TranscriptionState.Idle
        stopForegroundService()
        Timber.i("[STATE] cancel | operation cancelled → Idle")
    }

    /** Reset to Idle from Done or Error. */
    fun reset() {
        resetJob?.cancel()
        resetJob = null
        Timber.d("[STATE] reset | → Idle")
        _state.value = TranscriptionState.Idle
    }

    /**
     * Schedule an auto-reset to Idle after a terminal state (Done / Error).
     * Gives the UI ~1.5 s to flash the result before returning to Idle.
     * Cancelled if the user starts a new recording before the delay expires.
     */
    private fun scheduleResetToIdle() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(1500)
            if (_state.value is TranscriptionState.Done ||
                _state.value is TranscriptionState.Error ||
                _state.value is TranscriptionState.CommandDetected
            ) {
                Timber.d("[STATE] scheduleResetToIdle | auto-reset → Idle from %s", _state.value::class.simpleName)
                _state.value = TranscriptionState.Idle
            }
        }
    }

    private suspend fun transcribe(samples: FloatArray) {
        val pipelineStart = System.nanoTime()
        // Read current settings for transcription params
        val settings = settingsRepository.settings.first()
        val audioDurationSec = samples.size.toFloat() / AudioRecorder.SAMPLE_RATE
        val whisperThreads = InferenceConfig.optimalWhisperThreads(audioDurationSec)
        val inputContext = currentInputContext()
        val contextualPrompt = buildContextAwarePrompt(settings.initialPrompt, inputContext)

        Timber.i("[ENTER] TranscriptionCoordinator.transcribe | sampleCount=%d durationSec=%.2f threads=%d vadEnabled=%b",
            samples.size, audioDurationSec, whisperThreads, settings.vadEnabled)
        Timber.d("[TRANSCRIPTION] transcribe | inputContext pkg=%s hint=%s class=%s promptLen=%d",
            inputContext.packageName, inputContext.hintText, inputContext.className, contextualPrompt.length)

        // Pre-process audio off the main thread.
        // When native VAD is available, skip the ONNX Silero VAD pre-processing pass entirely —
        // whisper.cpp's built-in VAD will handle speech detection during decoding.
        val vadStart = System.nanoTime()
        val processedSamples = withContext(Dispatchers.Default) {
            if (settings.vadEnabled && vadDetector.isLoaded) {
                val windowProbs = audioRecorder.getWindowProbabilities()
                Timber.i("[VAD] transcribe | using ONNX Silero VAD threshold=%.2f minSpeechMs=%d minSilenceMs=%d cachedWindows=%d",
                    settings.vadThreshold, settings.vadMinSpeechDurationMs, settings.vadMinSilenceDurationMs, windowProbs.size)
                vadDetector.extractSpeechFromWindowProbs(
                    samples = samples,
                    windowProbs = windowProbs,
                    threshold = settings.vadThreshold,
                    minSpeechDurationMs = settings.vadMinSpeechDurationMs,
                    minSilenceDurationMs = settings.vadMinSilenceDurationMs,
                )
            } else {
                Timber.i("[VAD] transcribe | VAD disabled or not loaded — using energy-based silence trimming vadEnabled=%b isLoaded=%b",
                    settings.vadEnabled, vadDetector.isLoaded)
                AudioRecorder.trimSilence(samples)
            }
        }
        val vadMs = (System.nanoTime() - vadStart) / 1_000_000
        Timber.d("[PERF] TranscriptionCoordinator.transcribe | vadPreProcessMs=%d inputSamples=%d outputSamples=%d reduction=%.0f%%",
            vadMs, samples.size, processedSamples.size,
            if (samples.isNotEmpty()) (1 - processedSamples.size.toFloat() / samples.size) * 100 else 0f)

        if (processedSamples.isEmpty()) {
            Timber.w("[TRANSCRIPTION] transcribe | no speech detected after processing — all silence")
            _state.value = TranscriptionState.Error("No speech detected — audio was all silence")
            scheduleResetToIdle()
            return
        }

        // Short-circuit inference when VAD speech density is very low.
        // If ONNX VAD was used and less than 5% of audio was speech, skip whisper entirely.
        if (settings.vadEnabled && vadDetector.isLoaded) {
            val speechRatio = processedSamples.size.toFloat() / samples.size.toFloat()
            if (speechRatio < NO_SPEECH_DENSITY_THRESHOLD) {
                Timber.w("[TRANSCRIPTION] transcribe | speech density %.1f%% < %.0f%% — skipping inference",
                    speechRatio * 100, NO_SPEECH_DENSITY_THRESHOLD * 100)
                _state.value = TranscriptionState.Error("No speech detected — audio was all silence")
                scheduleResetToIdle()
                return
            }
        }

        // Long recordings (>30 s) are chunked so Whisper's 30-s encoder window is not overflowed.
        if (processedSamples.size > CHUNK_SAMPLES) {
            Timber.i("[ENTER] TranscriptionCoordinator.transcribeChunked | sampleCount=%d", processedSamples.size)
            transcribeChunked(processedSamples, pipelineStart)
            return
        }

        // Pad audio shorter than 1.25s to 1.25s (20000 samples @ 16kHz).
        // Whisper degrades noticeably on very short clips; silence padding is free.
        val minSamples = (AudioRecorder.SAMPLE_RATE * MIN_AUDIO_DURATION_SEC).toInt()
        val paddedSamples = if (processedSamples.size < minSamples) {
            Timber.d("[TRANSCRIPTION] transcribe | short audio padded | before=%d after=%d samples", processedSamples.size, minSamples)
            val buf = paddingBuffer.let { existing ->
                if (existing != null && existing.size >= minSamples) {
                    existing.fill(0f)
                    existing
                } else {
                    FloatArray(minSamples).also { paddingBuffer = it }
                }
            }
            processedSamples.copyInto(buf)
            buf
        } else {
            processedSamples
        }

        val audioDurationMs = (paddedSamples.size.toLong() * 1000) / AudioRecorder.SAMPLE_RATE
        _state.value = TranscriptionState.Transcribing(audioDurationMs = audioDurationMs)

        try {
            // Disable native VAD — audio is already pre-trimmed by ONNX Silero
            // VAD above; running a second VAD pass inside whisper.cpp adds latency
            // with no benefit on pre-processed audio.
            val inferenceConfig = TranscriptionConfig(
                language = "en",
                nThreads = whisperThreads,
                translate = false,
                autoDetect = false,
                initialPrompt = contextualPrompt,
                useVad = false,
                noSpeechThreshold = settings.noSpeechThreshold,
                logprobThreshold = settings.logprobThreshold,
                entropyThreshold = settings.entropyThreshold,
            )
            val accumulatedText = StringBuilder()
            val rawResult = whisperEngine.transcribeStreaming(
                samples = paddedSamples,
                config = inferenceConfig,
                onSegment = { segmentText ->
                    accumulatedText.append(segmentText)
                    _state.value = TranscriptionState.Transcribing(
                        audioDurationMs = audioDurationMs,
                        partialText = accumulatedText.toString().trim(),
                    )
                },
            )

            val correctedRawText = ConfusionSetCorrector.apply(
                rawResult.text,
                ConfusionSetCorrector.Context(
                    packageName = inputContext.packageName,
                    hintText = inputContext.hintText,
                    className = inputContext.className,
                    avgLogprob = rawResult.avgLogprob,
                ),
            )
            val correctedRawResult = if (correctedRawText != rawResult.text) {
                Timber.w("[TRANSCRIPTION] transcribe | confusion-set corrected raw text from=\"%s\" to=\"%s\" avgLogprob=%.3f",
                    rawResult.text, correctedRawText, rawResult.avgLogprob)
                rawResult.copy(text = correctedRawText)
            } else {
                rawResult
            }

            // Phase 1: Voice command detection — short-circuit before text processing
            when (val cmdResult = VoiceCommandDetector.detect(correctedRawResult.text)) {
                is VoiceCommandResult.Command -> {
                    val pipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000
                    Timber.i(
                        "[EXIT] TranscriptionCoordinator.transcribe | voiceCommand=%s pipelineMs=%d rawTextLen=%d",
                        cmdResult.action::class.simpleName, pipelineMs, correctedRawResult.text.length,
                    )
                    _state.value = TranscriptionState.CommandDetected(cmdResult.action)
                    executeVoiceAction(cmdResult.action)
                    return
                }
                is VoiceCommandResult.Text -> {
                    // Continue with Phase 2 + 3 text processing below
                }
            }

            // Phase 2 + 3: Content normalization and formatting (async — emit raw result first)
            val pipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000
            val rtf = if (audioDurationMs > 0) pipelineMs.toFloat() / audioDurationMs else 0f
            _state.value = TranscriptionState.Done(correctedRawResult)
            _history.value = _history.value + correctedRawResult

            Timber.i("[PERF] TranscriptionCoordinator.transcribe | pipelineMs=%d rtf=%.2f audioDurationMs=%d inferenceMs=%d vadMs=%d",
                pipelineMs, rtf, audioDurationMs, correctedRawResult.inferenceDurationMs, vadMs)
            Timber.i("[EXIT] TranscriptionCoordinator.transcribe | textLen=%d lang=%s (raw — post-processing async)",
                correctedRawResult.text.length, correctedRawResult.language)

            // Normalize async — update state and history when done
            val postStart = System.nanoTime()
            val cleanedText = TextPostProcessor.process(correctedRawResult.text)
            val postMs = (System.nanoTime() - postStart) / 1_000_000
            Timber.d("[PERF] TranscriptionCoordinator.transcribe | postProcessMs=%d changed=%b rawLen=%d cleanLen=%d",
                postMs, cleanedText != correctedRawResult.text, correctedRawResult.text.length, cleanedText.length)

            // Retry voice-command detection after normalization to handle minor ASR punctuation/spacing noise.
            when (val cmdResult = VoiceCommandDetector.detect(cleanedText)) {
                is VoiceCommandResult.Command -> {
                    Timber.i(
                        "[EXIT] TranscriptionCoordinator.transcribe | normalizedVoiceCommand=%s rawTextLen=%d cleanTextLen=%d",
                        cmdResult.action::class.simpleName, correctedRawResult.text.length, cleanedText.length,
                    )
                    _state.value = TranscriptionState.CommandDetected(cmdResult.action)
                    executeVoiceAction(cmdResult.action)
                    return
                }
                is VoiceCommandResult.Text -> {
                    // Continue with regular text flow below.
                }
            }

            val result = if (cleanedText != correctedRawResult.text) {
                val normalizedResult = correctedRawResult.copy(text = cleanedText)
                _state.value = TranscriptionState.Done(normalizedResult)
                // Update history entry with normalized text
                _history.value = _history.value.dropLast(1) + normalizedResult
                normalizedResult
            } else {
                correctedRawResult
            }

            maybeSwitchBackendForLatency(audioDurationMs = audioDurationMs, rtf = rtf)

            // Auto-reset to Idle so the overlay hides when the keyboard dismisses.
            scheduleResetToIdle()

            // Post-transcription actions based on settings
            if (result.text.isNotBlank()) {
                var insertedDirectly = false
                if (settings.autoInsertText && SafeWordAccessibilityService.isActive()) {
                    Timber.d("[TRANSCRIPTION] transcribe | auto-insert enabled, a11y active")
                    insertedDirectly = SafeWordAccessibilityService.insertText(result.text)
                } else if (settings.autoInsertText) {
                    Timber.d("[TRANSCRIPTION] transcribe | auto-insert enabled but a11y NOT active")
                }
                if (settings.autoCopyToClipboard && !insertedDirectly) {
                    Timber.d("[TRANSCRIPTION] transcribe | copying to clipboard (direct insert %s)",
                        if (insertedDirectly) "succeeded" else "unavailable")
                    copyToClipboard(result.text)
                }
                if (settings.saveToHistory) {
                    Timber.d("[TRANSCRIPTION] transcribe | auto-save to history enabled")
                    saveToDatabase(result)
                }
            } else {
                Timber.d("[TRANSCRIPTION] transcribe | result text blank — skipping post-actions")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "[TRANSCRIPTION] transcribe | error threads=%d", whisperThreads)
            _state.value = TranscriptionState.Error(
                message = e.message ?: context.getString(R.string.error_transcription_failed),
                previousState = TranscriptionState.Transcribing(audioDurationMs),
            )
            scheduleResetToIdle()
        }
    }

    // ── Chunked transcription ──

    /**
     * Transcribe audio longer than 30 s by splitting it into 30-s chunks.
     * Each chunk is transcribed sequentially; the accumulated text is emitted
     * progressively as [TranscriptionState.Transcribing] partial updates.
     * Post-processing (ConfusionSetCorrector, TextPostProcessor) runs on the
     * merged result. Voice-command detection is intentionally skipped for
     * long recordings (no typical use case for commands in 30 s+ audio).
     */
    private suspend fun transcribeChunked(samples: FloatArray, pipelineStart: Long) {
        val fullDurationMs = (samples.size.toLong() * 1000) / AudioRecorder.SAMPLE_RATE
        _state.value = TranscriptionState.Transcribing(audioDurationMs = fullDurationMs)

        val chunkCount = (samples.size + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
        val chunkResults = mutableListOf<TranscriptionResult>()

        try {
            val settings = settingsRepository.settings.first()
            val inputContext = currentInputContext()
            val basePrompt = buildContextAwarePrompt(settings.initialPrompt, inputContext)

            for (i in 0 until chunkCount) {
                val from = i * CHUNK_SAMPLES
                val to = minOf(from + CHUNK_SAMPLES, samples.size)
                val chunk = samples.copyOfRange(from, to)
                val chunkDurationSec = chunk.size.toFloat() / AudioRecorder.SAMPLE_RATE
                val whisperThreads = InferenceConfig.optimalWhisperThreads(chunkDurationSec)

                // Use previous chunk text as continuation prompt for better coherence.
                val chunkPrompt = if (i == 0) {
                    basePrompt
                } else {
                    chunkResults.takeLast(2).joinToString(" ") { it.text.takeLast(200) }
                }

                val config = TranscriptionConfig(
                    language = "en",
                    nThreads = whisperThreads,
                    translate = false,
                    autoDetect = false,
                    initialPrompt = chunkPrompt,
                    useVad = false,
                    noSpeechThreshold = settings.noSpeechThreshold,
                    logprobThreshold = settings.logprobThreshold,
                    entropyThreshold = settings.entropyThreshold,
                )

                val accSegBuffer = StringBuilder()
                val chunkResult = whisperEngine.transcribeStreaming(chunk, config) { seg ->
                    accSegBuffer.append(seg)
                    val preview = (chunkResults.map { it.text } + accSegBuffer.toString())
                        .joinToString(" ")
                        .trim()
                    _state.value = TranscriptionState.Transcribing(
                        audioDurationMs = fullDurationMs,
                        partialText = preview,
                    )
                }
                if (chunkResult.text.isNotBlank()) chunkResults.add(chunkResult)

                val chunkPreview = chunkResults.joinToString(" ") { it.text }.trim()
                _state.value = TranscriptionState.Transcribing(
                    audioDurationMs = fullDurationMs,
                    partialText = chunkPreview,
                )
                Timber.i("[PERF] transcribeChunked | chunk=%d/%d chunkMs=%d textLen=%d",
                    i + 1, chunkCount, chunkResult.inferenceDurationMs, chunkResult.text.length)
            }

            val mergedText = chunkResults.joinToString(" ") { it.text }.trim()
            val avgLogprob = if (chunkResults.isEmpty()) 0f else chunkResults.map { it.avgLogprob }.average().toFloat()
            val pipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000

            val rawResult = TranscriptionResult(
                text = mergedText,
                audioDurationMs = fullDurationMs,
                inferenceDurationMs = pipelineMs,
                avgLogprob = avgLogprob,
            )

            val correctedText = ConfusionSetCorrector.apply(
                rawResult.text,
                ConfusionSetCorrector.Context(
                    packageName = inputContext.packageName,
                    hintText = inputContext.hintText,
                    className = inputContext.className,
                    avgLogprob = rawResult.avgLogprob,
                ),
            )
            val correctedResult = if (correctedText != rawResult.text) rawResult.copy(text = correctedText) else rawResult

            _state.value = TranscriptionState.Done(correctedResult)
            _history.value = _history.value + correctedResult
            Timber.i("[EXIT] TranscriptionCoordinator.transcribeChunked | chunks=%d pipelineMs=%d textLen=%d",
                chunkCount, pipelineMs, correctedResult.text.length)

            val cleanedText = TextPostProcessor.process(correctedResult.text)
            val finalResult = if (cleanedText != correctedResult.text) {
                val normalized = correctedResult.copy(text = cleanedText)
                _state.value = TranscriptionState.Done(normalized)
                _history.value = _history.value.dropLast(1) + normalized
                normalized
            } else {
                correctedResult
            }

            val rtf = if (fullDurationMs > 0) pipelineMs.toFloat() / fullDurationMs else 0f
            maybeSwitchBackendForLatency(audioDurationMs = fullDurationMs, rtf = rtf)
            scheduleResetToIdle()

            if (finalResult.text.isNotBlank()) {
                var insertedDirectly = false
                if (settings.autoInsertText && SafeWordAccessibilityService.isActive()) {
                    insertedDirectly = SafeWordAccessibilityService.insertText(finalResult.text)
                }
                if (settings.autoCopyToClipboard && !insertedDirectly) {
                    copyToClipboard(finalResult.text)
                }
                if (settings.saveToHistory) saveToDatabase(finalResult)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] TranscriptionCoordinator.transcribeChunked | chunks=%d", chunkCount)
            _state.value = TranscriptionState.Error(
                message = e.message ?: context.getString(R.string.error_transcription_failed),
                previousState = TranscriptionState.Transcribing(fullDurationMs),
            )
            scheduleResetToIdle()
        }
    }

    // ── Voice action execution ──

    /**
     * Execute a voice action then transition to Idle.
     * StopListening is handled inline; all other actions are dispatched
     * to [SafeWordAccessibilityService].
     */
    private fun executeVoiceAction(action: VoiceAction) {
        when (action) {
            is VoiceAction.StopListening -> {
                Timber.i("[VOICE] executeVoiceAction | StopListening → cancelling")
                cancel()
                return
            }
            else -> {
                if (SafeWordAccessibilityService.isActive()) {
                    val success = SafeWordAccessibilityService.executeVoiceAction(action)
                    Timber.i("[VOICE] executeVoiceAction | action=%s success=%b", action, success)
                    if (!success) {
                        _state.value = TranscriptionState.Error(
                            message = context.getString(R.string.error_voice_command_failed),
                            previousState = TranscriptionState.CommandDetected(action),
                        )
                        scheduleResetToIdle()
                        return
                    }
                } else {
                    Timber.w("[VOICE] executeVoiceAction | a11y service not active, cannot execute %s", action)
                    _state.value = TranscriptionState.Error(
                        message = context.getString(R.string.error_accessibility_service_inactive),
                        previousState = TranscriptionState.CommandDetected(action),
                    )
                    scheduleResetToIdle()
                    return
                }
                _state.value = TranscriptionState.Idle
            }
        }
    }

    // ── End voice action execution ──

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clipboard_label_transcription), text))
            Timber.i("[CLIPBOARD] copyToClipboard | auto-copied %d chars", text.length)
        } catch (e: Exception) {
            Timber.e(e, "[CLIPBOARD] copyToClipboard | failed")
        }
    }

    private suspend fun saveToDatabase(result: TranscriptionResult) {
        try {
            transcriptionDao.insert(
                TranscriptionEntity(
                    text = result.text,
                    audioDurationMs = result.audioDurationMs,
                    inferenceDurationMs = result.inferenceDurationMs,
                    language = result.language,
                    createdAt = result.timestamp,
                ),
            )
            Timber.i("[DB] saveToDatabase | saved transcription lang=%s textLen=%d", result.language, result.text.length)
        } catch (e: Exception) {
            Timber.e(e, "[DB] saveToDatabase | failed")
        }
    }

    private fun stopForegroundService() {
        try {
            context.stopService(Intent(context, RecordingService::class.java))
            Timber.d("[SERVICE] stopForegroundService | RecordingService stopped")
        } catch (e: Exception) {
            Timber.w(e, "[SERVICE] stopForegroundService | error stopping RecordingService")
        }
    }

    suspend fun destroy() {
        Timber.i("[LIFECYCLE] TranscriptionCoordinator.destroy | releasing all resources")
        durationJob?.cancel()
        recordingJob?.cancel()
        // Do NOT cancel scope — it's the shared @ApplicationScope
        whisperEngine.release()
        vadDetector.release()
        stopForegroundService()
        Timber.i("[EXIT] TranscriptionCoordinator.destroy | complete")
    }
}
