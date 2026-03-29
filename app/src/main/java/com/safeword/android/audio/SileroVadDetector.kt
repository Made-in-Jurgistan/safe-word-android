package com.safeword.android.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Silero VAD detector backed by ONNX Runtime.
 *
 * Mirrors the Python `OnnxWrapper` from snakers4/silero-vad.
 * Accepts 480-sample windows (30 ms at 16 kHz), returns a speech
 * probability in [0, 1].
 *
 * Thread-safety: all inference and state-mutation methods are guarded
 * by a [ReentrantLock] — safe to call from any thread.
 */
@Singleton
class SileroVadDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        /** Audio sample rate expected by the model. */
        const val SAMPLE_RATE = 16_000

        /** Number of raw audio samples per window. */
        const val WINDOW_SIZE = 480

        /** Context prepended to each window (maintained internally). */
        private const val CONTEXT_SIZE = 64

        /** Size of the input tensor = context + window. */
        private const val INPUT_SIZE = CONTEXT_SIZE + WINDOW_SIZE // 544

        /** Hidden state shape: [2, batch, 128]. */
        private const val STATE_DIM_0 = 2
        private const val STATE_DIM_2 = 128

        /** Asset filename of the Silero VAD ONNX model. */
        private const val MODEL_ASSET = "silero_vad.onnx"

        /** Default speech probability threshold for segment detection. */
        const val DEFAULT_SPEECH_THRESHOLD = 0.5f
        /** Offset subtracted from threshold to compute negative (silence) threshold. */
        private const val NEG_THRESHOLD_OFFSET = 0.15f
        /** Floor for the computed negative threshold. */
        private const val MIN_NEG_THRESHOLD = 0.01f
        /** Default minimum speech segment duration in milliseconds. */
        const val DEFAULT_MIN_SPEECH_MS = 250
        /** Default minimum silence gap to split segments in milliseconds. */
        const val DEFAULT_MIN_SILENCE_MS = 100
        /** Default padding added to each side of a speech segment in milliseconds. */
        const val DEFAULT_SPEECH_PAD_MS = 30
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /** Guards all mutable inference state (state, contextBuffer, inputData). */
    private val lock = ReentrantLock()

    /** Pre-allocated sample-rate tensor — constant (16 kHz), reused across detect() calls. */
    private var srTensor: OnnxTensor? = null

    /** Hidden state carried across calls — shape [2, 1, 128]. */
    private val state = FloatArray(STATE_DIM_0 * 1 * STATE_DIM_2)

    /** Context from the tail of the previous window — shape [CONTEXT_SIZE]. */
    private val contextBuffer = FloatArray(CONTEXT_SIZE)

    /** Reusable input buffer — avoids allocation per detect() call. */
    private val inputData = FloatArray(INPUT_SIZE)

    /** Pre-allocated FloatBuffer views for ONNX tensor creation — eliminates per-call wrapper allocation. */
    private val inputFloatBuffer: FloatBuffer = FloatBuffer.wrap(inputData)
    private val stateFloatBuffer: FloatBuffer = FloatBuffer.wrap(state)

    /** Pre-allocated shape arrays — avoids per-call longArrayOf allocation. */
    private val inputShape = longArrayOf(1, INPUT_SIZE.toLong())
    private val stateShape = longArrayOf(STATE_DIM_0.toLong(), 1, STATE_DIM_2.toLong())

    /** Whether the model has been loaded successfully. */
    val isLoaded: Boolean get() = ortSession != null

    /**
     * Load the ONNX model from assets.  Idempotent — subsequent calls
     * are no-ops if already loaded.
     *
     * @return `true` on success.
     */
    fun load(): Boolean {
        if (ortSession != null) {
            Timber.d("[VAD] load | already loaded, skipping")
            return true
        }

        Timber.i("[ENTER] SileroVadDetector.load | loading Silero VAD model from assets file=%s", MODEL_ASSET)
        val loadStart = System.nanoTime()
        return try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setOptimizationLevel(OptLevel.ALL_OPT)
            }
            Timber.d("[DIAGNOSTICS] SileroVadDetector.load | intraOpThreads=1 interOpThreads=1 optLevel=ALL_OPT windowSize=%d contextSize=%d inputSize=%d", WINDOW_SIZE, CONTEXT_SIZE, INPUT_SIZE)

            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            ortSession = env.createSession(modelBytes, opts)
            srTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
                longArrayOf(1),
            )
            resetStates()

            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            Timber.i("[PERF] SileroVadDetector.load | success loadMs=%d modelSizeKB=%d sampleRate=%d", loadMs, modelBytes.size / 1024, SAMPLE_RATE)
            true
        } catch (e: Exception) {
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            Timber.e(e, "[ERROR] SileroVadDetector.load | failed loadMs=%d", loadMs)
            false
        }
    }

    /** Reset hidden state and context — call between independent audio segments. */
    fun resetStates() {
        lock.lock()
        try {
            Timber.d("[STATE] SileroVadDetector.resetStates | clearing hidden state dims=[%d,1,%d] and context size=%d",
                STATE_DIM_0, STATE_DIM_2, CONTEXT_SIZE)
            state.fill(0f)
            contextBuffer.fill(0f)
        } finally {
            lock.unlock()
        }
    }

    /**
    * Run VAD on a single 480-sample window.
     *
     * @param window exactly [WINDOW_SIZE] PCM samples ∈ [-1, 1].
     * @return speech probability in [0, 1], or -1 if the model is not loaded.
     */
    fun detect(window: FloatArray): Float {
        val session = ortSession ?: run {
            Timber.w("[VAD] detect | session=null model not loaded")
            return -1f
        }
        val env = ortEnv ?: run {
            Timber.w("[VAD] detect | env=null")
            return -1f
        }

        require(window.size == WINDOW_SIZE) {
            "Expected $WINDOW_SIZE samples, got ${window.size}"
        }

        lock.lock()
        try {

        // Build input: context ++ window  → shape [1, INPUT_SIZE]
        System.arraycopy(contextBuffer, 0, inputData, 0, CONTEXT_SIZE)
        System.arraycopy(window, 0, inputData, CONTEXT_SIZE, WINDOW_SIZE)

        // Prepare ONNX tensors using pre-allocated buffer views
        inputFloatBuffer.clear()
        val inputTensor = OnnxTensor.createTensor(
            env,
            inputFloatBuffer,
            inputShape,
        )
        stateFloatBuffer.clear()
        val stateTensor = OnnxTensor.createTensor(
            env,
            stateFloatBuffer,
            stateShape,
        )
        val sr = srTensor ?: return -1f

        val prob: Float
        try {
            val inputs = mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to sr,
            )
            val results = session.run(inputs)
            try {
                // Output 0: speech probability  shape [1, 1]
                @Suppress("UNCHECKED_CAST")
                val outArray = results[0].value as Array<FloatArray>
                prob = outArray[0][0]

                // Output 1: updated state  shape [2, 1, 128]
                @Suppress("UNCHECKED_CAST")
                val newState = results[1].value as Array<Array<FloatArray>>
                // Flatten back to our 1-D state buffer
                var idx = 0
                for (d0 in newState) {
                    for (d1 in d0) {
                        System.arraycopy(d1, 0, state, idx, d1.size)
                        idx += d1.size
                    }
                }
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
            stateTensor.close()
        }

        // Update context for next call (last CONTEXT_SIZE samples of the input)
        System.arraycopy(inputData, INPUT_SIZE - CONTEXT_SIZE, contextBuffer, 0, CONTEXT_SIZE)

        return prob
        } finally {
            lock.unlock()
        }
    }

    /**
     * Convenience: run VAD over a complete audio buffer and return
     * per-window speech probabilities.
     *
     * @param samples full PCM audio at 16 kHz.
     * @return list of (windowStartSample, probability) pairs.
     */
    fun detectAll(samples: FloatArray): List<Pair<Int, Float>> {
        val windowCount = samples.size / WINDOW_SIZE
        Timber.d("[ENTER] SileroVadDetector.detectAll | sampleCount=%d windowCount=%d durationSec=%.2f",
            samples.size, windowCount, samples.size.toFloat() / SAMPLE_RATE)
        val detectStart = System.nanoTime()
        resetStates()
        val results = mutableListOf<Pair<Int, Float>>()
        val window = FloatArray(WINDOW_SIZE)

        var offset = 0
        var speechWindowCount = 0
        while (offset + WINDOW_SIZE <= samples.size) {
            System.arraycopy(samples, offset, window, 0, WINDOW_SIZE)
            val prob = detect(window)
            results.add(offset to prob)
            if (prob >= DEFAULT_SPEECH_THRESHOLD) speechWindowCount++
            offset += WINDOW_SIZE
        }

        // Handle trailing samples by zero-padding
        if (offset < samples.size) {
            window.fill(0f)
            System.arraycopy(samples, offset, window, 0, samples.size - offset)
            val prob = detect(window)
            results.add(offset to prob)
            if (prob >= DEFAULT_SPEECH_THRESHOLD) speechWindowCount++
        }

        val detectMs = (System.nanoTime() - detectStart) / 1_000_000
        val avgProbPerWindow = if (results.isNotEmpty()) results.map { it.second }.average() else 0.0
        Timber.i("[EXIT] SileroVadDetector.detectAll | windows=%d speechWindows=%d avgProb=%.3f detectMs=%d msPerWindow=%.1f",
            results.size, speechWindowCount, avgProbPerWindow, detectMs, if (results.isNotEmpty()) detectMs.toFloat() / results.size else 0f)

        return results
    }

    /**
     * State-machine segment detector — shared by ONNX-probed and cached-probe paths.
     * Converts a sorted list of (absoluteSampleOffset, probability) pairs into
     * padded [IntRange] speech segments.
     */
    private fun segmentsFromProbs(
        probs: List<Pair<Int, Float>>,
        totalSamples: Int,
        threshold: Float,
        minSpeechDurationMs: Int,
        minSilenceDurationMs: Int,
        speechPadMs: Int,
    ): List<IntRange> {
        if (probs.isEmpty()) return emptyList()
        val negThreshold = (threshold - NEG_THRESHOLD_OFFSET).coerceAtLeast(MIN_NEG_THRESHOLD)
        val minSpeechSamples = (SAMPLE_RATE * minSpeechDurationMs) / 1000
        val minSilenceSamples = (SAMPLE_RATE * minSilenceDurationMs) / 1000
        val speechPadSamples = (SAMPLE_RATE * speechPadMs) / 1000

        data class RawSegment(val start: Int, val end: Int)

        val segments = mutableListOf<RawSegment>()
        var triggered = false
        var tempEnd = 0
        var currentStart = 0

        for ((sampleOffset, prob) in probs) {
            if (prob >= threshold && tempEnd > 0) {
                tempEnd = 0
            }
            if (prob >= threshold && !triggered) {
                triggered = true
                currentStart = sampleOffset
                continue
            }
            if (prob < negThreshold && triggered) {
                if (tempEnd == 0) {
                    tempEnd = sampleOffset
                }
                if (sampleOffset - tempEnd >= minSilenceSamples) {
                    val seg = RawSegment(currentStart, tempEnd)
                    if (seg.end - seg.start >= minSpeechSamples) {
                        segments.add(seg)
                    }
                    triggered = false
                    tempEnd = 0
                }
            }
        }
        // Close final segment
        if (triggered) {
            val seg = RawSegment(currentStart, totalSamples)
            if (seg.end - seg.start >= minSpeechSamples) {
                segments.add(seg)
            }
        }
        return segments.map { seg ->
            val start = (seg.start - speechPadSamples).coerceAtLeast(0)
            val end = (seg.end + speechPadSamples).coerceAtMost(totalSamples)
            start..end
        }
    }

    /**
     * Extract speech segments from audio using VAD probabilities.
     *
     * @param samples full PCM audio at 16 kHz.
     * @param threshold probability above which a window is speech (default 0.5).
     * @param minSpeechDurationMs minimum speech segment to keep (default 250 ms).
     * @param minSilenceDurationMs minimum silence gap to split on (default 100 ms).
     * @param speechPadMs padding added to each side of a speech segment (default 30 ms).
     * @return list of [IntRange] sample ranges that contain speech.
     */
    fun getSpeechSegments(
        samples: FloatArray,
        threshold: Float = DEFAULT_SPEECH_THRESHOLD,
        minSpeechDurationMs: Int = DEFAULT_MIN_SPEECH_MS,
        minSilenceDurationMs: Int = DEFAULT_MIN_SILENCE_MS,
        speechPadMs: Int = DEFAULT_SPEECH_PAD_MS,
    ): List<IntRange> {
        Timber.i("[ENTER] SileroVadDetector.getSpeechSegments | samples=%d durationSec=%.2f threshold=%.2f minSpeechMs=%d minSilenceMs=%d padMs=%d",
            samples.size, samples.size.toFloat() / SAMPLE_RATE, threshold, minSpeechDurationMs, minSilenceDurationMs, speechPadMs)
        val segmentStart = System.nanoTime()
        val probs = detectAll(samples)
        val segments = segmentsFromProbs(probs, samples.size, threshold, minSpeechDurationMs, minSilenceDurationMs, speechPadMs)
        val segmentMs = (System.nanoTime() - segmentStart) / 1_000_000
        val totalSpeechSamples = segments.sumOf { it.last - it.first }
        Timber.i("[EXIT] SileroVadDetector.getSpeechSegments | segments=%d speechSec=%.2f processMs=%d",
            segments.size, totalSpeechSamples.toFloat() / SAMPLE_RATE, segmentMs)
        return segments
    }

    /**
     * Extract only the speech portions of [samples] and concatenate them.
     *
     * @return speech-only audio, or the original if VAD fails or finds no speech.
     */
    fun extractSpeech(
        samples: FloatArray,
        threshold: Float = DEFAULT_SPEECH_THRESHOLD,
        minSpeechDurationMs: Int = DEFAULT_MIN_SPEECH_MS,
        minSilenceDurationMs: Int = DEFAULT_MIN_SILENCE_MS,
        speechPadMs: Int = DEFAULT_SPEECH_PAD_MS,
    ): FloatArray {
        if (!isLoaded) {
            Timber.w("[WARN] SileroVadDetector.extractSpeech | model not loaded — returning original audio sampleCount=%d", samples.size)
            return samples
        }
        Timber.i("[ENTER] SileroVadDetector.extractSpeech | sampleCount=%d durationSec=%.2f threshold=%.2f",
            samples.size, samples.size.toFloat() / SAMPLE_RATE, threshold)
        val extractStart = System.nanoTime()

        val segments = getSpeechSegments(
            samples, threshold, minSpeechDurationMs, minSilenceDurationMs, speechPadMs,
        )

        if (segments.isEmpty()) {
            val extractMs = (System.nanoTime() - extractStart) / 1_000_000
            Timber.w("[WARN] SileroVadDetector.extractSpeech | no speech segments found — returning original audio sampleCount=%d processMs=%d", samples.size, extractMs)
            return samples
        }

        val totalSpeechSamples = segments.sumOf { it.last - it.first }
        val result = FloatArray(totalSpeechSamples)
        var destOffset = 0
        for (range in segments) {
            val len = range.last - range.first
            System.arraycopy(samples, range.first, result, destOffset, len)
            destOffset += len
        }

        val originalSec = samples.size.toFloat() / SAMPLE_RATE
        val speechSec = result.size.toFloat() / SAMPLE_RATE
        val extractMs = (System.nanoTime() - extractStart) / 1_000_000
        Timber.i(
            "[PERF] SileroVadDetector.extractSpeech | %.1fs → %.1fs speech (%.0f%% reduction, %d segments) processMs=%d".format(
                originalSec,
                speechSec,
                (1 - speechSec / originalSec) * 100,
                segments.size,
                extractMs,
            ),
        )

        return result
    }

    /**
     * Extract speech from [samples] using probabilities cached during real-time recording,
     * avoiding a redundant ONNX inference pass.
     *
     * Falls back to [extractSpeech] (full ONNX) if [windowProbs] is empty.
     *
     * @param windowProbs per-window (absoluteSampleOffset, probability) pairs from [AudioRecorder].
     */
    fun extractSpeechFromWindowProbs(
        samples: FloatArray,
        windowProbs: List<Pair<Int, Float>>,
        threshold: Float = DEFAULT_SPEECH_THRESHOLD,
        minSpeechDurationMs: Int = DEFAULT_MIN_SPEECH_MS,
        minSilenceDurationMs: Int = DEFAULT_MIN_SILENCE_MS,
        speechPadMs: Int = DEFAULT_SPEECH_PAD_MS,
    ): FloatArray {
        if (!isLoaded || windowProbs.isEmpty()) {
            Timber.d("[VAD] extractSpeechFromWindowProbs | no cached probs (count=%d) — falling back to extractSpeech", windowProbs.size)
            return extractSpeech(samples, threshold, minSpeechDurationMs, minSilenceDurationMs, speechPadMs)
        }
        Timber.i("[ENTER] SileroVadDetector.extractSpeechFromWindowProbs | sampleCount=%d cachedWindows=%d threshold=%.2f",
            samples.size, windowProbs.size, threshold)
        val extractStart = System.nanoTime()

        val segments = segmentsFromProbs(windowProbs, samples.size, threshold, minSpeechDurationMs, minSilenceDurationMs, speechPadMs)
        if (segments.isEmpty()) {
            val extractMs = (System.nanoTime() - extractStart) / 1_000_000
            Timber.w("[WARN] SileroVadDetector.extractSpeechFromWindowProbs | no speech segments — returning original sampleCount=%d processMs=%d",
                samples.size, extractMs)
            return samples
        }

        val totalSpeechSamples = segments.sumOf { it.last - it.first }
        val result = FloatArray(totalSpeechSamples)
        var destOffset = 0
        for (range in segments) {
            val len = range.last - range.first
            System.arraycopy(samples, range.first, result, destOffset, len)
            destOffset += len
        }

        val originalSec = samples.size.toFloat() / SAMPLE_RATE
        val speechSec = result.size.toFloat() / SAMPLE_RATE
        val extractMs = (System.nanoTime() - extractStart) / 1_000_000
        Timber.i(
            "[PERF] SileroVadDetector.extractSpeechFromWindowProbs | %.1fs → %.1fs speech (%.0f%% reduction, %d segments) processMs=%d [no ONNX]".format(
                originalSec,
                speechSec,
                (1 - speechSec / originalSec) * 100,
                segments.size,
                extractMs,
            ),
        )
        return result
    }

    /** Release the ONNX session and environment. */
    fun release() {
        Timber.i("[ENTER] SileroVadDetector.release | isLoaded=%b", isLoaded)
        try {
            srTensor?.close()
            srTensor = null
            ortSession?.close()
            ortSession = null
            Timber.i("[EXIT] SileroVadDetector.release | session released successfully")
            // OrtEnvironment is a singleton — do not close it
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] SileroVadDetector.release | error releasing session")
        }
    }
}
