package com.safeword.android.transcription

import com.safeword.android.audio.SileroVadDetector
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.di.ApplicationScope
import com.safeword.android.service.ThermalMonitor
import com.safeword.android.service.ThermalTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Manages STT model lifecycle: path resolution, loading, and prewarming.
 *
 * The sole engine is [MoonshineNativeEngine] (Moonshine JNI bridge).
 */
@Singleton
class ModelManager @Inject constructor(
    private val moonshineStreamingEngine: MoonshineNativeEngine,
    private val modelRepository: ModelRepository,
    private val vadDetector: SileroVadDetector,
    private val thermalMonitor: ThermalMonitor,
    @ApplicationScope private val scope: CoroutineScope,
) {

    // @Synchronized (not @Volatile) is the correct primitive here:
    // - @Synchronized prevents concurrent preloads racing to launch duplicate coroutines.
    // - @Volatile alone would leave a TOCTOU window between the isActive check and the job assignment.
    // - cancelPreload() is also @Synchronized so it observes the same monitor, ensuring
    //   cancel() always sees the latest job reference.
    // Constraint: do NOT add suspend calls inside preloadModels() — @Synchronized blocks
    // the calling thread; suspending inside a synchronized block throws IllegalStateException.
    private var preloadJob: Job? = null

    /**
     * Eagerly load VAD + Moonshine streaming engine before the user presses the mic button.
     *
     * Idempotent: no-ops if models are already loaded or a preload is already in progress.
     */
    @Synchronized
    fun preloadModels() {
        if (preloadJob?.isActive == true) {
            Timber.d("[INIT] ModelManager.preloadModels | preload already in progress, skipping")
            return
        }
        preloadJob = scope.launch(Dispatchers.IO) {
            Timber.i("[INIT] ModelManager.preloadModels | background preload starting")
            // Load models sequentially to avoid CPU/memory spike on budget devices.
            // Each model is independent but loading them in parallel can overwhelm
            // low-end SoCs with only 2–4 cores.
            if (!vadDetector.isLoaded) {
                Timber.d("[INIT] ModelManager.preloadModels | loading VAD")
                vadDetector.load()
                yield()
            }
            if (!moonshineStreamingEngine.isLoaded) {
                val path = resolveModelPath()
                if (path != null) {
                    Timber.i("[INIT] ModelManager.preloadModels | loading Moonshine streaming model")
                    moonshineStreamingEngine.loadModel(path, ModelInfo.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
                    Timber.i("[INIT] ModelManager.preloadModels | Moonshine streaming engine loaded")
                } else {
                    Timber.w("[INIT] ModelManager.preloadModels | no Moonshine streaming model downloaded — skipping")
                }
                yield()
            }
            Timber.i("[INIT] ModelManager.preloadModels | preload complete")
        }
    }

    /** Cancel an active preload job (called when recording starts). */
    @Synchronized
    fun cancelPreload() {
        preloadJob?.cancel()
    }

    /**
     * Ensure the Silero VAD model is loaded.
     *
     * @return true if the VAD is loaded after this call completes
     */
    suspend fun ensureVadLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (!vadDetector.isLoaded) {
            Timber.i("[MODEL] ModelManager.ensureVadLoaded | loading Silero VAD")
            vadDetector.load()
        }
        vadDetector.isLoaded
    }

    /**
     * Ensure the Moonshine streaming engine is loaded and ready for inference.
     *
     * @return true if the engine is loaded after this call completes
     */
    suspend fun ensureEngineLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (!moonshineStreamingEngine.isLoaded) {
            val path = resolveModelPath()
            if (path != null) {
                Timber.i("[MODEL] ModelManager.ensureEngineLoaded | loading Moonshine streaming engine")
                moonshineStreamingEngine.loadModel(path, ModelInfo.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
            } else {
                Timber.e("[MODEL] ModelManager.ensureEngineLoaded | no Moonshine streaming model downloaded — cannot transcribe")
            }
        }
        moonshineStreamingEngine.isLoaded
    }

    /** True when the device is at SEVERE thermal status or above — transcription should be paused. */
    fun isTooHotForTranscription(): Boolean = thermalMonitor.thermalTier == ThermalTier.HOT

    /** Resolve the Moonshine small-streaming model directory. Performs disk I/O on IO dispatcher. */
    suspend fun resolveModelPath(): String? = withContext(Dispatchers.IO) {
        if (!modelRepository.isModelDownloaded(ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID)) return@withContext null
        val path = modelRepository.getModelPath(ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID)
        if (!java.io.File(path).exists()) return@withContext null
        path
    }

    /** The active streaming engine instance for access by the coordinator. */
    internal val streamingEngine: StreamingTranscriptionEngine get() = moonshineStreamingEngine

    /** Release all engine resources. */
    suspend fun releaseAll() {
        moonshineStreamingEngine.release()
        vadDetector.release()
    }
}
