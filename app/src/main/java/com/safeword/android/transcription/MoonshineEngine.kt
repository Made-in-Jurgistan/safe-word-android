package com.safeword.android.transcription

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.safeword.android.audio.AudioRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MoonshineEngine — on-device ASR using UsefulSensors Moonshine ONNX models.
 *
 * Uses a 2-session pattern:
 * - **encode.onnx** — input `"pcm"` float32[1, n_samples] → output `"last_hidden_state"`
 * - **uncached_decode.onnx** — inputs `"encoder_hidden_states"` + `"input_ids"` int64[1, seq]
 *   → output `"logits"` float32[1, seq, vocab_size]
 *
 * Greedy decoding starts from BOS (token 1) and stops at EOS (token 2) or [MAX_DECODE_STEPS].
 * Token strings are looked up from `tokens.txt` (line N = string for token ID N).
 *
 * NNAPI acceleration is requested at session creation and silently falls back to CPU
 * if the runtime does not support it.
 */
@Singleton
class MoonshineEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : TranscriptionEngine {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokens: Array<String> = emptyArray()
    private var modelLoaded = false

    override val isLoaded: Boolean
        get() = modelLoaded

    // ── Session management ──────────────────────────────────────────────────

    override suspend fun loadModel(path: String, useGpu: Boolean): Boolean = withContext(Dispatchers.IO) {
        Timber.i("[INIT] MoonshineEngine.loadModel | path=%s useGpu=%b", path, useGpu)
        runCatching {
            val opts = buildSessionOptions()
            encoderSession = ortEnv.createSession("$path/encode.onnx", opts)
            decoderSession = ortEnv.createSession("$path/uncached_decode.onnx", opts)
            tokens = loadTokens(File(path, "tokens.txt"))
            modelLoaded = true
            Timber.i("[INIT] MoonshineEngine.loadModel | loaded encoder + decoder vocab=%d", tokens.size)
            true
        }.getOrElse { e ->
            Timber.e(e, "[ERROR] MoonshineEngine.loadModel | failed path=%s", path)
            modelLoaded = false
            false
        }
    }

    override suspend fun prewarm() {
        if (!modelLoaded) return
        Timber.d("[INIT] MoonshineEngine.prewarm | running silent 1s inference")
        runCatching { runInference(FloatArray(16_000)) {} }
            .onFailure { Timber.w(it, "[WARN] MoonshineEngine.prewarm | prewarm failed") }
    }

    override suspend fun release() {
        Timber.i("[LIFECYCLE] MoonshineEngine.release | closing ONNX sessions")
        encoderSession?.close(); encoderSession = null
        decoderSession?.close(); decoderSession = null
        modelLoaded = false
    }

    // ── Transcription ────────────────────────────────────────────────────────

    override suspend fun transcribe(
        samples: FloatArray,
        config: TranscriptionConfig,
    ): TranscriptionResult {
        check(modelLoaded) { "MoonshineEngine not loaded — call loadModel() first" }
        val start = System.nanoTime()
        val text = runInference(samples) {}
        val inferenceMs = (System.nanoTime() - start) / 1_000_000
        val audioDurationMs = (samples.size.toLong() * 1000) / AudioRecorder.SAMPLE_RATE
        return TranscriptionResult(
            text = text,
            language = "en",
            audioDurationMs = audioDurationMs,
            inferenceDurationMs = inferenceMs,
        )
    }

    override suspend fun transcribeStreaming(
        samples: FloatArray,
        config: TranscriptionConfig,
        onSegment: (String) -> Unit,
    ): TranscriptionResult {
        check(modelLoaded) { "MoonshineEngine not loaded — call loadModel() first" }
        val start = System.nanoTime()
        val text = runInference(samples, onSegment)
        val inferenceMs = (System.nanoTime() - start) / 1_000_000
        val audioDurationMs = (samples.size.toLong() * 1000) / AudioRecorder.SAMPLE_RATE
        return TranscriptionResult(
            text = text,
            language = "en",
            audioDurationMs = audioDurationMs,
            inferenceDurationMs = inferenceMs,
        )
    }

    // ── Core inference ───────────────────────────────────────────────────────

    private suspend fun runInference(
        samples: FloatArray,
        onSegment: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val enc = requireNotNull(encoderSession) { "encoderSession is null" }
        val dec = requireNotNull(decoderSession) { "decoderSession is null" }

        // ── Encoder ─────────────────────────────────────────────────────────
        val pcmBuffer = FloatBuffer.wrap(samples)
        val pcmTensor = OnnxTensor.createTensor(ortEnv, pcmBuffer, longArrayOf(1L, samples.size.toLong()))
        val encResult = enc.run(mapOf("pcm" to pcmTensor))
        pcmTensor.close()
        // hiddenState is owned by encResult — do NOT close it separately; close encResult in finally.
        val hiddenState = encResult.get("last_hidden_state").get() as OnnxTensor

        // ── Greedy decoder ───────────────────────────────────────────────────
        val tokenIds = mutableListOf(BOS_TOKEN)
        val wordBuffer = StringBuilder()
        val fullText = StringBuilder()

        try {
            for (step in 0 until MAX_DECODE_STEPS) {
                val seqLen = tokenIds.size
                val idsBuf = LongBuffer.wrap(tokenIds.toLongArray())
                val idsTensor = OnnxTensor.createTensor(
                    ortEnv, idsBuf, longArrayOf(1L, seqLen.toLong()),
                )
                val decResult = dec.run(mapOf(
                    "encoder_hidden_states" to hiddenState,
                    "input_ids" to idsTensor,
                ))
                idsTensor.close()

                // Read argmax from last-position logits before closing decResult.
                val logitsTensor = decResult.get("logits").get() as OnnxTensor
                val logitsBuf = logitsTensor.floatBuffer
                val vocabSize = logitsBuf.limit() / seqLen
                val lastOffset = (seqLen - 1) * vocabSize

                var bestToken = 0
                var bestVal = Float.NEGATIVE_INFINITY
                for (v in 0 until vocabSize) {
                    val score = logitsBuf.get(lastOffset + v)
                    if (score > bestVal) { bestVal = score; bestToken = v }
                }
                decResult.close() // closes logitsTensor too

                if (bestToken == EOS_TOKEN.toInt()) break
                tokenIds.add(bestToken.toLong())

                val tokenStr = tokens.getOrNull(bestToken) ?: continue
                wordBuffer.append(tokenStr)

                // Emit at word boundaries (token ends with space or sentence punctuation)
                if (tokenStr.endsWith(' ') || tokenStr.lastOrNull() in SENTENCE_ENDINGS) {
                    val segment = wordBuffer.toString()
                    fullText.append(segment)
                    onSegment(segment)
                    wordBuffer.clear()
                }
            }

            // Flush any remaining partial word
            if (wordBuffer.isNotEmpty()) {
                val remaining = wordBuffer.toString()
                fullText.append(remaining)
                onSegment(remaining)
            }
        } finally {
            encResult.close() // closes hiddenState too
        }

        fullText.toString().trim()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)
        opts.setInterOpNumThreads(1)
        runCatching { opts.addNnapi() }
            .onFailure { Timber.d("[BRANCH] MoonshineEngine | NNAPI not available, using CPU") }
        return opts
    }

    private fun loadTokens(file: File): Array<String> {
        if (!file.exists()) {
            Timber.w("[WARN] MoonshineEngine.loadTokens | tokens.txt not found at %s", file.absolutePath)
            return emptyArray()
        }
        return file.readLines().toTypedArray().also {
            Timber.d("[INIT] MoonshineEngine.loadTokens | loaded %d tokens", it.size)
        }
    }

    private companion object {
        const val BOS_TOKEN = 1L
        const val EOS_TOKEN = 2L
        const val MAX_DECODE_STEPS = 200
        val SENTENCE_ENDINGS = setOf('.', '!', '?', ',', ';', ':')
    }
}
