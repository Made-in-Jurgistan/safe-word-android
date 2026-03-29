package com.safeword.android.data.model

/**
 * Whisper model metadata.
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
    val modelType: ModelType = ModelType.WHISPER,
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
        private const val HF_WHISPER_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
        private const val HF_VAD_BASE = "https://huggingface.co/ggml-org/whisper-vad/resolve/main"
        /** Hardcoded model ID for the sole Whisper model. */
        const val WHISPER_MODEL_ID = "small.en-q8_0"
        /** Hardcoded model ID for the GGML Silero VAD model used by whisper.cpp native VAD. */
        const val VAD_MODEL_ID = "silero-v6.2.0"

        /** All models shipped with Safe Word. */
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = WHISPER_MODEL_ID,
                name = "Small English Q8",
                sizeBytes = 264_241_152,
                description = "Whisper small.en — 8-bit quantized, 25% smaller, faster on ARM NEON.",
                downloadUrl = "$HF_WHISPER_BASE/ggml-small.en-q8_0.bin",
                language = "en",
                isQuantized = true,
                sha256 = "67a179f608ea6114bd3fdb9060e762b588a3fb3bd00c4387971be4d177958067",
                modelType = ModelType.WHISPER,
            ),
            ModelInfo(
                id = VAD_MODEL_ID,
                name = "Silero VAD v6.2.0",
                sizeBytes = 885_000,
                description = "GGML Silero VAD model for whisper.cpp native voice activity detection.",
                downloadUrl = "$HF_VAD_BASE/ggml-silero-v6.2.0.bin",
                language = "multilingual",
                isQuantized = false,
                modelType = ModelType.SILERO_VAD,
            ),
        )

        fun findById(id: String): ModelInfo? = AVAILABLE_MODELS.find { it.id == id }
    }
}

/** Type discriminator for model files. */
enum class ModelType {
    WHISPER,
    SILERO_VAD,
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
