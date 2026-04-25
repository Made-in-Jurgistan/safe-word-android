package com.safeword.android.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Silero VAD detector backed by ONNX Runtime.
 *
 * Mirrors the Python `OnnxWrapper` from snakers4/silero-vad.
 * Accepts 480-sample windows (30 ms at 16 kHz), returns a speech
 * probability in [0, 1].
 *
 * Thread-safety: detect() and resetStates() are guarded by a short monitor lock.
 * load() and release() must not be called concurrently with inference (upheld by
 * the app's preload lifecycle).
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

        /** Hidden state shape: [2, batch, 128] (Silero VAD v5). */
        private const val STATE_DIM_0 = 2
        private const val STATE_DIM_2 = 128

        /** Index of the hidden-state dimension in the ONNX tensor shape array [2, batch, 128]. */
        private const val STATE_SHAPE_DIM2_INDEX = 2

        /** Asset filename of the Silero VAD ONNX model. */
        private const val MODEL_ASSET = "silero_vad.onnx"
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /** Mutex for thread-safe inference. Replaces synchronized(lock) to allow suspend-friendly locking. */
    private val mutex = Mutex()

    /** Dedicated dispatcher for ONNX inference to avoid blocking the AudioRecord thread. */
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Pre-allocated sample-rate tensor — constant (16 kHz), reused across detect() calls. */
    private var srTensor: OnnxTensor? = null

    /** Hidden state carried across calls — shape [2, 1, 128]. */
    private val state = FloatArray(STATE_DIM_0 * 1 * STATE_DIM_2)

    /** Context from the tail of the previous window — shape [CONTEXT_SIZE]. */
    private val contextBuffer = FloatArray(CONTEXT_SIZE)

    /** Reusable input buffer — avoids allocation per detect() call. */
    private val inputData = FloatArray(INPUT_SIZE)

    /**
     * Pre-allocated FloatBuffer views for ONNX tensor creation — eliminates per-call wrapper allocation.
     *
     * Note: Two [OnnxTensor] objects are still created and closed per [detect] call (~66/sec at
     * 30 ms windows). The ONNX Runtime Java API ([OnnxTensor.createTensor]) allocates a native
     * wrapper each time and provides no reuse/reset mechanism. This is a known, unavoidable cost;
     * the pre-allocated FloatBuffer views minimise the JVM-side allocation overhead.
     */
    private val inputFloatBuffer: FloatBuffer = FloatBuffer.wrap(inputData)
    private val stateFloatBuffer: FloatBuffer = FloatBuffer.wrap(state)

    /** Pre-allocated shape arrays — avoids per-call longArrayOf allocation. */
    private val inputShape = longArrayOf(1, INPUT_SIZE.toLong())
    private val stateShape = longArrayOf(STATE_DIM_0.toLong(), 1, STATE_DIM_2.toLong())

    /**
     * Pre-allocated inputs map — reused across [detect] calls to avoid a new
     * [LinkedHashMap] allocation on every VAD window (~33/sec).  Values are
     * updated in-place inside [detect]; the map is only ever accessed under
     * [lock], so there is no concurrent-modification risk.
     */
    private val inputsMap = LinkedHashMap<String, OnnxTensor>(4)

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
            // ONNX Runtime needs a file path to memory-map the model for zero-copy. Android assets
            // are packaged inside the APK (potentially compressed), so extract once to cache on
            // first use, then load by absolute path for subsequent sessions.
            // Uses atomic temp-file + rename to prevent partial writes from concurrent loads.
            val cacheFile = java.io.File(context.cacheDir, MODEL_ASSET.replace("/", "_"))
            if (!cacheFile.exists()) {
                Timber.d("[PERF] SileroVadDetector.load | extracting asset to cache atomically")
                val tempFile = java.io.File(context.cacheDir, "${MODEL_ASSET.replace("/", "_")}.tmp")
                context.assets.open(MODEL_ASSET).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Atomic rename ensures other threads never see a partially-written file
                if (!tempFile.renameTo(cacheFile)) {
                    tempFile.delete()
                    throw java.io.IOException("Failed to rename temp file to cache file")
                }
                Timber.d(
                    "[PERF] SileroVadDetector.load | extracted model to cache size=%d KB",
                    cacheFile.length() / 1024,
                )
            }
            val session = env.createSession(cacheFile.absolutePath, opts)
            ortSession = session
            // Verify state output shape matches compile-time constants — catches model version mismatches.
            val stateShape = session.outputInfo["hn"]?.info
            val stateDim2 = (stateShape as? ai.onnxruntime.TensorInfo)?.shape?.getOrNull(STATE_SHAPE_DIM2_INDEX)?.toInt()
            check(stateDim2 == null || stateDim2 == STATE_DIM_2) {
                "Silero VAD state dim mismatch: expected STATE_DIM_2=$STATE_DIM_2 got $stateDim2 — update SileroVadDetector constants"
            }
            srTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
                longArrayOf(1),
            )
            // Direct state reset — load() is called before inference starts so there is
            // no concurrent access; calling the suspend resetStates() is unnecessary here.
            state.fill(0f)
            contextBuffer.fill(0f)

            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            Timber.i("[PERF] SileroVadDetector.load | success loadMs=%d sampleRate=%d", loadMs, SAMPLE_RATE)
            true
        } catch (e: Exception) {
            ortSession?.close()
            ortSession = null
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            Timber.e(e, "[ERROR] SileroVadDetector.load | failed loadMs=%d", loadMs)
            false
        }
    }

    /**
     * Reset hidden state and context — call between independent audio segments.
     * Suspend function to allow mutex-based locking without blocking threads.
     */
    suspend fun resetStates() {
        mutex.withLock {
            Timber.d("[STATE] SileroVadDetector.resetStates | clearing hidden state dims=[%d,1,%d] and context size=%d",
                STATE_DIM_0, STATE_DIM_2, CONTEXT_SIZE)
            state.fill(0f)
            contextBuffer.fill(0f)
        }
    }

    /**
     * Run VAD on a single 480-sample window.
     *
     * @param window exactly [WINDOW_SIZE] PCM samples ∈ [-1, 1].
     * @return speech probability in [0, 1], or -1 if the model is not loaded.
     *
     * Note: This is a suspend function that runs inference on a dedicated dispatcher
     * to avoid blocking the AudioRecord capture thread. Call using withContext or from
     * within a suspend function.
     */
    suspend fun detect(window: FloatArray): Float {
        require(window.size == WINDOW_SIZE) {
            "Expected $WINDOW_SIZE samples, got ${window.size}"
        }

        return withContext(inferenceDispatcher) {
        mutex.withLock {
        val session = ortSession ?: run {
            Timber.w("[VAD] detect | session=null model not loaded")
            return@withLock -1f
        }
        val env = ortEnv ?: run {
            Timber.w("[VAD] detect | env=null")
            return@withLock -1f
        }

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
        val sr = srTensor ?: return@synchronized -1f

        val prob: Float
        try {
            inputsMap["input"] = inputTensor
            inputsMap["state"] = stateTensor
            inputsMap["sr"] = sr
            val results = session.run(inputsMap)
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

        return@withLock prob
        }
        }
    }

    /** Release the ONNX session and environment. Not concurrent with detect() by design. */
    fun release() {
        Timber.i("[ENTER] SileroVadDetector.release | isLoaded=%b", isLoaded)
        // release() is only called after AudioRecorder.stop() completes, ensuring detect()
        // has exited before this runs — no lock needed.
        try {
            srTensor?.close()
            srTensor = null
            ortSession?.close()
            ortSession = null
            state.fill(0f)
            contextBuffer.fill(0f)
            Timber.i("[EXIT] SileroVadDetector.release | session released successfully")
            // OrtEnvironment is a singleton — do not close it
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] SileroVadDetector.release | error releasing session")
        }
    }
}
