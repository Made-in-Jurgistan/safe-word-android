
package com.safeword.android.transcription

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.PowerManager
import com.safeword.android.audio.AdaptiveVadSensitivity
import com.safeword.android.audio.AudioRecorder
import com.safeword.android.audio.SilenceAutoStopDetector
import com.safeword.android.data.MicAccessEventRepository
import com.safeword.android.data.db.MicAccessEventEntity
import com.safeword.android.R
import com.safeword.android.service.AccessibilityStateHolder
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.service.SafeWordAccessibilityService
import com.safeword.android.di.ApplicationScope
import com.safeword.android.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Orchestrates the recording → inference → post-processing pipeline
 * using Moonshine v2 streaming as the sole STT engine.
 *
 * Manages the state machine: Idle → Recording → Done → Idle.
 * Model lifecycle (loading, path resolution) is delegated to [ModelManager].
 * Text insertion is delegated to [AccessibilityStateHolder].
 */
@Singleton
class TranscriptionCoordinator @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val modelManager: ModelManager,
    private val accessibilityStateHolder: AccessibilityStateHolder,
    private val micAccessEventRepository: MicAccessEventRepository,
    private val correctionLearner: CorrectionLearner,
    private val incrementalInserter: IncrementalTextInserter,
    private val vocabularyObserver: VocabularyObserver,
    private val silenceAutoStopDetector: SilenceAutoStopDetector,
    private val voiceCommandDetector: VoiceCommandDetector,
    private val performanceMonitor: PerformanceMonitor,
    private val adaptiveVadSensitivity: AdaptiveVadSensitivity,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    companion object {
        /** Polling interval for recording duration updates (ms). */
        private const val DURATION_POLL_INTERVAL_MS = 200L

        /** How long Done / Error states linger before auto-resetting to Idle (5s to allow user to read error). */
        private const val RESET_TO_IDLE_DELAY_MS = 5000L

        /** Maximum single-session recording duration before auto-stop (5 min). */
        const val MAX_RECORDING_DURATION_MS = 300_000L

        /** Silence duration that triggers auto-stop when VAD is active (3.5 s). */
        const val SILENCE_AUTO_STOP_MS = 3_500L

        // ── VAD-gated audio feed constants ────────────────────────────────
        /**
         * Fallback minimum speech probability for a chunk to pass directly to the STT engine.
         * The actual threshold used is [AdaptiveVadSensitivity.speechThreshold] * 0.8f for dynamic
         * adaptation to ambient noise levels. This constant serves as a floor only.
         */
        private const val FEED_SPEECH_THRESHOLD_FALLBACK = 0.10f
        /** Pre-roll ring buffer depth (frames). 10 × 30 ms = 300 ms captured before speech onset. */
        private const val FEED_PRE_ROLL_FRAMES = 10
        /** Post-roll depth (frames). 33 × 30 ms ≈ 1 s fed after the last speech frame. */
        private const val FEED_POST_ROLL_FRAMES = 33
    }

    /**
     * Prevents concurrent [stopStreamingRecording] calls from rapid stop-button taps.
     *
     * Concurrency contract:
     * - [stopRecording] launches a fire-and-forget coroutine on [scope] that acquires
     *   [stopMutex] before calling [stopStreamingRecording]. The outer state check is
     *   intentionally outside the mutex (fast path); the inner guard re-checks under
     *   the lock to close the TOCTOU window between the state check and the lock.
     * - [cancel] does not use [stopMutex]; it performs an immediate synchronous reset
     *   and nulls the event listener before posting the cleanup job, so that any
     *   in-flight [onLineCompleted] callbacks after cancellation are silently dropped.
     */
    private val stopMutex = Mutex()

    /** Active recording session job — cancelling this cancels all child jobs atomically. */
    @Volatile private var sessionJob: Job? = null
    @Volatile private var resetJob: Job? = null
    @Volatile private var recordingStartTime: Long = 0

    /** Job that fires 12 s after dictation ends to capture text-field corrections for learning. */
    @Volatile private var idleSnapshotJob: Job? = null

    /**
     * Serialised dispatcher for incremental-insert coroutines — single-threaded FIFO ordering
     * ensures ordered insertion across consecutive [onLineCompleted] callbacks.
     */
    private val serialInsertDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Most-recent incremental-insert job. Joined in [stopStreamingRecording] to ensure
     * the final in-flight insert completes before the buffer is consumed.
     */
    @Volatile private var lastInsertJob: Job? = null

    /**
     * In-flight stopStream() job from [cancel]. Joined at the start of the next
     * [startStreamingRecording] to ensure the engine is fully stopped before reuse.
     */
    @Volatile private var cleanupJob: Job? = null

    /** Row id of the current mic access event — used to mark stopped. */
    @Volatile private var currentMicEventId: Long = 0

    // ---------------------------------------------------------------------------
    // Per-session performance metrics (reset in startRecording via resetForSession)
    // ---------------------------------------------------------------------------
    private val sessionCommandsDetected = AtomicInteger(0)
    private val sessionLinesInserted = AtomicInteger(0)
    private val sessionConfidenceSum = AtomicLong(0L)  // confidence * 1000 for integer math
    private val sessionConfidenceCount = AtomicInteger(0)
    private val partialUpdateEpoch = AtomicLong(0L)

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    /**
     * Eagerly load VAD + STT engine before the user presses the mic button.
     * Delegates to [ModelManager.preloadModels].
     */
    fun preloadModels() {
        modelManager.preloadModels()
    }

    /**
     * Cancel a running background model preload. Safe to call redundantly.
     * Delegates to [ModelManager.cancelPreload].
     */
    fun cancelPreload() {
        modelManager.cancelPreload()
    }

    /**
     * Prewarm the personal vocabulary cache with the most-used entries.
     * Call alongside [preloadModels] during service startup.
     */
    fun preloadVocabulary() {
        vocabularyObserver.preloadVocabulary()
    }

    /**
     * Start recording. Transitions: Idle → Recording.
     */
    fun startRecording() {
        val currentState = _state.value
        if (currentState !is TranscriptionState.Idle &&
            currentState !is TranscriptionState.Done &&
            currentState !is TranscriptionState.Error
        ) {
            Timber.w("[STATE] startRecording | blocked from state=%s", currentState)
            return
        }

        Timber.i("[ENTER] TranscriptionCoordinator.startRecording | currentState=%s", currentState)
        idleSnapshotJob?.cancel()
        idleSnapshotJob = null
        resetJob?.cancel()
        resetJob = null
        // Reset per-session metrics
        sessionCommandsDetected.set(0)
        sessionLinesInserted.set(0)
        sessionConfidenceSum.set(0L)
        sessionConfidenceCount.set(0)
        performanceMonitor.resetAll()
        recordingStartTime = System.currentTimeMillis()

        // Eagerly warm the SymSpell dictionary while the user speaks.
        incrementalInserter.warmSymSpell()

        sessionJob = scope.launch {
            // C2: All pre-flight checks run BEFORE transitioning to Recording state so
            //     the UI never flashes Recording→Error on a failing startup.

            // VAD is always on — load Silero if not already loaded.
            val useVad = run {
                val loaded = modelManager.ensureVadLoaded()
                if (!loaded) {
                    Timber.w("[WARN] TranscriptionCoordinator.startRecording | Silero VAD failed to load, continuing without VAD")
                }
                loaded
            }

            // Gate on thermal status — refuse to start if device is SEVERE+.
            if (modelManager.isTooHotForTranscription()) {
                Timber.w("[THERMAL] TranscriptionCoordinator.startRecording | device too hot, refusing to start")
                _state.value = TranscriptionState.Error(context.getString(R.string.error_device_too_hot))
                scheduleResetToIdle()
                return@launch
            }

            // Detect battery saver — log warning so support traces can explain latency spikes.
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (powerManager.isPowerSaveMode) {
                Timber.w("[POWER] startRecording | Battery saver active — expect degraded STT latency")
            }

            // Auto-load the Moonshine streaming engine if not yet ready.
            Timber.i("[STATE] TranscriptionCoordinator.startRecording | sttEngine=moonshine_v2")
            if (!modelManager.ensureEngineLoaded()) {
                Timber.e("[ERROR] TranscriptionCoordinator.startRecording | failed to ensure Moonshine streaming engine loaded")
                _state.value = TranscriptionState.Error(context.getString(R.string.error_no_model_downloaded))
                scheduleResetToIdle()
                return@launch
            }

            // C1: Commit the mic access row under NonCancellable so recordStop() always
            //     has a valid event ID, even if the session is cancelled immediately after.
            currentMicEventId = withContext(NonCancellable + ioDispatcher) {
                micAccessEventRepository.recordStart(
                    purpose = MicAccessEventEntity.PURPOSE_TRANSCRIPTION,
                    startedAt = recordingStartTime,
                )
            }

            // All checks passed and DB row committed — transition to Recording state.
            _state.value = TranscriptionState.Recording()

            val durationJob = launch {
                val maxMs = MAX_RECORDING_DURATION_MS
                while (true) {
                    delay(DURATION_POLL_INTERVAL_MS)
                    val elapsed = System.currentTimeMillis() - recordingStartTime
                    _state.update { current ->
                        if (current is TranscriptionState.Recording) current.copy(durationMs = elapsed) else current
                    }
                    val current = _state.value
                    if (current is TranscriptionState.Recording) {
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

            try {
                Timber.i("[ENTER] TranscriptionCoordinator.startStreamingRecording | sttEngine=moonshine_v2 architecture=streaming-native useVad=%b", useVad)
                startStreamingRecording(useVad)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "[RECORDING] recordingJob | recording error")
                _state.value = TranscriptionState.Error(
                    message = e.message ?: context.getString(R.string.error_recording_failed),
                )
                scheduleResetToIdle()
            } finally {
                durationJob.cancel()
            }
        }
    }

    /**
     * Stop recording and finalize transcription. Transitions: Recording → Done.
     */
    fun stopRecording() {
        val currentState = _state.value
        if (currentState !is TranscriptionState.Recording) {
            Timber.w("[STATE] stopRecording | blocked from state=%s", currentState)
            return
        }

        Timber.i("[ENTER] TranscriptionCoordinator.stopRecording | elapsedMs=%d",
            System.currentTimeMillis() - recordingStartTime)

        // Privacy audit — record mic access end
        val stopTime = System.currentTimeMillis()
        val eventId = currentMicEventId
        if (eventId > 0) {
            scope.launch(ioDispatcher) {
                micAccessEventRepository.recordStop(eventId, stopTime, stopTime - recordingStartTime)
            }
        }

        // Stop the AudioRecorder first so any pending read() unblocks
        audioRecorder.stop()
        // Null out the event listener immediately so stale onLineCompleted
        // callbacks cannot queue work on serialInsertDispatcher during teardown.
        modelManager.streamingEngine.setEventListener(null)
        // Clear the buffer recycler so the engine holds no reference to AudioRecorder.
        modelManager.streamingEngine.setBufferRecycler(null)
        // Cancel the session coroutine so its child jobs exit
        val previousSession = sessionJob
        sessionJob = null
        previousSession?.cancel()

        // fire-and-forget: outlives coordinator via @ApplicationScope
        scope.launch {
            stopMutex.withLock {
                // Re-check state under the lock to close the TOCTOU window between
                // the outer guard and the mutex acquisition.
                if (_state.value !is TranscriptionState.Recording) return@withLock
                try {
                    // Wait briefly for session coroutine cleanup (finally block);
                    // proceed after 500 ms even if it hasn't finished — the finally
                    // block is bookkeeping that does not affect streaming engine state.
                    withTimeoutOrNull(500) { previousSession?.join() }

                    stopStreamingRecording()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[TRANSCRIPTION] stopRecording | error after stopping recording")
                    _state.value = TranscriptionState.Error(e.message ?: context.getString(R.string.error_transcription_failed))
                    scheduleResetToIdle()
                }
            }
        }
    }


    /**
     * Execute a [VoiceAction] detected in a completed streaming line.
     *
     * [VoiceAction.StopListening] is handled locally; all other actions are
     * dispatched to [AccessibilityStateHolder.executeVoiceAction] which delegates
     * to the running [SafeWordAccessibilityService].
     */
    private fun executeVoiceAction(action: VoiceAction) {
        when (action) {
            is VoiceAction.StopListening -> {
                Timber.i("[VOICE] executeVoiceAction | StopListening → stopping recording")
                stopRecording()
            }
            else -> {
                val success = accessibilityStateHolder.executeVoiceAction(action)
                Timber.i("[VOICE] executeVoiceAction | action=%s success=%b", action, success)
                if (!success) {
                    Timber.w("[VOICE] executeVoiceAction | a11y service not active or action failed: %s", action)
                }
            }
        }
    }

    private fun currentCorrectionContext(): InputContextSnapshot =
        accessibilityStateHolder.inputContextSnapshot()

    private suspend fun handleLineCompleted(
        lineId: Long,
        lineText: String,
        fullText: String,
        completionEpoch: Long,
    ) {
        // TC-1 guard: a callback can race past setEventListener(null) on the
        // MoonshineFeed thread. If the session is no longer Recording, drop the work.
        if (_state.value !is TranscriptionState.Recording) return
        Timber.d("[VOICE] streaming.onLineCompleted | lineId=%d lineLen=%d fullLen=%d", lineId, lineText.length, fullText.length)

        val correctionContext = currentCorrectionContext()
        // Phase 1: voice command detection on the individual completed line.
        // Uses detectIncludingTrailing() so commands appended to a dictation
        // sentence ("Some text. Delete last sentence.") are caught even when
        // the ASR engine doesn't segment them as a standalone line.
        when (val cmdResult = voiceCommandDetector.detectIncludingTrailing(lineText)) {
            is VoiceCommandResult.Command -> {
                Timber.i("[VOICE] streaming.onLineCompleted | voiceCommand=%s lineText='%s'", cmdResult.action, lineText)
                sessionCommandsDetected.incrementAndGet()
                sessionConfidenceSum.addAndGet((cmdResult.confidence * 1000).toLong())
                sessionConfidenceCount.incrementAndGet()
                incrementalInserter.skipCommandText(fullText)
                executeVoiceAction(cmdResult.action)
                return
            }
            is VoiceCommandResult.TrailingCommand -> {
                Timber.i(
                    "[VOICE] streaming.onLineCompleted | trailingCommand=%s prefix='%s' lineText='%s'",
                    cmdResult.action, cmdResult.prefix, lineText,
                )
                sessionCommandsDetected.incrementAndGet()
                sessionConfidenceSum.addAndGet((cmdResult.confidence * 1000).toLong())
                sessionConfidenceCount.incrementAndGet()
                // Insert the dictation prefix that preceded the command.
                if (accessibilityStateHolder.isActive() && cmdResult.prefix.isNotBlank()) {
                    // Rebuild the "full transcript so far" with the last line
                    // replaced by just its prefix portion so incrementalInsert
                    // computes the correct delta.
                    val adjustedFull = when {
                        fullText == lineText -> cmdResult.prefix
                        fullText.endsWith(" $lineText") ->
                            fullText.dropLast(lineText.length + 1) + " ${cmdResult.prefix}"
                        else -> fullText  // fallback: shouldn't occur
                    }
                    incrementalInserter.incrementalInsert(adjustedFull, correctionContext)
                }
                incrementalInserter.skipCommandText(fullText)
                executeVoiceAction(cmdResult.action)
                return
            }
            is VoiceCommandResult.Text -> { /* fall through to incremental insert */ }
        }

        if (accessibilityStateHolder.isActive()) {
            incrementalInserter.incrementalInsert(fullText, correctionContext)
            sessionLinesInserted.incrementAndGet()
        }
        val inserted = incrementalInserter.getInsertedText()
        _state.update { current ->
            if (current is TranscriptionState.Recording) {
                // If a newer interim partial already arrived, only refresh
                // insertedText and avoid back-writing stale fullText.
                if (partialUpdateEpoch.get() == completionEpoch) {
                    current.copy(partialText = fullText, insertedText = inserted)
                } else {
                    current.copy(insertedText = inserted)
                }
            } else {
                current
            }
        }
    }

    // ── Moonshine streaming recording ──

    /**
     * Start a streaming recording session using Moonshine V2.
     *
     * The Moonshine library does its own internal VAD, so we skip the Silero ONNX
     * VAD windowing in the audio recorder. Audio chunks are forwarded to the engine
     * Audio chunks from [AudioRecorder.audioChunks] are collected inside the recording scope and
     * forwarded to the engine in real time.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun startStreamingRecording(
        useVad: Boolean,
    ) {
        cleanupJob?.join()
        cleanupJob = null
        val engine = modelManager.streamingEngine

        incrementalInserter.resetForSession()
        HallucinationFilter.resetSession()

        // Register event listener for live text updates.
        engine.setEventListener(object : StreamingEventListener {
            override fun onLineStarted(lineId: Long, text: String) {
                Timber.d("[VOICE] streaming.onLineStarted | lineId=%d textLen=%d", lineId, text.length)
            }

            override fun onLineTextChanged(lineId: Long, text: String) {
                incrementalInserter.updateStreamingText(text)
                // Gate partial-transcript propagation to the UI on ENABLE_INTERIM_RESULTS.
                if (!OptimalParameters.ENABLE_INTERIM_RESULTS) return

                // Moonshine can emit brief blank partials between non-blank updates.
                // Ignoring these transient clears prevents the overlay from collapsing
                // and reappearing (visible flicker) during active speech.
                val currentRecording = _state.value as? TranscriptionState.Recording
                if (text.isBlank() && currentRecording?.partialText?.isNotBlank() == true) {
                    return
                }

                partialUpdateEpoch.incrementAndGet()
                val inserted = incrementalInserter.getInsertedText()
                _state.update { current ->
                    if (current is TranscriptionState.Recording) {
                        current.copy(partialText = text, insertedText = inserted)
                    } else {
                        current
                    }
                }
            }

            override fun onLineCompleted(lineId: Long, lineText: String, fullText: String) {
                val completionEpoch = partialUpdateEpoch.get()
                incrementalInserter.updateStreamingText(fullText)
                lastInsertJob = scope.launch(serialInsertDispatcher) {
                    handleLineCompleted(lineId, lineText, fullText, completionEpoch)
                }
            }

            override fun onError(cause: Throwable) {
                Timber.e(cause, "[ERROR] streaming.onError | message=%s", cause.message)
                _state.value = TranscriptionState.Error(
                    message = cause.message ?: context.getString(R.string.error_recording_failed),
                )
                scheduleResetToIdle()
            }
        })

        // Wire the buffer recycler so pool arrays from AudioRecorder are returned after
        // each nativeAddAudio() call instead of being GC'd (completes the pool contract).
        engine.setBufferRecycler(audioRecorder::recycleBuffer)

        // Start the Moonshine stream before audio starts flowing.
        engine.startStream()
        Timber.d("[STATE] startStreamingRecording | streaming initialized")

        // VAD is always on by design. useVad=false only when Silero failed to load, in which
        // case the feed gate below opens unconditionally (isVadEnabled escape hatch).
        audioRecorder.isVadEnabled = useVad

        // Launch amplitude + speech-probability collectors sampled at 100 ms
        // to reduce ~70 state emissions/sec down to ~10/sec (UI refreshes at 60 fps
        // so sub-100 ms updates are imperceptible).
        coroutineScope {
            launch {
                // VAD-gated audio feed: suppress silence frames beyond the post-roll window to
                // prevent the Moonshine decoder from hallucinating on noise during pauses.
                // A pre-roll buffer ensures speech-onset phonemes captured before VAD triggers
                // are never dropped. framesSinceSpeech=0 at session start guarantees the first
                // FEED_POST_ROLL_FRAMES are always fed regardless of VAD warmup state.
                val preRollBuffer = ArrayDeque<FloatArray>(FEED_PRE_ROLL_FRAMES)
                var framesSinceSpeech = 0

                audioRecorder.audioChunks.collect { chunk ->
                    // Use dynamic threshold from AdaptiveVadSensitivity with 0.8 multiplier
                    // to provide hysteresis against ambient noise fluctuations.
                    val dynamicThreshold = adaptiveVadSensitivity.speechThreshold.value * 0.8f
                    val effectiveThreshold = maxOf(dynamicThreshold, FEED_SPEECH_THRESHOLD_FALLBACK)
                    val isSpeech = !audioRecorder.isVadEnabled ||
                        audioRecorder.speechProbability.value >= effectiveThreshold

                    if (isSpeech) {
                        // Speech (re-)onset: flush any buffered pre-roll if we were fully gated.
                        if (framesSinceSpeech > FEED_POST_ROLL_FRAMES) {
                            preRollBuffer.forEach { engine.feedAudio(it) }
                            preRollBuffer.clear()
                        }
                        framesSinceSpeech = 0
                        engine.feedAudio(chunk)
                    } else {
                        framesSinceSpeech++
                        // Always keep a rolling pre-roll of the most recent chunks (copyOf because
                        // AudioRecorder reuses pool arrays once the SharedFlow emit is consumed).
                        if (preRollBuffer.size >= FEED_PRE_ROLL_FRAMES) preRollBuffer.removeFirst()
                        preRollBuffer.addLast(chunk.copyOf())

                        if (framesSinceSpeech <= FEED_POST_ROLL_FRAMES) {
                            // Post-roll: feed for ≈1 s after last speech to catch utterance-final words.
                            engine.feedAudio(chunk)
                        }
                        // Beyond post-roll: chunk stays in preRollBuffer only, not fed to engine.
                    }
                }
            }
            launch {
                audioRecorder.amplitudeDb
                    .sample(100)
                    .collect { db ->
                        _state.update { current ->
                            if (current is TranscriptionState.Recording) current.copy(amplitudeDb = db) else current
                        }
                    }
            }
            launch {
                audioRecorder.speechProbability
                    .sample(100)
                    .collect { prob ->
                        _state.update { current ->
                            if (current is TranscriptionState.Recording) current.copy(speechProbability = prob) else current
                        }
                    }
            }

            // Auto-stop after sustained silence (only when VAD is active).
            // 2.5 s (up from 2 s) — natural conversational pauses can exceed 2 s at
            // sentence boundaries; 2.5 s prevents premature cut-off without making the
            // UX feel sluggish (tested at the 95th-percentile pause length for English).
            if (useVad) {
                launch {
                    silenceAutoStopDetector.collectUntilAutoStop(
                        speechProbability = audioRecorder.speechProbability,
                        silenceTimeoutMs = SILENCE_AUTO_STOP_MS,
                        isRecordingActive = { _state.value is TranscriptionState.Recording },
                        onAutoStop = { stopRecording() },
                    )
                }
            }

            // Start recording — this suspends until cancelled.
            audioRecorder.record()
        }
    }

    /**
     * Stop the Moonshine streaming session and run post-processing on the final text.
     *
     * If incremental insertion was active, most text is already in the text field.
     * Only the last in-progress partial (not yet a completed line) needs insertion.
     *
     * Transitions: Recording → Done (or Error).
     */
    private suspend fun stopStreamingRecording() {
        val engine = modelManager.streamingEngine
        val pipelineStart = System.nanoTime()
        // stopStream() drains buffered audio, waits for the feed consumer, then calls
        // transcriber.stop() which fires the final LineCompleted synchronously.
        // Use the engine's authoritative transcript rather than the locally-tracked
        // streamingTextBuffer, which is updated via async scope.launch and may not
        // have incorporated the final line by the time we read it here.
        val engineResult = engine.stopStream()
        // Wait for any in-flight incremental inserts (queued via serialInsertDispatcher) to
        // complete before reading the buffer — the final onLineCompleted fires synchronously
        // inside stopStream() but its scope.launch body may not have executed yet.
        lastInsertJob?.join()
        lastInsertJob = null
        incrementalInserter.clearStreamingText()

        val rawText = when (engineResult) {
            is TranscriptionResult.Success -> engineResult.text
            TranscriptionResult.NoSpeech -> ""
        }

        val alreadyInserted = incrementalInserter.consumeInsertedText()

        if (rawText.isBlank()) {
            // Diagnostics only — user-facing message is localized.
            val diagMsg = when (engineResult) {
                is TranscriptionResult.Success -> "Moonshine returned empty Success"
                TranscriptionResult.NoSpeech -> "Moonshine.NoSpeech: no speech detected"
            }
            Timber.w("[TRANSCRIPTION] stopStreamingRecording | no speech detected | %s | alreadyInserted=%d", diagMsg, alreadyInserted.length)
            _state.value = TranscriptionState.Error(context.getString(R.string.error_no_speech_detected))
            scheduleResetToIdle()
            return
        }

        val audioDurationMs = System.currentTimeMillis() - recordingStartTime
        val pipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000

        val correctionContext = currentCorrectionContext()

        // When incremental insertion ran, each line was already processed through the
        // full correction pipeline and inserted into the field. Skipping postProcessFull
        // here avoids three problems:
        //   1. Done-state text would diverge from field content (TextFormatter adds
        //      sentence-case + trailing period that weren't applied incrementally).
        //   2. Vocabulary usage would be double-counted (each line already called
        //      vocabularyObserver.recordVocabUsed during incrementalInsert).
        //   3. SymSpell would re-run on text that was already partially corrected,
        //      potentially producing different output.
        // When accessibility was off (no incremental path), run the full pipeline once
        // to produce clean text for clipboard / direct-insertion fallback.
        val doneText: String
        val finalMatched: List<String>
        if (alreadyInserted.isNotEmpty()) {
            doneText = alreadyInserted
            finalMatched = emptyList()  // vocab usage already recorded per-line
        } else {
            val processResult = incrementalInserter.postProcessFull(rawText, correctionContext)
            if (processResult == null) {
                Timber.w("[TRANSCRIPTION] stopStreamingRecording | post-processing yielded blank text | rawLen=%d rawHash=%d", rawText.length, rawText.hashCode())
                _state.value = TranscriptionState.Error(context.getString(R.string.error_text_filtered_vocabulary))
                scheduleResetToIdle()
                return
            }
            if (processResult.cleanedText.isBlank() && rawText.isNotBlank()) {
                Timber.w("[TRANSCRIPTION] stopStreamingRecording | post-processing stripped all text | rawLen=%d cleaned=0 rawHash=%d", rawText.length, rawText.hashCode())
                _state.value = TranscriptionState.Error(context.getString(R.string.error_text_filtered_generic))
                scheduleResetToIdle()
                return
            }
            doneText = processResult.cleanedText
            finalMatched = processResult.matchedVocab
        }

        val result = TranscriptionResult.Success(
            text = doneText,
            audioDurationMs = audioDurationMs,
            inferenceDurationMs = pipelineMs,
        )

        Timber.i("[EXIT] stopStreamingRecording | textLen=%d alreadyInserted=%d audioDurationMs=%d pipelineMs=%d",
            doneText.length, alreadyInserted.length, audioDurationMs, pipelineMs)

        // Session performance summary
        val cmds = sessionCommandsDetected.get()
        val lines = sessionLinesInserted.get()
        val confCount = sessionConfidenceCount.get()
        val avgConf = if (confCount > 0) sessionConfidenceSum.get().toFloat() / (confCount * 1000f) else 0f
        Timber.i("[PERF] session summary | commands=%d linesInserted=%d avgCommandConf=%.3f audioDurationMs=%d",
            cmds, lines, avgConf, audioDurationMs)
        if (confCount > 0 && avgConf < OptimalParameters.VOICE_COMMAND_CONFIDENCE_THRESHOLD - 0.15f) {
            Timber.w("[PERF] session | avgConfidence=%.3f dropped >15%% below threshold=%.3f — review accent/noise conditions",
                avgConf, OptimalParameters.VOICE_COMMAND_CONFIDENCE_THRESHOLD)
        }
        performanceMonitor.logSummary()

        _state.value = TranscriptionState.Done(result)
        scheduleResetToIdle()

        // Record dictation for correction learning — CorrectionLearner will
        // compare this text with the text-field content on the next dictation
        // start to detect user corrections.
        val dictationTextForLearner = doneText.trimEnd()
        val appliedCorrPairs: List<Pair<String, String>> = finalMatched.mapNotNull { phrase ->
            val written = vocabularyObserver.confirmedVocabulary.value
                .find { it.phrase.equals(phrase, ignoreCase = true) }
                ?.writtenForm ?: return@mapNotNull null
            phrase to written
        }
        correctionLearner.recordDictation(
            dictationTextForLearner,
            appPackage = correctionContext.packageName,
            appliedCorrections = appliedCorrPairs,
        )
        idleSnapshotJob = scope.launch {
            delay(CorrectionLearner.SNAPSHOT_DELAY_MS)
            val currentText = accessibilityStateHolder.getCurrentFocusedFieldText() ?: return@launch
            correctionLearner.onTextFieldSnapshot(currentText)
        }

        // Post-transcription actions based on settings (already read at top of function).
        if (result.text.isNotBlank()) {
            var insertedDirectly = alreadyInserted.isNotEmpty()
            // If incremental insertion was active, only insert what hasn't been inserted yet.
            if (accessibilityStateHolder.isActive() && !insertedDirectly) {
                insertedDirectly = accessibilityStateHolder.insertText(result.text)
            }
            if (!insertedDirectly) {
                copyToClipboard(result.text)
            }
        }
    }

    /**
     * Cancel current operation and return to Idle.
     */
    fun cancel() {
        Timber.i("[STATE] cancel | cancelling current operation → Idle")
        idleSnapshotJob?.cancel()
        idleSnapshotJob = null
        resetJob?.cancel()
        resetJob = null
        modelManager.cancelPreload()
        audioRecorder.stop()
        sessionJob?.cancel()
        sessionJob = null
        // Null out the listener before launching stopStream() so any final
        // onLineCompleted callbacks fired during teardown are silently dropped.
        modelManager.streamingEngine.setEventListener(null)
        // Clear the buffer recycler so the engine holds no reference to AudioRecorder.
        modelManager.streamingEngine.setBufferRecycler(null)
        cleanupJob = scope.launch { modelManager.streamingEngine.stopStream() }
        incrementalInserter.resetForSession()
        _state.value = TranscriptionState.Idle
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
     * Gives the UI [RESET_TO_IDLE_DELAY_MS] (5 s) to let the user read the final text or error
     * before returning to Idle. Cancelled if the user starts a new recording before the delay
     * expires.
     */
    private fun scheduleResetToIdle() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(RESET_TO_IDLE_DELAY_MS)
            if (_state.value is TranscriptionState.Done ||
                _state.value is TranscriptionState.Error
            ) {
                Timber.d("[STATE] scheduleResetToIdle | auto-reset → Idle from %s", _state.value::class.simpleName)
                _state.value = TranscriptionState.Idle
            }
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label_transcription), text)
            // Mark as sensitive so system clipboard UI redacts the content from other apps.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clipboard.setPrimaryClip(clip)
            Timber.i("[CLIPBOARD] copyToClipboard | auto-copied %d chars", text.length)
        } catch (e: Exception) {
            Timber.e(e, "[CLIPBOARD] copyToClipboard | failed")
        }
    }

    // Lint incorrectly flags Intent(context, Class) as an ImplicitSamInstance — comment kept for context.
    suspend fun destroy() {
        Timber.i("[LIFECYCLE] TranscriptionCoordinator.destroy | releasing all resources")
        sessionJob?.cancel()
        // Do NOT cancel scope — it's the shared @ApplicationScope
        modelManager.releaseAll()
        Timber.i("[EXIT] TranscriptionCoordinator.destroy | complete")
    }
}
