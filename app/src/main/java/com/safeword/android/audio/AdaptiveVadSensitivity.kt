package com.safeword.android.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10

/**
 * Dynamically adjusts the VAD speech detection threshold based on ambient noise.
 *
 * Replaces the manual LOW / MEDIUM / HIGH sensitivity settings with a fully
 * automatic system synthesised from industry best practices:
 *
 * - **Vapi**: 30-second rolling RMS windows, 85th-percentile baseline, exponential
 *   smoothing every 100 ms.
 * - **Notch.cx**: Combine VAD probability + RMS volume, exponential smoothing,
 *   adaptive normalisation via rolling window of recent speaking energy.
 * - **LiveKit / NVIDIA Riva**: Silero VAD threshold tunable 0.3–0.8 at runtime.
 *
 * ## Algorithm
 *
 * 1. Track ambient **noise floor** via dual-speed EMA of RMS energy during
 *    non-speech frames (VAD probability < 0.30), with a speech-trailing debounce
 *    guard to prevent contamination from end-of-speech energy.
 * 2. Derive a **dynamic speech threshold** from the noise floor:
 *    `threshold = BASE + SENSITIVITY × log₁₀(noiseFloor / REFERENCE)`.
 *    Quiet room → lower threshold (catch whispers); noisy café → higher threshold.
 * 3. Smooth the threshold output via EMA to prevent sudden jumps.
 * 4. Clamp to `[MIN_THRESHOLD, MAX_THRESHOLD]` for safety.
 *
 * Call [update] once per 30 ms audio chunk. Read [speechThreshold] for the
 * current value. Call [reset] at the start of each recording session.
 */
@Singleton
class AdaptiveVadSensitivity @Inject constructor() {

    companion object {
        /**
         * Base speech threshold in quiet conditions.
         *
         * Lowered from 0.42 → 0.35 for dictation apps where false negatives (missed speech)
         * are more harmful than false positives (background noise passed to Moonshine).
         * Silero's own "sensitive" recommended value is 0.3–0.4.
         */
        private const val BASE_SPEECH_THRESHOLD = 0.35f

        /** Absolute minimum — threshold never drops below this. */
        const val MIN_SPEECH_THRESHOLD = 0.25f

        /**
         * Absolute maximum — threshold never rises above this.
         *
         * Capped at 0.50 (Silero's own recommended default) to prevent the adaptive system
         * from inflating into the zone where clear speech is detected.  The previous value
         * of 0.78 could push the threshold above soft-speech probabilities (~0.4–0.6) in
         * moderately noisy environments, causing all speech to be classified as silence.
         */
        const val MAX_SPEECH_THRESHOLD = 0.50f

        /**
         * EMA alpha for RISING noise floor (fast attack).
         *
         * When ambient noise increases (AC turns on, café gets louder), the noise
         * floor must track upward quickly so the threshold adapts before false
         * auto-stops occur.  ~17 frames / ~500 ms to reach 63 % of a step change.
         */
        private const val NOISE_FLOOR_ALPHA_RISE = 0.06f

        /**
         * EMA alpha for FALLING noise floor (slow release).
         *
         * When noise decreases, the floor should drop slowly to avoid premature
         * threshold reduction that would cause speech-onset frames to be classified
         * as silence.  ~125 frames / ~3.75 s to reach 63 % of a step change.
         */
        private const val NOISE_FLOOR_ALPHA_FALL = 0.008f

        /**
         * EMA alpha for smoothing the output threshold.
         *
         * Prevents sudden jumps in the speech threshold that can cause the
         * auto-stop to fire or release erratically on threshold transitions.
         */
        private const val THRESHOLD_SMOOTH_ALPHA = 0.10f

        /** Initial noise floor assumption (quiet room ≈ 0.01 RMS). */
        private const val INITIAL_NOISE_FLOOR = 0.01f

        /** Reference RMS for "quiet" — threshold adjustments are relative to this. */
        private const val REFERENCE_NOISE = 0.01f

        /**
         * How much the threshold rises per 10× increase in ambient noise (log₁₀ scale).
         *
         * Reduced from 0.15 → 0.10 so the threshold rises more conservatively with noise.
         * MAX_SPEECH_THRESHOLD = 0.50 acts as the hard ceiling regardless.
         *
         * Example trajectory (with new BASE=0.35, SENSITIVITY=0.10):
         * | Ambient RMS | log₁₀ ratio | Raw threshold | Effective (capped at 0.50) |
         * |-------------|-------------|---------------|---------------------------|
         * | 0.01        | 0.0         | 0.35          | 0.35                      |
         * | 0.03        | 0.48        | 0.40          | 0.40                      |
         * | 0.10        | 1.0         | 0.45          | 0.45                      |
         * | 0.30        | 1.48        | 0.50          | 0.50                      |
         */
        private const val NOISE_SENSITIVITY = 0.10f

        /**
         * Maximum ratio of instantaneous RMS to current noise floor before the upward
         * noise-floor update is skipped for this frame.
         *
         * Inspired by the GSM AMR VAD spec (ETSI EN 301 708): "If the level of background
         * noise increases suddenly, background noise is not updated upwards."  A ratio > 3×
         * indicates either a transient loud sound (door slam, cough) or the start of a speech
         * burst — neither should inflate the noise floor estimate.
         */
        private const val SUDDEN_NOISE_SPIKE_RATIO = 3.0f

        /** RMS below which the signal is considered dead silence (DC offset only). */
        private const val DEAD_SILENCE_RMS = 0.001f

        /**
         * VAD probability below which a frame is "clearly non-speech" for noise estimation.
         *
         * In moderately noisy environments (office AC, street traffic) Silero VAD
         * returns ~0.26–0.29 for background noise.  0.30 still safely excludes speech
         * onset phonemes, which typically produce VAD probability > 0.40.
         */
        private const val NON_SPEECH_PROB_CEILING = 0.30f

        /**
         * Consecutive non-speech frames required before noise floor estimation begins.
         *
         * Guards against speech trailing-edge contamination: when speech ends, RMS
         * remains elevated for ~100–200 ms while VAD probability drops.  Without
         * this guard, those high-RMS frames corrupt the noise floor upward.
         * 5 frames × 30 ms = 150 ms debounce.
         */
        private const val NON_SPEECH_DEBOUNCE_FRAMES = 5

        /** Warmup duration in milliseconds before adaptation starts (noise floor bootstrapping).
         * 600ms = 20 frames × 30ms per chunk — time-based for consistency across different chunk sizes.
         */
        private const val WARMUP_DURATION_MS = 600L

        /** Minimum threshold delta to log a state change (avoids log spam). */
        private const val LOG_DELTA = 0.05f

        /**
         * Consecutive non-speech frames after which the Silero VAD hidden state
         * should be reset.  Prevents hidden-state drift during long pauses.
         * 67 frames × 30 ms ≈ 2 s.
         */
        const val VAD_RESET_SILENCE_FRAMES = 67
    }

    // No lock needed: update() is called exclusively from the AudioRecord thread;
    // reset() is called before the recording loop starts (sequenced, not concurrent).
    // Cross-thread reads use the existing @Volatile properties and StateFlow.

    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var frameCount = 0
    private var warmupStartTimeMs: Long = 0L
    private var lastLoggedThreshold = BASE_SPEECH_THRESHOLD

    /** Consecutive non-speech frames for debounce guard. */
    private var consecutiveNonSpeechFrames = 0

    /** Smoothed threshold output — EMA of the raw log-derived threshold. */
    private var smoothedThreshold = BASE_SPEECH_THRESHOLD

    /** Noise-floor bootstrap samples collected during warmup (pre-allocated for ~600ms). */
    private val warmupSamples = FloatArray(32)  // Enough for 600ms at 30ms chunks (20) + margin
    private var warmupCount = 0

    private val _speechThreshold = MutableStateFlow(BASE_SPEECH_THRESHOLD)

    /** Current dynamic speech threshold — rises in noisy environments, drops in quiet ones. */
    val speechThreshold: StateFlow<Float> = _speechThreshold.asStateFlow()

    /** Current noise floor estimate (exposed for diagnostics / logging). */
    @Volatile
    var currentNoiseFloor: Float = INITIAL_NOISE_FLOOR
        private set

    /**
     * Consecutive non-speech frames observed — used by [AudioRecorder] to trigger
     * Silero VAD hidden-state reset during sustained silence.
     */
    @Volatile
    var consecutiveSilenceFrames: Int = 0
        private set

    /** Reset adaptive state for a new recording session. */
    @Synchronized
    fun reset() {
        noiseFloor = INITIAL_NOISE_FLOOR
        frameCount = 0
        warmupStartTimeMs = System.currentTimeMillis()
        warmupCount = 0
        consecutiveNonSpeechFrames = 0
        consecutiveSilenceFrames = 0
        smoothedThreshold = BASE_SPEECH_THRESHOLD
        currentNoiseFloor = INITIAL_NOISE_FLOOR
        lastLoggedThreshold = BASE_SPEECH_THRESHOLD
        _speechThreshold.value = BASE_SPEECH_THRESHOLD
        Timber.d("[STATE] AdaptiveVadSensitivity.reset | noiseFloor=%.4f threshold=%.2f", noiseFloor, BASE_SPEECH_THRESHOLD)
    }

    /**
     * Update the adaptive threshold with this frame's audio metrics.
     *
     * @param rms RMS energy of the raw audio chunk (linear, not dB).
     * @param vadProbability Silero VAD speech probability `[0, 1]` for this chunk.
     * @return current dynamic speech threshold.
     */
    fun update(rms: Float, vadProbability: Float): Float {
        frameCount++

        // Track consecutive silence for debounce guard and external VAD reset.
        if (vadProbability < NON_SPEECH_PROB_CEILING) {
            consecutiveNonSpeechFrames++
            consecutiveSilenceFrames++
        } else {
            consecutiveNonSpeechFrames = 0
            consecutiveSilenceFrames = 0
        }

        // Update noise floor only after the debounce guard clears and with meaningful audio.
        // The debounce prevents speech trailing-edge contamination: when speech ends, RMS
        // stays elevated for ~150 ms while VAD probability drops — those frames must NOT
        // update the noise floor or it drifts upward, raising the threshold artificially.
        if (consecutiveNonSpeechFrames >= NON_SPEECH_DEBOUNCE_FRAMES && rms > DEAD_SILENCE_RMS) {
            // Dual-speed EMA: fast attack for rising noise, slow release for falling noise.
            // Skip upward adaptation on sudden spikes (AMR VAD spec §3.2): a transient loud
            // sound (door slam, cough, speech onset) must not inflate the noise floor.
            val isSuddenSpike = rms > noiseFloor * SUDDEN_NOISE_SPIKE_RATIO
            val alpha = when {
                rms > noiseFloor && !isSuddenSpike -> NOISE_FLOOR_ALPHA_RISE
                rms <= noiseFloor -> NOISE_FLOOR_ALPHA_FALL
                else -> 0f // sudden spike — freeze floor
            }
            if (alpha > 0f) {
                noiseFloor += alpha * (rms - noiseFloor)
                currentNoiseFloor = noiseFloor
            }
        }

        // During warmup, collect non-speech samples for robust initial noise floor.
        val elapsedMs = System.currentTimeMillis() - warmupStartTimeMs
        val isInWarmup = elapsedMs < WARMUP_DURATION_MS
        if (isInWarmup) {
            if (vadProbability < NON_SPEECH_PROB_CEILING && rms > DEAD_SILENCE_RMS) {
                if (warmupCount < warmupSamples.size) {
                    warmupSamples[warmupCount++] = rms
                }
            }
            // At warmup end, bootstrap noise floor from median of collected samples.
            if (elapsedMs >= WARMUP_DURATION_MS - 30 && warmupCount > 0) {  // ~30ms before warmup ends
                warmupSamples.sort(0, warmupCount)
                val medianSample = warmupSamples[warmupCount / 2]
                noiseFloor = medianSample
                currentNoiseFloor = noiseFloor
                Timber.d("[STATE] AdaptiveVadSensitivity.warmup | bootstrapped noiseFloor=%.4f from %d samples",
                    noiseFloor, warmupCount)
                warmupCount = 0
            }
            return BASE_SPEECH_THRESHOLD
        }

        // Derive raw threshold from noise floor (log scale — linear perceptual response).
        val noiseRatio = (noiseFloor / REFERENCE_NOISE).coerceAtLeast(1f)
        val rawThreshold = (BASE_SPEECH_THRESHOLD + NOISE_SENSITIVITY * log10(noiseRatio))
            .coerceIn(MIN_SPEECH_THRESHOLD, MAX_SPEECH_THRESHOLD)

        // Smooth the threshold to prevent sudden jumps that cause auto-stop glitches.
        // Clamp the accumulator itself to prevent unbounded drift beyond [MIN, MAX].
        smoothedThreshold = (smoothedThreshold + THRESHOLD_SMOOTH_ALPHA * (rawThreshold - smoothedThreshold))
            .coerceIn(MIN_SPEECH_THRESHOLD, MAX_SPEECH_THRESHOLD)
        val threshold = smoothedThreshold

        _speechThreshold.value = threshold

        // Log significant threshold changes to avoid per-frame noise.
        val delta = threshold - lastLoggedThreshold
        if (delta > LOG_DELTA || delta < -LOG_DELTA) {
            Timber.d(
                "[STATE] AdaptiveVadSensitivity.update | threshold=%.3f raw=%.3f noiseFloor=%.4f frameCount=%d silence=%d",
                threshold, rawThreshold, noiseFloor, frameCount, consecutiveSilenceFrames,
            )
            lastLoggedThreshold = threshold
        }

        return threshold
    }
}
