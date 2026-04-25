package com.safeword.android.transcription

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * On-device sentence embedding using all-MiniLM-L6-v2 (ONNX, INT8-quantized for ARM64).
 *
 * Produces 384-dimensional L2-normalised embeddings for short text inputs.
 * Used by [SemanticCommandMatcher] to compare voice utterances against
 * canonical command phrases via cosine similarity.
 *
 * Thread-safety: inference is guarded by a [ReentrantLock].
 * Model lifecycle: [load] → [embed] (repeated) → [release].
 */
@Singleton
class SentenceEmbeddingModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenizer: WordPieceTokenizer,
) {
    companion object {
        private const val MODEL_ASSET = "minilm_l6_v2.onnx"

        /** Output embedding dimensionality. */
        const val EMBEDDING_DIM = 384

        /** Matches [WordPieceTokenizer.MAX_LENGTH]. */
        private const val MAX_LENGTH = WordPieceTokenizer.MAX_LENGTH

        /** Pre-allocated all-zeros token-type-ids array — never mutated; lock serialises access. */
        private val ZERO_TOKEN_TYPE_IDS = LongArray(MAX_LENGTH)
    }

    private var ortEnv: OrtEnvironment? = null
    @Volatile private var ortSession: OrtSession? = null

    private val lock = ReentrantLock()

    /** Pre-allocated shape arrays for tensor creation. */
    private val inputShape = longArrayOf(1, MAX_LENGTH.toLong())

    val isLoaded: Boolean get() = ortSession != null

    /**
     * Load the ONNX model and vocab from assets. Idempotent.
     *
     * Uses double-checked locking: the fast-path null check avoids lock contention
     * on every call after the first successful load. The inner check under the lock
     * prevents two concurrent first-callers from both creating an ONNX session —
     * the second session would otherwise be discarded without being closed (resource leak).
     *
     * @return `true` on success.
     */
    fun load(): Boolean {
        if (ortSession != null) return true // fast path — no lock needed (@Volatile ensures visibility)
        lock.lock()
        try {
            if (ortSession != null) return true // re-check under lock: concurrent load already succeeded
            val start = System.nanoTime()
            return try {
                if (!tokenizer.isLoaded && !tokenizer.load()) {
                    Timber.e("[ERROR] SentenceEmbeddingModel.load | tokenizer failed to load")
                    return false
                }

                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OptLevel.ALL_OPT)
                }

                val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                ortSession = env.createSession(modelBytes, opts)

                val ms = (System.nanoTime() - start) / 1_000_000
                Timber.i(
                    "[INIT] SentenceEmbeddingModel.load | success loadMs=%d modelSizeKB=%d embDim=%d maxLen=%d",
                    ms, modelBytes.size / 1024, EMBEDDING_DIM, MAX_LENGTH,
                )
                true
            } catch (e: Exception) {
                ortSession?.close()
                ortSession = null
                val ms = (System.nanoTime() - start) / 1_000_000
                Timber.e(e, "[ERROR] SentenceEmbeddingModel.load | failed loadMs=%d", ms)
                false
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Compute a 384-dim L2-normalised sentence embedding for [text].
     *
     * @return [FloatArray] of size [EMBEDDING_DIM], or `null` if the model is not loaded.
     */
    fun embed(text: String): FloatArray? {
        lock.lock()
        try {
            val session = ortSession ?: run {
                Timber.w("[WARN] SentenceEmbeddingModel.embed | model not loaded")
                return null
            }
            val env = ortEnv ?: return null

            val (inputIds, attentionMask) = tokenizer.tokenize(text)
            var inputIdsTensor: OnnxTensor? = null
            var attentionMaskTensor: OnnxTensor? = null
            var tokenTypeTensor: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                inputIdsTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(inputIds),
                    inputShape,
                )
                attentionMaskTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(attentionMask),
                    inputShape,
                )
                // MiniLM also takes token_type_ids (all zeros)
                tokenTypeTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(ZERO_TOKEN_TYPE_IDS),
                    inputShape,
                )

                val inputs = mapOf(
                    "input_ids" to inputIdsTensor,
                    "attention_mask" to attentionMaskTensor,
                    "token_type_ids" to tokenTypeTensor,
                )

                result = session.run(inputs)

                // Output shape: [1, MAX_LENGTH, 384] — last_hidden_state
                @Suppress("UNCHECKED_CAST")
                val output = result[0].value as Array<Array<FloatArray>>
                val tokenEmbeddings = output[0] // [MAX_LENGTH][384]

                // Mean pooling over non-padding tokens
                val pooled = meanPool(tokenEmbeddings, attentionMask)

                // L2 normalise
                l2Normalize(pooled)

                return pooled
            } finally {
                result?.close()
                inputIdsTensor?.close()
                attentionMaskTensor?.close()
                tokenTypeTensor?.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] SentenceEmbeddingModel.embed | inference failed")
            return null
        } finally {
            lock.unlock()
        }
    }

    /** Release the ONNX session. Safe to call multiple times. */
    fun release() {
        lock.lock()
        try {
            ortSession?.close()
            ortSession = null
            Timber.i("[LIFECYCLE] SentenceEmbeddingModel.release | session closed")
        } finally {
            lock.unlock()
        }
    }

    /**
     * Mean pooling: average token embeddings where attention_mask == 1.
     */
    private fun meanPool(
        tokenEmbeddings: Array<FloatArray>,
        attentionMask: LongArray,
    ): FloatArray {
        val pooled = FloatArray(EMBEDDING_DIM)
        var count = 0

        for (i in tokenEmbeddings.indices) {
            if (attentionMask[i] == 1L) {
                val emb = tokenEmbeddings[i]
                for (j in pooled.indices) {
                    pooled[j] += emb[j]
                }
                count++
            }
        }

        if (count > 0) {
            val scale = 1f / count
            for (j in pooled.indices) {
                pooled[j] *= scale
            }
        }

        return pooled
    }

    /**
     * In-place L2 normalisation.
     */
    private fun l2Normalize(vector: FloatArray) {
        var sumSq = 0f
        for (v in vector) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm > 1e-12f) {
            val invNorm = 1f / norm
            for (i in vector.indices) vector[i] *= invNorm
        }
    }
}
