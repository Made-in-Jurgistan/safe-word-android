package com.safeword.android.transcription

import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
/**
 * WhisperEngine — mirrors desktop Safe Word's TranscriptionManager / whisper-rs engine.
 *
 * Backed by whisper.cpp via JNI (WhisperLib). Passes all user settings
 * (threads, language, translate, auto-detect) through to the native layer.
 */
@Singleton
class WhisperEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : TranscriptionEngine {

    private var contextPtr: Long = 0
    private var currentModelPath: String? = null
    private var currentUseGpu: Boolean = false
    private val mutex = Mutex()

    override val isLoaded: Boolean
        get() = contextPtr != 0L

    /**
     * Load a Whisper GGML model file.
     * Calls whisper_init_from_file_with_params() via JNI.
     *
     * @param path   absolute path to the GGML model file
     * @param useGpu true to use Vulkan GPU inference (falls back to CPU on failure).
     *               Default false — CPU with ARM NEON is faster than Vulkan on most
     *               mobile GPUs for medium-sized models.
     */
    override suspend fun loadModel(path: String, useGpu: Boolean): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val modelName = java.io.File(path).name
            Timber.i("[ENTER] WhisperEngine.loadModel | model=%s useGpu=%b", modelName, useGpu)
            val loadStart = System.nanoTime()

            // Release previous context if any
            if (contextPtr != 0L) {
                Timber.d("[STATE] WhisperEngine.loadModel | releasing previous context ptr=%d", contextPtr)
                try { WhisperLib.nativeFree(contextPtr) } catch (_: Exception) { }
                contextPtr = 0
            }

        try {
            WhisperLib.nativeLoadBackends(appContext.applicationInfo.nativeLibraryDir)
            val ptr = WhisperLib.nativeInit(path, useGpu)
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            if (ptr == 0L) {
                Timber.e("[ERROR] WhisperEngine.loadModel | nativeInit returned 0 model=%s loadMs=%d", modelName, loadMs)
                false
            } else {
                contextPtr = ptr
                currentModelPath = path
                currentUseGpu = useGpu
                Timber.i("[PERF] WhisperEngine.loadModel | success ptr=%d loadMs=%d model=%s gpu=%b", ptr, loadMs, modelName, useGpu)
                if (useGpu) {
                    Timber.i("[INIT] WhisperEngine.loadModel | Vulkan GPU backend requested and init succeeded")
                } else {
                    Timber.i("[INIT] WhisperEngine.loadModel | CPU-only backend (NEON)")
                }
                true
            }
        } catch (e: Exception) {
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            Timber.e(e, "[ERROR] WhisperEngine.loadModel | failed loadMs=%d model=%s", loadMs, modelName)
            false
        }
        }
    }

    /**
     * Transcribe via [TranscriptionConfig] — satisfies the [TranscriptionEngine] interface.
     * Delegates to the fully-parameterised overload below.
     */
    override suspend fun transcribe(
        samples: FloatArray,
        config: TranscriptionConfig,
    ): TranscriptionResult = transcribe(
        samples = samples,
        language = config.language,
        nThreads = config.nThreads,
        translate = config.translate,
        autoDetect = config.autoDetect,
        initialPrompt = config.initialPrompt,
        useVad = config.useVad,
        vadModelPath = config.vadModelPath,
        vadThreshold = config.vadThreshold,
        vadMinSpeechMs = config.vadMinSpeechMs,
        vadMinSilenceMs = config.vadMinSilenceMs,
        vadSpeechPadMs = config.vadSpeechPadMs,
        noSpeechThreshold = config.noSpeechThreshold,
        logprobThreshold = config.logprobThreshold,
        entropyThreshold = config.entropyThreshold,
    )

    /**
     * Transcribe PCM audio samples via whisper.cpp JNI with per-segment streaming callbacks.
     *
     * [onSegment] is called on the inference thread for each segment as it is decoded.
     * The returned [TranscriptionResult] carries the aggregated final text.
     *
     * @param samples   Float array of PCM samples normalized to [-1.0, 1.0]
     * @param config    Inference configuration
     * @param onSegment Called for each new decoded segment (non-blocking, thread-safe required)
     */
    override suspend fun transcribeStreaming(
        samples: FloatArray,
        config: TranscriptionConfig,
        onSegment: (String) -> Unit,
    ): TranscriptionResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            require(contextPtr != 0L) { "Model not loaded — call loadModel() first" }
            require(samples.isNotEmpty()) { "Empty audio buffer" }

            val sampleRate = 16_000
            val startTime = System.currentTimeMillis()
            val audioDurationMs = (samples.size.toLong() * 1000) / sampleRate

            Timber.i(
                "[ENTER] WhisperEngine.transcribeStreaming | audioSec=%.2f sampleCount=%d threads=%d lang=%s"
                    .format(
                        samples.size.toFloat() / sampleRate,
                        samples.size,
                        config.nThreads,
                        config.language,
                    ),
            )

            val jsonStr = WhisperLib.nativeTranscribeStreaming(
                contextPtr = contextPtr,
                samples = samples,
                language = config.language,
                nThreads = config.nThreads,
                translate = config.translate,
                autoDetect = config.autoDetect,
                initialPrompt = config.initialPrompt,
                useVad = config.useVad,
                vadModelPath = config.vadModelPath,
                vadThreshold = config.vadThreshold,
                vadMinSpeechMs = config.vadMinSpeechMs,
                vadMinSilenceMs = config.vadMinSilenceMs,
                vadSpeechPadMs = config.vadSpeechPadMs,
                noSpeechThreshold = config.noSpeechThreshold,
                logprobThreshold = config.logprobThreshold,
                entropyThreshold = config.entropyThreshold,
                segmentCallback = SegmentCallback { text -> onSegment(text) },
            )

            val inferenceDuration = System.currentTimeMillis() - startTime
            val rtf = if (audioDurationMs > 0) inferenceDuration.toFloat() / audioDurationMs else 0f

            val json = org.json.JSONObject(jsonStr)
            val text = json.optString("text", "")
            val noSpeechProb = json.optDouble("no_speech_prob", 0.0).toFloat()
            val avgLogprob = json.optDouble("avg_logprob", 0.0).toFloat()

            Timber.i(
                "[PERF] WhisperEngine.transcribeStreaming | inferenceMs=%d audioDurationMs=%d rtf=%.2f textLen=%d noSpeech=%.3f"
                    .format(inferenceDuration, audioDurationMs, rtf, text.length, noSpeechProb),
            )

            if (noSpeechProb > config.noSpeechThreshold) {
                Timber.w(
                    "[BRANCH] WhisperEngine.transcribeStreaming | hallucination suppressed noSpeech=%.3f > threshold=%.3f",
                    noSpeechProb,
                    config.noSpeechThreshold,
                )
                return@withContext TranscriptionResult(
                    text = "",
                    audioDurationMs = audioDurationMs,
                    inferenceDurationMs = inferenceDuration,
                    language = if (config.autoDetect) "auto" else config.language,
                    noSpeechProb = noSpeechProb,
                    avgLogprob = avgLogprob,
                )
            }

            Timber.d("[EXIT] WhisperEngine.transcribeStreaming | textLen=%d", text.length)

            TranscriptionResult(
                text = text.trim(),
                audioDurationMs = audioDurationMs,
                inferenceDurationMs = inferenceDuration,
                language = if (config.autoDetect) "auto" else config.language,
                noSpeechProb = noSpeechProb,
                avgLogprob = avgLogprob,
            )
        }
    }

    /**
     * Transcribe PCM audio samples via whisper.cpp JNI.
     *
     * @param samples              Float array of PCM samples normalized to [-1.0, 1.0]
     * @param language             Language code ("en", "auto", etc.)
     * @param nThreads             Number of CPU threads for inference
     * @param translate            If true, translate non-English speech to English
     * @param autoDetect           If true, auto-detect language (ignores language param)
     * @param initialPrompt        Optional prompt to bias decoder vocabulary/style
     * @param useVad               Enable native GGML Silero VAD in whisper.cpp
     * @param vadModelPath         Path to ggml-silero VAD model file (or empty)
     * @param vadThreshold         VAD speech probability threshold
     * @param vadMinSpeechMs       Minimum speech duration ms
     * @param vadMinSilenceMs      Minimum silence duration ms
     * @param vadSpeechPadMs       Speech padding ms
     * @param noSpeechThreshold    No-speech probability threshold for hallucination suppression
     * @param logprobThreshold     Average log-probability threshold
     * @param entropyThreshold     Entropy / compression-ratio threshold
     * @return TranscriptionResult with transcribed text, timing, and confidence info
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String,
        nThreads: Int,
        translate: Boolean,
        autoDetect: Boolean,
        initialPrompt: String,
        useVad: Boolean = false,
        vadModelPath: String = "",
        vadThreshold: Float = DEFAULT_VAD_THRESHOLD,
        vadMinSpeechMs: Int = DEFAULT_VAD_MIN_SPEECH_MS,
        vadMinSilenceMs: Int = DEFAULT_VAD_MIN_SILENCE_MS,
        vadSpeechPadMs: Int = DEFAULT_VAD_SPEECH_PAD_MS,
        noSpeechThreshold: Float = DEFAULT_NO_SPEECH_THRESHOLD,
        logprobThreshold: Float = DEFAULT_LOGPROB_THRESHOLD,
        entropyThreshold: Float = DEFAULT_ENTROPY_THRESHOLD,
    ): TranscriptionResult = mutex.withLock {
        withContext(Dispatchers.Default) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        require(contextPtr != 0L) { "Model not loaded — call loadModel() first" }
        require(samples.isNotEmpty()) { "Empty audio buffer" }

        val sampleRate = 16_000
        val startTime = System.currentTimeMillis()
        val audioDurationSec = samples.size.toFloat() / sampleRate
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        Timber.i("[ENTER] WhisperEngine.transcribe | audioSec=%.2f sampleCount=%d threads=%d availableProcessors=%d lang=%s translate=%b autoDetect=%b promptLen=%d vad=%b".format(
            audioDurationSec, samples.size, nThreads, availableProcessors, language, translate, autoDetect, initialPrompt.length, useVad,
        ))
        if (nThreads > availableProcessors) {
            Timber.w("[DIAGNOSTICS] WhisperEngine.transcribe | nThreads=%d exceeds availableProcessors=%d — may degrade performance", nThreads, availableProcessors)
        }

        val jsonStr = WhisperLib.nativeTranscribe(
            contextPtr, samples, language, nThreads, translate, autoDetect, initialPrompt,
            useVad, vadModelPath, vadThreshold, vadMinSpeechMs, vadMinSilenceMs, vadSpeechPadMs,
            noSpeechThreshold, logprobThreshold, entropyThreshold,
        )

        val inferenceDuration = System.currentTimeMillis() - startTime
        val audioDuration = (samples.size.toLong() * 1000) / sampleRate
        val rtf = if (audioDuration > 0) inferenceDuration.toFloat() / audioDuration else 0f

        // Parse JSON result from native layer
        val json = JSONObject(jsonStr)
        val text = json.optString("text", "")
        val noSpeechProb = json.optDouble("no_speech_prob", 0.0).toFloat()
        val avgLogprob = json.optDouble("avg_logprob", 0.0).toFloat()

        Timber.i("[PERF] WhisperEngine.transcribe | inferenceMs=%d audioDurationMs=%d rtf=%.2f textLen=%d noSpeech=%.3f avgLogprob=%.3f",
            inferenceDuration, audioDuration, rtf, text.length, noSpeechProb, avgLogprob)

        // Hallucination suppression: if no_speech_prob exceeds threshold, return empty
        if (noSpeechProb > noSpeechThreshold) {
            Timber.w("[BRANCH] WhisperEngine.transcribe | hallucination suppressed noSpeech=%.3f > threshold=%.3f",
                noSpeechProb, noSpeechThreshold)
            return@withContext TranscriptionResult(
                text = "",
                audioDurationMs = audioDuration,
                inferenceDurationMs = inferenceDuration,
                language = if (autoDetect) "auto" else language,
                noSpeechProb = noSpeechProb,
                avgLogprob = avgLogprob,
            )
        }

        Timber.d("[EXIT] WhisperEngine.transcribe | textLen=%d", text.length)

        TranscriptionResult(
            text = text.trim(),
            audioDurationMs = audioDuration,
            inferenceDurationMs = inferenceDuration,
            language = if (autoDetect) "auto" else language,
            noSpeechProb = noSpeechProb,
            avgLogprob = avgLogprob,
        )
        }
    }

    companion object {
        const val DEFAULT_VAD_THRESHOLD = 0.5f
        const val DEFAULT_VAD_MIN_SPEECH_MS = 250
        const val DEFAULT_VAD_MIN_SILENCE_MS = 500
        const val DEFAULT_VAD_SPEECH_PAD_MS = 300
        const val DEFAULT_NO_SPEECH_THRESHOLD = 0.6f
        const val DEFAULT_LOGPROB_THRESHOLD = -1.0f
        const val DEFAULT_ENTROPY_THRESHOLD = 2.4f
    }

    /**
     * Run a representative silent inference to warm up the compute graph and GPU pipeline.
     * Uses the same thread count as real transcription so Vulkan compiles identical
     * shader pipelines, and a 5-second buffer to match typical recording lengths.
     */
    override suspend fun prewarm(): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (contextPtr == 0L) return@withContext
            val startMs = System.currentTimeMillis()
            val threads = InferenceConfig.optimalWhisperThreads()
            Timber.d("[INIT] WhisperEngine.prewarm | warming up compute graph threads=%d", threads)
            // 5 seconds of silence — representative of typical recordings so Vulkan
            // compiles the same compute-shader variants needed for real inference.
            val silentSamples = FloatArray(80_000)
            try {
                WhisperLib.nativeTranscribe(
                    contextPtr, silentSamples, "en", threads, false, false, "",
                    false, "", DEFAULT_VAD_THRESHOLD, DEFAULT_VAD_MIN_SPEECH_MS,
                    DEFAULT_VAD_MIN_SILENCE_MS, DEFAULT_VAD_SPEECH_PAD_MS,
                    DEFAULT_NO_SPEECH_THRESHOLD, DEFAULT_LOGPROB_THRESHOLD,
                    DEFAULT_ENTROPY_THRESHOLD,
                )
                Timber.i("[PERF] WhisperEngine.prewarm | done warmupMs=%d threads=%d", System.currentTimeMillis() - startMs, threads)
            } catch (e: Exception) {
                Timber.w(e, "[WARN] WhisperEngine.prewarm | warmup failed (non-fatal)")
            }
        }
    }

    override suspend fun release() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val modelName = currentModelPath?.let { java.io.File(it).name }
            Timber.i("[ENTER] WhisperEngine.release | ptr=%d model=%s", contextPtr, modelName)
            if (contextPtr != 0L) {
                try {
                    WhisperLib.nativeFree(contextPtr)
                    Timber.d("[STATE] WhisperEngine.release | native context freed ptr=%d", contextPtr)
                } catch (e: Exception) {
                    Timber.e(e, "[ERROR] WhisperEngine.release | error freeing context ptr=%d", contextPtr)
                }
                contextPtr = 0
            }
            currentModelPath = null
            Timber.i("[EXIT] WhisperEngine.release | engine released")
        }
    }

    fun isReady(): Boolean {
        val ready = contextPtr != 0L
        Timber.v("[MODEL] isReady | ready=%b ptr=%d", ready, contextPtr)
        return ready
    }

    fun isUsingGpu(): Boolean = currentUseGpu
}
