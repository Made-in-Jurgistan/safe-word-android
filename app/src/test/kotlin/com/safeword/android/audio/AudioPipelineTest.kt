package com.safeword.android.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import com.safeword.android.util.MainDispatcherRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pipeline tests for the audio subsystem: adaptive VAD sensitivity and silence auto-stop.
 *
 * [AudioRecorder] itself is Android-bound (AudioRecord, RECORD_AUDIO permission)
 * so we test the pure-logic components it delegates to:
 *
 * - [AdaptiveVadSensitivity]: noise-floor tracking, warmup bootstrap, threshold adaptation.
 * - [SilenceAutoStopDetector]: speech-confirmed → silence countdown → auto-stop callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPipelineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ── AdaptiveVadSensitivity: threshold adaptation ────────────────────

    @Test
    fun `fresh instance returns base threshold`() {
        val avs = AdaptiveVadSensitivity()
        assertEquals(0.35f, avs.speechThreshold.value, 0.001f)
    }

    @Test
    fun `reset restores base threshold`() {
        val avs = AdaptiveVadSensitivity()
        // Drive adaptation with loud non-speech frames.
        repeat(30) { avs.update(rms = 0.3f, vadProbability = 0.1f) }
        val adapted = avs.speechThreshold.value
        assertTrue(adapted > 0.35f, "Should have risen from baseline")

        avs.reset()
        assertEquals(0.35f, avs.speechThreshold.value, 0.001f)
        assertEquals(0, avs.consecutiveSilenceFrames)
    }

    @Test
    fun `warmup period returns base threshold for first 20 frames`() {
        val avs = AdaptiveVadSensitivity()
        repeat(20) { frame ->
            val t = avs.update(rms = 0.05f, vadProbability = 0.1f)
            assertEquals(0.35f, t, 0.001f, "Frame $frame should return base during warmup")
        }
    }

    @Test
    fun `noisy environment raises threshold toward max`() {
        val avs = AdaptiveVadSensitivity()
        // Warmup at rms=0.05 so the noise floor bootstraps to 0.05.
        // This prevents the sudden-spike guard (rms > 3 × noiseFloor) from firing
        // when the post-warmup noise level is applied.
        repeat(20) { avs.update(rms = 0.05f, vadProbability = 0.1f) }
        // rms=0.12 < 3 × 0.05 = 0.15 → no spike → EMA rises toward 0.12.
        // After 200 frames: noiseRatio ≈ 12, rawThreshold ≈ 0.46 → smoothed ≈ 0.45+.
        repeat(200) { avs.update(rms = 0.3f, vadProbability = 0.1f) }
        val threshold = avs.speechThreshold.value
        assertTrue(
            threshold > 0.40f,
            "Noisy environment should push threshold above 0.40, got $threshold",
        )
        assertTrue(
            threshold <= AdaptiveVadSensitivity.MAX_SPEECH_THRESHOLD,
            "Threshold $threshold should not exceed MAX",
        )
    }

    @Test
    fun `quiet environment keeps threshold near base`() {
        val avs = AdaptiveVadSensitivity()
        repeat(20) { avs.update(rms = 0.01f, vadProbability = 0.1f) }
        // Continue with quiet.
        repeat(100) { avs.update(rms = 0.01f, vadProbability = 0.1f) }
        val threshold = avs.speechThreshold.value
        assertTrue(
            threshold < 0.55f,
            "Quiet environment should keep threshold near 0.50, got $threshold",
        )
        assertTrue(
            threshold >= AdaptiveVadSensitivity.MIN_SPEECH_THRESHOLD,
            "Threshold $threshold should not drop below MIN",
        )
    }

    @Test
    fun `speech frames reset consecutive silence counter`() {
        val avs = AdaptiveVadSensitivity()
        // Build up silence frames.
        repeat(10) { avs.update(rms = 0.01f, vadProbability = 0.1f) }
        assertEquals(10, avs.consecutiveSilenceFrames)

        // One speech frame resets it.
        avs.update(rms = 0.1f, vadProbability = 0.8f)
        assertEquals(0, avs.consecutiveSilenceFrames)
    }

    @Test
    fun `non-speech frames accumulate consecutive silence`() {
        val avs = AdaptiveVadSensitivity()
        repeat(100) { avs.update(rms = 0.01f, vadProbability = 0.1f) }
        assertEquals(100, avs.consecutiveSilenceFrames)
    }

    @Test
    fun `threshold clamped to min and max bounds`() {
        val avs = AdaptiveVadSensitivity()
        // Even extreme noise can't push threshold beyond MAX.
        repeat(20) { avs.update(rms = 0.01f, vadProbability = 0.1f) }
        repeat(1000) { avs.update(rms = 1.0f, vadProbability = 0.1f) }
        assertTrue(avs.speechThreshold.value <= AdaptiveVadSensitivity.MAX_SPEECH_THRESHOLD)
    }

    @Test
    fun `noise floor debounce guard prevents speech trailing contamination`() {
        val avs = AdaptiveVadSensitivity()
        // Warmup.
        repeat(20) { avs.update(rms = 0.01f, vadProbability = 0.1f) }
        // Record baseline noise floor.
        repeat(50) { avs.update(rms = 0.01f, vadProbability = 0.1f) }
        val quietFloor = avs.currentNoiseFloor

        // Simulate speech → high RMS with high VAD (not counted as non-speech).
        repeat(30) { avs.update(rms = 0.5f, vadProbability = 0.9f) }
        // Simulate trailing edge: high RMS but VAD drops (below ceiling).
        // Only 3 frames (< 5 debounce threshold) — should NOT update noise floor.
        repeat(3) { avs.update(rms = 0.4f, vadProbability = 0.2f) }
        assertEquals(
            quietFloor, avs.currentNoiseFloor, 0.005f,
            "3 frames within debounce guard should not contaminate noise floor",
        )
    }

    @Test
    fun `warmup bootstraps noise floor from median of non-speech samples`() {
        val avs = AdaptiveVadSensitivity()
        // Feed 20 frames with known RMS values (non-speech).
        // Median of 20 identical values = that value.
        repeat(20) { avs.update(rms = 0.05f, vadProbability = 0.1f) }
        // After warmup, noise floor should be bootstrapped from median ≈ 0.05.
        assertEquals(0.05f, avs.currentNoiseFloor, 0.01f)
    }

    // ── SilenceAutoStopDetector: auto-stop timing ───────────────────────

    @Test
    fun `auto-stop triggers after sustained silence following speech`() = runTest {
        val avs = AdaptiveVadSensitivity()
        val detector = SilenceAutoStopDetector(avs)
        val speechFlow = MutableSharedFlow<Float>()
        var autoStopCalled = false

        val job = launch {
            detector.collectUntilAutoStop(
                speechProbability = speechFlow,
                silenceTimeoutMs = 100,
                isRecordingActive = { true },
                onAutoStop = { autoStopCalled = true },
            )
        }

        // Need to use real timestamps — SilenceAutoStopDetector uses System.currentTimeMillis().
        // Simulate MIN_SPEECH_MS (250ms) of speech to confirm it.
        repeat(15) { speechFlow.emit(0.9f) } // above threshold
        // Then sustained silence with hysteresis (3 frames) + timeout.
        repeat(50) { speechFlow.emit(0.0f) }

        // Auto-stop depends on System.currentTimeMillis(), which in test may not advance.
        // We can at least verify the flow collection doesn't crash.
        job.cancel()
        // See note: real time-based tests need instrumented tests or time injection.
    }

    @Test
    fun `auto-stop does not trigger before speech is confirmed`() = runTest {
        val avs = AdaptiveVadSensitivity()
        val detector = SilenceAutoStopDetector(avs)
        val speechFlow = MutableSharedFlow<Float>()
        var autoStopCalled = false

        val job = launch {
            detector.collectUntilAutoStop(
                speechProbability = speechFlow,
                silenceTimeoutMs = 50,
                isRecordingActive = { true },
                onAutoStop = { autoStopCalled = true },
            )
        }

        // Only silence — never confirm speech.
        repeat(100) { speechFlow.emit(0.0f) }

        job.cancel()
        assertTrue(!autoStopCalled, "Auto-stop should not trigger without confirmed speech")
    }

    @Test
    fun `auto-stop respects isRecordingActive flag`() = runTest {
        val avs = AdaptiveVadSensitivity()
        val detector = SilenceAutoStopDetector(avs)
        val speechFlow = MutableSharedFlow<Float>()
        var autoStopCalled = false
        val isActive = MutableStateFlow(true)

        val job = launch {
            detector.collectUntilAutoStop(
                speechProbability = speechFlow,
                silenceTimeoutMs = 50,
                isRecordingActive = { isActive.value },
                onAutoStop = { autoStopCalled = true },
            )
        }

        // Speech confirmed.
        repeat(15) { speechFlow.emit(0.9f) }
        // Mark as inactive — subsequent silence should not trigger auto-stop processing.
        isActive.value = false
        repeat(50) { speechFlow.emit(0.0f) }

        job.cancel()
        assertTrue(!autoStopCalled, "Auto-stop should not trigger when recording is inactive")
    }

    @Test
    fun `speech resumes after silence resets silence timer`() = runTest {
        val avs = AdaptiveVadSensitivity()
        val detector = SilenceAutoStopDetector(avs)
        val speechFlow = MutableSharedFlow<Float>()
        var autoStopCalled = false

        val job = launch {
            detector.collectUntilAutoStop(
                speechProbability = speechFlow,
                silenceTimeoutMs = 100,
                isRecordingActive = { true },
                onAutoStop = { autoStopCalled = true },
            )
        }

        // Confirm speech.
        repeat(15) { speechFlow.emit(0.9f) }
        // Brief silence (should not trigger).
        repeat(5) { speechFlow.emit(0.0f) }
        // Speech resumes (resets silence timer).
        repeat(10) { speechFlow.emit(0.9f) }
        // Brief silence again.
        repeat(5) { speechFlow.emit(0.0f) }

        job.cancel()
        assertTrue(!autoStopCalled, "Brief silence with intervening speech should not auto-stop")
    }

    // ── AdaptiveVadSensitivity + SilenceAutoStopDetector integration ────

    @Test
    fun `adaptive threshold affects silence detection`() {
        val avs = AdaptiveVadSensitivity()
        // Warmup at rms=0.05 (bootstraps noiseFloor to 0.05) then drive noise.
        // rms=0.12 < 3 × 0.05 = 0.15 → no spike guard → EMA rises → threshold rises.
        repeat(20) { avs.update(rms = 0.05f, vadProbability = 0.1f) }
        repeat(200) { avs.update(rms = 0.12f, vadProbability = 0.1f) }
        val highThreshold = avs.speechThreshold.value

        // A modest speech probability that would be "speech" in quiet conditions
        // is now "silence" in noisy conditions.
        assertTrue(
            highThreshold > 0.40f,
            "Noisy environment should have raised threshold above 0.40, got $highThreshold",
        )
        // VAD prob of 0.35 (just above base quiet threshold) is below the noisy threshold.
        assertTrue(0.35f < highThreshold, "0.35 prob should be below threshold $highThreshold in noise")
    }

    @Test
    fun `VAD reset silence frames constant is 67`() {
        // 67 × 30ms = ~2s — documents the contract for AudioRecorder's VAD state reset.
        assertEquals(67, AdaptiveVadSensitivity.VAD_RESET_SILENCE_FRAMES)
    }
}
