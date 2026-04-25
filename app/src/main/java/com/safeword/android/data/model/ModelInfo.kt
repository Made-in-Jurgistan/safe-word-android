package com.safeword.android.data.model

/**
 * STT model metadata.
 * Describes available models that can be downloaded and used for on-device transcription.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val description: String,
    val downloadUrl: String,
    val language: String = "multilingual",
    val isQuantized: Boolean = false,
    val sha256: String? = null,

    /**
     * For multi-file models (e.g. Moonshine ONNX), maps each required filename
     * to its download URL. When non-empty, [downloadUrl] is ignored and files
     * are downloaded into a subdirectory named after the model ID.
     */
    val downloadFiles: Map<String, String> = emptyMap(),

    /**
     * Per-file SHA-256 checksums for multi-file models. Maps the same filename
     * keys used in [downloadFiles] to their expected lowercase hex digest.
     * When a file's hash is present, [downloadMultiFileModel] verifies the
     * downloaded file before accepting it. Entries may be omitted to skip
     * verification for that file.
     */
    val downloadFileHashes: Map<String, String> = emptyMap(),
) {
    /** Human-readable file size. */
    val sizeLabel: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) "%.1f GB".format(mb / 1024.0)
            else "%.0f MB".format(mb)
        }

    companion object {
        private const val MOONSHINE_STREAMING_CDN = "https://download.moonshine.ai/model/small-streaming-en/quantized"

        const val MOONSHINE_SMALL_STREAMING_MODEL_ID = "moonshine-small-streaming"
        // Architecture value 4 = MoonshineModelArchitecture.SMALL_STREAMING per Moonshine Voice SDK.
        const val MOONSHINE_MODEL_ARCH_SMALL_STREAMING = 4

        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = MOONSHINE_SMALL_STREAMING_MODEL_ID,
                name = "Moonshine Small Streaming v2",
                sizeBytes = 246_070_310,
                description = "Moonshine Small Streaming v2 — real-time streaming, English-only, 123M params, 7.84% WER.",
                downloadUrl = "", // Multi-file: uses downloadFiles instead
                language = "en",
                isQuantized = true,
                downloadFiles = mapOf(
                    "adapter.ort" to "$MOONSHINE_STREAMING_CDN/adapter.ort",
                    "cross_kv.ort" to "$MOONSHINE_STREAMING_CDN/cross_kv.ort",
                    "decoder_kv.ort" to "$MOONSHINE_STREAMING_CDN/decoder_kv.ort",
                    "encoder.ort" to "$MOONSHINE_STREAMING_CDN/encoder.ort",
                    "frontend.ort" to "$MOONSHINE_STREAMING_CDN/frontend.ort",
                    "streaming_config.json" to "$MOONSHINE_STREAMING_CDN/streaming_config.json",
                    "tokenizer.bin" to "$MOONSHINE_STREAMING_CDN/tokenizer.bin",
                    "decoder_kv_with_attention.ort" to "$MOONSHINE_STREAMING_CDN/decoder_kv_with_attention.ort",
                ),
                downloadFileHashes = mapOf(
                    "adapter.ort" to "d8493e0ac76a198b309a8be6f74b3101e235f773ffe5d6b378278cd7e4177992",
                    "cross_kv.ort" to "6e57d1361717e00d73336a0c3beafedae784b1e537905ad253dee33db4007466",
                    "decoder_kv.ort" to "d5adfcfaa6e582144791f1568bd0f683852c7bfbb8c79acad97499da05e4ffcf",
                    "encoder.ort" to "3b21d02eff6aa5651524ada4271d37c1d7bba4eb3d256415074f2cfdbaeb526a",
                    "frontend.ort" to "e086451043c1c8652a9614e4a4a81d5807221b611584a3cf31f73779d5900003",
                    "streaming_config.json" to "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a",
                    "tokenizer.bin" to "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d",
                    "decoder_kv_with_attention.ort" to "2ac12d0b1ab1459ae2572b0d8f0a359a79ac83ad0a5de0b40bdb33c9357048ee",
                ),
            ),
        )

        fun findById(id: String): ModelInfo? = AVAILABLE_MODELS.find { it.id == id }
    }
}

/**
 * Download state for a model.
 */
sealed interface ModelDownloadState {
    data object NotDownloaded : ModelDownloadState
    data class Downloading(val progress: Float) : ModelDownloadState
    data object Downloaded : ModelDownloadState
    data class Error(val message: String) : ModelDownloadState
}
