package com.safeword.android.transcription

import android.content.Context
import android.os.PowerManager
import com.safeword.android.audio.AudioRecorder
import com.safeword.android.audio.SilenceAutoStopDetector
import com.safeword.android.data.MicAccessEventRepository
import com.safeword.android.service.AccessibilityStateHolder
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.testutil.FakeStreamingEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import io.mockk.clearMocks
import kotlin.test.assertIs

/**
 * Pipeline-level tests for [TranscriptionCoordinator]'s state machine and recording lifecycle.
 *
 * Uses [FakeStreamingEngine] for deterministic audio → transcript output
 * and MockK for Android-dependent collaborators.
 *
 * Coverage focus:
 * - State machine transitions (Idle → Recording → Done/Error → Idle)
 * - Guard conditions that prevent illegal transitions
 * - Model-load and thermal gate failures
 * - Cancel/reset/destroy paths
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeEngine: FakeStreamingEngine
    private lateinit var coordinator: TranscriptionCoordinator

    // ── Mocks ──
    private val audioRecorder = mockk<AudioRecorder>(relaxed = true) {
        every { audioChunks } returns MutableSharedFlow()
        every { amplitudeDb } returns MutableStateFlow(-60f)
        every { speechProbability } returns MutableStateFlow(0f)
    }
    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val accessibilityStateHolder = mockk<AccessibilityStateHolder>(relaxed = true) {
        every { isActive() } returns true
        every { insertText(any()) } returns true
        every { executeVoiceAction(any()) } returns true
        every { inputContextSnapshot() } returns InputContextSnapshot(
            packageName = "com.example.test",
            hintText = "",
            className = "android.widget.EditText",
            textFieldFocused = true,
            keyboardVisible = true,
        )
    }
    private val micAccessEventRepository = mockk<MicAccessEventRepository>(relaxed = true) {
        coEvery { recordStart(any(), any()) } returns 1L
    }
    private val correctionLearner = mockk<CorrectionLearner>(relaxed = true)
    private val incrementalInserter = mockk<IncrementalTextInserter>(relaxed = true) {
        every { getInsertedText() } returns ""
        every { consumeInsertedText() } returns ""
    }
    private val vocabularyObserver = mockk<VocabularyObserver>(relaxed = true) {
        every { confirmedVocabulary } returns MutableStateFlow(emptyList())
    }
    private val silenceAutoStopDetector = mockk<SilenceAutoStopDetector>(relaxed = true)
    private val voiceCommandDetector = mockk<VoiceCommandDetector>(relaxed = true) {
        every { detectIncludingTrailing(any()) } returns VoiceCommandResult.Text("")
    }
    private val powerManager = mockk<PowerManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.POWER_SERVICE) } returns powerManager
    }

    @Before
    fun setUp() {
        fakeEngine = FakeStreamingEngine()

        every { modelManager.streamingEngine } returns fakeEngine
        coEvery { modelManager.ensureVadLoaded() } returns true
        coEvery { modelManager.ensureEngineLoaded() } coAnswers {
            fakeEngine.loadModel("/fake/model", 0)
            true
        }
        every { modelManager.isTooHotForTranscription() } returns false

        coordinator = TranscriptionCoordinator(
            audioRecorder = audioRecorder,
            modelManager = modelManager,
            accessibilityStateHolder = accessibilityStateHolder,
            micAccessEventRepository = micAccessEventRepository,
            correctionLearner = correctionLearner,
            incrementalInserter = incrementalInserter,
            vocabularyObserver = vocabularyObserver,
            silenceAutoStopDetector = silenceAutoStopDetector,
            voiceCommandDetector = voiceCommandDetector,
            context = context,
            scope = testScope,
            performanceMonitor = PerformanceMonitor(),
            ioDispatcher = testDispatcher,
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  1. State machine — basic transitions
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `initial state is Idle`() {
        assertIs<TranscriptionState.Idle>(coordinator.state.value)
    }

    @Test
    fun `startRecording transitions from Idle to Recording`() = testScope.runTest {
        coordinator.startRecording()
        runCurrent()
        assertIs<TranscriptionState.Recording>(coordinator.state.value)
        coordinator.cancel()
    }

    @Test
    fun `cancel returns to Idle from Recording`() = testScope.runTest {
        coordinator.startRecording()
        runCurrent()
        assertIs<TranscriptionState.Recording>(coordinator.state.value)
        coordinator.cancel()
        assertIs<TranscriptionState.Idle>(coordinator.state.value)
    }

    @Test
    fun `reset returns to Idle from Error`() = testScope.runTest {
        coEvery { modelManager.ensureEngineLoaded() } returns false
        every { context.getString(any()) } returns "Error"

        coordinator.startRecording()
        runCurrent()
        assertIs<TranscriptionState.Error>(coordinator.state.value)

        coordinator.reset()
        assertIs<TranscriptionState.Idle>(coordinator.state.value)
    }

    // ════════════════════════════════════════════════════════════════════
    //  2. Guard conditions — illegal transitions blocked
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `startRecording blocked when already Recording`() = testScope.runTest {
        coordinator.startRecording()
        runCurrent()
        assertIs<TranscriptionState.Recording>(coordinator.state.value)

        // Second call should be a no-op — state stays Recording
        coordinator.startRecording()
        assertIs<TranscriptionState.Recording>(coordinator.state.value)
        coordinator.cancel()
    }

    @Test
    fun `stopRecording blocked when not Recording`() = testScope.runTest {
        assertIs<TranscriptionState.Idle>(coordinator.state.value)
        coordinator.stopRecording()
        advanceUntilIdle()
        // Should remain Idle — no crash
        assertIs<TranscriptionState.Idle>(coordinator.state.value)
    }

    // ════════════════════════════════════════════════════════════════════
    //  3. Model-load and thermal gate failures
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `startRecording transitions to Error when engine fails to load`() = testScope.runTest {
        coEvery { modelManager.ensureEngineLoaded() } returns false
        every { context.getString(any()) } returns "No model downloaded"

        coordinator.startRecording()
        runCurrent()

        assertIs<TranscriptionState.Error>(coordinator.state.value)
        coordinator.cancel()
    }

    @Test
    fun `startRecording transitions to Error when device is too hot`() = testScope.runTest {
        every { modelManager.isTooHotForTranscription() } returns true
        every { context.getString(any()) } returns "Device too hot"

        coordinator.startRecording()
        runCurrent()

        assertIs<TranscriptionState.Error>(coordinator.state.value)
        coordinator.cancel()
    }

    // ════════════════════════════════════════════════════════════════════
    //  4. Cancel resets engine and state
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `cancel stops audio and resets inserter`() {
        coordinator.startRecording()
        coordinator.cancel()

        verify { audioRecorder.stop() }
        verify { incrementalInserter.resetForSession() }
        assertIs<TranscriptionState.Idle>(coordinator.state.value)
    }

    @Test
    fun `cancel nulls event listener to prevent stale callbacks`() {
        coordinator.startRecording()
        coordinator.cancel()

        // After cancel, the engine listener should have been cleared.
        // A late callback should not crash or change state.
        fakeEngine.simulateLineCompleted(0, "late text")
        assertIs<TranscriptionState.Idle>(coordinator.state.value)
    }

    // ════════════════════════════════════════════════════════════════════
    //  5. Preload delegation
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `preloadModels delegates to ModelManager`() {
        coordinator.preloadModels()
        verify { modelManager.preloadModels() }
    }

    @Test
    fun `cancelPreload delegates to ModelManager`() {
        coordinator.cancelPreload()
        verify { modelManager.cancelPreload() }
    }

    @Test
    fun `preloadVocabulary delegates to VocabularyObserver`() {
        coordinator.preloadVocabulary()
        verify { vocabularyObserver.preloadVocabulary() }
    }

    // ════════════════════════════════════════════════════════════════════
    //  6. Mic access privacy audit
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `startRecording records mic access event`() = testScope.runTest {
        coordinator.startRecording()
        runCurrent()
        coVerify { micAccessEventRepository.recordStart(any(), any()) }
        coordinator.cancel()
    }

    // ════════════════════════════════════════════════════════════════════
    //  7. Destroy
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `destroy releases model resources`() = testScope.runTest {
        coordinator.destroy()
        coVerify { modelManager.releaseAll() }
    }

    // ════════════════════════════════════════════════════════════════════
    //  8. Voice command integration wiring
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `command line executes voice action and skips incremental insertion`() = testScope.runTest {
        clearMocks(incrementalInserter, accessibilityStateHolder, answers = false)
        every { voiceCommandDetector.detectIncludingTrailing("delete last word") } returns
            VoiceCommandResult.Command(VoiceAction.DeleteLastWord, confidence = 0.95f)
        every { accessibilityStateHolder.executeVoiceAction(VoiceAction.DeleteLastWord) } returns true

        coordinator.startRecording()
        runCurrent()

        val lineId = fakeEngine.simulateLineStarted()
        fakeEngine.simulateLineCompleted(lineId, "delete last word", "delete last word")
        runCurrent()

        verify { incrementalInserter.skipCommandText("delete last word") }
        verify { accessibilityStateHolder.executeVoiceAction(VoiceAction.DeleteLastWord) }
        coVerify(exactly = 0) { incrementalInserter.incrementalInsert(any(), any()) }

        coordinator.cancel()
    }

    @Test
    fun `trailing command inserts prefix then executes action`() = testScope.runTest {
        clearMocks(incrementalInserter, accessibilityStateHolder, answers = false)
        every { accessibilityStateHolder.isActive() } returns true
        every { voiceCommandDetector.detectIncludingTrailing("hello there delete last word") } returns
            VoiceCommandResult.TrailingCommand(
                prefix = "hello there",
                action = VoiceAction.DeleteLastWord,
                confidence = 0.90f,
            )
        every { accessibilityStateHolder.executeVoiceAction(VoiceAction.DeleteLastWord) } returns true

        coordinator.startRecording()
        runCurrent()

        val lineId = fakeEngine.simulateLineStarted()
        fakeEngine.simulateLineCompleted(
            lineId,
            "hello there delete last word",
            "hello there delete last word",
        )
        runCurrent()

        coVerify { incrementalInserter.incrementalInsert("hello there", any()) }
        verify { incrementalInserter.skipCommandText("hello there delete last word") }
        verify { accessibilityStateHolder.executeVoiceAction(VoiceAction.DeleteLastWord) }

        coordinator.cancel()
    }

    @Test
    fun `non-command insertion uses current accessibility active state per line`() = testScope.runTest {
        clearMocks(incrementalInserter, accessibilityStateHolder, answers = false)
        every { voiceCommandDetector.detectIncludingTrailing(any()) } returns VoiceCommandResult.Text("")
        every { accessibilityStateHolder.isActive() } returnsMany listOf(false, true)

        coordinator.startRecording()
        runCurrent()

        val lineId1 = fakeEngine.simulateLineStarted()
        fakeEngine.simulateLineCompleted(lineId1, "hello", "hello")
        runCurrent()

        val lineId2 = fakeEngine.simulateLineStarted()
        fakeEngine.simulateLineCompleted(lineId2, "hello world", "hello world")
        runCurrent()

        coVerify(exactly = 1) { incrementalInserter.incrementalInsert(any(), any()) }

        coordinator.cancel()
    }
}
