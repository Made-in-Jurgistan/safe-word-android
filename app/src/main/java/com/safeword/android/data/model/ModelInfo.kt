package com.safeword.android.data.model

/**
 * Moonshine model metadata.
 * Describes available models that can be downloaded and used for on-device transcription.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val description: String,
    val downloadUrl: String,
    val components: List<String> = emptyList(),
    val language: String = "multilingual",
    val isQuantized: Boolean = false,
    val sha256: String? = null,
    val modelType: ModelType = ModelType.MOONSHINE,
) {
    /** Human-readable file size. */
    val sizeLabel: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.ROOT, "%.0f MB", mb)
            }
        }

    companion object {
        private const val MOONSHINE_DOWNLOAD_BASE = "https://download.moonshine.ai/model"
        /** Moonshine v2 English small streaming model ID. */
        const val MOONSHINE_SMALL_STREAMING_MODEL_ID = "moonshine-small-streaming-en"

        /** All models shipped with Safe Word. */
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = MOONSHINE_SMALL_STREAMING_MODEL_ID,
                name = "Moonshine Small Streaming (English)",
                sizeBytes = 160_000_000,
                description = "Moonshine v2 small streaming — English-only, low-latency streaming model.",
                downloadUrl = "$MOONSHINE_DOWNLOAD_BASE/small-streaming-en/quantized",
                components = listOf(
                    "adapter.ort",
                    "cross_kv.ort",
                    "decoder_kv.ort",
                    "decoder_kv_with_attention.ort",
                    "encoder.ort",
                    "frontend.ort",
                    "streaming_config.json",
                    "tokenizer.bin",
                ),
                language = "en",
                isQuantized = true,
                modelType = ModelType.MOONSHINE,
            ),
        )

        fun findById(id: String): ModelInfo? = AVAILABLE_MODELS.find { it.id == id }
    }
}

/** Type discriminator for model files. */
enum class ModelType {
    MOONSHINE,
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
