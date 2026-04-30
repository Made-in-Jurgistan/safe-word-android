package com.safeword.android.transcription

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.data.settings.CustomCommandRepository
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.R
import com.safeword.android.service.AccessibilityBridge
import com.safeword.android.service.SafeWordAccessibilityService
import com.safeword.android.service.ThermalMonitor
import com.safeword.android.di.ApplicationScope
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Coordinates between AudioRecorder, Moonshine streaming, and output actions.
 * Wires user settings (streaming updates, VAD, auto-stop) through to the pipeline.
 * Performs post-transcription actions: clipboard copy, accessibility insert, DB save.
 */
@Singleton
class TranscriptionCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    @ApplicationScope private val scope: CoroutineScope,
    private val streamingEngine: MoonshineStreamingEngine,
    private val thermalMonitor: ThermalMonitor,
    private val customCommandRepository: CustomCommandRepository,
    private val textProcessor: DefaultTextProcessor,
    private val audioRecorder: com.safeword.android.audio.AudioRecorder,
    private val a11y: AccessibilityBridge,
    private val outputHandler: TranscriptionOutputHandler,
) {

    private val vibrator: Vibrator by lazy {
        context.getSystemService(Vibrator::class.java)
    }

    companion object {
        /** Polling interval for recording duration updates (ms). */
        private const val DURATION_POLL_INTERVAL_MS = 200L
        /** Thermal status at or above which post-processing is skipped. */
        private const val THERMAL_SKIP_POSTPROCESS = PowerManager.THERMAL_STATUS_SEVERE
        /** Thermal status at or above which recording is auto-stopped. */
        private const val THERMAL_AUTO_STOP = PowerManager.THERMAL_STATUS_CRITICAL
    }

    private var recordingJob: Job? = null
    private var durationJob: Job? = null
    private var preloadJob: Job? = null
    private var resetJob: Job? = null
    private var recordingStartTime: Long = 0
    private val streamState = StreamingTextState()

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    /** All completed transcriptions this session. */
    private val _history = MutableStateFlow<List<TranscriptionResult>>(emptyList())
    val history: StateFlow<List<TranscriptionResult>> = _history.asStateFlow()

    /**
     * Eagerly load VAD + Moonshine before the user presses the mic button.
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
        // Observe custom voice commands and keep VoiceCommandDetector in sync.
        scope.launch {
            customCommandRepository.commands.collect { commands ->
                VoiceCommandDetector.updateCustomCommands(commands)
            }
        }
        preloadJob = scope.launch(Dispatchers.IO) {
            Timber.i("[INIT] TranscriptionCoordinator.preloadModels | background preload starting")
            if (!streamingEngine.isLoaded.value) {
                val modelInfo = resolveModelInfo() ?: run {
                    Timber.w("[INIT] TranscriptionCoordinator.preloadModels | no downloaded model — skipping")
                    return@launch
                }
                val updateIntervalMs = settingsRepository.settings.first().streamingUpdateIntervalMs
                val modelDir = modelRepository.getModelDir(modelInfo.id)
                Timber.i("[INIT] TranscriptionCoordinator.preloadModels | loading Moonshine model")
                streamingEngine.load(
                    modelDir = modelDir,
                    expectedComponents = modelInfo.components,
                    updateIntervalMs = updateIntervalMs,
                )
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
        streamState.reset()
        _draftText.value = ""
        _state.value = TranscriptionState.Recording()
        recordingStartTime = System.currentTimeMillis()

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

                if (!streamingEngine.isLoaded.value) {
                    val modelInfo = resolveModelInfo()
                    if (modelInfo == null) {
                        Timber.e("[MODEL] startRecording | no downloaded model — cannot transcribe")
                        _state.value = TranscriptionState.Error(context.getString(R.string.error_no_model_downloaded))
                        scheduleResetToIdle()
                        return@launch
                    }
                    val modelDir = modelRepository.getModelDir(modelInfo.id)
                    Timber.i("[MODEL] startRecording | auto-loading Moonshine model")
                    withContext(Dispatchers.IO) {
                        streamingEngine.load(
                            modelDir = modelDir,
                            expectedComponents = modelInfo.components,
                            updateIntervalMs = settings.streamingUpdateIntervalMs,
                        )
                    }
                }

                // Launch amplitude monitoring
                launch {
                    audioRecorder.amplitudeDb.collect { db ->
                        val current = _state.value
                        if (current is TranscriptionState.Recording) {
                            _state.value = current.copy(amplitudeDb = db)
                        }
                    }
                }

                // Thermal-aware auto-stop: abort recording if device reaches CRITICAL+
                launch {
                    thermalMonitor.thermalStatus.collect { status ->
                        if (status >= THERMAL_AUTO_STOP && _state.value is TranscriptionState.Recording) {
                            Timber.w("[THERMAL] auto-stop recording | thermalStatus=%d (CRITICAL+)", status)
                            stopRecording()
                        }
                    }
                }

                // Start Moonshine streaming — feeds live partial text to the UI during recording
                launch {
                    if (streamingEngine.isLoaded.value) {
                        streamingEngine.startStreaming()
                        val sessionStart = System.currentTimeMillis()
                        launch {
                            streamingEngine.streamingEvents.collect { line ->
                                streamState.setLiveText(line.text)
                                if (!streamState.draftEditedByUser) {
                                    _draftText.value = streamState.buildDraftText()
                                }
                                if (_state.value is TranscriptionState.Recording ||
                                    _state.value is TranscriptionState.Streaming
                                ) {
                                    _state.value = TranscriptionState.Streaming(
                                        liveText = line.text,
                                        lineId = line.lineId,
                                        durationMs = System.currentTimeMillis() - sessionStart,
                                    )
                                }
                            }
                        }
                        // B2 + D3: For each finalised Moonshine line — pre-screen for voice
                        // commands (D3), then insert remaining dictation text directly (B2).
                        // This delivers command execution and text insertion without a second pass.
                        launch {
                            streamingEngine.completedLines.collect { line ->
                                if (line.text.isBlank()) return@collect
                                val inputCtx = currentInputContext()
                                val correctorCtx = buildCorrectorCtx(inputCtx)
                                val corrected = ConfusionSetCorrector.apply(line.text, correctorCtx)
                                // D3: voice command pre-screening on each completed line
                                val fieldType = deriveFieldType(inputCtx)
                                when (val commandResult = VoiceCommandDetector.detect(corrected, fieldType)) {
                                    is VoiceCommandResult.Command -> {
                                        val action = commandResult.action
                                        streamState.markCommandTriggered()
                                        Timber.i("[VOICE] D3 streaming cmd | lineId=%d action=%s", line.lineId, action)
                                        when (action) {
                                            is VoiceAction.StopListening -> {
                                                stopRecording()
                                            }
                                            else -> {
                                                if (a11y.isActive()) {
                                                    a11y.executeVoiceAction(action)
                                                    Timber.i("[VOICE] D3 streaming cmd executed | action=%s", action)
                                                } else {
                                                    Timber.w("[VOICE] D3 streaming cmd | a11y not active, skipping action=%s", action)
                                                }
                                            }
                                        }
                                        return@collect // skip text insertion for command lines
                                    }
                                    is VoiceCommandResult.Text -> {
                                        // B2: not a command — insert dictation text into the focused field
                                        val processed = textProcessor.process(corrected, correctorCtx)
                                        if (processed.isNotBlank()) {
                                            streamState.addCompletedLine(processed)
                                            if (streamState.draftEditedByUser) {
                                                _draftText.value = listOf(_draftText.value.trim(), processed)
                                                    .filter { it.isNotBlank() }
                                                    .joinToString(" ")
                                            } else {
                                                _draftText.value = streamState.buildDraftText()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Start recording — this suspends until cancelled
                try {
                    audioRecorder.record(onChunkAvailable = { chunk ->
                        if (streamingEngine.isLoaded.value && streamingEngine.isStreaming.value) {
                            streamingEngine.addAudio(chunk)
                        }
                    })
                } finally {
                    streamingEngine.stopStreaming()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "[RECORDING] recordingJob | recording error")
                _state.value = TranscriptionState.Error(
                    message = e.message ?: context.getString(R.string.error_recording_failed),
                    previousState = TranscriptionState.Recording(),
                )
                scheduleResetToIdle()
            }
        }
    }

    /**
     * Stop recording and begin transcription. Transitions: Recording/Streaming → Transcribing → Done.
     *
    * Finalizes the Moonshine streaming transcription.
     */
    fun stopRecording() {
        val currentState = _state.value
        if (currentState !is TranscriptionState.Recording &&
            currentState !is TranscriptionState.Streaming
        ) {
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
                finalizeStreamingResult()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "[TRANSCRIPTION] stopRecording | error after stopping recording")
                _state.value = TranscriptionState.Error(e.message ?: context.getString(R.string.error_transcription_failed))
                scheduleResetToIdle()
            }
        }
    }

    /** Resolve the Moonshine model info for streaming. */
    private fun resolveModelInfo(): ModelInfo? {
        val modelId = if (modelRepository.isModelDownloaded(ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID)) {
            ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        } else {
            modelRepository.getDownloadedModels().firstOrNull()?.id
        }
        return modelId?.let { ModelInfo.findById(it) }
    }

    private fun currentInputContext(): SafeWordAccessibilityService.InputContextSnapshot =
        a11y.inputContextSnapshot()

    /** Build a [ConfusionSetCorrector.Context] from the current accessibility snapshot.
     *  avgLogprob defaults below LOW_CONF threshold so confusion-set corrections fire.
     *  When word-level timestamps become available from the SDK,
     *  [WordConfidenceEstimator] can compute a real value here. */
    private fun buildCorrectorCtx(ctx: SafeWordAccessibilityService.InputContextSnapshot): ConfusionSetCorrector.Context =
        ConfusionSetCorrector.Context(
            packageName = ctx.packageName,
            hintText = ctx.hintText,
            className = ctx.className,
            avgLogprob = WordConfidenceEstimator.TRUST_THRESHOLD - 1.0f,
        )

    /**
     * Derive [FieldType] from the current input context using hint / class heuristics.
     * Used to gate voice commands that are inappropriate in certain contexts.
     */
    private fun deriveFieldType(ctx: SafeWordAccessibilityService.InputContextSnapshot): FieldType {
        val hint = ctx.hintText.lowercase()
        val cls = ctx.className.lowercase()
        return when {
            hint.contains("password") || hint.contains("pin") || cls.contains("password") -> FieldType.PASSWORD
            hint.contains("search") || hint.contains("url") || hint.contains("address") ||
                cls.contains("url") || cls.contains("search") -> FieldType.SEARCH
            hint.contains("message") || hint.contains("chat") || hint.contains("sms") ||
                hint.contains("compose") -> FieldType.MESSAGING
            else -> FieldType.UNKNOWN
        }
    }


    /** Finalize transcription from Moonshine streaming output. */
    private suspend fun finalizeStreamingResult() {
        val settings = settingsRepository.settings.first()
        val audioDurationMs = System.currentTimeMillis() - recordingStartTime
        _state.value = TranscriptionState.Transcribing(audioDurationMs = audioDurationMs)

        // Completed lines are already corrected + processed during streaming.
        // Only fallback text (live / user-edited draft) needs processing.
        val processedStreamText = streamState.completedText()
        val draftVal = _draftText.value.trim()
        val alreadyProcessed: Boolean
        val combinedText: String
        when {
            streamState.draftEditedByUser && draftVal.isNotBlank() -> {
                combinedText = draftVal
                alreadyProcessed = false
            }
            processedStreamText.isNotBlank() -> {
                combinedText = processedStreamText
                alreadyProcessed = true
            }
            else -> {
                combinedText = streamState.liveText.trim()
                alreadyProcessed = false
            }
        }

        if (combinedText.isBlank()) {
            Timber.w("[TRANSCRIPTION] finalizeStreamingResult | no text captured")
            _state.value = TranscriptionState.Error(context.getString(R.string.error_no_speech_detected))
            scheduleResetToIdle()
            return
        }

        val inputContext = currentInputContext()
        val correctorCtx = buildCorrectorCtx(inputContext)

        // Voice command check on the full combined text (skipped if already triggered during streaming)
        val fieldType = deriveFieldType(inputContext)
        if (!streamState.commandTriggered) {
            val textForCmd = if (alreadyProcessed) combinedText
                else ConfusionSetCorrector.apply(combinedText, correctorCtx)
            when (val cmdResult = VoiceCommandDetector.detect(textForCmd, fieldType)) {
                is VoiceCommandResult.Command -> {
                    fireCommandHaptic()
                    _state.value = TranscriptionState.CommandDetected(cmdResult.action)
                    executeVoiceAction(cmdResult.action)
                    return
                }
                is VoiceCommandResult.Text -> {
                    // Continue with text flow below.
                }
            }
        }

        // Skip redundant correction/processing if streaming already handled it
        val thermalStatus = thermalMonitor.thermalStatus.value
        val cleanedText = when {
            alreadyProcessed -> combinedText
            thermalStatus >= THERMAL_SKIP_POSTPROCESS -> {
                Timber.w("[THERMAL] finalizeStreamingResult | skipping post-processing thermalStatus=%d", thermalStatus)
                ConfusionSetCorrector.apply(combinedText, correctorCtx)
            }
            else -> textProcessor.process(combinedText, correctorCtx)
        }
        val result = TranscriptionResult(
            text = cleanedText,
            audioDurationMs = audioDurationMs,
            inferenceDurationMs = 0L,
        )

        _state.value = TranscriptionState.Done(result)
        _history.value = _history.value + result
        scheduleResetToIdle()

        if (result.text.isNotBlank()) {
            val alreadyInserted = streamState.insertedCount > 0
            var insertedDirectly = false
            if (!alreadyInserted && settings.autoInsertText) {
                insertedDirectly = outputHandler.insertText(result.text)
            }
            if (!alreadyInserted && settings.autoCopyToClipboard && !insertedDirectly) {
                outputHandler.copyToClipboard(result.text)
            }
            if (settings.saveToHistory) {
                outputHandler.saveToDatabase(result)
            }
        }
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
        streamState.clearDraftEditedByUser()
        _draftText.value = ""
        audioRecorder.stop()
        recordingJob?.cancel()
        recordingJob = null
        _state.value = TranscriptionState.Idle
        Timber.i("[STATE] cancel | operation cancelled → Idle")
    }

    /** Reset to Idle from Done or Error. */
    fun reset() {
        resetJob?.cancel()
        resetJob = null
        streamState.clearDraftEditedByUser()
        _draftText.value = ""
        Timber.d("[STATE] reset | → Idle")
        _state.value = TranscriptionState.Idle
    }

    fun updateDraftText(text: String) {
        if (_state.value !is TranscriptionState.Recording &&
            _state.value !is TranscriptionState.Streaming
        ) {
            return
        }
        streamState.markDraftEditedByUser()
        _draftText.value = text
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



    // ── Voice action execution ──

    /**
     * Execute a voice action then transition to Idle.
     * StopListening is handled inline; all other actions are dispatched
     * to the [AccessibilityBridge].
     */
    private fun executeVoiceAction(action: VoiceAction) {
        when (action) {
            is VoiceAction.StopListening -> {
                Timber.i("[VOICE] executeVoiceAction | StopListening → cancelling")
                cancel()
                return
            }
            else -> {
                if (a11y.isActive()) {
                    val success = a11y.executeVoiceAction(action)
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

    /**
     * Fire a short 50 ms haptic pulse to confirm a voice command was recognised.
     * Silent failure — vibrator unavailable on some devices/emulators.
     */
    private fun fireCommandHaptic() {
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Timber.w(e, "[WARN] fireCommandHaptic | vibrate failed — ignoring")
        }
    }

    suspend fun destroy() {
        Timber.i("[LIFECYCLE] TranscriptionCoordinator.destroy | releasing all resources")
        durationJob?.cancel()
        recordingJob?.cancel()
        // Do NOT cancel scope — it's the shared @ApplicationScope
        streamingEngine.release()
        Timber.i("[EXIT] TranscriptionCoordinator.destroy | complete")
    }
}
